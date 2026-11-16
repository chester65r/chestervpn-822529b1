import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { BLUEPRINT_FILES } from "./android-blueprint";
import { ALL_IN_ONE_ANDROID_KOTLIN } from "./all-in-one-android";
import { buildSingBoxClientConfig, type SingBoxProtocol, type UtlsFingerprint } from "./singbox";
import { HETZNER_SERVER_SPEC } from "./tunnel";
import { buildZipArchive, type ZipEntryInput } from "./zip";

export type AndroidProjectBundleOptions = {
  endpointHost?: string | undefined;
  endpointPort?: number | undefined;
  sni?: string | undefined;
  networkProfileId?: string | undefined;
  serverPublicKey?: string | undefined;
  protocol?: SingBoxProtocol | undefined;
  utlsFingerprint?: UtlsFingerprint | undefined;
  uuid?: string | undefined;
  realityPublicKey?: string | undefined;
  realityShortId?: string | undefined;
  trojanPassword?: string | undefined;
  wsHost?: string | undefined;
  wsPath?: string | undefined;
};

const ANDROID_VPN_FILES = [
  "app/src/main/java/com/example/advancedtunnel/vpn/SingBoxConfigBuilder.kt",
  "app/src/main/java/com/example/advancedtunnel/vpn/LibboxPlatformBridge.kt",
  "app/src/main/java/com/example/advancedtunnel/vpn/TunPacketForwarder.kt",
  "app/src/main/java/com/example/advancedtunnel/vpn/TunnelVpnService.kt",
  "app/src/main/java/com/example/advancedtunnel/vpn/ReconnectionMonitor.kt",
  "app/src/main/java/com/example/advancedtunnel/vpn/TunnelStateHolder.kt",
] as const;

function sanitizePropertyValue(raw: string | undefined, fallback: string, pattern = /[^a-zA-Z0-9._:/+-]/g): string {
  const cleaned = (raw ?? "")
    .replace(/[\r\n\0]/g, "")
    .replace(pattern, "")
    .trim();
  return cleaned.length > 0 ? cleaned.slice(0, 128) : fallback;
}

function patchGradleWithLibbox(gradleCode: string): string {
  let out = gradleCode;
  if (!out.includes("ndk {")) {
    out = out.replace(
      'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"',
      `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }`,
    );
  }
  if (!out.includes("jniLibs.srcDirs")) {
    out = out.replace(
      "buildFeatures { viewBinding = true }",
      `buildFeatures { viewBinding = true }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }`,
    );
  }
  if (!out.includes("kotlinx-coroutines-android")) {
    out = out.replace(
      'implementation("androidx.core:core-ktx:1.12.0")',
      `implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))`,
    );
  }
  return out;
}

function patchManifestForTunnelService(manifestCode: string): string {
  let out = manifestCode;
  if (!out.includes("FOREGROUND_SERVICE_SPECIAL_USE")) {
    out = out.replace(
      '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
      `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />`,
    );
  }
  if (!out.includes("com.example.advancedtunnel.vpn.TunnelVpnService") && !out.includes('android:name=".vpn.TunnelVpnService"')) {
    out = out.replace(
      "    </application>",
      `        <service
            android:name=".vpn.TunnelVpnService"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:foregroundServiceType="specialUse"
            android:exported="false">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="vpn" />
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
    </application>`,
    );
  }
  return out;
}

/**
 * Packages a complete, pinned-toolchain Android Studio / Gradle 8.9 project archive
 * powered by real native Go sing-box / libbox (io.nekohasekai.libbox.*):
 * - gVisor TUN inbound (`172.19.0.1/30`, MTU 1500, `auto_route`, `strict_route`)
 * - VLESS + `xtls-rprx-vision` + Reality (port 443) & Trojan over TLS (port 8443)
 * - uTLS fingerprinting (`chrome`, `ios`) & ClientHello fragmentation (`fragment` + `record_fragment`)
 * - WireGuard `GoBackend` fallback engine (`com.wireguard.android:tunnel`)
 */
