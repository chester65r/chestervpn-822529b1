import { inflateRawSync } from "node:zlib";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ALL_IN_ONE_ANDROID_KOTLIN } from "@/lib/all-in-one-android";
import { buildAndroidStudioProjectZip } from "@/lib/android-project-bundle";
import { buildRelayBundleZip, buildRelayInstallerScript } from "@/lib/relay-bundle";
import { buildSingBoxClientConfig, buildSingBoxServerConfig, validateSingBoxClientConfig } from "@/lib/singbox";
import { buildZipArchive, crc32 } from "@/lib/zip";

function readZipEntries(archive: Uint8Array): Map<string, string> {
  const buf = Buffer.from(archive);
  const files = new Map<string, string>();
  let offset = 0;
  while (offset + 30 <= buf.length && buf.readUInt32LE(offset) === 0x04034b50) {
    const method = buf.readUInt16LE(offset + 8);
    const compSize = buf.readUInt32LE(offset + 18);
    const nameLen = buf.readUInt16LE(offset + 26);
    const extraLen = buf.readUInt16LE(offset + 28);
    const name = buf.subarray(offset + 30, offset + 30 + nameLen).toString("utf8");
    const dataStart = offset + 30 + nameLen + extraLen;
    const payload = buf.subarray(dataStart, dataStart + compSize);
    const raw = method === 8 ? inflateRawSync(payload) : payload;
    files.set(name, raw.toString("utf8"));
    offset = dataStart + compSize;
  }
  return files;
}

