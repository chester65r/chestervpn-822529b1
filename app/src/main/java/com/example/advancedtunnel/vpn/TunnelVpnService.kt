package com.example.advancedtunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.advancedtunnel.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Android [VpnService] backed by the real native Go `sing-box` core (`io.nekohasekai.libbox`):
 * - Creates the OS TUN interface inside [LibboxPlatformBridge.openTun] (`172.19.0.1/30`, MTU 1500, `auto_route`, `strict_route`)
 * - Terminates all Layer 3 IPv4/IPv6 TCP + UDP/DNS traffic inside gVisor in native Go
 * - Protects outbound sockets via `VpnService.protect(fd)` (`autoDetectInterfaceControl`)
 * - Connects to Hetzner CX23 (`2.29.8.194`) via VLESS + XTLS-Vision + Reality (port 443) or Trojan + TLS (port 8443)
 *   with native uTLS (`chrome` / `ios`) and ClientHello fragmentation (`fragment` + `record_fragment`).
 */
class TunnelVpnService : VpnService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile
    private var activeSession: TunPacketForwarder.RunningSession? = null
    private var activeTunnelJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val manualDisconnect = AtomicBoolean(false)

    private var activeHost = DEFAULT_HOST
    private var activePort = DEFAULT_PORT
    private var activeSni = DEFAULT_SNI
    private var activeMtu = DEFAULT_MTU
    private var activeProtocol = SingBoxConfigBuilder.Protocol.VLESS_REALITY
    private var activeCdnHost = SingBoxConfigBuilder.DEFAULT_MOBILY_CDN_HOST
    private var activeWsPath = SingBoxConfigBuilder.DEFAULT_MOBILY_WS_PATH
    private var activePayloadJson = ""
    private var activeUuid = ""
    private var activeRealityPublicKey = ""
    private var activeRealityShortId = ""
    private var activeTrojanPassword = ""
    private var activeUtlsFingerprint = "chrome"
    private var activeFragmentEnabled = true
    private var activeRecordFragmentEnabled = true
    private var activeCarrierProfileId = "stc-ksa-5g-cgnat"

    private val reconnectionMonitor: ReconnectionMonitor by lazy {
        ReconnectionMonitor(
            serviceScope = serviceScope,
            isReconnectEnabled = { isRunning.get() && !manualDisconnect.get() },
            isInterfaceDown = { activeSession?.platformBridge?.activeTunDescriptor == null },
            isSocketDown = { activeSession == null },
            onPrepareReconnect = { reason ->
                tearDownTunnelResources()
                updateNotification("Reconnecting…", reason)
                TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
                TunnelStateHolder.setReconnectPhase("Reconnecting: $reason")
                true
            },
            onAttemptConnection = { attempt, maxAttempts ->
                updateNotification(
                    "Reconnecting ($attempt/$maxAttempts)",
                    "Dialing $activeHost:$activePort via libbox (${activeProtocol.id})"
                )
                establishVpnTunnel(isReconnectAttempt = true)
            },
            onReconnectSucceeded = { attempt ->
                TunnelStateHolder.setConnectionState(TunnelConnectionState.TunnelEstablished)
                updateNotification(
                    "Tunnel Console Active",
                    "Recovered on attempt $attempt ($activeHost:$activePort)"
                )
            },
            onReconnectExhausted = { errorMessage ->
                TunnelStateHolder.setConnectionState(TunnelConnectionState.Error(errorMessage))
                updateNotification("Connection Error", errorMessage)
            },
            onStatusUpdate = { statusText ->
                updateNotification("Tunnel Reconnecting", statusText)
            }
        )
    }

    companion object {
        private const val TAG = "TunnelVpnService"
        const val ACTION_START = "com.example.advancedtunnel.START_VPN"
        const val ACTION_STOP = "com.example.advancedtunnel.STOP_VPN"
        const val ACTION_CONNECT = "com.example.advancedtunnel.CONNECT"
        const val ACTION_DISCONNECT = "com.example.advancedtunnel.DISCONNECT"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_SNI = "extra_sni"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_CDN_HOST = "extra_cdn_host"
        const val EXTRA_WS_PATH = "extra_ws_path"
        const val EXTRA_UUID = "extra_uuid"
        const val EXTRA_REALITY_PUBLIC_KEY = "extra_reality_public_key"
        const val EXTRA_REALITY_SHORT_ID = "extra_reality_short_id"
        const val EXTRA_TROJAN_PASSWORD = "extra_trojan_password"
        const val EXTRA_UTLS_FINGERPRINT = "extra_utls_fingerprint"
        const val EXTRA_FRAGMENT_ENABLED = "extra_fragment_enabled"
        const val EXTRA_RECORD_FRAGMENT_ENABLED = "extra_record_fragment_enabled"

        const val DEFAULT_HOST = SingBoxConfigBuilder.DEFAULT_SERVER_HOST
        const val DEFAULT_PORT = SingBoxConfigBuilder.DEFAULT_VLESS_PORT
        const val DEFAULT_SNI = SingBoxConfigBuilder.DEFAULT_REALITY_SNI
        const val DEFAULT_MTU = SingBoxConfigBuilder.DEFAULT_TUN_MTU

        const val NOTIFICATION_CHANNEL_ID = "tunnel_console_channel"
        const val NOTIFICATION_ID = 1984
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_DISCONNECT -> stopVpn()
            else -> {
                intent?.let { incoming ->
                    val host = incoming.getStringExtra(EXTRA_HOST)
                        ?: incoming.getStringExtra("tunnel_ip")
                    if (!host.isNullOrBlank()) activeHost = host.trim()

                    val intPort = incoming.getIntExtra(EXTRA_PORT, -1)
                    val strPort = incoming.getStringExtra("tunnel_port")?.toIntOrNull()
                    activePort = when {
                        intPort in 1..65535 -> intPort
                        strPort != null && strPort in 1..65535 -> strPort
                        else -> activePort
                    }

                    val sni = incoming.getStringExtra(EXTRA_SNI)
                        ?: incoming.getStringExtra("tunnel_sni")
                    if (!sni.isNullOrBlank()) activeSni = sni.trim()

                    activeMtu = incoming.getIntExtra(EXTRA_MTU, activeMtu).coerceIn(1280, 1500)

                    (incoming.getStringExtra(EXTRA_PROTOCOL) ?: incoming.getStringExtra("tunnel_protocol"))?.let {
                        activeProtocol = SingBoxConfigBuilder.Protocol.fromId(it)
                    }
                    (incoming.getStringExtra(EXTRA_CDN_HOST) ?: incoming.getStringExtra("tunnel_cdn_host"))
                        ?.takeIf { it.isNotBlank() }?.let { activeCdnHost = it.trim() }
                    (incoming.getStringExtra(EXTRA_WS_PATH) ?: incoming.getStringExtra("tunnel_ws_path"))
                        ?.takeIf { it.isNotBlank() }?.let { activeWsPath = it.trim() }
                    incoming.getStringExtra("tunnel_payload")?.takeIf { it.isNotBlank() }?.let {
                        activePayloadJson = it.trim()
                    }
                    incoming.getStringExtra(EXTRA_UUID)?.takeIf { it.isNotBlank() }?.let {
                        activeUuid = it.trim()
                    }
                    incoming.getStringExtra(EXTRA_REALITY_PUBLIC_KEY)?.takeIf { it.isNotBlank() }?.let {
                        activeRealityPublicKey = it.trim()
                    }
                    incoming.getStringExtra(EXTRA_REALITY_SHORT_ID)?.takeIf { it.isNotBlank() }?.let {
                        activeRealityShortId = it.trim()
                    }
                    incoming.getStringExtra(EXTRA_TROJAN_PASSWORD)?.takeIf { it.isNotBlank() }?.let {
                        activeTrojanPassword = it
                    }
                    incoming.getStringExtra(EXTRA_UTLS_FINGERPRINT)?.takeIf { it.isNotBlank() }?.let {
                        activeUtlsFingerprint = SingBoxConfigBuilder.mapFingerprintProfileToUtls(it)
                    }
                    activeFragmentEnabled = incoming.getBooleanExtra(EXTRA_FRAGMENT_ENABLED, activeFragmentEnabled)
                    activeRecordFragmentEnabled =
                        incoming.getBooleanExtra(EXTRA_RECORD_FRAGMENT_ENABLED, activeRecordFragmentEnabled)

                    val profileId = incoming.getStringExtra(EXTRA_PROFILE_ID)
                    if (!profileId.isNullOrBlank()) activeCarrierProfileId = profileId.trim()
                }
                startVpn()
            }
        }
        return START_STICKY
    }

    private fun buildSingBoxClientConfig(): SingBoxConfigBuilder.ClientConfig {
        val parsed = SingBoxConfigBuilder.parseClientConfigFromJson(
            rawJson = activePayloadJson,
            fallbackHost = activeHost,
            fallbackPort = activePort,
            fallbackSni = activeSni,
            excludePackages = listOf(packageName)
        )
        if (parsed != null) return parsed

        return SingBoxConfigBuilder.ClientConfig(
            protocol = activeProtocol,
            serverHost = activeHost,
            serverPort = activePort,
            sniHost = activeSni,
            uuid = activeUuid,
            realityPublicKey = activeRealityPublicKey,
            realityShortId = activeRealityShortId,
            trojanPassword = activeTrojanPassword,
            wsHost = activeCdnHost,
            wsPath = activeWsPath,
            utlsFingerprint = activeUtlsFingerprint,
            tlsFragmentEnabled = activeFragmentEnabled,
            tlsRecordFragmentEnabled = activeRecordFragmentEnabled,
            mtu = activeMtu,
            strictRoute = true,
            excludePackages = listOf(packageName)
        )
    }

    private fun startVpn() {
        if (isRunning.get()) return
        manualDisconnect.set(false)
        isRunning.set(true)

        TunnelStateHolder.resetData()
        TunnelStateHolder.setConnectionState(TunnelConnectionState.Connecting)

        createNotificationChannel()
        val notification = buildNotification(
            "Connecting sing-box (${activeProtocol.id})",
            "$activeHost:$activePort ($activeSni)"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        activeTunnelJob = serviceScope.launch {
            val established = establishVpnTunnel(isReconnectAttempt = false)
            if (!established && isRunning.get() && !manualDisconnect.get()) {
                reconnectionMonitor.start("Initial libbox start failed")
            } else if (established) {
                reconnectionMonitor.startWatchdog()
            }
        }
    }

    private suspend fun establishVpnTunnel(isReconnectAttempt: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            tearDownTunnelResources()
            if (!isReconnectAttempt) {
                TunnelStateHolder.setConnectionState(TunnelConnectionState.Authenticating)
            }

            val session = TunPacketForwarder.startSingBoxCore(
                vpnService = this@TunnelVpnService,
                context = applicationContext,
                clientConfig = buildSingBoxClientConfig(),
                onCoreLog = { line -> Log.i(TAG, "[libbox] $line") },
                onNotification = { title, body -> updateNotification(title, body) }
            )
            activeSession = session

            TunnelStateHolder.setConnectionState(TunnelConnectionState.TunnelEstablished)
            updateNotification(
                "Tunnel Console Active (${activeProtocol.id})",
                "$activeHost:$activePort · uTLS=$activeUtlsFingerprint"
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "libbox tunnel failed: ${e.message}", e)
            tearDownTunnelResources()
            if (!isReconnectAttempt && !manualDisconnect.get() && isRunning.get()) {
                TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
                reconnectionMonitor.onIoException("sing-box startup error", e)
            }
            false
        }
    }

    private fun tearDownTunnelResources() {
        TunPacketForwarder.stopSingBoxCore(activeSession)
        activeSession = null
    }

    private fun stopVpn() {
        manualDisconnect.set(true)
        isRunning.set(false)
        reconnectionMonitor.stop()
        activeTunnelJob?.cancel()
        activeTunnelJob = null
        serviceJob.cancelChildren()
        tearDownTunnelResources()
        TunnelStateHolder.setConnectionState(TunnelConnectionState.Disconnected)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tunnel Console VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains persistent native sing-box (libbox) gVisor TUN + Anti-DPI tunnel"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        serviceJob.cancel()
        super.onDestroy()
    }
}
