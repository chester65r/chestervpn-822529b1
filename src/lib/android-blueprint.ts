import { clientConfig, serverConfig } from "./wireguard";

export type BlueprintFile = { name: string; path: string; lang: string; code: string };

/** Pinned engine versions; update together with the interop fixture (interop/wireguard/go.mod). */
export const WIREGUARD_ANDROID_TUNNEL = "com.wireguard.android:tunnel:1.0.20260102";

const manifest = String.raw`<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!--
      The packet engine's VPN service, com.wireguard.android.backend.GoBackend$VpnService,
      is declared by the WireGuard tunnel library manifest with BIND_VPN_SERVICE and
      exported=false, and is merged automatically. Do not declare a second VpnService.
    -->
    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="Tunnel Console"
        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>`;

const gradle = String.raw`plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.advancedtunnel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.advancedtunnel"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the WireGuard tunnel library (Java 8+ APIs).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }
}

dependencies {
    // Packet engine: official WireGuard userspace implementation (wireguard-go) + VpnService.
    implementation("${WIREGUARD_ANDROID_TUNNEL}")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}`;

const secureStore = String.raw`package com.example.advancedtunnel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the wg-quick configuration (it contains the device private key and preshared key)
 * encrypted with a non-exportable Android Keystore AES-256-GCM key, in no-backup storage.
 * The configuration is never placed in share codes, intents, logs or the clipboard.
 */
class SecureConfigStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "tunnel.conf.enc"))

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(configText: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(configText.toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try {
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(sealed)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** Returns null when nothing is stored or the ciphertext cannot be authenticated. */
    fun load(): String? = try {
        val bytes = file.readFully()
        val ivLength = bytes[0].toInt()
        require(ivLength in 12..16 && bytes.size > 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivLength))
        }
        String(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun clear() = file.delete()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tunnel_config_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}`;

const controller = String.raw`package com.example.advancedtunnel

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

private class AppTunnel : Tunnel {
    @Volatile var state: Tunnel.State = Tunnel.State.DOWN
    override fun getName(): String = "console"
    override fun onStateChange(newState: Tunnel.State) { state = newState }
}

sealed interface TunnelStatus {
    data object Down : TunnelStatus
    data object WaitingForHandshake : TunnelStatus
    data class Connected(val rxBytes: Long, val txBytes: Long) : TunnelStatus
    data class Failed(val reason: String) : TunnelStatus
}

/**
 * Wraps the WireGuard GoBackend packet engine. GoBackend owns the VpnService, creates the
 * TUN interface, protects its UDP sockets from the VPN, and moves IP packets through
 * wireguard-go. All backend calls run on one worker thread, so starts/stops cannot overlap.
 */
object TunnelController {
    private const val REJECT_AFTER_MS = 180_000L // WireGuard Reject-After-Time
    private val worker = Executors.newSingleThreadExecutor()
    private val tunnel = AppTunnel()
    @Volatile private var backend: GoBackend? = null
    @Volatile private var serverKey: Key? = null

    private fun backend(context: Context): GoBackend =
        backend ?: synchronized(this) {
            backend ?: GoBackend(context.applicationContext).also { backend = it }
        }

    /** Parses with the engine's own parser and enforces the single-server profile shape. */
    fun parse(configText: String): Config {
        val config = ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)).use { Config.parse(it) }
        require(config.peers.size == 1) { "Profile must contain exactly one server peer" }
        val peer = config.peers[0]
        require(peer.endpoint.isPresent) { "Server endpoint is required" }
        require(peer.preSharedKey.isPresent) { "Preshared key is required" }
        require(config.`interface`.addresses.isNotEmpty()) { "Interface address is required" }
        return config
    }

    fun up(context: Context, configText: String, done: (String?) -> Unit) = worker.execute {
        val error = try {
            val config = parse(configText)
            serverKey = config.peers[0].publicKey
            backend(context).setState(tunnel, Tunnel.State.UP, config)
            null
        } catch (e: Exception) {
            serverKey = null
            // Report the category only; exception text may contain configuration details.
            if (e is IllegalArgumentException) e.message else "Engine could not start the tunnel"
        }
        done(error)
    }

    fun down(context: Context, done: () -> Unit) = worker.execute {
        try { backend(context).setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        serverKey = null
        done()
    }

    /** CONNECTED only after a fresh authenticated handshake with the configured server key. */
    fun status(context: Context, done: (TunnelStatus) -> Unit) = worker.execute {
        val key = serverKey
        val result = if (tunnel.state != Tunnel.State.UP || key == null) {
            TunnelStatus.Down
        } else try {
            val peer = backend(context).getStatistics(tunnel).peer(key)
            val age = System.currentTimeMillis() - (peer?.latestHandshakeEpochMillis() ?: 0L)
            if (peer == null || peer.latestHandshakeEpochMillis() == 0L || age > REJECT_AFTER_MS) {
                TunnelStatus.WaitingForHandshake
            } else {
                TunnelStatus.Connected(peer.rxBytes(), peer.txBytes())
            }
        } catch (_: Exception) {
            TunnelStatus.Failed("Statistics unavailable")
        }
        done(result)
    }
}`;

