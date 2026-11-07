package com.example.advancedtunnel.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.util.Log
import com.example.advancedtunnel.AdvancedVpnService
import com.example.advancedtunnel.model.TunnelProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "VpnManager"

sealed interface RealVpnState {
    data object Disconnected : RealVpnState
    data object Connecting : RealVpnState
    data class Connected(
        val serverEndpoint: String,
        val connectedAt: Long
    ) : RealVpnState
    data object Reconnecting : RealVpnState
    data class Failed(val error: String) : RealVpnState
}

data class TelemetryLogEntry(
    val id: Long = System.nanoTime(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val level: String,
    val message: String
)

object VpnManager {

    private const val MAX_LOG_ENTRIES = 120

    private val _vpnState = MutableStateFlow<RealVpnState>(RealVpnState.Disconnected)
    val vpnState: StateFlow<RealVpnState> = _vpnState.asStateFlow()

    private val _rxBytes = MutableStateFlow(0L)
    val rxBytes: StateFlow<Long> = _rxBytes.asStateFlow()

    private val _txBytes = MutableStateFlow(0L)
    val txBytes: StateFlow<Long> = _txBytes.asStateFlow()

    private val _rxSpeed = MutableStateFlow(0.0)
    val rxSpeed: StateFlow<Double> = _rxSpeed.asStateFlow()

    private val _txSpeed = MutableStateFlow(0.0)
    val txSpeed: StateFlow<Double> = _txSpeed.asStateFlow()

    private val _peakRxSpeed = MutableStateFlow(0.0)
    val peakRxSpeed: StateFlow<Double> = _peakRxSpeed.asStateFlow()

    private val _peakTxSpeed = MutableStateFlow(0.0)
    val peakTxSpeed: StateFlow<Double> = _peakTxSpeed.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _serverLatencyMs = MutableStateFlow<Long?>(null)
    val serverLatencyMs: StateFlow<Long?> = _serverLatencyMs.asStateFlow()

    private val _telemetryLogs = MutableStateFlow<List<TelemetryLogEntry>>(
        listOf(
            TelemetryLogEntry(
                level = "INFO",
                message = "Tunnel Console telemetry engine initialized (MTU 1500, IPv4/IPv6 dual-stack ready)."
            )
        )
    )
    val telemetryLogs: StateFlow<List<TelemetryLogEntry>> = _telemetryLogs.asStateFlow()

    private var previousRx = 0L
    private var previousTx = 0L
    private var statsJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun appendTelemetryLog(level: String, message: String) {
        val current = _telemetryLogs.value.toMutableList()
        current.add(0, TelemetryLogEntry(level = level, message = message))
        if (current.size > MAX_LOG_ENTRIES) {
            current.subList(MAX_LOG_ENTRIES, current.size).clear()
        }
        _telemetryLogs.value = current
    }

    fun clearTelemetryLogs() {
        _telemetryLogs.value = emptyList()
        appendTelemetryLog("INFO", "Telemetry log buffer cleared by operator.")
    }

    fun exportSanitizedDiagnosticsReport(
        profile: TunnelProfile,
        tlsConfig: TlsFragmentConfig = TlsFragmentConfig()
    ): String {
        val stateName = when (val s = _vpnState.value) {
            is RealVpnState.Connected -> "CONNECTED (since ${s.connectedAt})"
            is RealVpnState.Connecting -> "CONNECTING"
            is RealVpnState.Reconnecting -> "RECONNECTING"
            is RealVpnState.Failed -> "FAILED (${s.error})"
            is RealVpnState.Disconnected -> "DISCONNECTED"
        }
        val maskedIp = if (profile.ip.isNotBlank()) {
            val parts = profile.ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.***.***" else "${profile.ip.take(4)}***"
        } else {
            "not-set"
        }
        val fp = TlsFingerprintEngine.computeFingerprint(
            profileId = tlsConfig.fingerprintProfileId,
            sniHost = profile.sni.ifBlank { "sni.configured" },
            alpnEnabled = tlsConfig.alpnEnabled
        )
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val recentLogs = _telemetryLogs.value.take(30).joinToString("\n") { entry ->
            "[${sdf.format(java.util.Date(entry.timestampMillis))}] [${entry.level}] ${entry.message}"
        }
        return buildString {
            appendLine("=== TUNNEL CONSOLE DIAGNOSTICS REPORT ===")
            appendLine("State: $stateName")
            appendLine("Endpoint (Sanitized): $maskedIp:${profile.port}")
            appendLine("Uptime: ${_uptimeSeconds.value}s")
            appendLine("RTT Latency: ${_serverLatencyMs.value?.let { "${it}ms" } ?: "N/A"}")
            appendLine("Total RX: ${_rxBytes.value} bytes (Peak: ${_peakRxSpeed.value.toLong()} B/s)")
            appendLine("Total TX: ${_txBytes.value} bytes (Peak: ${_peakTxSpeed.value.toLong()} B/s)")
            appendLine("Configured MTU: 1500 | DNS: 1.1.1.1, 8.8.8.8")
            appendLine("TLS Fragmentation: ${if (tlsConfig.enabled) "${tlsConfig.mode.id} (${tlsConfig.normalizedMinChunk()}-${tlsConfig.normalizedMaxChunk()}B, jitter ${tlsConfig.normalizedMinJitter()}-${tlsConfig.normalizedMaxJitter()}ms)" else "DISABLED"}")
            appendLine("JA3 Profile: ${fp.profileName} | JA3 MD5: ${fp.ja3HashMd5}")
            appendLine("JA4 Signature: ${fp.ja4String} | ALPN: ${fp.negotiatedAlpn}")
            appendLine("--- RECENT TELEMETRY EVENTS ---")
            appendLine(recentLogs)
        }
    }

    fun isVpnPrepared(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    fun startVpn(
        context: Context,
        profile: TunnelProfile,
        killswitchEnabled: Boolean = false,
        disallowedApps: List<String> = emptyList(),
        tlsFragmentConfig: TlsFragmentConfig = TlsFragmentConfig()
    ) {
        if (_vpnState.value is RealVpnState.Connected || _vpnState.value is RealVpnState.Connecting) {
            return
        }

        _vpnState.value = RealVpnState.Connecting
        appendTelemetryLog(
            "INFO",
            "Starting VPN session to ${profile.ip}:${profile.port} (Desync=${if (tlsFragmentConfig.enabled) tlsFragmentConfig.mode.id else "OFF"}, JA3=${tlsFragmentConfig.fingerprintProfileId})"
        )
        registerNetworkMonitoring(context, profile)

        val intent = Intent(context, AdvancedVpnService::class.java).apply {
            putExtra("SERVER_IP", profile.ip)
            putExtra("SERVER_PORT", profile.port.toIntOrNull() ?: 443)
            putExtra("SNI_HOST", profile.sni)
            putExtra("PAYLOAD", profile.payload)
            putExtra("PROTOCOL", "tls")
            putExtra("KILLSWITCH_ENABLED", killswitchEnabled)
            putStringArrayListExtra("DISALLOWED_APPS", ArrayList(disallowedApps))
            putExtra("TLS_FRAG_ENABLED", tlsFragmentConfig.enabled)
            putExtra("TLS_FRAG_MODE", tlsFragmentConfig.mode.id)
            putExtra("TLS_FRAG_MIN_CHUNK", tlsFragmentConfig.normalizedMinChunk())
            putExtra("TLS_FRAG_MAX_CHUNK", tlsFragmentConfig.normalizedMaxChunk())
            putExtra("TLS_FRAG_MIN_JITTER", tlsFragmentConfig.normalizedMinJitter())
            putExtra("TLS_FRAG_MAX_JITTER", tlsFragmentConfig.normalizedMaxJitter())
            putExtra("TLS_JA3_PROFILE_ID", tlsFragmentConfig.fingerprintProfileId)
            putExtra("TLS_ALPN_ENABLED", tlsFragmentConfig.alpnEnabled)
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
            appendTelemetryLog("ERROR", "Failed to start foreground VPN service: ${e.localizedMessage}")
            _vpnState.value = RealVpnState.Failed("Could not start service: ${e.localizedMessage}")
        }
    }

    fun startWireGuardVpn(
        context: Context,
        configText: String,
        peerEndpoint: String
    ) {
        if (_vpnState.value is RealVpnState.Connected || _vpnState.value is RealVpnState.Connecting) {
            return
        }

        _vpnState.value = RealVpnState.Connecting
        appendTelemetryLog(
            "INFO",
            "Starting WireGuard GoBackend session via AdvancedVpnService -> $peerEndpoint"
        )

        val intent = Intent(context, AdvancedVpnService::class.java).apply {
            putExtra("PROTOCOL", "wireguard")
            putExtra("WG_CONFIG_TEXT", configText)
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WireGuard VPN service", e)
            appendTelemetryLog("ERROR", "Failed to start WireGuard VPN service: ${e.localizedMessage}")
            _vpnState.value = RealVpnState.Failed("Could not start service: ${e.localizedMessage}")
        }
    }

    fun stopVpn(context: Context) {
        appendTelemetryLog("INFO", "Stopping active VPN tunnel interface.")
        val intent = Intent(context, AdvancedVpnService::class.java).apply {
            action = "ACTION_STOP"
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN service", e)
        }
        unregisterNetworkMonitoring()
        onServiceStopped()
    }

    // Called by AdvancedVpnService when the TUN interface is genuinely established and handshake passes
    fun onServiceConnected(serverEndpoint: String) {
        val now = System.currentTimeMillis()
        _vpnState.value = RealVpnState.Connected(serverEndpoint, now)
        appendTelemetryLog("SUCCESS", "TUN0 interface established (10.0.0.2/24, MTU 1500) -> $serverEndpoint")
        startTelemetryLoop()
    }

    // Called by AdvancedVpnService when an error occurs or disconnects
    fun onServiceStopped(reason: String? = null) {
        statsJob?.cancel()
        if (reason != null) {
            appendTelemetryLog("WARN", "Session ended: $reason")
        }
        _vpnState.value = if (reason != null) RealVpnState.Failed(reason) else RealVpnState.Disconnected
        _rxSpeed.value = 0.0
        _txSpeed.value = 0.0
        _uptimeSeconds.value = 0L
        _serverLatencyMs.value = null
    }

    fun updateTelemetry(rxTotal: Long, txTotal: Long) {
        _rxBytes.value = rxTotal
        _txBytes.value = txTotal
    }

    suspend fun checkServerReachability(host: String, port: Int, timeoutMs: Int = 3000): Long? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    val latency = System.currentTimeMillis() - startTime
                    _serverLatencyMs.value = latency
                    appendTelemetryLog("PROBE", "TCP RTT probe to $host:$port succeeded in ${latency}ms")
                    latency
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server reachability probe failed: ${e.message}")
                appendTelemetryLog("WARN", "TCP RTT probe to $host:$port failed: ${e.localizedMessage ?: "Timeout"}")
                _serverLatencyMs.value = null
                null
            }
        }
    }

    private fun startTelemetryLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            previousRx = _rxBytes.value
            previousTx = _txBytes.value
            while (isActive && _vpnState.value is RealVpnState.Connected) {
                delay(1000)
                _uptimeSeconds.value += 1

                val currentRx = _rxBytes.value
                val currentTx = _txBytes.value

                val downBps = (currentRx - previousRx).coerceAtLeast(0L).toDouble()
                val upBps = (currentTx - previousTx).coerceAtLeast(0L).toDouble()

                _rxSpeed.value = downBps
                _txSpeed.value = upBps
                if (downBps > _peakRxSpeed.value) _peakRxSpeed.value = downBps
                if (upBps > _peakTxSpeed.value) _peakTxSpeed.value = upBps

                previousRx = currentRx
                previousTx = currentTx
            }
        }
    }

    private fun registerNetworkMonitoring(context: Context, profile: TunnelProfile) {
        if (connectivityManager == null) {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Underlying physical network available: $network")
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Underlying network lost: $network. Reconnecting tunnel...")
                if (_vpnState.value is RealVpnState.Connected) {
                    _vpnState.value = RealVpnState.Reconnecting
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkMonitoring() {
        try {
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
                networkCallback = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }
}
