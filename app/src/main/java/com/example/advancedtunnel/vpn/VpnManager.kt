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

object VpnManager {

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

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _serverLatencyMs = MutableStateFlow<Long?>(null)
    val serverLatencyMs: StateFlow<Long?> = _serverLatencyMs.asStateFlow()

    private var previousRx = 0L
    private var previousTx = 0L
    private var statsJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun isVpnPrepared(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    fun startVpn(context: Context, profile: TunnelProfile) {
        if (_vpnState.value is RealVpnState.Connected || _vpnState.value is RealVpnState.Connecting) {
            return
        }

        _vpnState.value = RealVpnState.Connecting
        registerNetworkMonitoring(context, profile)

        val intent = Intent(context, AdvancedVpnService::class.java).apply {
            putExtra("SERVER_IP", profile.ip)
            putExtra("SERVER_PORT", profile.port.toIntOrNull() ?: 443)
            putExtra("SNI_HOST", profile.sni)
            putExtra("PAYLOAD", profile.payload)
            putExtra("PROTOCOL", "tls")
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
            _vpnState.value = RealVpnState.Failed("Could not start service: ${e.localizedMessage}")
        }
    }

    fun stopVpn(context: Context) {
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
        startTelemetryLoop()
    }

    // Called by AdvancedVpnService when an error occurs or disconnects
    fun onServiceStopped(reason: String? = null) {
        statsJob?.cancel()
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
                    latency
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server reachability probe failed: ${e.message}")
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