const mainActivity = String.raw`package com.example.advancedtunnel

import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.advancedtunnel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecureConfigStore
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var waitingSince = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var stopReason: String? = null

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startTunnel()
            else showState("VPN permission denied")
        }

    private val poll = object : Runnable {
        override fun run() {
            TunnelController.status(this@MainActivity) { status -> main.post { render(status) } }
            if (started) main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SecureConfigStore(this)
        binding.etWgConfig.setText(store.load() ?: "")

        binding.btnSave.setOnClickListener { if (validateAndSave() != null) toast("Profile saved securely") }
        binding.btnToggle.setOnClickListener {
            stopReason = null
            if (started) stopTunnel() else validateAndSave()?.let { requestVpnPermission() }
        }
        binding.btnClear.setOnClickListener {
            store.clear()
            binding.etWgConfig.setText("")
            toast("Stored profile deleted")
        }
    }

    override fun onResume() {
        super.onResume()
        TunnelController.status(this) { status ->
            main.post {
                started = status != TunnelStatus.Down
                render(status)
                if (started) { main.removeCallbacks(poll); main.post(poll) }
            }
        }
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    private fun validateAndSave(): String? {
        val text = binding.etWgConfig.text.toString()
        return try {
            TunnelController.parse(text)
            store.save(text)
            text
        } catch (e: IllegalArgumentException) {
            binding.etWgConfig.error = e.message ?: "Invalid WireGuard profile"
            null
        } catch (_: Exception) {
            binding.etWgConfig.error = "Invalid WireGuard profile"
            null
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startTunnel()
    }

    private fun startTunnel() {
        val text = store.load() ?: return showState("No stored profile")
        showState("STARTING")
        binding.btnToggle.isEnabled = false
        TunnelController.up(this, text) { error ->
            main.post {
                binding.btnToggle.isEnabled = true
                if (error != null) {
                    started = false
                    showState(error)
                } else {
                    started = true
                    waitingSince = System.currentTimeMillis()
                    lastRx = 0L
                    lastTx = 0L
                    stopReason = null
                    main.removeCallbacks(poll)
                    main.post(poll)
                }
                renderButton()
            }
        }
    }

    private fun stopTunnel() {
        started = false
        main.removeCallbacks(poll)
        TunnelController.down(this) { main.post { render(TunnelStatus.Down) } }
    }

    private fun render(status: TunnelStatus) {
        when (status) {
            TunnelStatus.Down -> {
                showState(stopReason ?: "DISCONNECTED")
                binding.tvDownloadSpeed.text = "0.00 KB/s"
                binding.tvUploadSpeed.text = "0.00 KB/s"
            }
            TunnelStatus.WaitingForHandshake -> {
                showState("WAITING FOR HANDSHAKE")
                // A full-tunnel route without a handshake blocks traffic; stop and explain.
                if (System.currentTimeMillis() - waitingSince > 20_000) {
                    stopReason = "No handshake: check server key, endpoint and firewall"
                    stopTunnel()
                }
            }
            is TunnelStatus.Connected -> {
                showState("CONNECTED")
                binding.tvDownloadSpeed.text = String.format("%.2f KB/s", (status.rxBytes - lastRx).coerceAtLeast(0L) / 1024.0)
                binding.tvUploadSpeed.text = String.format("%.2f KB/s", (status.txBytes - lastTx).coerceAtLeast(0L) / 1024.0)
                lastRx = status.rxBytes
                lastTx = status.txBytes
            }
            is TunnelStatus.Failed -> showState(status.reason)
        }
        renderButton()
    }

    private fun renderButton() {
        binding.btnToggle.text = if (started) "STOP TUNNEL" else "START TUNNEL"
    }

    private fun showState(text: String) { binding.tvStatusInfo.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}`;

