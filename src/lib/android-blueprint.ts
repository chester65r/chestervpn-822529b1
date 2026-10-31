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
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Educational skeleton. It forwards raw IP packets over a TLS socket, so it only works
 * against a server YOU run that understands this framing (for example a custom relay).
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
    @Volatile private var rxCounter = 0L
    @Volatile private var txCounter = 0L

    private var serverIp = ""
    private var serverPort = 443
    private var sniHost = ""
    private var rawPayload = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }

        serverIp = intent?.getStringExtra("SERVER_IP") ?: ""
        serverPort = intent?.getIntExtra("SERVER_PORT", 443) ?: 443
        sniHost = intent?.getStringExtra("SNI_HOST") ?: ""
        rawPayload = intent?.getStringExtra("PAYLOAD") ?: ""

        if (serverIp.isBlank() || sniHost.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
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
            protect(raw)
            raw.connect(InetSocketAddress(serverIp, serverPort), 10_000)
            rawSocket = raw

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, serverIp, serverPort, true) as SSLSocket
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(sniHost))
            tls.sslParameters = params
            tls.startHandshake()
            secureSocket = tls

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
                            rxCounter += n
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
                        val rx = rxCounter.also { rxCounter = 0 }
                        val tx = txCounter.also { txCounter = 0 }
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
                    txCounter += n
                }
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Tunnel runtime error.", e)
        } finally {
            stopTunnel()
        }
    }

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
        if (!isRunning) return
        isRunning = false
        try { secureSocket?.close() } catch (_: Exception) {}
        try { rawSocket?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {
            Log.e("VpnService", "Error while releasing the interface.", e)
        }
        vpnInterface = null
        broadcastState("DISCONNECTED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
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
// Do not expose this sample publicly without client authentication, connection
// limits, timeouts and backpressure handling. It is not a production VPN server.
//
// Setup:
//   1. Install Node.js 18+ on your server
//   2. npm install ws
//   3. ALLOWED_HOSTS=your-service.example.com PORT=8443 node relay-server.mjs
//   4. Put a TLS terminator (Caddy/nginx) in front for wss:// in production
//
// Only relay traffic you are authorized to carry. Keep the allowlist tight.

import { WebSocketServer } from "ws";
import net from "node:net";

const PORT = Number(process.env.PORT || 8443);

// Hosts this relay will connect to. Edit for your own infrastructure.
const ALLOWED_HOSTS = (process.env.ALLOWED_HOSTS || "example.com,example.org")
  .split(",")
  .map((h) => h.trim())
  .filter(Boolean);

const wss = new WebSocketServer({ port: PORT });

wss.on("connection", (ws) => {
  let upstream = null;

  ws.on("message", (data) => {
    if (!upstream) {
      // First message must be JSON: {"host":"example.com","port":443}
      let target;
      try {
        target = JSON.parse(data.toString());
      } catch {
        ws.close(1002, "first message must be JSON target");
        return;
      }
      const host = String(target.host || "");
      const port = Number(target.port || 0);
      if (!ALLOWED_HOSTS.includes(host) || port < 1 || port > 65535) {
        ws.close(1008, "target not allowed");
        return;
      }
      upstream = net.connect(port, host, () => ws.send(JSON.stringify({ ok: true })));
      upstream.on("data", (chunk) => ws.send(chunk));
      upstream.on("close", () => ws.close());
      upstream.on("error", () => ws.close(1011, "upstream error"));
      return;
    }
    upstream.write(data);
  });

  ws.on("close", () => upstream?.destroy());
});

console.log("relay listening on :" + PORT + ", allowlist: " + ALLOWED_HOSTS.join(", "));
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
