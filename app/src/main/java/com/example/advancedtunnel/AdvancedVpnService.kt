package com.example.advancedtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.example.advancedtunnel.vpn.KeepAliveManager
import com.example.advancedtunnel.vpn.SingBoxConfigBuilder
import com.example.advancedtunnel.vpn.TlsFragmentConfig
import com.example.advancedtunnel.vpn.TlsFragmentMode
import com.example.advancedtunnel.vpn.TunPacketForwarder
import com.example.advancedtunnel.vpn.VpnManager
import com.example.advancedtunnel.wireguard.WireGuardManager
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified Foreground VpnService (`AdvancedVpnService`) managing:
 * - Native `sing-box` / `libbox` (`io.nekohasekai.libbox`) gVisor TUN + VLESS-Reality (`xtls-rprx-vision`)
 *   or Trojan over TLS 443 with uTLS (Chrome/iOS) and ClientHello record fragmentation
 * - Official WireGuard `GoBackend.VpnService` integration for standard `wg-quick` `.conf` tunnels
 * - `KeepAliveManager` (`PARTIAL_WAKE_LOCK`, `WIFI_MODE_FULL_LOW_LATENCY`)
 * - Android 14+ (`UPSIDE_DOWN_CAKE`) `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` compliance
 */
class AdvancedVpnService : GoBackend.VpnService() {

