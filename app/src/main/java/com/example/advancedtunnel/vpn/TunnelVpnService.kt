package com.example.advancedtunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.example.advancedtunnel.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local SOCKS5 to TUN Bridge & VpnService Implementation (`TunnelVpnService`).
 *
 * Capabilities:
 * - Establishes virtual `tun0` interface (`10.0.0.2/24`, MTU 1500, routes `0.0.0.0/0` & `::/0`).
 * - Configures per-app split tunneling via `addAllowedApplication` or `addDisallowedApplication`
 *   with automatic self-exclusion (`addDisallowedApplication(packageName)`) to prevent routing loops.
 * - Maintains Foreground Service persistence with `PARTIAL_WAKE_LOCK` and `KeepAliveManager`.
 * - Bridges the `ParcelFileDescriptor` TUN interface with a local SOCKS5 proxy handler or
 *   chunked TLS/HTTP stream (`CarrierTunnelOptimizer`) and updates live uplink/downlink byte counters.
 */
class TunnelVpnService : VpnService(), Runnable {

    companion object {
        private const val TAG = "TunnelVpnService"
        private const val CHANNEL_ID = "tunnel_vpn_bridge_channel"
        private const val NOTIFICATION_ID = 1002
        private const val BUFFER_SIZE = 32768
        private const val WAKE_LOCK_TAG = "TunnelConsole:TunnelVpnWakeLock"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile
    private var relaySocket: Socket? = null
    private var workerThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var keepAliveManager: KeepAliveManager? = null

    private val isRunning = AtomicBoolean(false)
    private val sessionCounter = AtomicLong(0L)
    private val uplinkBytes = AtomicLong(0L)
    private val downlinkBytes = AtomicLong(0L)

    private var serverIp: String = ""
    private var serverPort: Int = 443
    private var sniHost: String = ""
    private var payloadTemplate: String = ""
    private var localSocksHost: String = "127.0.0.1"
    private var localSocksPort: Int = 10808
    private var useSocks5Bridge: Boolean = false
    private var killswitchEnabled: Boolean = false
    private var disallowedPackages: List<String> = emptyList()
    private var allowedPackages: List<String> = emptyList()
    private var tlsFragmentConfig: TlsFragmentConfig = TlsFragmentConfig()

    override fun onCreate() {
        super.onCreate()
        keepAliveManager = KeepAliveManager(
            context = applicationContext,
            scope = serviceScope,
            onReconnectRequired = { reason ->
                VpnManager.appendTelemetryLog("WARN", "TunnelVpnService keep-alive check: $reason")
                false
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            stopBridgeSession("Disconnect requested by user", stopSelfService = true)
            return START_NOT_STICKY
        }

        serverIp = intent?.getStringExtra("SERVER_IP") ?: ""
        serverPort = intent?.getIntExtra("SERVER_PORT", 443) ?: 443
        sniHost = intent?.getStringExtra("SNI_HOST") ?: ""
        payloadTemplate = intent?.getStringExtra("PAYLOAD") ?: ""
        localSocksHost = intent?.getStringExtra("SOCKS_HOST")?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
        localSocksPort = intent?.getIntExtra("SOCKS_PORT", 10808) ?: 10808
        useSocks5Bridge = intent?.getStringExtra("PROTOCOL")?.equals("socks5", ignoreCase = true) == true
        killswitchEnabled = intent?.getBooleanExtra("KILLSWITCH_ENABLED", false) ?: false
        disallowedPackages = intent?.getStringArrayListExtra("DISALLOWED_APPS") ?: emptyList()
        allowedPackages = intent?.getStringArrayListExtra("ALLOWED_APPS") ?: emptyList()

        if (serverIp.isBlank()) {
            val repo = com.example.advancedtunnel.data.TunnelRepository(applicationContext)
            val (savedProfile, _) = repo.loadActiveProfile()
            serverIp = savedProfile.ip
            serverPort = savedProfile.port.toIntOrNull() ?: 443
            sniHost = savedProfile.sni
            payloadTemplate = savedProfile.payload
            killswitchEnabled = repo.loadKillswitchEnabled()
            tlsFragmentConfig = repo.loadTlsFragmentConfig()
            if (repo.loadSplitTunnelingEnabled()) {
                disallowedPackages = repo.loadDisallowedApps().toList()
            }
        }

        if (serverIp.isBlank()) {
            VpnManager.onServiceStopped("Server IP or hostname is required")
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning.get()) {
            closeActiveDescriptors()
            workerThread?.interrupt()
        }

        uplinkBytes.set(0L)
        downlinkBytes.set(0L)
        isRunning.set(true)
        acquireWakeLock()
        keepAliveManager?.acquireLocks()

        val activeSession = sessionCounter.incrementAndGet()
        promoteToForeground("Connecting to $serverIp:$serverPort…")
        workerThread = Thread({ executeBridgeLoop(activeSession) }, "TunnelVpnBridge-$activeSession").apply {
            start()
        }

        return START_STICKY
    }

    override fun run() {
        executeBridgeLoop(sessionCounter.get())
    }

    /**
     * Performs an RFC 1928 SOCKS5 greeting and CONNECT request over a VPN-protected socket.
     */
    @Throws(IOException::class)
    fun establishSocks5BridgeSocket(
        socksHost: String,
        socksPort: Int,
        remoteHost: String,
        remotePort: Int,
        timeoutMs: Int = 7000
    ): Socket {
        val sock = Socket()
        protect(sock)
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock.soTimeout = 20_000
        sock.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)

        val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

        // SOCKS5 Greeting: VER=0x05, NMETHODS=0x01, METHOD=0x00 (No Auth)
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val ver = input.readByte()
        val chosenMethod = input.readByte()
        if (ver != 0x05.toByte() || chosenMethod != 0x00.toByte()) {
            sock.close()
            throw IOException("SOCKS5 handshake rejected (version=$ver, method=$chosenMethod)")
        }

        // SOCKS5 CONNECT command: VER=0x05, CMD=0x01 (CONNECT), RSV=0x00, ATYP=0x03 (DOMAINNAME)
        val domainBytes = remoteHost.trim().toByteArray(Charsets.US_ASCII)
        val requestBuffer = ByteBuffer.allocate(4 + 1 + domainBytes.size + 2)
        requestBuffer.put(0x05.toByte())
        requestBuffer.put(0x01.toByte())
        requestBuffer.put(0x00.toByte())
        requestBuffer.put(0x03.toByte())
        requestBuffer.put(domainBytes.size.toByte())
        requestBuffer.put(domainBytes)
        requestBuffer.putShort(remotePort.toShort())

        output.write(requestBuffer.array())
        output.flush()

        val replyVer = input.readByte()
        val replyStatus = input.readByte()
        input.readByte() // RSV
        val addressType = input.readByte().toInt() and 0xFF

        if (replyVer != 0x05.toByte() || replyStatus != 0x00.toByte()) {
            sock.close()
            throw IOException("SOCKS5 CONNECT failed with status code 0x${replyStatus.toString(16)}")
        }

        when (addressType) {
            0x01 -> input.skipBytes(4 + 2) // IPv4 + Port
            0x03 -> {
                val len = input.readByte().toInt() and 0xFF
                input.skipBytes(len + 2)
            }
            0x04 -> input.skipBytes(16 + 2) // IPv6 + Port
            else -> throw IOException("Unsupported SOCKS5 address type: $addressType")
        }

        return sock
    }

    /**
     * Configures and establishes the virtual `tun0` interface (`10.0.0.2/24`, MTU 1500)
     * with full routing (`0.0.0.0/0`, `::/0`) and per-app split tunneling.
     */
    private fun configureAndEstablishTun(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("Tunnel Console Bridge")
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        try {
            builder.addAddress("fd00:1234:5678::2", 64)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111")
        } catch (e: Exception) {
            Log.w(TAG, "IPv6 route configuration skipped: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (killswitchEnabled) {
                try {
                    builder.setBlocking(true)
                } catch (e: Exception) {
                    Log.w(TAG, "setBlocking configuration skipped: ${e.message}")
                }
            }
        }

        // Per-App Split Tunneling: either Allowed-List mode or Disallowed-List mode
        if (allowedPackages.isNotEmpty()) {
            for (pkg in allowedPackages) {
                if (pkg != packageName) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add allowed application $pkg: ${e.message}")
                    }
                }
            }
        } else {
            try {
                // Always exclude own package to prevent routing loops alongside protect(socket)
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
            for (pkg in disallowedPackages) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not exclude application $pkg: ${e.message}")
                }
            }
        }

        return builder.establish()
            ?: throw IllegalStateException("Android OS returned null ParcelFileDescriptor for TUN0")
    }

    private fun executeBridgeLoop(sessionId: Long) {
        var underlyingSocket: Socket? = null
        var activeStreamSocket: Socket? = null
        var localTunPfd: ParcelFileDescriptor? = null
        var tunIn: FileInputStream? = null
        var tunOut: FileOutputStream? = null

        try {
            // 1. Connect and protect transport socket
            underlyingSocket = if (useSocks5Bridge) {
                establishSocks5BridgeSocket(
                    socksHost = localSocksHost,
                    socksPort = localSocksPort,
                    remoteHost = serverIp,
                    remotePort = serverPort
                )
            } else {
                Socket().apply {
                    protect(this)
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = 20_000
                    connect(InetSocketAddress(serverIp, serverPort), 7000)
                }
            }

            // 2. Wrap with DesyncSocketWrapper & upgrade to TLS if port 443 / TLS enabled
            val effectiveSni = sniHost.ifBlank { serverIp }
            val desyncSocket = DesyncSocketWrapper(
                delegate = underlyingSocket,
                sniHost = effectiveSni,
                config = tlsFragmentConfig
            )

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(desyncSocket, serverIp, serverPort, true) as SSLSocket
            TlsFingerprintEngine.applyFingerprintToSocket(
                sslSocket = sslSocket,
                profileId = tlsFragmentConfig.fingerprintProfileId,
                sniHost = effectiveSni,
                alpnEnabled = tlsFragmentConfig.alpnEnabled
            )
            sslSocket.startHandshake()
            desyncSocket.markHandshakeCompleted()
            activeStreamSocket = sslSocket
            relaySocket = sslSocket

            // 3. Transmit chunked custom header payload via CarrierTunnelOptimizer
            if (payloadTemplate.isNotBlank()) {
                val browserProfile = TlsFingerprintEngine.resolveProfile(tlsFragmentConfig.fingerprintProfileId)
                val handshakeResult = CarrierTunnelOptimizer.performUpgradedHandshake(
                    socket = activeStreamSocket,
                    rawTemplate = payloadTemplate,
                    host = serverIp,
                    port = serverPort,
                    sni = effectiveSni,
                    userAgent = browserProfile.userAgent,
                    config = HandshakeStreamConfig(
                        minSegmentBytes = 6,
                        maxSegmentBytes = 12,
                        interSegmentDelayMs = tlsFragmentConfig.normalizedMinJitter().toLong().coerceAtLeast(2L),
                        expectHttpUpgradeResponse = false
                    )
                )
                VpnManager.appendTelemetryLog(
                    "INFO",
                    "CarrierTunnelOptimizer flushed ${handshakeResult.bytesTransmitted}B across ${handshakeResult.segmentsFlushed} chunks (${handshakeResult.statusLine})"
                )
            }

            // 4. Establish virtual tun0 interface
            localTunPfd = configureAndEstablishTun()
            tunDescriptor = localTunPfd
            tunIn = FileInputStream(localTunPfd.fileDescriptor)
            tunOut = FileOutputStream(localTunPfd.fileDescriptor)

            VpnManager.onServiceConnected("$serverIp:$serverPort")
            promoteToForeground("Connected to $serverIp:$serverPort")
            keepAliveManager?.startMonitoring(
                socketProvider = { relaySocket },
                probeTargetHost = serverIp,
                probeTargetPort = serverPort
            )

            val remoteIn = activeStreamSocket.getInputStream()
            val remoteOut = activeStreamSocket.getOutputStream()

            // 5. Downlink Relay Thread (Remote Socket -> TUN0)
            val downlinkThread = Thread({
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isRunning.get() && sessionCounter.get() == sessionId) {
                        val readCount = remoteIn.read(buffer)
                        if (readCount > 0) {
                            tunOut.write(buffer, 0, readCount)
                            tunOut.flush()
                            val rxTotal = downlinkBytes.addAndGet(readCount.toLong())
                            keepAliveManager?.recordTrafficActivity()
                            VpnManager.updateTelemetry(rxTotal, uplinkBytes.get())
                        } else if (readCount < 0) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get() && sessionCounter.get() == sessionId) {
                        Log.w(TAG, "Downlink relay closed: ${e.message}")
                    }
                }
            }, "TunnelDownlink-$sessionId")
            downlinkThread.start()

            // 6. Uplink Relay Loop (TUN0 -> Remote Socket)
            val uplinkBuffer = ByteArray(BUFFER_SIZE)
            while (isRunning.get() && sessionCounter.get() == sessionId) {
                val readCount = tunIn.read(uplinkBuffer)
                if (readCount > 0) {
                    remoteOut.write(uplinkBuffer, 0, readCount)
                    remoteOut.flush()
                    val txTotal = uplinkBytes.addAndGet(readCount.toLong())
                    keepAliveManager?.recordTrafficActivity()
                    VpnManager.updateTelemetry(downlinkBytes.get(), txTotal)
                } else if (readCount < 0) {
                    break
                }
            }

            downlinkThread.join(1000)
        } catch (e: Exception) {
            if (isRunning.get() && sessionCounter.get() == sessionId) {
                Log.e(TAG, "Bridge session error: ${e.message}", e)
                VpnManager.onServiceStopped(e.localizedMessage ?: e.javaClass.simpleName)
            }
        } finally {
            try { tunIn?.close() } catch (_: Exception) {}
            try { tunOut?.close() } catch (_: Exception) {}
            try { localTunPfd?.close() } catch (_: Exception) {}
            try { activeStreamSocket?.close() } catch (_: Exception) {}
            try { underlyingSocket?.close() } catch (_: Exception) {}
            if (sessionCounter.get() == sessionId) {
                stopBridgeSession(stopSelfService = true)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquisition skipped: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun closeActiveDescriptors() {
        try { relaySocket?.close() } catch (_: Exception) {}
        try { tunDescriptor?.close() } catch (_: Exception) {}
        relaySocket = null
        tunDescriptor = null
    }

    private fun promoteToForeground(statusText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tunnel Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active TUN0 & SOCKS5/TLS bridge status"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Tunnel Console VPN")
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

    private fun stopBridgeSession(reason: String? = null, stopSelfService: Boolean = true) {
        val wasActive = isRunning.getAndSet(false)
        keepAliveManager?.stopMonitoringAndReleaseLocks()
        releaseWakeLock()
        closeActiveDescriptors()
        workerThread?.interrupt()
        workerThread = null
        if (wasActive || reason != null) {
            VpnManager.onServiceStopped(reason)
        }
        if (stopSelfService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopBridgeSession("VPN permission revoked by system", stopSelfService = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopBridgeSession("Service destroyed", stopSelfService = false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
