package com.example.advancedtunnel.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.system.measureTimeMillis

enum class SessionHealthState {
    HEALTHY,
    DEGRADED,
    RECONNECTING,
    DISCONNECTED
}

data class KeepAliveTelemetry(
    val state: SessionHealthState = SessionHealthState.DISCONNECTED,
    val lastRttMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val totalHeartbeatsSent: Long = 0L,
    val totalRecoveries: Int = 0
)

/**
 * Manages long-term VPN session stability:
 * 1. Acquires PARTIAL_WAKE_LOCK and WIFI_MODE_FULL_LOW_LATENCY locks so Android Doze mode
 *    does not suspend the packet forwarding loop.
 * 2. Sends adaptive TCP/UDP keep-alive heartbeats when the tunnel is idle to prevent
 *    NAT/firewall state-table expiration.
 * 3. Executes exponential-backoff reconnection callbacks to hot-swap broken upstream sockets
 *    without tearing down the active TUN interface.
 */
class KeepAliveManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onReconnectRequired: suspend (reason: String) -> Boolean
) {
    companion object {
        private const val TAG = "KeepAliveManager"
        private const val WAKELOCK_TAG = "TunnelConsole:VpnSessionWakeLock"
        private const val WIFILOCK_TAG = "TunnelConsole:VpnLowLatencyWifiLock"

        private const val IDLE_HEARTBEAT_THRESHOLD_MS = 15_000L
        private const val HEARTBEAT_INTERVAL_MS = 12_000L
        private const val PROBE_TIMEOUT_MS = 3_500
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var heartbeatJob: Job? = null

    private val lastTrafficTimestampMs = AtomicLong(System.currentTimeMillis())
    private val consecutiveFailures = AtomicInteger(0)
    private val totalHeartbeats = AtomicLong(0L)
    private val totalRecoveries = AtomicInteger(0)
    private val random = SecureRandom()

    private val _telemetry = MutableStateFlow(KeepAliveTelemetry())
    val telemetry: StateFlow<KeepAliveTelemetry> = _telemetry.asStateFlow()

    /**
     * Acquires CPU WakeLock and high-performance Wi-Fi lock for uninterrupted background execution.
     */
    fun acquireLocks() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // Hold for up to 24 hours per session; renewed on reconnect
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
                Log.d(TAG, "Acquired PARTIAL_WAKE_LOCK")
            }

            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, WIFILOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d(TAG, "Acquired low-latency WifiLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire power/wifi locks: ${e.message}")
        }
    }

    /**
     * Checks whether the app is exempted from Android Doze battery optimizations,
     * and returns an Intent to request exemption if needed.
     */
    fun createBatteryOptimizationExemptionIntent(): Intent? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            null
        }
    }

    fun recordTrafficActivity() {
        lastTrafficTimestampMs.set(System.currentTimeMillis())
    }

    fun startMonitoring(
        socketProvider: () -> Socket?,
        probeTargetHost: String,
        probeTargetPort: Int
    ) {
        heartbeatJob?.cancel()
        consecutiveFailures.set(0)
        lastTrafficTimestampMs.set(System.currentTimeMillis())
        _telemetry.value = KeepAliveTelemetry(state = SessionHealthState.HEALTHY)

        heartbeatJob = scope.launch {
            while (isActive) {
                val jitterMs = random.nextInt(2_500).toLong()
                delay(HEARTBEAT_INTERVAL_MS + jitterMs)

                val idleDuration = System.currentTimeMillis() - lastTrafficTimestampMs.get()
                val activeSocket = socketProvider()

                if (activeSocket == null || activeSocket.isClosed || !activeSocket.isConnected) {
                    triggerRecoveryWithBackoff("Upstream socket closed or null")
                    continue
                }

                // If traffic is actively flowing and socket is healthy, skip extra network probe
                if (idleDuration < IDLE_HEARTBEAT_THRESHOLD_MS) {
                    continue
                }

                val rtt = performMultiplexedHeartbeat(probeTargetHost, probeTargetPort)
                if (rtt != null) {
                    val beats = totalHeartbeats.incrementAndGet()
                    consecutiveFailures.set(0)
                    _telemetry.value = _telemetry.value.copy(
                        state = SessionHealthState.HEALTHY,
                        lastRttMs = rtt,
                        consecutiveFailures = 0,
                        totalHeartbeatsSent = beats
                    )
                } else {
                    val fails = consecutiveFailures.incrementAndGet()
                    Log.w(TAG, "Heartbeat probe failed ($fails/$MAX_CONSECUTIVE_FAILURES)")
                    _telemetry.value = _telemetry.value.copy(
                        state = SessionHealthState.DEGRADED,
                        consecutiveFailures = fails
                    )

                    if (fails >= MAX_CONSECUTIVE_FAILURES) {
                        triggerRecoveryWithBackoff("Exceeded $MAX_CONSECUTIVE_FAILURES missed heartbeats")
                    }
                }
            }
        }
    }

    /**
     * Executes a lightweight TCP + UDP keep-alive probe to refresh NAT state tables
     * and measure live path RTT.
     */
    private fun performMultiplexedHeartbeat(host: String, port: Int): Long? {
        if (host.isBlank()) return null
        return try {
            var rttMs = 0L
            rttMs = measureTimeMillis {
                Socket().use { probeSocket ->
                    probeSocket.tcpNoDelay = true
                    probeSocket.soTimeout = PROBE_TIMEOUT_MS
                    probeSocket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                }
            }
            // Send a 4-byte UDP NAT binding refresh datagram on best-effort basis
            try {
                DatagramSocket().use { udp ->
                    val keepAliveFrame = ByteBuffer.allocate(8)
                        .putInt(0x54554E4C) // "TUNL" magic
                        .putInt(random.nextInt())
                        .array()
                    val pkt = DatagramPacket(
                        keepAliveFrame,
                        keepAliveFrame.size,
                        InetAddress.getByName(host),
                        port
                    )
                    udp.send(pkt)
                }
            } catch (_: Exception) {
                // UDP refresh is supplementary to TCP liveness check
            }
            rttMs
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun triggerRecoveryWithBackoff(reason: String) {
        _telemetry.value = _telemetry.value.copy(state = SessionHealthState.RECONNECTING)
        var attempt = 0

        while (scope.isActive && attempt < 6) {
            val expDelay = min(BASE_BACKOFF_MS * (1L shl attempt), MAX_BACKOFF_MS)
            val jitter = random.nextInt(500).toLong()
            delay(expDelay + jitter)

            val recovered = onReconnectRequired("$reason (attempt ${attempt + 1})")
            if (recovered) {
                consecutiveFailures.set(0)
                lastTrafficTimestampMs.set(System.currentTimeMillis())
                val count = totalRecoveries.incrementAndGet()
                _telemetry.value = _telemetry.value.copy(
                    state = SessionHealthState.HEALTHY,
                    consecutiveFailures = 0,
                    totalRecoveries = count
                )
                return
            }
            attempt++
        }
    }

    fun stopMonitoringAndReleaseLocks() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _telemetry.value = _telemetry.value.copy(state = SessionHealthState.DISCONNECTED)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
    }
}
