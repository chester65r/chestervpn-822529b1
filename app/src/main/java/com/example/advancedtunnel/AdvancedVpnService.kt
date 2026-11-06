package com.example.advancedtunnel

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
import android.util.Log
import com.example.advancedtunnel.vpn.DesyncSocketWrapper
import com.example.advancedtunnel.vpn.KeepAliveManager
import com.example.advancedtunnel.vpn.TlsFingerprintEngine
import com.example.advancedtunnel.vpn.TlsFragmentConfig
import com.example.advancedtunnel.vpn.TlsFragmentMode
import com.example.advancedtunnel.vpn.TlsFragmentationEngine
import com.example.advancedtunnel.vpn.VpnManager
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
 * Unified Foreground VpnService (`AdvancedVpnService`) managing:
 * - Virtual TUN interface (`tun0`, MTU 1500, IPv4 `10.0.0.2/24`, IPv6 `fd00:1234:5678::2/64`)
 * - Full-tunnel routing (`0.0.0.0/0`, `::/0`) with `addDisallowedApplication` & Killswitch (`setBlocking`)
 * - Outbound TLS + custom SNI + TLS Record Fragmentation (`DesyncSocketWrapper`) + JA3/JA4 Fingerprint Spoofing (`TlsFingerprintEngine`)
 * - `KeepAliveManager` (`PARTIAL_WAKE_LOCK`, `WIFI_MODE_FULL_LOW_LATENCY`, adaptive TCP/UDP heartbeats)
 * - Android 14+ (`UPSIDE_DOWN_CAKE`) `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` compliance
 */
class AdvancedVpnService : VpnService(), Runnable {

    companion object {
        private const val TAG = "AdvancedVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "tunnel_console_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BUFFER_SIZE = 32768
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var activeRawSocket: Socket? = null
    @Volatile
    private var activeSecureSocket: SSLSocket? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val sessionIdGenerator = AtomicLong(0L)
    private var keepAliveManager: KeepAliveManager? = null

    private var serverIp = ""
    private var serverPort = 443
    private var sniHost = ""
    private var rawPayload = ""
    private var protocol = "tls"
    private var socksHost = ""
    private var socksPort = 10808
    private var killswitchEnabled = false
    private var disallowedApps = ArrayList<String>()
    private var tlsFragmentConfig = TlsFragmentConfig()

    private var totalBytesSent: Long = 0
    private var totalBytesReceived: Long = 0

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

        serverIp = intent?.getStringExtra("SERVER_IP") ?: ""
        serverPort = intent?.getIntExtra("SERVER_PORT", 443) ?: 443
        sniHost = intent?.getStringExtra("SNI_HOST") ?: ""
        rawPayload = intent?.getStringExtra("PAYLOAD") ?: ""
        protocol = intent?.getStringExtra("PROTOCOL") ?: "tls"
        socksHost = intent?.getStringExtra("SOCKS_HOST") ?: ""
        socksPort = intent?.getIntExtra("SOCKS_PORT", 10808) ?: 10808
        killswitchEnabled = intent?.getBooleanExtra("KILLSWITCH_ENABLED", false) ?: false
        disallowedApps = intent?.getStringArrayListExtra("DISALLOWED_APPS") ?: ArrayList()

        val fragEnabled = intent?.getBooleanExtra("TLS_FRAG_ENABLED", true) ?: true
        val fragModeId = intent?.getStringExtra("TLS_FRAG_MODE") ?: TlsFragmentMode.TLS_RECORD_AND_TCP.id
        val minChunk = intent?.getIntExtra("TLS_FRAG_MIN_CHUNK", 5) ?: 5
        val maxChunk = intent?.getIntExtra("TLS_FRAG_MAX_CHUNK", 15) ?: 15
        val minJitter = intent?.getIntExtra("TLS_FRAG_MIN_JITTER", 3) ?: 3
        val maxJitter = intent?.getIntExtra("TLS_FRAG_MAX_JITTER", 12) ?: 12
        val ja3ProfileId = intent?.getStringExtra("TLS_JA3_PROFILE_ID") ?: "chrome_128_android"
        val alpnEnabled = intent?.getBooleanExtra("TLS_ALPN_ENABLED", true) ?: true

        tlsFragmentConfig = TlsFragmentConfig(
            enabled = fragEnabled,
            mode = TlsFragmentMode.fromId(fragModeId),
            minChunkBytes = minChunk,
            maxChunkBytes = maxChunk,
            minJitterMs = minJitter,
            maxJitterMs = maxJitter,
            fingerprintProfileId = ja3ProfileId,
            alpnEnabled = alpnEnabled
        )

        // Always-On VPN support: if started by Android OS without extras, load persisted profile
        if (serverIp.isBlank()) {
            val repo = com.example.advancedtunnel.data.TunnelRepository(applicationContext)
            val (savedProfile, _) = repo.loadActiveProfile()
            serverIp = savedProfile.ip
            serverPort = savedProfile.port.toIntOrNull() ?: 443
            sniHost = savedProfile.sni
            rawPayload = savedProfile.payload
            killswitchEnabled = repo.loadKillswitchEnabled()
            tlsFragmentConfig = repo.loadTlsFragmentConfig()
            if (repo.loadSplitTunnelingEnabled()) {
                disallowedApps = ArrayList(repo.loadDisallowedApps())
            }
            VpnManager.appendTelemetryLog(
                "INFO",
                "Always-On VPN start triggered using stored profile ($serverIp:$serverPort)"
            )
        }

        if (serverIp.isBlank()) {
            Log.e(TAG, "No server IP provided")
            VpnManager.onServiceStopped("Server IP is required")
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning.get()) {
            Log.w(TAG, "Tunnel already running; stopping previous session before reconnect")
            closeActiveResources()
            tunnelThread?.interrupt()
        }

        totalBytesSent = 0
        totalBytesReceived = 0
        isRunning.set(true)
        keepAliveManager?.acquireLocks()
        val currentSession = sessionIdGenerator.incrementAndGet()
        showForegroundNotification("Connecting to $serverIp:$serverPort...")
        tunnelThread = Thread({ runSession(currentSession) }, "RealVpnTunnelThread-$currentSession").apply {
            start()
        }

        return START_STICKY
    }