    companion object {
        private const val TAG = "AdvancedVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "tunnel_console_vpn_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var singBoxSession: TunPacketForwarder.RunningSession? = null
    private val isRunning = AtomicBoolean(false)
    private val sessionIdGenerator = AtomicLong(0L)
    private var keepAliveManager: KeepAliveManager? = null

    private var serverIp = SingBoxConfigBuilder.DEFAULT_SERVER_HOST
    private var serverPort = SingBoxConfigBuilder.DEFAULT_VLESS_PORT
    private var sniHost = SingBoxConfigBuilder.DEFAULT_REALITY_SNI
    private var protocol = "vless-reality"
    private var cdnHost = SingBoxConfigBuilder.DEFAULT_MOBILY_CDN_HOST
    private var wsPath = SingBoxConfigBuilder.DEFAULT_MOBILY_WS_PATH
    private var rawPayloadJson = ""
    private var uuid = SingBoxConfigBuilder.DEFAULT_UUID
    private var realityPublicKey = SingBoxConfigBuilder.DEFAULT_REALITY_PUBLIC_KEY
    private var realityShortId = SingBoxConfigBuilder.DEFAULT_REALITY_SHORT_ID
    private var trojanPassword = ""
    private var killswitchEnabled = false
    private var disallowedApps = ArrayList<String>()
    private var tlsFragmentConfig = TlsFragmentConfig()

    override fun onCreate() {
        super.onCreate()
        keepAliveManager = KeepAliveManager(
            context = applicationContext,
            scope = serviceScope,
            onReconnectRequired = { reason ->
                VpnManager.appendTelemetryLog("WARN", "KeepAlive recovery triggered: $reason")
                false
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            stopTunnel("User requested disconnect", stopService = true)
            return START_NOT_STICKY
        }

        protocol = intent?.getStringExtra("PROTOCOL") ?: "vless-reality"

        if (protocol == "wireguard") {
            val wgConfigText = intent?.getStringExtra("WG_CONFIG_TEXT") ?: ""
            if (wgConfigText.isBlank()) {
                VpnManager.onServiceStopped("WireGuard config is required")
                stopSelf()
                return START_NOT_STICKY
            }
            if (isRunning.get()) {
                closeActiveResources()
                WireGuardManager.stopTunnelBlocking(applicationContext)
            }
            isRunning.set(true)
            keepAliveManager?.acquireLocks()
            showForegroundNotification("Establishing WireGuard tunnel...")
            serviceScope.launch {
                val parseResult = runCatching { WireGuardManager.parseConfig(wgConfigText) }
                if (parseResult.isFailure) {
                    val err = parseResult.exceptionOrNull()?.localizedMessage ?: "Invalid WireGuard config"
                    stopTunnel(err, stopService = true)
                    return@launch
                }
                val config = parseResult.getOrThrow()
                val peerEndpoint = config.peers.firstOrNull()?.endpoint?.map { it.toString() }?.orElse("WireGuard Server") ?: "WireGuard Server"
                val startResult = WireGuardManager.startTunnel(applicationContext, config)
                if (startResult.isSuccess) {
                    showForegroundNotification("WireGuard Connected • $peerEndpoint")
                    VpnManager.onServiceConnected("WireGuard • $peerEndpoint")
                } else {
                    val err = startResult.exceptionOrNull()?.localizedMessage ?: "WireGuard GoBackend error"
                    stopTunnel(err, stopService = true)
                }
            }
            return START_STICKY
        }

        serverIp = intent?.getStringExtra("SERVER_IP")?.ifBlank { SingBoxConfigBuilder.DEFAULT_SERVER_HOST }
            ?: SingBoxConfigBuilder.DEFAULT_SERVER_HOST
        serverPort = intent?.getIntExtra("SERVER_PORT", SingBoxConfigBuilder.DEFAULT_VLESS_PORT)
            ?: SingBoxConfigBuilder.DEFAULT_VLESS_PORT
        sniHost = intent?.getStringExtra("SNI_HOST")?.ifBlank { SingBoxConfigBuilder.DEFAULT_REALITY_SNI }
            ?: SingBoxConfigBuilder.DEFAULT_REALITY_SNI
        cdnHost = intent?.getStringExtra("CDN_HOST")?.ifBlank { SingBoxConfigBuilder.DEFAULT_MOBILY_CDN_HOST }
            ?: SingBoxConfigBuilder.DEFAULT_MOBILY_CDN_HOST
        wsPath = intent?.getStringExtra("WS_PATH")?.ifBlank { SingBoxConfigBuilder.DEFAULT_MOBILY_WS_PATH }
            ?: SingBoxConfigBuilder.DEFAULT_MOBILY_WS_PATH
        rawPayloadJson = intent?.getStringExtra("PAYLOAD") ?: ""
        uuid = intent?.getStringExtra("UUID")?.ifBlank { SingBoxConfigBuilder.DEFAULT_UUID }
            ?: SingBoxConfigBuilder.DEFAULT_UUID
        realityPublicKey = intent?.getStringExtra("REALITY_PUBLIC_KEY")?.ifBlank { SingBoxConfigBuilder.DEFAULT_REALITY_PUBLIC_KEY }
            ?: SingBoxConfigBuilder.DEFAULT_REALITY_PUBLIC_KEY
        realityShortId = intent?.getStringExtra("REALITY_SHORT_ID")?.ifBlank { SingBoxConfigBuilder.DEFAULT_REALITY_SHORT_ID }
            ?: SingBoxConfigBuilder.DEFAULT_REALITY_SHORT_ID
        trojanPassword = intent?.getStringExtra("TROJAN_PASSWORD") ?: ""
        killswitchEnabled = intent?.getBooleanExtra("KILLSWITCH_ENABLED", false) ?: false
        disallowedApps = intent?.getStringArrayListExtra("DISALLOWED_APPS") ?: ArrayList()

        val fragEnabled = intent?.getBooleanExtra("TLS_FRAG_ENABLED", true) ?: true
        val fragModeId = intent?.getStringExtra("TLS_FRAG_MODE") ?: TlsFragmentMode.SNI_BOUNDARY_SPLIT.id
        val minChunk = intent?.getIntExtra("TLS_FRAG_MIN_CHUNK", 5) ?: 5
        val maxChunk = intent?.getIntExtra("TLS_FRAG_MAX_CHUNK", 15) ?: 15
        val ja3ProfileId = intent?.getStringExtra("TLS_JA3_PROFILE_ID") ?: "chrome_128_android"
        val alpnEnabled = intent?.getBooleanExtra("TLS_ALPN_ENABLED", true) ?: true

        tlsFragmentConfig = TlsFragmentConfig(
            enabled = fragEnabled,
            mode = TlsFragmentMode.fromId(fragModeId),
            minChunkBytes = minChunk,
            maxChunkBytes = maxChunk,
            fingerprintProfileId = ja3ProfileId,
            alpnEnabled = alpnEnabled
        )

        if (intent == null || intent.getStringExtra("SERVER_IP").isNullOrBlank()) {
            VpnManager.appendTelemetryLog(
                "INFO",
                "Always-On VPN start triggered using pre-configured endpoint ($serverIp:$serverPort, protocol=$protocol)"
            )
        }

        if (isRunning.get()) {
            Log.w(TAG, "Tunnel already running; closing previous libbox session before reconnect")
            closeActiveResources()
        }

        isRunning.set(true)
        keepAliveManager?.acquireLocks()
        val currentSession = sessionIdGenerator.incrementAndGet()
        showForegroundNotification("Connecting sing-box -> $serverIp:$serverPort…")

        serviceScope.launch {
            runSingBoxSession(currentSession)
        }

        return START_STICKY
    }

    private fun runSingBoxSession(mySessionId: Long) {
        try {
            val sbProtocol = SingBoxConfigBuilder.Protocol.fromId(protocol)
            val utlsFp = SingBoxConfigBuilder.mapFingerprintProfileToUtls(tlsFragmentConfig.fingerprintProfileId)
            val excludeList = (disallowedApps + packageName).distinct()

            val parsedFromPayload = SingBoxConfigBuilder.parseClientConfigFromJson(
                rawJson = rawPayloadJson,
                fallbackHost = serverIp,
                fallbackPort = serverPort,
                fallbackSni = sniHost,
                excludePackages = excludeList
            )

            val clientConfig = parsedFromPayload ?: SingBoxConfigBuilder.ClientConfig(
                protocol = sbProtocol,
                serverHost = serverIp,
                serverPort = serverPort,
                sniHost = sniHost,
                uuid = uuid,
                realityPublicKey = realityPublicKey,
                realityShortId = realityShortId,
                trojanPassword = trojanPassword,
                wsHost = cdnHost,
                wsPath = wsPath,
                utlsFingerprint = utlsFp,
                tlsFragmentEnabled = tlsFragmentConfig.useFragment(),
                tlsRecordFragmentEnabled = tlsFragmentConfig.useRecordFragment(),
                sniSplitMinBytes = tlsFragmentConfig.normalizedMinChunk(),
                sniSplitMaxBytes = tlsFragmentConfig.normalizedMaxChunk(),
                strictRoute = true,
                excludePackages = excludeList
            )

            VpnManager.appendTelemetryLog(
                "INFO",
                "Starting native libbox (sing-box gVisor TUN): ${clientConfig.protocol.id} -> ${clientConfig.serverHost}:${clientConfig.serverPort} (SNI=${clientConfig.sniHost}, uTLS=${clientConfig.utlsFingerprint}, fragment=${clientConfig.tlsFragmentEnabled}, record_fragment=${clientConfig.tlsRecordFragmentEnabled})"
            )

            val session = TunPacketForwarder.startSingBoxCore(
                vpnService = this@AdvancedVpnService,
                context = applicationContext,
                clientConfig = clientConfig,
                onCoreLog = { line ->
                    VpnManager.appendTelemetryLog("CORE", line)
                },
                onNotification = { title, body ->
                    showForegroundNotification("$title: $body")
                }
            )

            if (sessionIdGenerator.get() != mySessionId || !isRunning.get()) {
                TunPacketForwarder.stopSingBoxCore(session)
                return
            }

            singBoxSession = session
            VpnManager.onServiceConnected("sing-box (${sbProtocol.id}) • $serverIp:$serverPort")
            showForegroundNotification("Connected (${sbProtocol.id}) -> $serverIp:$serverPort")
        } catch (err: Throwable) {
            Log.e(TAG, "libbox core startup failed: ${err.message}", err)
            if (sessionIdGenerator.get() == mySessionId) {
                stopTunnel(
                    reason = err.localizedMessage ?: "Failed to start native libbox core",
                    stopService = true
                )
            }
        }
    }

    private fun closeActiveResources() {
        TunPacketForwarder.stopSingBoxCore(singBoxSession)
        singBoxSession = null
    }

    private fun showForegroundNotification(statusText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tunnel Console VPN status and telemetry"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Tunnel Console VPN (sing-box)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopTunnel(reason: String? = null, stopService: Boolean = true) {
        val wasRunning = isRunning.getAndSet(false)
        if (protocol == "wireguard") {
            WireGuardManager.stopTunnelBlocking(applicationContext)
        }
        keepAliveManager?.stopMonitoringAndReleaseLocks()
        closeActiveResources()
        if (wasRunning || reason != null) {
            VpnManager.onServiceStopped(reason)
        }
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopTunnel("VPN permission revoked by system", stopService = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel("Service destroyed", stopService = false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
