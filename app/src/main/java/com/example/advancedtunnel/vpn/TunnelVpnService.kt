package com.example.advancedtunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.advancedtunnel.MainActivity
import com.example.advancedtunnel.data.TunnelSessionCache
import com.example.advancedtunnel.model.TunnelProfile
import kotlinx.coroutines.CancellationException
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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private var reconnectionMonitor: ReconnectionMonitor? = null

    private var activeProfile: TunnelProfile? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val shouldReconnect = AtomicBoolean(false)

    private val transportManager = MultiTransportTunnelManager()

    private lateinit var sessionCache: TunnelSessionCache

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onCreate() {
        super.onCreate()

        sessionCache = TunnelSessionCache(applicationContext)

        registerNetworkCallback()

        Log.i(TAG, "TunnelVpnService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_CONNECT -> {
                val profile = extractProfileFromIntent(intent)

                sessionCache.save(profile)

                cleanupResources()
                startForegroundTunnel(profile)
            }

            ACTION_DISCONNECT -> {
                sessionCache.clear()
                stopTunnel()
            }

            else -> {
                // START_STICKY recovery path.
                // If the system restarts the service, restore last known profile.
                if (activeProfile == null) {
                    val cachedProfile = sessionCache.load()

                    if (cachedProfile != null) {
                        Log.i(TAG, "Restoring last known tunnel profile from session cache.")
                        cleanupResources()
                        startForegroundTunnel(cachedProfile)
                    } else {
                        Log.i(TAG, "No cached profile found. Stopping service.")
                        stopSelf()
                    }
                }
            }
        }

        return START_STICKY
    }

    /**
     * Instantiates (or reuses) a [ReconnectionMonitor] bound to the active [serviceScope]
     * and starts its exponential-backoff reconnection workflow while updating
     * [TunnelStateHolder] to [TunnelConnectionState.Reconnecting].
     */
    private fun getOrCreateReconnectionMonitor(): ReconnectionMonitor {
        val scope = ensureServiceScope()
        val existing = reconnectionMonitor
        if (existing != null && existing.isScopeActive()) {
            return existing
        }

        val monitor = ReconnectionMonitor(
            serviceScope = scope,
            isReconnectEnabled = { shouldReconnect.get() },
            isInterfaceDown = ::isTunnelInterfaceDown,
            isSocketDown = ::isTunnelSocketDown,
            onPrepareReconnect = ::prepareForReconnect,
            onAttemptConnection = ::attemptReconnectWithProfile,
            onReconnectSucceeded = { attempt ->
                TunnelStateHolder.setConnectionState(TunnelConnectionState.TunnelEstablished)
                startTrafficForwarding()
                updateNotification("Tunnel restored | ${transportManager.getActiveTransport()}")
                Log.i(TAG, "Tunnel restored on reconnect attempt $attempt")
            },
            onReconnectExhausted = { errorMessage ->
                Log.e(TAG, "ReconnectionMonitor exhausted all attempts: $errorMessage")
                TunnelStateHolder.setConnectionState(TunnelConnectionState.Error(errorMessage))
                stopTunnel(preserveErrorState = true)
            },
            onStatusUpdate = ::updateNotification
        )

        reconnectionMonitor = monitor
        return monitor
    }

    private fun startReconnectionMonitor(reason: String, force: Boolean = true) {
        if (!shouldReconnect.get()) return

        TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
        TunnelStateHolder.setReconnectPhase("Reconnecting: $reason")

        val monitor = getOrCreateReconnectionMonitor()
        monitor.startWatchdog()
        monitor.start(reason = reason, force = force)
    }

    private fun startForegroundTunnel(profile: TunnelProfile) {
        activeProfile = profile
        sessionCache.save(profile)

        shouldReconnect.set(true)

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

        val scope = ensureServiceScope()
        getOrCreateReconnectionMonitor().startWatchdog()

        scope.launch {
            try {
                val established = runConnectionEstablishmentFlow(
                    profile = profile,
                    isReconnectAttempt = false
                )

                if (established) {
                    Log.i(TAG, "Tunnel established and traffic forwarding started.")
                } else if (shouldReconnect.get()) {
                    val errorMsg = "Tunnel establishment failed."
                    TunnelStateHolder.setConnectionState(TunnelConnectionState.Error(errorMsg))
                    Log.w(TAG, "Tunnel entered error state ($errorMsg); starting ReconnectionMonitor.")
                    startReconnectionMonitor(reason = errorMsg, force = true)
                } else {
                    TunnelStateHolder.setConnectionState(
                        TunnelConnectionState.Error("Tunnel establishment failed.")
                    )
                    stopTunnel(preserveErrorState = true)
                }

            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: IOException) {
                Log.e(TAG, "Tunnel I/O error during connection flow", e)
                val errorMsg = e.message ?: "Tunnel I/O error"
                TunnelStateHolder.setConnectionState(TunnelConnectionState.Error(errorMsg))
                if (shouldReconnect.get()) {
                    getOrCreateReconnectionMonitor().onIoException("Startup I/O failure", e)
                } else {
                    stopTunnel(preserveErrorState = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal tunnel error", e)
                val errorMsg = e.message ?: "Unknown tunnel error"
                TunnelStateHolder.setConnectionState(TunnelConnectionState.Error(errorMsg))

                if (shouldReconnect.get()) {
                    startReconnectionMonitor(reason = errorMsg, force = true)
                } else {
                    stopTunnel(preserveErrorState = true)
                }
            }
        }
    }

    /**
     * Executes the full connection establishment flow: ensures the VPN interface is ready,
     * establishes the anti-DPI TLS/payload socket, starts traffic forwarding, and updates
     * [TunnelStateHolder].
     */
    private suspend fun runConnectionEstablishmentFlow(
        profile: TunnelProfile,
        isReconnectAttempt: Boolean,
        attemptNumber: Int = 1,
        maxAttempts: Int = ReconnectionMonitor.DEFAULT_MAX_ATTEMPTS
    ): Boolean {
        if (!isReconnectAttempt) {
            updateNotification("Establishing secure tunnel...")
        }

        if (isTunnelInterfaceDown()) {
            try {
                vpnInterface?.close()
            } catch (_: Exception) {}
            vpnInterface = createVpnInterface()
        }

        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface.")
            return false
        }

        Log.i(TAG, "VPN interface ready.")

        if (isReconnectAttempt) {
            TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
            TunnelStateHolder.setReconnectPhase(
                "Reconnecting ($attemptNumber/$maxAttempts) — authenticating..."
            )
        } else {
            TunnelStateHolder.setConnectionState(TunnelConnectionState.Authenticating)
            updateNotification("Applying anti-DPI transport...")
        }

        val tunnelEstablished = establishTunnelConnection(
            profile = profile,
            isReconnectAttempt = isReconnectAttempt,
            attemptNumber = attemptNumber,
            maxAttempts = maxAttempts
        )

        if (!tunnelEstablished) {
            return false
        }

        if (!isReconnectAttempt) {
            TunnelStateHolder.setConnectionState(TunnelConnectionState.TunnelEstablished)
            updateNotification("Tunnel active | ${transportManager.getActiveTransport()}")
            startTrafficForwarding()
        }

        return true
    }

    private suspend fun prepareForReconnect(reason: String): Boolean {
        val profile = activeProfile ?: sessionCache.load()

        if (profile == null) {
            TunnelStateHolder.setConnectionState(
                TunnelConnectionState.Error("No stored profile available for reconnect.")
            )
            stopTunnel(preserveErrorState = true)
            return false
        }

        activeProfile = profile
        closeBrokenTunnelOnly()

        if (isTunnelInterfaceDown()) {
            try {
                vpnInterface?.close()
            } catch (_: Exception) {}
            vpnInterface = createVpnInterface()
        }

        if (vpnInterface == null) {
            TunnelStateHolder.setConnectionState(
                TunnelConnectionState.Error("VPN interface lost during reconnect ($reason).")
            )
            stopTunnel(preserveErrorState = true)
            return false
        }

        return true
    }

    private suspend fun attemptReconnectWithProfile(
        attempt: Int,
        maxAttempts: Int
    ): Boolean {
        val profile = activeProfile ?: sessionCache.load() ?: return false
        activeProfile = profile

        closeBrokenTunnelOnly()

        return runConnectionEstablishmentFlow(
            profile = profile,
            isReconnectAttempt = true,
            attemptNumber = attempt,
            maxAttempts = maxAttempts
        )
    }

    private fun isTunnelInterfaceDown(): Boolean {
        val fd = vpnInterface?.fileDescriptor ?: return true
        return !fd.valid()
    }

    private fun isTunnelSocketDown(): Boolean {
        val sock = tunnelSocket ?: return true
        return sock.isClosed || !sock.isConnected || sock.isInputShutdown || sock.isOutputShutdown
    }

    private fun createVpnInterface(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("Tunnel Console")
                .setMtu(VPN_MTU)
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VPN interface", e)
            null
        }
    }

    private suspend fun establishTunnelConnection(
        profile: TunnelProfile,
        isReconnectAttempt: Boolean = false,
        attemptNumber: Int = 1,
        maxAttempts: Int = ReconnectionMonitor.DEFAULT_MAX_ATTEMPTS
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val targetHost = profile.ip.ifBlank { "127.0.0.1" }
                val targetPort = profile.port.toIntOrNull() ?: 443

                if (isReconnectAttempt) {
                    TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
                    TunnelStateHolder.setReconnectPhase(
                        "Reconnecting ($attemptNumber/$maxAttempts) — probing corridors..."
                    )
                }

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

                if (isReconnectAttempt) {
                    TunnelStateHolder.setReconnectPhase(
                        "Reconnecting ($attemptNumber/$maxAttempts) — TLS handshake..."
                    )
                }

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

                keepAliveManager?.stop()

                keepAliveManager = KeepAliveManager(
                    outputStreamProvider = { secureSocket.outputStream },
                    intervalMs = 20000L,
                    onFailure = { error ->
                        Log.e(TAG, "Keep-Alive failure detected. Starting ReconnectionMonitor.")
                        if (shouldReconnect.get()) {
                            getOrCreateReconnectionMonitor().onIoException(
                                "Keep-Alive heartbeat failure",
                                error
                            )
                        }
                    }
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

        forwardingJob?.cancel()

        forwardingJob = ensureServiceScope().launch {
            val failureReason = AtomicReference<String?>(null)
            val failureException = AtomicReference<Throwable?>(null)

            var vpnToTunnel: Job? = null
            var tunnelToVpn: Job? = null

            try {
                val vpnInput = FileInputStream(vpnFd.fileDescriptor)
                val vpnOutput = FileOutputStream(vpnFd.fileDescriptor)

                val tunnelInput = tunnel.getInputStream()
                val tunnelOutput = tunnel.getOutputStream()

                val vpnBuffer = ByteArray(BUFFER_SIZE)
                val tunnelBuffer = ByteArray(BUFFER_SIZE)

                vpnToTunnel = launch {
                    try {
                        while (isActive) {
                            val bytesRead = vpnInput.read(vpnBuffer)

                            if (bytesRead == -1) {
                                if (shouldReconnect.get()) {
                                    failureReason.compareAndSet(
                                        null,
                                        "VPN input stream EOF"
                                    )
                                }
                                break
                            }

                            tunnelOutput.write(vpnBuffer, 0, bytesRead)
                            tunnelOutput.flush()

                            TunnelStateHolder.addData(bytesRead.toLong())
                        }
                    } catch (cancellation: CancellationException) {
                        // Ignore intentional cancellation.
                    } catch (e: Exception) {
                        if (shouldReconnect.get()) {
                            failureException.compareAndSet(null, e)
                            failureReason.compareAndSet(
                                null,
                                "VPN -> Tunnel I/O error"
                            )
                        }
                    }
                }

                tunnelToVpn = launch {
                    try {
                        while (isActive) {
                            val bytesRead = tunnelInput.read(tunnelBuffer)

                            if (bytesRead == -1) {
                                if (shouldReconnect.get()) {
                                    failureReason.compareAndSet(
                                        null,
                                        "Remote tunnel stream EOF"
                                    )
                                }
                                break
                            }

                            vpnOutput.write(tunnelBuffer, 0, bytesRead)
                            vpnOutput.flush()

                            TunnelStateHolder.addData(bytesRead.toLong())
                        }
                    } catch (cancellation: CancellationException) {
                        // Ignore intentional cancellation.
                    } catch (e: Exception) {
                        if (shouldReconnect.get()) {
                            failureException.compareAndSet(null, e)
                            failureReason.compareAndSet(
                                null,
                                "Tunnel -> VPN I/O error"
                            )
                        }
                    }
                }

                while (vpnToTunnel.isActive && tunnelToVpn.isActive && isActive) {
                    if (isTunnelInterfaceDown() || isTunnelSocketDown()) {
                        failureReason.compareAndSet(
                            null,
                            "Tunnel interface or socket down"
                        )
                        break
                    }
                    delay(200)
                }

                if (isActive && shouldReconnect.get()) {
                    val reason = failureReason.get()
                        ?: "Traffic forwarding stopped unexpectedly"

                    val error = failureException.get()
                    if (error != null) {
                        getOrCreateReconnectionMonitor().onIoException(reason, error)
                    } else {
                        startReconnectionMonitor(reason = reason, force = true)
                    }
                }

            } catch (cancellation: CancellationException) {
                // Ignore intentional cancellation.
            } catch (e: Exception) {
                if (isActive && shouldReconnect.get()) {
                    getOrCreateReconnectionMonitor().onIoException("Forwarding fatal I/O error", e)
                }
            } finally {
                vpnToTunnel?.cancel()
                tunnelToVpn?.cancel()
            }
        }
    }

    private fun triggerAutoReconnect(reason: String, force: Boolean = false) {
        startReconnectionMonitor(reason = reason, force = force)
    }

    private fun closeBrokenTunnelOnly() {
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

        Log.i(TAG, "Broken tunnel socket resources closed. VPN interface preserved.")
    }

    private fun cleanupResources(preserveErrorState: Boolean = false) {
        shouldReconnect.set(false)
        reconnectionMonitor?.stop()
        reconnectionMonitor = null

        closeBrokenTunnelOnly()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }

        vpnInterface = null

        if (!preserveErrorState) {
            TunnelStateHolder.setConnectionState(TunnelConnectionState.Disconnected)
        }
        TunnelStateHolder.resetData()

        Log.i(TAG, "Tunnel resources cleaned.")
    }

    fun stopTunnel(preserveErrorState: Boolean = false) {
        Log.i(TAG, "Stopping tunnel...")

        shouldReconnect.set(false)
        reconnectionMonitor?.stop()
        reconnectionMonitor = null

        cleanupResources(preserveErrorState = preserveErrorState)

        serviceScope?.cancel()
        serviceScope = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Tunnel service stopped.")
    }

    override fun onRevoke() {
        super.onRevoke()

        Log.w(TAG, "VPN revoked by system or user.")

        sessionCache.clear()
        stopTunnel()
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterNetworkCallback()
        cleanupResources()

        Log.i(TAG, "TunnelVpnService destroyed.")
    }

    private fun ensureServiceScope(): CoroutineScope {
        if (serviceScope == null || serviceScope?.isActive != true) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        return serviceScope!!
    }

    private fun extractProfileFromIntent(intent: Intent): TunnelProfile {
        return TunnelProfile(
            ip = intent.getStringExtra("tunnel_ip") ?: "",
            port = intent.getStringExtra("tunnel_port") ?: "443",
            sni = intent.getStringExtra("tunnel_sni") ?: "",
            payload = intent.getStringExtra("tunnel_payload") ?: ""
        )
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                if (!shouldReconnect.get()) return

                val state = TunnelStateHolder.connectionState.value

                if (state !is TunnelConnectionState.TunnelEstablished) {
                    Log.i(TAG, "Underlying network available. Triggering reconnect check.")
                    triggerAutoReconnect("Underlying network available", force = false)
                }
            }

            override fun onLost(network: Network) {
                if (!shouldReconnect.get()) return

                Log.w(TAG, "Underlying network lost. Triggering reconnect.")
                triggerAutoReconnect("Underlying network lost", force = true)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "Network connectivity callback registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.i(TAG, "Network connectivity callback unregistered.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }

        networkCallback = null
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
