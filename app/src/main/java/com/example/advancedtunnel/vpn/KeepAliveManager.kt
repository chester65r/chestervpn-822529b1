package com.example.advancedtunnel.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class KeepAliveManager(
    private val outputStreamProvider: () -> OutputStream,
    private val intervalMs: Long,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val TAG = "KeepAliveManager"

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val stopped = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)

    private var context: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Secondary constructor preserving compatibility with AdvancedVpnService.
     */
    constructor(
        context: Context?,
        scope: CoroutineScope,
        onReconnectRequired: suspend (reason: String) -> Boolean
    ) : this(
        outputStreamProvider = { ByteArrayOutputStream() },
        intervalMs = 20000L,
        onFailure = {}
    ) {
        this.context = context
    }

    fun start() {
        if (job?.isActive == true) return

        stopped.set(false)
        failureReported.set(false)

        job = scope.launch {
            Log.i(TAG, "Keep-Alive engine started.")

            while (isActive && !stopped.get()) {
                try {
                    val heartbeat = "\r\n".toByteArray()
                    val outputStream = outputStreamProvider()

                    outputStream.write(heartbeat)
                    outputStream.flush()

                    Log.d(TAG, "Keep-Alive heartbeat sent.")
                } catch (cancellation: CancellationException) {
                    Log.i(TAG, "Keep-Alive coroutine cancelled.")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Keep-Alive write failed: ${e.message}", e)

                    if (!stopped.get() && failureReported.compareAndSet(false, true)) {
                        onFailure(e)
                    }

                    break
                }

                delay(intervalMs)
            }

            Log.i(TAG, "Keep-Alive loop exited.")
        }
    }

    fun stop() {
        stopped.set(true)
        job?.cancel()
        job = null

        Log.i(TAG, "Keep-Alive engine stopped.")
    }

    // Compatibility helpers for AdvancedVpnService
    fun acquireLocks() {
        val ctx = context ?: return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TunnelConsole:VpnSessionWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            }
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "TunnelConsole:VpnLowLatencyWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire power/wifi locks: ${e.message}")
        }
    }

    fun createBatteryOptimizationExemptionIntent(): Intent? {
        val ctx = context ?: return null
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            null
        }
    }

    fun recordTrafficActivity() {}

    fun startMonitoring(
        socketProvider: () -> Socket?,
        probeTargetHost: String,
        probeTargetPort: Int
    ) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val sock = socketProvider()
                if (sock == null || sock.isClosed || !sock.isConnected) break
            }
        }
    }

    fun stopMonitoringAndReleaseLocks() {
        stop()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }
}
