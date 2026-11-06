package com.example.advancedtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.advancedtunnel.vpn.VpnManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class AdvancedVpnService : VpnService(), Runnable {

    companion object {
        private const val TAG = "AdvancedVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "tunnel_console_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BUFFER_SIZE = 32768
    }

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var activeRawSocket: Socket? = null
    @Volatile
    private var activeSecureSocket: SSLSocket? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val sessionIdGenerator = AtomicLong(0L)

    private var serverIp = ""
    private var serverPort = 443
    private var sniHost = ""
    private var rawPayload = ""
    private var protocol = "tls"
    private var killswitchEnabled = false
    private var disallowedApps = ArrayList<String>()

    private var totalBytesSent: Long = 0
    private var totalBytesReceived: Long = 0

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
        killswitchEnabled = intent?.getBooleanExtra("KILLSWITCH_ENABLED", false) ?: false
        disallowedApps = intent?.getStringArrayListExtra("DISALLOWED_APPS") ?: ArrayList()

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
        val currentSession = sessionIdGenerator.incrementAndGet()
        showForegroundNotification("Connecting to $serverIp:$serverPort...")
        tunnelThread = Thread({ runSession(currentSession) }, "RealVpnTunnelThread-$currentSession").apply { start() }

        return START_STICKY
    }

    override fun run() {
        runSession(sessionIdGenerator.get())
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

            // 2. Establish TLS with SNI
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            secureSocket = factory.createSocket(rawSocket, serverIp, serverPort, true) as SSLSocket
            activeSecureSocket = secureSocket
            val params = SSLParameters()
            if (sniHost.isNotBlank()) {
                params.serverNames = listOf(SNIHostName(sniHost))
            }
            secureSocket.sslParameters = params
            secureSocket.startHandshake()

            Log.i(TAG, "TLS handshake successfully verified with $sniHost")

            // 3. Send handshake protocol payload if configured
            if (rawPayload.isNotBlank()) {
                val parsed = rawPayload
                    .replace("[host]", sniHost.ifBlank { serverIp })
                    .replace("[crlf]", "\r\n")
                    .replace("\\r\\n", "\r\n")
                secureSocket.outputStream.write(parsed.toByteArray(Charsets.UTF_8))
                secureSocket.outputStream.flush()
                Log.d(TAG, "Handshake protocol payload transmitted.")
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
                    VpnManager.updateTelemetry(totalBytesReceived, totalBytesSent)
                } else if (n < 0) {
                    break
                }
            }

            downThread.join(1000)

        } catch (e: java.net.UnknownHostException) {
            val msg = "Cannot resolve '$serverIp'. Enter your Hetzner Cloud IP or valid domain."
            Log.e(TAG, "DNS resolution failed: $msg", e)
            if (sessionIdGenerator.get() == mySessionId) VpnManager.onServiceStopped(msg)
        } catch (e: java.net.SocketTimeoutException) {
            val msg = "Connection to $serverIp:$serverPort timed out after 7s. Check Hetzner firewall rules."
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

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopTunnel(reason: String? = null, stopService: Boolean = true) {
        val wasRunning = isRunning.getAndSet(false)
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
        super.onDestroy()
    }
}
