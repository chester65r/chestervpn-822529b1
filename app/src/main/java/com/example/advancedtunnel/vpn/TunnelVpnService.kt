package com.example.advancedtunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.advancedtunnel.MainActivity
import com.example.advancedtunnel.model.TunnelProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class TunnelVpnService : VpnService() {

    companion object {
        private const val TAG = "TunnelVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "tunnel_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BUFFER_SIZE = 32768
        private const val VPN_MTU = 1500

        const val ACTION_CONNECT = "com.example.advancedtunnel.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.advancedtunnel.ACTION_DISCONNECT"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelSocket: Socket? = null
    private var keepAliveManager: KeepAliveManager? = null
    private var serviceScope: CoroutineScope? = null
    private var forwardingJob: Job? = null

    private val transportManager = MultiTransportTunnelManager()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TunnelVpnService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_CONNECT -> {
                val profile = extractProfileFromIntent(intent)
                cleanupResources()
                startForegroundTunnel(profile)
            }

            ACTION_DISCONNECT -> {
                stopTunnel()
            }
        }

        return START_STICKY
    }

    private fun startForegroundTunnel(profile: TunnelProfile) {
        createNotificationChannel()

        val notification = buildNotification("Preparing tunnel...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        TunnelStateHolder.resetData()
        TunnelStateHolder.setConnectionState(TunnelConnectionState.Connecting)

        if (serviceScope == null || serviceScope?.isActive != true) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        serviceScope?.launch {
            try {
                updateNotification("Establishing secure tunnel...")

                val builder = Builder()
                    .setSession("Tunnel Console")
                    .setMtu(VPN_MTU)
                    .addAddress("10.8.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .setBlocking(true)

                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("Failed to establish VPN interface.")

                Log.i(TAG, "VPN interface established.")

                TunnelStateHolder.setConnectionState(TunnelConnectionState.Authenticating)
                updateNotification("Applying anti-DPI transport...")

                val tunnelEstablished = establishTunnelConnection(profile)

                if (tunnelEstablished) {
                    TunnelStateHolder.setConnectionState(TunnelConnectionState.TunnelEstablished)
                    updateNotification(
                        "Tunnel active | ${transportManager.getActiveTransport()}"
                    )

                    startTrafficForwarding()

                    Log.i(TAG, "Tunnel established and traffic forwarding started.")
                } else {
                    TunnelStateHolder.setConnectionState(
                        TunnelConnectionState.Error("Tunnel establishment failed.")
                    )
                    stopTunnel()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Fatal tunnel error", e)
                TunnelStateHolder.setConnectionState(
                    TunnelConnectionState.Error(e.message ?: "Unknown tunnel error")
                )
                stopTunnel()
            }
        }
    }

    private suspend fun establishTunnelConnection(profile: TunnelProfile): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val targetHost = profile.ip.ifBlank { "127.0.0.1" }
                val targetPort = profile.port.toIntOrNull() ?: 443

                TunnelStateHolder.setConnectionState(TunnelConnectionState.Connecting)

                val corridors = BugHostProbeEngine.scanBypassCorridors(
                    candidateHosts = listOf(targetHost),
                    ports = listOf(targetPort, 443, 80, 8080).distinct()
                )

                val bestCorridor = corridors.firstOrNull()

                val finalHost = bestCorridor?.host ?: targetHost
                val finalPort = bestCorridor?.port ?: targetPort

                if (bestCorridor != null) {
                    Log.i(
                        TAG,
                        "Best corridor selected: ${bestCorridor.host}:${bestCorridor.port} " +
                            "latency=${bestCorridor.latencyMs}ms"
                    )
                } else {
                    Log.w(TAG, "No corridor found. Trying direct route.")
                }

                transportManager.switchTransport(
                    MultiTransportTunnelManager.TransportType.HTTP_CUSTOM_PAYLOAD
                )

                val rawSocket = Socket()

                if (!protect(rawSocket)) {
                    Log.w(TAG, "Socket protect failed. Possible VPN routing loop risk.")
                }

                rawSocket.connect(InetSocketAddress(finalHost, finalPort), 5000)
                rawSocket.keepAlive = true
                rawSocket.tcpNoDelay = true
                rawSocket.receiveBufferSize = BUFFER_SIZE
                rawSocket.sendBufferSize = BUFFER_SIZE

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory

                val secureSocket = factory.createSocket(
                    rawSocket,
                    finalHost,
                    finalPort,
                    true
                ) as SSLSocket

                val sslParams = SSLParameters()

                if (profile.sni.isNotBlank()) {
                    sslParams.serverNames = listOf(SNIHostName(profile.sni))
                }

                secureSocket.sslParameters = sslParams
                secureSocket.startHandshake()

                Log.i(TAG, "TLS handshake completed. SNI=${profile.sni}")

                val payloadTemplate = profile.payload.ifBlank {
                    "GET / HTTP/1.1[crlf]" +
                        "Host: [host][crlf]" +
                        "Connection: Upgrade[crlf]" +
                        "Upgrade: websocket[crlf]" +
                        "Sec-WebSocket-Version: 13[crlf]" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==[crlf]" +
                        "[crlf]"
                }

                uTLSEvasionEngine.sendFragmentedClientHello(
                    secureSocket = secureSocket,
                    sniHost = profile.sni.ifBlank { finalHost },
                    payloadTemplate = payloadTemplate,
                    chunkSize = 8,
                    jitterDelayMs = 12L
                )

                tunnelSocket = secureSocket

                keepAliveManager = KeepAliveManager(
                    outputStreamProvider = { secureSocket.outputStream },
                    intervalMs = 20000L
                )

                keepAliveManager?.start()

                true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish tunnel connection", e)
                false
            }
        }
    }

    private fun startTrafficForwarding() {
        val vpnFd = vpnInterface ?: return
        val tunnel = tunnelSocket ?: return

        forwardingJob = serviceScope?.launch {
            try {
                val vpnInput = FileInputStream(vpnFd.fileDescriptor)
                val vpnOutput = FileOutputStream(vpnFd.fileDescriptor)

                val tunnelInput = tunnel.getInputStream()
                val tunnelOutput = tunnel.getOutputStream()

                val vpnBuffer = ByteArray(BUFFER_SIZE)
                val tunnelBuffer = ByteArray(BUFFER_SIZE)

                val vpnToTunnel = launch {
                    try {
                        while (isActive) {
                            val bytesRead = vpnInput.read(vpnBuffer)
                            if (bytesRead == -1) break

                            tunnelOutput.write(vpnBuffer, 0, bytesRead)
                            tunnelOutput.flush()

                            TunnelStateHolder.addData(bytesRead.toLong())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "VPN -> Tunnel forwarding error", e)
                    }
                }

                val tunnelToVpn = launch {
                    try {
                        while (isActive) {
                            val bytesRead = tunnelInput.read(tunnelBuffer)
                            if (bytesRead == -1) break

                            vpnOutput.write(tunnelBuffer, 0, bytesRead)
                            vpnOutput.flush()

                            TunnelStateHolder.addData(bytesRead.toLong())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Tunnel -> VPN forwarding error", e)
                    }
                }

                while (vpnToTunnel.isActive && tunnelToVpn.isActive && isActive) {
                    delay(250)
                }

                vpnToTunnel.cancel()
                tunnelToVpn.cancel()

                Log.i(TAG, "Traffic forwarding loop ended.")

            } catch (e: Exception) {
                Log.e(TAG, "Traffic forwarding fatal error", e)
            }
        }
    }

    private fun extractProfileFromIntent(intent: Intent): TunnelProfile {
        return TunnelProfile(
            ip = intent.getStringExtra("tunnel_ip") ?: "",
            port = intent.getStringExtra("tunnel_port") ?: "443",
            sni = intent.getStringExtra("tunnel_sni") ?: "",
            payload = intent.getStringExtra("tunnel_payload") ?: ""
        )
    }

    private fun cleanupResources() {
        forwardingJob?.cancel()
        forwardingJob = null

        keepAliveManager?.stop()
        keepAliveManager = null

        try {
            tunnelSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing tunnel socket", e)
        }

        tunnelSocket = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }

        vpnInterface = null

        TunnelStateHolder.setConnectionState(TunnelConnectionState.Disconnected)
        TunnelStateHolder.resetData()

        Log.i(TAG, "Tunnel resources cleaned.")
    }

    fun stopTunnel() {
        Log.i(TAG, "Stopping tunnel...")

        cleanupResources()

        serviceScope?.cancel()
        serviceScope = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Tunnel service stopped.")
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.w(TAG, "VPN revoked by system or user.")
        stopTunnel()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
        Log.i(TAG, "TunnelVpnService destroyed.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tunnel Console VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active tunnel connection state"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)

        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, TunnelVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }

        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Tunnel Console")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openAppPendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    disconnectPendingIntent
                )
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Tunnel Console")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