    override fun run() {
        runSession(sessionIdGenerator.get())
    }

    /**
     * Performs an RFC 1928 SOCKS5 handshake + CONNECT command over a protected socket when
     * `protocol == "socks5"`.
     */
    @Throws(IOException::class)
    private fun connectSocks5Channel(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int = 7000
    ): Socket {
        val socket = Socket()
        if (!protect(socket)) {
            Log.w(TAG, "VpnService.protect(socket) returned false for SOCKS5 socket")
        }
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 20_000
        socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)

        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val version = input.readByte()
        val method = input.readByte()
        if (version != 0x05.toByte() || method != 0x00.toByte()) {
            socket.close()
            throw IOException("SOCKS5 negotiation failed (ver=$version, method=$method)")
        }

        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val reqBuf = ByteBuffer.allocate(4 + 1 + hostBytes.size + 2)
        reqBuf.put(0x05.toByte())
        reqBuf.put(0x01.toByte())
        reqBuf.put(0x00.toByte())
        reqBuf.put(0x03.toByte())
        reqBuf.put(hostBytes.size.toByte())
        reqBuf.put(hostBytes)
        reqBuf.putShort(targetPort.toShort())

        output.write(reqBuf.array())
        output.flush()

        val repVer = input.readByte()
        val repStatus = input.readByte()
        input.readByte()
        val atyp = input.readByte().toInt() and 0xFF

        if (repVer != 0x05.toByte() || repStatus != 0x00.toByte()) {
            socket.close()
            throw IOException("SOCKS5 CONNECT rejected with status=0x${repStatus.toString(16)}")
        }

        when (atyp) {
            0x01 -> input.skipBytes(4 + 2)
            0x03 -> {
                val len = input.readByte().toInt() and 0xFF
                input.skipBytes(len + 2)
            }
            0x04 -> input.skipBytes(16 + 2)
            else -> throw IOException("Unsupported SOCKS5 ATYP: $atyp")
        }