const layout = String.raw`<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">

        <!-- Private key material: no suggestions, no autofill, never copied by the app. -->
        <EditText android:id="@+id/etWgConfig"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Paste the WireGuard profile issued by your server"
            android:importantForAutofill="no"
            android:inputType="textMultiLine|textNoSuggestions|textVisiblePassword"
            android:fontFamily="monospace" android:minLines="8" />

        <LinearLayout
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="horizontal" android:weightSum="3"
            android:layout_marginTop="16dp" android:layout_marginBottom="16dp">

            <TextView android:id="@+id/tvDownloadSpeed"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="0.00 KB/s" android:gravity="center" />
            <TextView android:id="@+id/tvUploadSpeed"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="0.00 KB/s" android:gravity="center" />
            <TextView android:id="@+id/tvStatusInfo"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="DISCONNECTED" android:gravity="center" />
        </LinearLayout>

        <Button android:id="@+id/btnToggle"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="START TUNNEL" />

        <Button android:id="@+id/btnSave"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Save profile" />

        <Button android:id="@+id/btnClear"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Delete stored profile" />
    </LinearLayout>
</ScrollView>`;

const P = (name: string) => `<${name}>`;

/** Same generator the interop fixture uses; placeholders are filled by server-setup.sh. */
const serverTemplate = serverConfig({
  privateKey: P("SERVER_PRIVATE_KEY"),
  address: "10.66.66.1/24",
  listenPort: 51820,
  mtu: 1280,
  peers: [{ name: "android-device-1", publicKey: P("DEVICE_PUBLIC_KEY"), presharedKey: P("DEVICE_PRESHARED_KEY"), address: "10.66.66.2/32" }],
});

const clientTemplate = clientConfig({
  privateKey: P("DEVICE_PRIVATE_KEY"),
  address: "10.66.66.2/32",
  dns: "10.66.66.1",
  mtu: 1280,
  serverPublicKey: P("SERVER_PUBLIC_KEY"),
  presharedKey: P("DEVICE_PRESHARED_KEY"),
  endpointHost: "vpn.example.com",
  endpointPort: 51820,
  allowedIps: ["0.0.0.0/0", "::/0"],
  keepalive: 25,
});

