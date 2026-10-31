export type BlueprintFile = { name: string; path: string; lang: string; code: string };

const manifest = String.raw`<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="Advanced Tunnel"
        android:theme="@style/Theme.AppCompat.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".AdvancedVpnService"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:foregroundServiceType="specialUse"
            android:exported="false">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="vpn" />
        </service>

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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}`;

const storage = String.raw`package com.example.advancedtunnel

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.io.Serializable

data class TunnelConfig(
    val serverIp: String,
    val serverPort: Int,
    val sniHost: String,
    val customPayload: String
) : Serializable

object PayloadEngine {
    const val DEFAULT_TEMPLATE =
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]"

    fun parse(rawPayload: String, sniHost: String): String {
        val template = if (rawPayload.isBlank()) DEFAULT_TEMPLATE else rawPayload
        val out = template
            .replace("[host]", sniHost)
            .replace("[crlf]", "\r\n")
            .replace("\\r\\n", "\r\n")
        return when {
            out.endsWith("\r\n\r\n") -> out
            out.endsWith("\r\n") -> out + "\r\n"
            else -> out + "\r\n\r\n"
        }
    }
}

class ConfigStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("TunnelPrefs", Context.MODE_PRIVATE)

    fun saveConfig(config: TunnelConfig) {
        prefs.edit().apply {
            putString("SERVER_IP", config.serverIp)
            putInt("SERVER_PORT", config.serverPort)
            putString("SNI_HOST", config.sniHost)
            putString("PAYLOAD", config.customPayload)
            apply()
        }
    }

    fun loadActiveConfig(): TunnelConfig {
        return TunnelConfig(
            serverIp = prefs.getString("SERVER_IP", "") ?: "",
            serverPort = prefs.getInt("SERVER_PORT", 443),
            sniHost = prefs.getString("SNI_HOST", "") ?: "",
            customPayload = prefs.getString("PAYLOAD", "") ?: ""
        )
    }
}

/** TUNNEL:// share codes. Base64 is encoding only, NOT encryption. */
object ConfigEncoder {
    private const val PREFIX = "TUNNEL://"

    fun exportToCode(config: TunnelConfig): String {
        val json = JSONObject().apply {
            put("ip", config.serverIp)
            put("port", config.serverPort)
            put("sni", config.sniHost)
            put("payload", config.customPayload)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun importFromCode(inputCode: String): TunnelConfig? {
        return try {
            val code = inputCode.trim()
            if (!code.startsWith(PREFIX)) return null
            val decoded = Base64.decode(code.substring(PREFIX.length), Base64.DEFAULT)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            TunnelConfig(
                serverIp = json.getString("ip"),
                serverPort = json.getInt("port"),
                sniHost = json.getString("sni"),
                customPayload = json.getString("payload")
            )
        } catch (e: Exception) {
            null
        }
    }
}`;