export function buildAndroidStudioProjectZip(options: AndroidProjectBundleOptions = {}): Uint8Array {
  const host = sanitizePropertyValue(options.endpointHost, HETZNER_SERVER_SPEC.ip, /[^a-zA-Z0-9.-]/g);
  const protocol: SingBoxProtocol =
    options.protocol === "trojan-tls"
      ? "trojan-tls"
      : options.protocol === "vless-ws-tls"
        ? "vless-ws-tls"
        : "vless-reality";
  const utlsFingerprint: UtlsFingerprint = options.utlsFingerprint ?? "chrome";
  const port = options.endpointPort && options.endpointPort > 0 && options.endpointPort <= 65535
    ? options.endpointPort
    : protocol === "trojan-tls"
      ? HETZNER_SERVER_SPEC.altTlsPort
      : HETZNER_SERVER_SPEC.tlsPort;
  const sni = sanitizePropertyValue(
    options.sni,
    protocol === "vless-reality"
      ? "www.stc.com.sa"
      : protocol === "vless-ws-tls"
        ? "www.mobily.com.sa"
        : HETZNER_SERVER_SPEC.reverseDns,
    /[^a-zA-Z0-9.-]/g,
  );
  const wsHost = sanitizePropertyValue(
    options.wsHost,
    protocol === "vless-ws-tls" ? "cdn.mobily.com.sa" : sni,
    /[^a-zA-Z0-9.-]/g,
  );
  const wsPath = sanitizePropertyValue(options.wsPath, "/cdn-ws", /[^a-zA-Z0-9/_-]/g);
  const networkProfileId = sanitizePropertyValue(
    options.networkProfileId,
    protocol === "vless-ws-tls" ? "mobily-ksa-lte-bypass" : "stc-ksa-5g-cgnat",
    /[^a-zA-Z0-9_-]/g,
  );
  const serverPublicKey = sanitizePropertyValue(
    options.serverPublicKey,
    HETZNER_SERVER_SPEC.wireguardPublicKey,
    /[^a-zA-Z0-9+/=]/g,
  );

  const entries: ZipEntryInput[] = [];
  const rootPrefix = "tunnel-console-android";

  for (const file of BLUEPRINT_FILES) {
    let content = file.code;
    if (file.path === "app/build.gradle.kts") {
      content = patchGradleWithLibbox(file.code);
    } else if (file.path === "app/src/main/AndroidManifest.xml") {
      content = patchManifestForTunnelService(file.code);
    }
    entries.push({
      path: `${rootPrefix}/${file.path}`,
      content,
    });
  }

  entries.push({
    path: `${rootPrefix}/app/src/main/java/com/example/advancedtunnel/AllInOneHetznerVpn.kt`,
    content: ALL_IN_ONE_ANDROID_KOTLIN,
  });

  for (const relPath of ANDROID_VPN_FILES) {
    const diskPath = resolve(process.cwd(), relPath);
    if (existsSync(diskPath)) {
      entries.push({
        path: `${rootPrefix}/${relPath}`,
        content: readFileSync(diskPath, "utf8"),
      });
    }
  }

  entries.push({
    path: `${rootPrefix}/settings.gradle.kts`,
    content: `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
    }
}
rootProject.name = "TunnelConsole"
include(":app")
`,
  });

  entries.push({
    path: `${rootPrefix}/build.gradle.kts`,
    content: `plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
tasks.wrapper {
    gradleVersion = "8.9"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"
}
`,
  });

  entries.push({
    path: `${rootPrefix}/gradle/wrapper/gradle-wrapper.properties`,
    content: `distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
`,
  });

  entries.push({
    path: `${rootPrefix}/gradle.properties`,
    content:
      "android.useAndroidX=true\nkotlin.code.style=official\norg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\n",
  });

  const cleanUuid = sanitizePropertyValue(options.uuid, "", /[^a-fA-F0-9-]/g);
  const cleanRealityPub = sanitizePropertyValue(options.realityPublicKey, "", /[^a-zA-Z0-9_-]/g);
  const cleanRealitySid = sanitizePropertyValue(options.realityShortId, "", /[^a-fA-F0-9]/g);
  const cleanTrojanPwd = sanitizePropertyValue(options.trojanPassword, "", /[^a-zA-Z0-9._-]/g);

  const singBoxConfigJson = JSON.stringify(
    buildSingBoxClientConfig({
      protocol,
      serverHost: host,
      serverPort: port,
      sni,
      wsHost,
      wsPath,
      utlsFingerprint,
      fragmentEnabled: true,
      recordFragmentEnabled: true,
      sniSplitMinBytes: 5,
      sniSplitMaxBytes: 15,
      ...(cleanUuid ? { uuid: cleanUuid } : {}),
      ...(cleanRealityPub ? { realityPublicKey: cleanRealityPub } : {}),
      ...(cleanRealitySid ? { realityShortId: cleanRealitySid } : {}),
      ...(cleanTrojanPwd ? { trojanPassword: cleanTrojanPwd } : {}),
    }),
    null,
    2,
  );

  entries.push({
    path: `${rootPrefix}/app/src/main/assets/sing-box-config.json`,
    content: singBoxConfigJson + "\n",
  });

  entries.push({
    path: `${rootPrefix}/app/src/main/assets/hetzner-tunnel.properties`,
    content: [
      `# Generated from Tunnel Console for ${HETZNER_SERVER_SPEC.serverName} (#${HETZNER_SERVER_SPEC.serverId})`,
      `endpointHost=${host}`,
      `endpointPort=${port}`,
      `protocol=${protocol}`,
      `utlsFingerprint=${utlsFingerprint}`,
      `wireguardPort=${HETZNER_SERVER_SPEC.wireguardPort}`,
      `sni=${sni}`,
      `wsHost=${wsHost}`,
      `wsPath=${wsPath}`,
      `networkProfileId=${networkProfileId}`,
      `serverPublicKey=${serverPublicKey}`,
      `tunCidr=172.19.0.1/30`,
      `tunStack=gvisor`,
      `tunMtu=1500`,
      `tunDns=1.1.1.1`,
      "",
    ].join("\n"),
  });

  entries.push({
    path: `${rootPrefix}/README-BUILD.md`,
    content: `# Tunnel Console Android Project (${HETZNER_SERVER_SPEC.ip})

## Architecture (sing-box / libbox AAR + gVisor TUN + Anti-DPI)

- **Core Native Engine**: \`io.nekohasekai.libbox\` (\`libbox.aar\` built from \`SagerNet/sing-box\` \`experimental/libbox\`)
- **TUN Stack**: \`gvisor\` userspace TCP/IP stack (\`172.19.0.1/30\`, MTU \`1500\`, \`auto_route: true\`, \`strict_route: true\`, \`sniff: true\`)
- **Anti-DPI Outbound**: VLESS + \`xtls-rprx-vision\` + Reality (port \`443\`) or Trojan over TLS (port \`8443\`), native uTLS (\`chrome\` / \`ios\`), and TLS ClientHello fragmentation (\`fragment: true\`, \`record_fragment: true\`)

## Native libbox.aar Setup

1. Build or obtain \`libbox.aar\` from \`SagerNet/sing-box\`:
   \`\`\`bash
   go install -v github.com/sagernet/gomobile/cmd/gomobile@v0.1.5
   gomobile init
   git clone https://github.com/SagerNet/sing-box.git
   cd sing-box
   go run ./cmd/internal/build_libbox -target android
   # Copy resulting libbox.aar into app/libs/libbox.aar
   \`\`\`
2. Replace placeholders in \`app/src/main/assets/sing-box-config.json\` with the generated values from \`/etc/sing-box/client-credentials.env\` on your Hetzner CX23 server (\`${host}\`).
3. Build in Android Studio (JDK 17 + Android SDK Platform 34) or via Gradle:

\`\`\`bash
gradle wrapper --gradle-version 8.9 --distribution-type bin
./gradlew assembleDebug assembleRelease lintDebug
\`\`\`
`,
  });

  return buildZipArchive(entries);
}