        return socket
    }

    private fun runSession(mySessionId: Long) {
        var rawSocket: Socket? = null
        var secureSocket: SSLSocket? = null
        var vpnInput: FileInputStream? = null
        var vpnOutput: FileOutputStream? = null
        var localVpnInterface: ParcelFileDescriptor? = null

        try {
            Log.d(TAG, "Initializing real VPN tunnel to $serverIp:$serverPort (SNI: $sniHost)")
            showForegroundNotification("Connecting to $serverIp:$serverPort…")

            // 1. Create and protect the outbound socket FIRST to avoid establishing TUN on failure
            if (protocol.equals("socks5", ignoreCase = true) && socksHost.isNotBlank()) {
                rawSocket = connectSocks5Channel(socksHost, socksPort, serverIp, serverPort, 7000)
                activeRawSocket = rawSocket
            } else {
                rawSocket = Socket()
                activeRawSocket = rawSocket
                val protected = protect(rawSocket)
                if (!protected) {
                    Log.w(TAG, "Warning: Could not protect raw socket from VPN routing table.")
                }

                rawSocket.soTimeout = 20000
                rawSocket.keepAlive = true
                rawSocket.tcpNoDelay = true

                // Connect to server with a 7-second timeout for responsive feedback
                rawSocket.connect(InetSocketAddress(serverIp, serverPort), 7000)
            }

            // 2. Wrap underlying TCP socket with DesyncSocketWrapper for TLS Record Fragmentation
            val effectiveSni = sniHost.ifBlank { serverIp }
            val desyncSocket = DesyncSocketWrapper(
                delegate = rawSocket,
                sniHost = effectiveSni,
                config = tlsFragmentConfig,
                onStatsRecorded = { stats ->
                    VpnManager.appendTelemetryLog(
                        "DESYNC",
                        "ClientHello desynchronized: ${stats.originalBytes}B -> ${stats.tlsRecordCount} TLS records, ${stats.tcpWriteChunks} TCP chunks (SNI split @ +${stats.sniSplitOffset}B, jitter ${stats.totalJitterDelayMs}ms)"
                    )
                }
            )

            // 3. Establish TLS with JA3/JA4 Fingerprint Spoofing & SNI
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            secureSocket = factory.createSocket(desyncSocket, serverIp, serverPort, true) as SSLSocket
            activeSecureSocket = secureSocket

            val appliedFp = TlsFingerprintEngine.applyFingerprintToSocket(
                sslSocket = secureSocket,
                profileId = tlsFragmentConfig.fingerprintProfileId,
                sniHost = sniHost,
                alpnEnabled = tlsFragmentConfig.alpnEnabled
            )
            VpnManager.appendTelemetryLog(
                "JA3/JA4",
                "Applied ${appliedFp.profileName} • JA4=${appliedFp.ja4String} • JA3=${appliedFp.ja3HashMd5.take(12)}…"
            )

            secureSocket.startHandshake()
            desyncSocket.markHandshakeCompleted()

            Log.i(TAG, "TLS handshake successfully verified with $sniHost")
            VpnManager.appendTelemetryLog("SUCCESS", "TLS handshake verified (SNI=$effectiveSni, Cipher=${secureSocket.session?.cipherSuite ?: "AEAD"})")

            // 4. Send handshake protocol payload with macro fragmentation ([split], [sni], [ua], [crlf]) if configured
            if (rawPayload.isNotBlank()) {
                val browserProfile = TlsFingerprintEngine.resolveProfile(tlsFragmentConfig.fingerprintProfileId)
                val bytesWritten = TlsFragmentationEngine.writePayloadWithMacros(
                    outputStream = secureSocket.outputStream,
                    rawPayload = rawPayload,
                    host = serverIp,
                    port = serverPort,
                    sni = effectiveSni,
                    userAgent = browserProfile.userAgent,
                    config = tlsFragmentConfig
                )
                Log.d(TAG, "Handshake protocol payload transmitted ($bytesWritten bytes).")
                VpnManager.appendTelemetryLog("INFO", "Transmitted HTTP/WS upgrade payload ($bytesWritten bytes)")
            }

            val serverIn = secureSocket.inputStream
            val serverOut = secureSocket.outputStream

            // 4. Remote connection succeeded! NOW establish the OS TUN interface
            val builder = Builder()
                .setSession("Tunnel Console")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0) // Full IPv4 tunnel route
                .addDnsServer("1.1.1.1") // Cloudflare secure DNS
                .addDnsServer("8.8.8.8") // Google secure DNS

            // IPv6 support and leak prevention
            try {
                builder.addAddress("fd00:1234:5678::2", 64)
                    .addRoute("::", 0)
                    .addDnsServer("2606:4700:4700::1111")
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 address allocation skipped: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
                if (killswitchEnabled) {
                    try {
                        builder.setBlocking(true)
                        Log.i(TAG, "Killswitch enabled: blocking traffic outside VPN")
                    } catch (e: Exception) {
                        Log.w(TAG, "Killswitch setBlocking skipped: ${e.message}")
                    }
                }
            }

            disallowedApps.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                    Log.d(TAG, "Split Tunneling: Excluded app $pkg from VPN route")
                } catch (e: Exception) {
                    Log.w(TAG, "Split Tunneling: Could not exclude app $pkg: ${e.message}")
                }
            }

            localVpnInterface = builder.establish()
            vpnInterface = localVpnInterface
            if (localVpnInterface == null) {
                throw IllegalStateException("VPN interface could not be established by Android OS.")
            }

            vpnInput = FileInputStream(localVpnInterface.fileDescriptor)
            vpnOutput = FileOutputStream(localVpnInterface.fileDescriptor)

            // Update state: genuinely CONNECTED only now!
            VpnManager.onServiceConnected("$serverIp:$serverPort")
            showForegroundNotification("Connected to $serverIp:$serverPort")
            keepAliveManager?.startMonitoring(
                socketProvider = { activeSecureSocket ?: activeRawSocket },
                probeTargetHost = serverIp,
                probeTargetPort = serverPort
            )

            // 5. Downstream thread (Server -> TUN)
            val downThread = Thread({
                val buf = ByteArray(BUFFER_SIZE)
                try {
                    while (isRunning.get() && sessionIdGenerator.get() == mySessionId) {
                        val n = serverIn.read(buf)
                        if (n > 0) {
                            vpnOutput.write(buf, 0, n)
                            vpnOutput.flush()
                            totalBytesReceived += n
                            keepAliveManager?.recordTrafficActivity()
                            VpnManager.updateTelemetry(totalBytesReceived, totalBytesSent)
                        } else if (n < 0) {
                            Log.i(TAG, "Server closed downstream connection.")
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get() && sessionIdGenerator.get() == mySessionId) {
                        Log.e(TAG, "Downstream pipe error: ${e.message}")
                    }
                }
            }, "VpnDownstream-$mySessionId")
            downThread.start()

            // 6. Upstream loop (TUN -> Server)
            val upBuf = ByteArray(BUFFER_SIZE)
            while (isRunning.get() && sessionIdGenerator.get() == mySessionId) {
                val n = vpnInput.read(upBuf)
                if (n > 0) {
                    serverOut.write(upBuf, 0, n)
                    serverOut.flush()
                    totalBytesSent += n
                    keepAliveManager?.recordTrafficActivity()
                    VpnManager.updateTelemetry(totalBytesReceived, totalBytesSent)
                } else if (n < 0) {
                    break
                }
            }

            downThread.join(1000)

        } catch (e: java.net.UnknownHostException) {
            val msg = "Cannot resolve '$serverIp'. Enter a valid server IP or domain."
            Log.e(TAG, "DNS resolution failed: $msg", e)
            if (sessionIdGenerator.get() == mySessionId) VpnManager.onServiceStopped(msg)
        } catch (e: java.net.SocketTimeoutException) {
            val msg = "Connection to $serverIp:$serverPort timed out after 7s. Check firewall rules."
            Log.e(TAG, "Connection timeout: $msg", e)
            if (sessionIdGenerator.get() == mySessionId) VpnManager.onServiceStopped(msg)
        } catch (e: java.net.ConnectException) {
            val msg = "Connection refused by $serverIp:$serverPort. Check if your tunnel service is active."
            Log.e(TAG, "Connection refused: $msg", e)
            if (sessionIdGenerator.get() == mySessionId) VpnManager.onServiceStopped(msg)
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            val msg = "TLS Handshake failed with $sniHost: ${e.localizedMessage ?: "Certificate mismatch"}"
            Log.e(TAG, "TLS error: $msg", e)
            if (sessionIdGenerator.get() == mySessionId) VpnManager.onServiceStopped(msg)
        } catch (e: Exception) {
            if (isRunning.get() && sessionIdGenerator.get() == mySessionId) {
                Log.e(TAG, "Tunnel session terminated: ${e.message}", e)
                VpnManager.onServiceStopped("Connection error: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        } finally {
            try { secureSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
            try { vpnInput?.close() } catch (_: Exception) {}
            try { vpnOutput?.close() } catch (_: Exception) {}
            try { localVpnInterface?.close() } catch (_: Exception) {}
            if (sessionIdGenerator.get() == mySessionId) {
                stopTunnel(stopService = true)
            }
        }
    }

    private fun closeActiveResources() {
        try { activeSecureSocket?.close() } catch (_: Exception) {}
        try { activeRawSocket?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        activeSecureSocket = null
        activeRawSocket = null
        vpnInterface = null
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

    private fun stopTunnel(reason: String? = null, stopService: Boolean = true) {
        val wasRunning = isRunning.getAndSet(false)
        keepAliveManager?.stopMonitoringAndReleaseLocks()
        closeActiveResources()
        tunnelThread?.interrupt()
        tunnelThread = null
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