const service = String.raw`package com.example.advancedtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Educational TLS inspection skeleton, NOT a working VPN.
 * No compatible authenticated packet engine exists in these snippets.
 * Fail closed before creating a TUN interface; never report CONNECTED on TLS alone.
 */
class AdvancedVpnService : VpnService(), Runnable {

    companion object {
        const val ACTION_STOP = "ACTION_STOP"
        const val STATS_ACTION = "com.example.advancedtunnel.STATS_UPDATE"
        const val EXTRA_RX = "RX_SPEED"
        const val EXTRA_TX = "TX_SPEED"
        const val EXTRA_STATE = "STATE" // CONNECTING, CONNECTED, DISCONNECTED
        private const val CHANNEL_ID = "tunnel_status"
        private const val NOTIFICATION_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private var rawSocket: Socket? = null
    private var secureSocket: SSLSocket? = null

    @Volatile private var isRunning = false
    private val rxCounter = AtomicLong(0)
    private val txCounter = AtomicLong(0)

    private var serverIp = ""
    private var serverPort = 443
    private var sniHost = ""
    private var rawPayload = ""

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }

        // Do not overwrite an active worker's configuration or start a second one.
        if (isRunning || tunnelThread?.isAlive == true) return START_NOT_STICKY

        serverIp = intent?.getStringExtra("SERVER_IP") ?: ""
        serverPort = intent?.getIntExtra("SERVER_PORT", 443) ?: 443
        sniHost = intent?.getStringExtra("SNI_HOST") ?: ""
        rawPayload = intent?.getStringExtra("PAYLOAD") ?: ""

        if (serverIp.isBlank() || sniHost.isBlank() || serverPort !in 1..65535) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
        } catch (_: Exception) {
            broadcastState("DISCONNECTED")
            stopSelf()
            return START_NOT_STICKY
        }
        rxCounter.set(0)
        txCounter.set(0)
        isRunning = true
        broadcastState("CONNECTING")
        tunnelThread = Thread(this, "AdvancedTunnelThread").apply { start() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tunnel status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Advanced Tunnel")
            .setContentText("Tunnel is running")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun run() {
        try {
            // 1) Connect the control socket first and exclude it from the VPN so it cannot loop back.
            val raw = Socket()
            rawSocket = raw
            check(protect(raw)) { "VPN socket protection failed" }
            raw.connect(InetSocketAddress(serverIp, serverPort), 10_000)
            check(isRunning) { "Connection cancelled" }

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // Peer identity is the configured SNI name, not the TCP destination IP.
            val tls = factory.createSocket(raw, sniHost, serverPort, true) as SSLSocket
            secureSocket = tls
            tls.soTimeout = 10_000
            val params = tls.sslParameters
            params.serverNames = listOf(SNIHostName(sniHost))
            params.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = params
            tls.startHandshake()
            check(isRunning) { "Connection cancelled" }

            // This check always stops the educational sample before TUN activation.
            // Replace it only with a proven packet engine and authenticated peer acknowledgement.
            check(packetEngineAvailable()) { "No interoperable VPN packet engine is implemented" }

            tls.outputStream.write(PayloadEngine.parse(rawPayload, sniHost).toByteArray(Charsets.UTF_8))
            tls.outputStream.flush()

            // 2) Create the virtual interface.
            val vpn = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setSession("AdvancedTunnelEngine")
                .addDisallowedApplication(packageName)
                .establish() ?: throw IllegalStateException("VPN permission was revoked")
            vpnInterface = vpn

            val vpnInput = FileInputStream(vpn.fileDescriptor)
            val vpnOutput = FileOutputStream(vpn.fileDescriptor)
            val serverIn = tls.inputStream
            val serverOut = tls.outputStream

            broadcastState("CONNECTED")

            // Downlink: server -> device
            Thread {
                val buf = ByteArray(32768)
                try {
                    while (isRunning) {
                        val n = serverIn.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            vpnOutput.write(buf, 0, n)
                            rxCounter.addAndGet(n.toLong())
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VpnService", "Downstream channel closed.")
                }
                stopTunnel()
            }.start()

            // Statistics once per second
            Thread {
                try {
                    while (isRunning) {
                        Thread.sleep(1000)
                        val rx = rxCounter.getAndSet(0)
                        val tx = txCounter.getAndSet(0)
                        broadcastStats(rx, tx)
                    }
                } catch (_: InterruptedException) {
                }
            }.start()

            // Uplink: device -> server (blocking read, no busy loop)
            val buffer = ByteArray(32768)
            while (isRunning) {
                val n = vpnInput.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    serverOut.write(buffer, 0, n)
                    serverOut.flush()
                    txCounter.addAndGet(n.toLong())
                }
            }
        } catch (e: Exception) {
            // Never log exception details that can include peer or configuration data.
            Log.e("VpnService", "Tunnel stopped; native compatibility is not established.")
        } finally {
            stopTunnel()
        }
    }

    private fun packetEngineAvailable(): Boolean = false

    private fun broadcastStats(rx: Long, tx: Long) {
        sendBroadcast(Intent(STATS_ACTION).setPackage(packageName).apply {
            putExtra(EXTRA_RX, rx)
            putExtra(EXTRA_TX, tx)
        })
    }

    private fun broadcastState(state: String) {
        sendBroadcast(Intent(STATS_ACTION).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, state)
        })
    }

    @Synchronized
    private fun stopTunnel() {
        isRunning = false
        tunnelThread?.interrupt()
        try { secureSocket?.close() } catch (_: Exception) {}
        try { rawSocket?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {
            Log.e("VpnService", "Error while releasing the interface.", e)
        }
        vpnInterface = null
        secureSocket = null
        rawSocket = null
        broadcastState("DISCONNECTED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }
}`;