const serverSetup = String.raw`#!/usr/bin/env bash
# server-setup.sh — WireGuard server for the Android client (Debian/Ubuntu, run as root).
# Run only on a server you own, for traffic you are authorized to carry.
# Replaces the earlier WebSocket relay: both sides now speak the WireGuard protocol.
set -euo pipefail
umask 077
EGRESS_IF="${"$"}{EGRESS_IF:-eth0}"       # interface that reaches the internet
DEVICE_NAME="${"$"}{DEVICE_NAME:-android-device-1}"

apt-get update && apt-get install -y wireguard-tools nftables unbound
mkdir -p /etc/wireguard/clients

# Keys are generated on the server; private keys never leave this machine except the
# device profile, which must be transferred to the phone over a trusted channel once.
wg genkey | tee /etc/wireguard/server.key | wg pubkey > /etc/wireguard/server.pub
wg genkey | tee "/etc/wireguard/clients/$DEVICE_NAME.key" | wg pubkey > "/etc/wireguard/clients/$DEVICE_NAME.pub"
wg genpsk > "/etc/wireguard/clients/$DEVICE_NAME.psk"

fill() {
  sed -e "s|<SERVER_PRIVATE_KEY>|$(cat /etc/wireguard/server.key)|" \
      -e "s|<SERVER_PUBLIC_KEY>|$(cat /etc/wireguard/server.pub)|" \
      -e "s|<DEVICE_PRIVATE_KEY>|$(cat "/etc/wireguard/clients/$DEVICE_NAME.key")|" \
      -e "s|<DEVICE_PUBLIC_KEY>|$(cat "/etc/wireguard/clients/$DEVICE_NAME.pub")|" \
      -e "s|<DEVICE_PRESHARED_KEY>|$(cat "/etc/wireguard/clients/$DEVICE_NAME.psk")|"
}
fill < wg0.conf > /etc/wireguard/wg0.conf
fill < client.conf > "/etc/wireguard/clients/$DEVICE_NAME.conf"

# Forward only tunnel traffic; block tunnel clients from the server's own private networks.
echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wireguard.conf && sysctl --system >/dev/null
cat > /etc/nftables.d-wireguard.nft <<NFT
table inet wg_filter {
  chain forward {
    type filter hook forward priority 0; policy drop;
    ct state established,related accept
    iifname "wg0" ip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 } drop
    iifname "wg0" oifname "$EGRESS_IF" accept
  }
}
table ip wg_nat {
  chain postrouting { type nat hook postrouting priority 100; oifname "$EGRESS_IF" ip saddr 10.66.66.0/24 masquerade; }
}
NFT
nft -f /etc/nftables.d-wireguard.nft
# Persist: include /etc/nftables.d-wireguard.nft from /etc/nftables.conf and enable nftables.service.

# Tunnel-only DNS resolver at 10.66.66.1 (the profile's DNS), not exposed publicly.
cat > /etc/unbound/unbound.conf.d/wireguard.conf <<UNB
server:
  interface: 10.66.66.1
  access-control: 10.66.66.0/24 allow
UNB

systemctl enable --now wg-quick@wg0
systemctl restart unbound
echo "Device profile: /etc/wireguard/clients/$DEVICE_NAME.conf (transfer once, then delete the copy)."
echo "Revoke a device: remove its [Peer] from wg0.conf and run: wg syncconf wg0 <(wg-quick strip wg0)"
`;

export const BLUEPRINT_FILES: BlueprintFile[] = [
  { name: "AndroidManifest.xml", path: "app/src/main/AndroidManifest.xml", lang: "xml", code: manifest },
  { name: "TunnelController.kt", path: "app/src/main/java/com/example/advancedtunnel/TunnelController.kt", lang: "kotlin", code: controller },
  { name: "MainActivity.kt", path: "app/src/main/java/com/example/advancedtunnel/MainActivity.kt", lang: "kotlin", code: mainActivity },
  { name: "SecureConfigStore.kt", path: "app/src/main/java/com/example/advancedtunnel/SecureConfigStore.kt", lang: "kotlin", code: secureStore },
  { name: "activity_main.xml", path: "app/src/main/res/layout/activity_main.xml", lang: "xml", code: layout },
  { name: "build.gradle.kts", path: "app/build.gradle.kts", lang: "kotlin", code: gradle },
  { name: "wg0.conf", path: "server/wg0.conf", lang: "ini", code: serverTemplate },
  { name: "client.conf", path: "server/client.conf", lang: "ini", code: clientTemplate },
  { name: "server-setup.sh", path: "server/server-setup.sh", lang: "bash", code: serverSetup },
];