describe("ZIP builder & Path A (sing-box / libbox) bundles", () => {
  it("computes standard CRC-32 and builds valid PKZIP signatures", () => {
    expect(crc32(Buffer.from("123456789", "utf8"))).toBe(0xcbf43926);

    const archive = buildZipArchive([
      { path: "hello.txt", content: "hello world" },
      { path: "bin/run.sh", content: "#!/bin/sh\necho hi\n" },
    ]);
    expect(archive[0]).toBe(0x50);
    expect(archive[1]).toBe(0x4b);
    expect(archive[2]).toBe(0x03);
    expect(archive[3]).toBe(0x04);
    const eocdOffset = archive.length - 22;
    expect(archive[eocdOffset]).toBe(0x50);
    expect(archive[eocdOffset + 1]).toBe(0x4b);
    expect(archive[eocdOffset + 2]).toBe(0x05);
    expect(archive[eocdOffset + 3]).toBe(0x06);
  });

  it("generates valid sing-box client & server JSON schemas for STC (VLESS-Reality), Mobily (VLESS-WSS-CDN), and Trojan-TLS", () => {
    const stcVless = buildSingBoxClientConfig({
      protocol: "vless-reality",
      serverHost: "2.29.8.194",
      serverPort: 443,
      sni: "www.stc.com.sa",
      utlsFingerprint: "chrome",
      sniSplitMinBytes: 5,
      sniSplitMaxBytes: 15,
    });
    expect(validateSingBoxClientConfig(stcVless)).toEqual([]);

    const mobilyWss = buildSingBoxClientConfig({
      protocol: "vless-ws-tls",
      serverHost: "2.29.8.194",
      serverPort: 443,
      sni: "www.mobily.com.sa",
      wsHost: "cdn.mobily.com.sa",
      wsPath: "/cdn-ws",
      utlsFingerprint: "chrome",
    });
    expect(validateSingBoxClientConfig(mobilyWss)).toEqual([]);

    const trojan = buildSingBoxClientConfig({
      protocol: "trojan-tls",
      serverHost: "2.29.8.194",
      serverPort: 8443,
      sni: "static.194.8.29.2.clients.your-server.de",
      utlsFingerprint: "ios",
    });
    expect(validateSingBoxClientConfig(trojan)).toEqual([]);

    const serverCfg = buildSingBoxServerConfig();
    const inbounds = serverCfg["inbounds"] as Array<{ type: string; listen_port: number }>;
    expect(inbounds.map((i) => `${i.type}:${i.listen_port}`)).toEqual([
      "vless:443",
      "vless:2053",
      "trojan:8443",
    ]);
  });

  it("packages the complete Android Studio project ZIP with libbox PlatformInterface, SingBoxConfigBuilder, and sing-box-config.json", () => {
    const zip = buildAndroidStudioProjectZip({
      endpointHost: "2.29.8.194\r\nevil=injected",
      endpointPort: 443,
      sni: "www.stc.com.sa\nfoo=bar",
      networkProfileId: "stc-ksa-5g-cgnat",
      protocol: "vless-reality",
      utlsFingerprint: "chrome",
    });

    const files = readZipEntries(zip);
    expect(files.has("tunnel-console-android/settings.gradle.kts")).toBe(true);
    expect(files.has("tunnel-console-android/gradle/wrapper/gradle-wrapper.properties")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/AllInOneHetznerVpn.kt")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/SingBoxConfigBuilder.kt")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/LibboxPlatformBridge.kt")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/TunPacketForwarder.kt")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/TunnelVpnService.kt")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/ReconnectionMonitor.kt")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/TunnelStateHolder.kt")).toBe(true);
    expect(files.has("tunnel-console-android/app/src/main/assets/sing-box-config.json")).toBe(true);

    // Ensure legacy simulated/tun2socks & removed mock classes are gone
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/CarrierTunnelOptimizer.kt")).toBe(false);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/BugHostProbeEngine.kt")).toBe(false);
    expect(files.has("tunnel-console-android/app/src/main/java/hev/htproxy/TProxyService.kt")).toBe(false);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/OutboundTlsSocketEngine.kt")).toBe(false);
    expect(files.has("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/LocalSocks5Bridge.kt")).toBe(false);

    // Verify exact Gradle configuration & repositories
    const settingsGradle = files.get("tunnel-console-android/settings.gradle.kts") ?? "";
    expect(settingsGradle).toContain("https://jitpack.io");

    const gradle = files.get("tunnel-console-android/app/build.gradle.kts") ?? "";
    expect(gradle).toContain('implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")');
    expect(gradle).toContain('implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))');
    expect(gradle).toContain("abiFilters");
    expect(gradle).toContain("jniLibs.srcDirs");

    // Verify Manifest declares TunnelVpnService
    const manifest = files.get("tunnel-console-android/app/src/main/AndroidManifest.xml") ?? "";
    expect(manifest).toContain('android:name=".vpn.TunnelVpnService"');
    expect(manifest).toContain("FOREGROUND_SERVICE_SPECIAL_USE");

    // Verify SingBoxConfigBuilder uses gVisor TUN + VLESS-Reality + Trojan + uTLS + record_fragment
    const configBuilder =
      files.get("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/SingBoxConfigBuilder.kt") ?? "";
    expect(configBuilder).toContain('"gvisor"');
    expect(configBuilder).toContain('"xtls-rprx-vision"');
    expect(configBuilder).toContain('"record_fragment"');
    expect(configBuilder).toContain('"utls"');

    // Verify LibboxPlatformBridge implements io.nekohasekai.libbox.PlatformInterface
    const platformBridge =
      files.get("tunnel-console-android/app/src/main/java/com/example/advancedtunnel/vpn/LibboxPlatformBridge.kt") ?? "";
    expect(platformBridge).toContain("io.nekohasekai.libbox.PlatformInterface");
    expect(platformBridge).toContain("override fun openTun(options: TunOptions): Int");
    expect(platformBridge).toContain("vpnService.protect(fd)");

    // Verify bundled sing-box-config.json is valid
    const bundledJson = JSON.parse(
      files.get("tunnel-console-android/app/src/main/assets/sing-box-config.json") ?? "{}",
    ) as Record<string, unknown>;
    expect(validateSingBoxClientConfig(bundledJson)).toEqual([]);

    const zipWithCreds = buildAndroidStudioProjectZip({
      endpointHost: "2.29.8.194",
      endpointPort: 443,
      protocol: "vless-reality",
      uuid: "11111111-2222-3333-4444-555555555555",
      realityPublicKey: "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-",
      realityShortId: "a1b2c3d4",
    });
    const credFiles = readZipEntries(zipWithCreds);
    const credCfg = credFiles.get("tunnel-console-android/app/src/main/assets/sing-box-config.json") ?? "";
    expect(credCfg).toContain("11111111-2222-3333-4444-555555555555");
    expect(credCfg).toContain("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-");
    expect(credCfg).toContain("a1b2c3d4");

    // Verify sanitized hetzner-tunnel.properties
    const props = files.get("tunnel-console-android/app/src/main/assets/hetzner-tunnel.properties") ?? "";
    expect(props).not.toContain("\nevil=injected");
    expect(props).not.toContain("\nfoo=bar");
    expect(props).toContain("endpointHost=2.29.8.194evilinjected");
    expect(props).toContain("tunStack=gvisor");
  });

  it("keeps AllInOneHetznerVpn.kt 100% synced and removes manual raw TUN packet loops across app/", () => {
    const onDiskAllInOne = readFileSync(
      resolve(process.cwd(), "app/src/main/java/com/example/advancedtunnel/AllInOneHetznerVpn.kt"),
      "utf8",
    );
    expect(ALL_IN_ONE_ANDROID_KOTLIN).toBe(onDiskAllInOne);

    const paths = [
      "app/src/main/java/com/example/advancedtunnel/AllInOneHetznerVpn.kt",
      "app/src/main/java/com/example/advancedtunnel/vpn/TunnelVpnService.kt",
      "app/src/main/java/com/example/advancedtunnel/vpn/TunPacketForwarder.kt",
      "app/src/main/java/com/example/advancedtunnel/vpn/LibboxPlatformBridge.kt",
      "app/src/main/java/com/example/advancedtunnel/vpn/TlsFragmentationEngine.kt",
      "app/src/main/java/com/example/advancedtunnel/AdvancedVpnService.kt",
    ];
    for (const relPath of paths) {
      const absPath = resolve(process.cwd(), relPath);
      expect(existsSync(absPath)).toBe(true);
      const source = readFileSync(absPath, "utf8");
      expect(source).not.toContain("FileInputStream");
      expect(source).not.toContain("DesyncSocketWrapper");
      expect(source).not.toContain("HevSocks5Tunnel");
    }
  });

  it("packages the Hetzner sing-box relay ZIP and installer script without gateway.mjs", () => {
    const relayZip = buildRelayBundleZip();
    const relayText = Buffer.from(relayZip).toString("latin1");
    expect(relayText).toContain("tunnel-relay/config.json");
    expect(relayText).toContain("tunnel-relay/sing-box.service");
    expect(relayText).toContain("tunnel-relay/setup-singbox.sh");
    expect(relayText).toContain("tunnel-relay/Dockerfile");
    expect(relayText).toContain("tunnel-relay/docker-compose.yml");
    expect(relayText).toContain("tunnel-relay/deploy-hetzner.sh");
    expect(relayText).not.toContain("gateway.mjs");

    const script = buildRelayInstallerScript("2.29.8.194");
    expect(script).toContain("#!/usr/bin/env bash");
    expect(script).toContain("setup-singbox.sh");
    expect(script).toContain("sing-box check -c /etc/sing-box/config.json");
  });
});
