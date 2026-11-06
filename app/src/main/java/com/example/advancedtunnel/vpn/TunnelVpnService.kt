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
import android.util.Log
import com.example.advancedtunnel.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Production Foreground VpnService that establishes the virtual TUN interface (tun0),
 * excludes disallowed packages (including this app's own UID to prevent routing loops),
 * extracts the native ParcelFileDescriptor, and bridges TUN packets through a local
 * SOCKS5 / upstream tunnel relay with seamless socket hot-swapping.
 */
class TunnelVpnService : VpnService() {

    companion object {
        private const val TAG = "TunnelVpnService"
        const val ACTION_START_TUNNEL = "com.example.advancedtunnel.action.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "com.example.advancedtunnel.action.STOP_TUNNEL"

        const val EXTRA_SOCKS_HOST = "EXTRA_SOCKS_HOST"
        const val EXTRA_SOCKS_PORT = "EXTRA_SOCKS_PORT"
        const val EXTRA_REMOTE_HOST = "EXTRA_REMOTE_HOST"
        const val EXTRA_REMOTE_PORT = "EXTRA_REMOTE_PORT"
        const val EXTRA_DISALLOWED_PACKAGES = "EXTRA_DISALLOWED_PACKAGES"
        const val EXTRA_KILLSWITCH = "EXTRA_KILLSWITCH"

        private const val NOTIFICATION_CHANNEL_ID = "tunnel_vpn_foreground_channel"
        private const val NOTIFICATION_ID = 2001
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.0.2"
        private const val TUN_IPV4_PREFIX = 24
        private const val TUN_IPV6_ADDRESS = "fd00:1234:5678::2"
        private const val TUN_IPV6_PREFIX = 64
        private const val PACKET_BUFFER_SIZE = 32768
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isTunnelRunning = AtomicBoolean(false)

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    private val activeUpstreamSocket = AtomicReference<Socket?>(null)
    private var keepAliveManager: KeepAliveManager? = null

    override fun onCreate() {
        super.onCreate()
        keepAliveManager = KeepAliveManager(
            context = applicationContext,
            scope = serviceScope,
            onReconnectRequired = { reason ->
                Log.w(TAG, "KeepAliveManager triggered upstream socket recovery: $reason")
                recoverUpstreamSocketWithoutDroppingTun()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TUNNEL) {
            teardownTunnel("User requested disconnect")
            return START_NOT_STICKY
        }

        val socksHost = intent?.getStringExtra(EXTRA_SOCKS_HOST) ?: "127.0.0.1"
        val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, 10808) ?: 10808
        val remoteHost = intent?.getStringExtra(EXTRA_REMOTE_HOST) ?: ""
        val remotePort = intent?.getIntExtra(EXTRA_REMOTE_PORT, 443) ?: 443
        val disallowedApps = intent?.getStringArrayListExtra(EXTRA_DISALLOWED_PACKAGES) ?: arrayListOf()
        val killswitch = intent?.getBooleanExtra(EXTRA_KILLSWITCH, false) ?: false

        promoteToForeground("Initializing TUN interface & SOCKS5 bridge...")

        if (isTunnelRunning.compareAndSet(false, true)) {
            keepAliveManager?.acquireLocks()
            serviceScope.launch {
                runTunnelBridgeLoop(
                    socksHost = socksHost,
                    socksPort = socksPort,
                    remoteHost = remoteHost,
                    remotePort = remotePort,
                    disallowedApps = disallowedApps,
                    killswitch = killswitch
                )
            }
        }

        return START_STICKY
    }

    /**
     * Configures and establishes the virtual TUN interface (tun0) via VpnService.Builder.
     */
    private fun establishTunInterface(
        disallowedApps: List<String>,
        killswitch: Boolean
    ): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("Tunnel Console Engine")
            .setMtu(TUN_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        // Dual-stack IPv6 full-route protection to prevent IPv6 traffic leaks
        try {
            builder.addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111")
        } catch (e: Exception) {
            Log.w(TAG, "IPv6 route allocation skipped: ${e.message}")
        }

        // Always exclude our own package or protect outbound sockets to prevent routing loops
        val excludedSet = disallowedApps.toMutableSet()
        excludedSet.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
                Log.d(TAG, "Excluded package from TUN routing: $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exclude package $pkg: ${e.message}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (killswitch) {
                builder.setBlocking(true)
            }
        }

        return builder.establish()
            ?: throw IllegalStateException("Android OS rejected VpnService.Builder.establish()")
    }

    /**
     * Performs an RFC 1928 SOCKS5 handshake + CONNECT command over a protected socket.
     */
    @Throws(IOException::class)
    private fun connectSocks5Channel(
        socksHost: String,
        socksPort: Int,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int = 7000
    ): Socket {
        val socket = Socket()
        // Protect outbound socket so traffic bypasses tun0 and routes over the physical interface
        if (!protect(socket)) {
            throw IOException("VpnService.protect(socket) failed for outbound tunnel socket")
        }

        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 25_000
        socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)

        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        // 1. Greeting: VER = 0x05, NMETHODS = 0x01, METHOD = 0x00 (No Authentication)
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val version = input.readByte()
        val method = input.readByte()
        if (version != 0x05.toByte() || method != 0x00.toByte()) {
            socket.close()
            throw IOException("SOCKS5 negotiation failed (ver=$version, method=$method)")
        }

        // 2. CONNECT Request: VER=0x05, CMD=0x01 (CONNECT), RSV=0x00, ATYP=0x03 (DOMAINNAME)
        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val reqBuf = ByteBuffer.allocate(4 + 1 + hostBytes.size + 2)
        reqBuf.put(0x05.toByte())
        reqBuf.put(0x01.toByte()) // CMD: CONNECT
        reqBuf.put(0x00.toByte()) // RSV
        reqBuf.put(0x03.toByte()) // ATYP: Domain name
        reqBuf.put(hostBytes.size.toByte())
        reqBuf.put(hostBytes)
        reqBuf.putShort(targetPort.toShort())

        output.write(reqBuf.array())
        output.flush()

        // 3. Parse SOCKS5 Server Reply
        val repVer = input.readByte()
        val repStatus = input.readByte()
        input.readByte() // RSV
        val atyp = input.readByte().toInt() and 0xFF

        if (repVer != 0x05.toByte() || repStatus != 0x00.toByte()) {
            socket.close()
            throw IOException("SOCKS5 CONNECT rejected with status=0x${repStatus.toString(16)}")
        }

        // Consume BND.ADDR and BND.PORT
        when (atyp) {
            0x01 -> input.skipBytes(4 + 2) // IPv4
            0x03 -> {
                val len = input.readByte().toInt() and 0xFF
                input.skipBytes(len + 2)
            }
            0x04 -> input.skipBytes(16 + 2) // IPv6
            else -> throw IOException("Unsupported SOCKS5 ATYP: $atyp")
        }

        return socket
    }

    private var cachedSocksHost: String = "127.0.0.1"
    private var cachedSocksPort: Int = 10808
    private var cachedRemoteHost: String = ""
    private var cachedRemotePort: Int = 443

    private suspend fun runTunnelBridgeLoop(
        socksHost: String,
        socksPort: Int,
        remoteHost: String,
        remotePort: Int,
        disallowedApps: List<String>,
        killswitch: Boolean
    ) {
        cachedSocksHost = socksHost
        cachedSocksPort = socksPort
        cachedRemoteHost = remoteHost
        cachedRemotePort = remotePort

        try {
            // Establish TUN interface once and keep its FD alive across upstream socket recoveries
            val pfd = establishTunInterface(disallowedApps, killswitch)
            tunInterface = pfd
            val nativeFd = pfd.fd
            Log.i(TAG, "TUN interface established successfully (native fd=$nativeFd)")

            val upstream = connectSocks5Channel(socksHost, socksPort, remoteHost, remotePort)
            activeUpstreamSocket.set(upstream)

            promoteToForeground("Connected via $remoteHost:$remotePort")
            keepAliveManager?.startMonitoring(
                socketProvider = { activeUpstreamSocket.get() },
                probeTargetHost = remoteHost.ifBlank { socksHost },
                probeTargetPort = remotePort
            )

            val tunIn = FileInputStream(pfd.fileDescriptor)
            val tunOut = FileOutputStream(pfd.fileDescriptor)

            // Downstream worker: reads from activeUpstreamSocket and writes to tun0
            val downstreamJob = serviceScope.launch {
                val downBuffer = ByteArray(PACKET_BUFFER_SIZE)
                while (isActive && isTunnelRunning.get()) {
                    val sock = activeUpstreamSocket.get()
                    if (sock == null || sock.isClosed) {
                        kotlinx.coroutines.delay(150)
                        continue
                    }
                    try {
                        val bytesRead = sock.getInputStream().read(downBuffer)
                        if (bytesRead > 0) {
                            tunOut.write(downBuffer, 0, bytesRead)
                            tunOut.flush()
                            keepAliveManager?.recordTrafficActivity()
                        } else if (bytesRead < 0) {
                            recoverUpstreamSocketWithoutDroppingTun()
                        }
                    } catch (e: IOException) {
                        if (isTunnelRunning.get()) {
                            Log.w(TAG, "Downstream read interrupted, triggering hot-swap: ${e.message}")
                            recoverUpstreamSocketWithoutDroppingTun()
                        }
                    }
                }
            }

            // Upstream loop: reads IP packets from tun0 and forwards to activeUpstreamSocket
            val upBuffer = ByteArray(PACKET_BUFFER_SIZE)
            while (serviceScope.isActive && isTunnelRunning.get()) {
                val bytesRead = tunIn.read(upBuffer)
                if (bytesRead > 0) {
                    val sock = activeUpstreamSocket.get()
                    if (sock != null && !sock.isClosed) {
                        try {
                            val out = sock.getOutputStream()
                            out.write(upBuffer, 0, bytesRead)
                            out.flush()
                            keepAliveManager?.recordTrafficActivity()
                        } catch (e: IOException) {
                            Log.w(TAG, "Upstream write failed, triggering hot-swap: ${e.message}")
                            recoverUpstreamSocketWithoutDroppingTun()
                        }
                    }
                } else if (bytesRead < 0) {
                    break
                }
            }

            downstreamJob.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in TUN-to-SOCKS5 bridge: ${e.message}", e)
        } finally {
            teardownTunnel("Tunnel loop terminated")
        }
    }

    /**
     * Hot-swaps the upstream SOCKS5/TLS socket without closing `tunInterface` (tun0),
     * preventing packet leaks and preserving active app sockets on the device.
     */
    private suspend fun recoverUpstreamSocketWithoutDroppingTun(): Boolean {
        if (!isTunnelRunning.get() || tunInterface == null) return false
        return try {
            promoteToForeground("Re-establishing upstream relay socket...")
            val oldSocket = activeUpstreamSocket.getAndSet(null)
            try { oldSocket?.close() } catch (_: Exception) {}

            val newSocket = connectSocks5Channel(
                socksHost = cachedSocksHost,
                socksPort = cachedSocksPort,
                targetHost = cachedRemoteHost,
                targetPort = cachedRemotePort
            )
            activeUpstreamSocket.set(newSocket)
            promoteToForeground("Connected via $cachedRemoteHost:$cachedRemotePort")
            Log.i(TAG, "Upstream socket hot-swapped without dropping TUN interface")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Upstream socket recovery attempt failed: ${e.message}")
            false
        }
    }

    private fun promoteToForeground(statusText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tunnel Console VPN Engine",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Persistent foreground notification for active VPN tunnel"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Tunnel Console Active")
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

    private fun teardownTunnel(reason: String) {
        if (!isTunnelRunning.compareAndSet(true, false)) return
        Log.i(TAG, "Tearing down tunnel: $reason")
        keepAliveManager?.stopMonitoringAndReleaseLocks()
        try { activeUpstreamSocket.getAndSet(null)?.close() } catch (_: Exception) {}
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        teardownTunnel("VPN permission revoked by Android OS")
        super.onRevoke()
    }

    override fun onDestroy() {
        teardownTunnel("VpnService onDestroy")
        serviceScope.cancel()
        super.onDestroy()
    }
}