const mainActivity = String.raw`package com.example.advancedtunnel

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.advancedtunnel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ConfigStorage
    private var isVpnActive = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
            else Toast.makeText(this, "VPN permission required", Toast.LENGTH_LONG).show()
        }

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(AdvancedVpnService.EXTRA_STATE)?.let { applyState(it) }
            if (intent?.hasExtra(AdvancedVpnService.EXTRA_RX) == true) {
                val rx = intent.getLongExtra(AdvancedVpnService.EXTRA_RX, 0L)
                val tx = intent.getLongExtra(AdvancedVpnService.EXTRA_TX, 0L)
                binding.tvDownloadSpeed.text = String.format("%.2f KB/s", rx / 1024.0)
                binding.tvUploadSpeed.text = String.format("%.2f KB/s", tx / 1024.0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ConfigStorage(this)
        loadSavedProfile()

        binding.btnToggleVpn.setOnClickListener {
            if (isVpnActive) stopVpnService()
            else if (validateInputs()) {
                saveCurrentProfile()
                startVpnAuthorization()
            }
        }

        binding.btnExport.setOnClickListener {
            if (!validateInputs()) return@setOnClickListener
            val code = ConfigEncoder.exportToCode(readForm())
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Tunnel profile", code))
            Toast.makeText(this, "Profile code copied", Toast.LENGTH_SHORT).show()
        }

        binding.btnImport.setOnClickListener {
            val config = ConfigEncoder.importFromCode(binding.etImportCode.text.toString())
            if (config == null) {
                binding.etImportCode.error = "Invalid profile code"
            } else {
                fillForm(config)
                Toast.makeText(this, "Profile imported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AdvancedVpnService.STATS_ACTION)
        ContextCompat.registerReceiver(this, statsReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(statsReceiver)
        super.onStop()
    }

    private fun applyState(state: String) {
        isVpnActive = state != "DISCONNECTED"
        binding.tvStatusInfo.text = state
        binding.btnToggleVpn.text = if (isVpnActive) "STOP TUNNEL" else "START TUNNEL"
        if (!isVpnActive) {
            binding.tvDownloadSpeed.text = "0.00 KB/s"
            binding.tvUploadSpeed.text = "0.00 KB/s"
        }
    }

    private fun validateInputs(): Boolean {
        val ip = binding.etServerIp.text.toString().trim()
        val port = binding.etServerPort.text.toString().trim().toIntOrNull()
        val sni = binding.etSniHost.text.toString().trim()
        if (ip.isEmpty()) {
            binding.etServerIp.error = "Server IP cannot be empty"
            return false
        }
        if (port == null || port !in 1..65535) {
            binding.etServerPort.error = "Enter a valid port (1-65535)"
            return false
        }
        if (sni.isEmpty()) {
            binding.etSniHost.error = "SNI host required"
            return false
        }
        return true
    }

    private fun readForm() = TunnelConfig(
        serverIp = binding.etServerIp.text.toString().trim(),
        serverPort = binding.etServerPort.text.toString().trim().toInt(),
        sniHost = binding.etSniHost.text.toString().trim(),
        customPayload = binding.etPayload.text.toString()
    )

    private fun fillForm(config: TunnelConfig) {
        binding.etServerIp.setText(config.serverIp)
        binding.etServerPort.setText(config.serverPort.toString())
        binding.etSniHost.setText(config.sniHost)
        binding.etPayload.setText(config.customPayload)
    }

    private fun loadSavedProfile() = fillForm(storage.loadActiveConfig())

    private fun saveCurrentProfile() = storage.saveConfig(readForm())

    private fun startVpnAuthorization() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpnService()
    }

    private fun startVpnService() {
        val config = storage.loadActiveConfig()
        val serviceIntent = Intent(this, AdvancedVpnService::class.java).apply {
            putExtra("SERVER_IP", config.serverIp)
            putExtra("SERVER_PORT", config.serverPort)
            putExtra("SNI_HOST", config.sniHost)
            putExtra("PAYLOAD", config.customPayload)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopVpnService() {
        startService(Intent(this, AdvancedVpnService::class.java).apply {
            action = AdvancedVpnService.ACTION_STOP
        })
    }
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

        <EditText android:id="@+id/etServerIp"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Server IP or domain" android:inputType="textUri" />

        <EditText android:id="@+id/etServerPort"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Port" android:inputType="number" />

        <EditText android:id="@+id/etSniHost"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Server name (SNI)" android:inputType="textUri" />

        <EditText android:id="@+id/etPayload"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Handshake payload ([host], [crlf])"
            android:inputType="textMultiLine" android:minLines="3" />

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

        <Button android:id="@+id/btnToggleVpn"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="START TUNNEL" />

        <Button android:id="@+id/btnExport"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Copy profile code" />

        <EditText android:id="@+id/etImportCode"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Paste TUNNEL:// code" android:inputType="textMultiLine" />

        <Button android:id="@+id/btnImport"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Import profile" />
    </LinearLayout>
</ScrollView>`;

const relay = String.raw`// relay-server.mjs — Educational WebSocket → TCP relay
//
// Run this on a server YOU own (e.g. a VPS). It accepts WebSocket connections
// and forwards a byte stream for a compatible WebSocket client. This is NOT
// compatible with the raw TLS Android blueprint and does not forward TUN packets.
// Loopback-only demonstration with a shared credential and bounded streams.
// It is not a production VPN server. TLS, credential lifecycle, client-specific
// permissions, DNS/IP policy and deployment/load tests remain external requirements.
//
// Setup:
//   1. Install Node.js 18+ on your server
//   2. npm install ws
//   3. Supply RELAY_TOKEN through your server's secret manager (at least 32 characters).
//      Set ALLOWED_TARGETS=your-service.example.com:443 PORT=8443, then run this file.
//   4. Keep loopback binding; any exposure requires independently verified TLS and access controls.
//
// Only relay traffic you are authorized to carry. Keep the allowlist tight.

import { WebSocketServer } from "ws";
import net from "node:net";
import { timingSafeEqual } from "node:crypto";

const PORT = Number(process.env.PORT || 8443);
const TOKEN = process.env.RELAY_TOKEN;
const TARGETS = (process.env.ALLOWED_TARGETS || "").split(",").map(t => t.trim()).filter(Boolean);
if (!Number.isInteger(PORT) || PORT < 1 || PORT > 65535 || !TOKEN || TOKEN.length < 32 || !TARGETS.length) {
  throw new Error("Require valid PORT, RELAY_TOKEN (32+ characters) and explicit ALLOWED_TARGETS");
}
const MAX_BUFFER = 256 * 1024;
const MAX_CONNECTIONS = 20;
const wss = new WebSocketServer({ host: "127.0.0.1", port: PORT, maxPayload: 64 * 1024, perMessageDeflate: false });

wss.on("connection", (ws) => {
  let upstream = null;
  let state = "handshake";
  let lastActivity = Date.now();
  const close = (code = 1008, reason = "connection refused") => {
    state = "closed";
    upstream?.destroy();
    ws.close(code, reason);
  };
  if (wss.clients.size > MAX_CONNECTIONS) { close(1013, "capacity reached"); return; }
  const deadline = setTimeout(() => close(1008, "handshake timeout"), 5000);
  const watchdog = setInterval(() => {
    if (Date.now() - lastActivity > 30_000) close(1008, "idle timeout");
    if (state === "closed") ws.terminate();
  }, 1000);

  ws.on("message", (data, isBinary) => {
    lastActivity = Date.now();
    if (state === "handshake") {
      // Credential stays outside app profiles: JSON {token, host, port} over verified TLS only.
      let target;
      try {
        if (isBinary || data.length > 4096) throw new Error("invalid handshake");
        target = JSON.parse(data.toString());
      } catch {
        close(1002, "invalid handshake");
        return;
      }
      if (!target || typeof target !== "object" || Array.isArray(target) || typeof target.token !== "string" || typeof target.host !== "string" || !Number.isInteger(target.port)) {
        close(); return;
      }
      const supplied = Buffer.from(target.token);
      const expected = Buffer.from(TOKEN);
      if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) { close(); return; }
      const { host, port } = target;
      if (!/^[a-zA-Z0-9.-]+$/.test(host) || port < 1 || port > 65535 || !TARGETS.includes(host + ":" + port)) {
        close();
        return;
      }
      clearTimeout(deadline);
      state = "connecting";
      upstream = net.connect({ port, host });
      upstream.setTimeout(10_000, () => close(1011, "upstream timeout"));
      upstream.on("connect", () => {
        if (state !== "connecting") { upstream.destroy(); return; }
        state = "ready";
        upstream.setTimeout(30_000);
        ws.send(JSON.stringify({ ok: true }));
      });
      upstream.on("data", chunk => {
        lastActivity = Date.now();
        if (ws.readyState !== 1 || ws.bufferedAmount + chunk.length > MAX_BUFFER) { close(1009, "buffer limit"); return; }
        ws.send(chunk, { binary: true }, error => { if (error) close(1011, "stream error"); });
      });
      upstream.on("close", () => close(1000, "upstream closed"));
      upstream.on("error", () => close(1011, "upstream error"));
      return;
    }
    if (state !== "ready" || !upstream || !isBinary) { close(1002, "stream not ready"); return; }
    if (upstream.writableLength + data.length > MAX_BUFFER) { close(1009, "buffer limit"); return; }
    upstream.write(data);
  });

  ws.on("error", () => close(1011, "stream error"));
  ws.on("close", () => { state = "closed"; clearTimeout(deadline); clearInterval(watchdog); upstream?.destroy(); });
});

wss.on("listening", () => console.log("Educational relay listening on loopback; production compatibility is unverified."));
`;

export const BLUEPRINT_FILES: BlueprintFile[] = [
  { name: "AndroidManifest.xml", path: "app/src/main/AndroidManifest.xml", lang: "xml", code: manifest },
  { name: "AdvancedVpnService.kt", path: "app/src/main/java/com/example/advancedtunnel/AdvancedVpnService.kt", lang: "kotlin", code: service },
  { name: "MainActivity.kt", path: "app/src/main/java/com/example/advancedtunnel/MainActivity.kt", lang: "kotlin", code: mainActivity },
  { name: "TunnelData.kt", path: "app/src/main/java/com/example/advancedtunnel/TunnelData.kt", lang: "kotlin", code: storage },
  { name: "activity_main.xml", path: "app/src/main/res/layout/activity_main.xml", lang: "xml", code: layout },
  { name: "build.gradle.kts", path: "app/build.gradle.kts", lang: "kotlin", code: gradle },
  { name: "relay-server.mjs", path: "relay/relay-server.mjs", lang: "javascript", code: relay },
];
