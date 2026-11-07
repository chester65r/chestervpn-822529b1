package com.example.advancedtunnel.wireguard

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Encapsulates WireGuard runtime telemetry stats from GoBackend.
 */
data class WireGuardTunnelStats(
    val lastHandshakeEpochSeconds: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val peerEndpoint: String = "",
    val allowedIps: String = "",
    val interfaceAddresses: String = "",
    val dnsServers: String = "",
    val mtu: Int? = null,
    val listenPort: Int? = null,
    val persistentKeepalive: Int? = null,
    val peerPublicKey: String = "",
    val interfacePublicKey: String = "",
    val hasPresharedKey: Boolean = false
) {
    fun formatLastHandshake(): String {
        if (lastHandshakeEpochSeconds <= 0L) return "Awaiting handshake…"
        val nowSec = System.currentTimeMillis() / 1000L
        val diffSec = (nowSec - lastHandshakeEpochSeconds).coerceAtLeast(0L)
        return when {
            diffSec < 60 -> "${diffSec}s ago"
            diffSec < 3600 -> "${diffSec / 60}m ${diffSec % 60}s ago"
            else -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m ago"
        }
    }
}

object WireGuardManager {
    private const val TAG = "WireGuardManager"
    private const val TUNNEL_NAME = "wg0"

    private var backend: Backend? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var statsJob: Job? = null

    private val _wgStats = MutableStateFlow(WireGuardTunnelStats())
    val wgStats: StateFlow<WireGuardTunnelStats> = _wgStats.asStateFlow()

    private val tunnelHandle = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "WireGuard tunnel state changed: $newState")
        }
    }

    @Synchronized
    fun getOrCreateBackend(context: Context): Backend {
        if (backend == null) {
            backend = GoBackend(context.applicationContext)
        }
        return backend!!
    }

    /**
     * Parses standard wg-quick .conf text into com.wireguard.config.Config.
     * Supports:
     * [Interface]
     * PrivateKey, Address, DNS, ListenPort, MTU
     * [Peer]
     * PublicKey, PresharedKey, Endpoint, AllowedIPs, PersistentKeepalive
     */
    fun parseConfig(configText: String): Config {
        val inputStream = ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8))
        return Config.parse(inputStream)
    }

    /**
     * Extracts human-readable summary metadata from a parsed WireGuard config.
     */
    fun extractMetadata(config: Config): WireGuardTunnelStats {
        val peer = config.peers.firstOrNull()
        val iface = config.`interface`
        val addresses = iface.addresses.joinToString(", ") { it.toString() }
        val dns = iface.dnsServers.joinToString(", ") { it.hostAddress ?: it.toString() }
        val endpoint = peer?.endpoint?.map { it.toString() }?.orElse("") ?: ""
        val allowedIps = peer?.allowedIps?.joinToString(", ") { it.toString() } ?: ""
        val mtu = if (iface.mtu.isPresent) iface.mtu.get() else null
        val listenPort = if (iface.listenPort.isPresent) iface.listenPort.get() else null
        val keepalive = if (peer?.persistentKeepalive?.isPresent == true) peer.persistentKeepalive.get() else null
        val peerPk = peer?.publicKey?.toBase64() ?: ""
        val ifacePk = iface.keyPair.publicKey.toBase64()
        val hasPsk = peer?.preSharedKey?.isPresent == true

        return WireGuardTunnelStats(
            interfaceAddresses = addresses,
            dnsServers = dns,
            peerEndpoint = endpoint,
            allowedIps = allowedIps,
            mtu = mtu,
            listenPort = listenPort,
            persistentKeepalive = keepalive,
            peerPublicKey = peerPk,
            interfacePublicKey = ifacePk,
            hasPresharedKey = hasPsk
        )
    }

    fun syncMetadataFromConfig(config: Config) {
        val meta = extractMetadata(config)
        val cur = _wgStats.value
        _wgStats.value = meta.copy(
            lastHandshakeEpochSeconds = cur.lastHandshakeEpochSeconds,
            rxBytes = cur.rxBytes,
            txBytes = cur.txBytes
        )
    }

    /**
     * Establishes the WireGuard tunnel through the GoBackend.
     */
    suspend fun startTunnel(context: Context, config: Config): Result<Unit> {
        return try {
            val b = getOrCreateBackend(context)
            val state = b.getState(tunnelHandle)
            if (state == Tunnel.State.UP) {
                b.setState(tunnelHandle, Tunnel.State.DOWN, null)
            }

            b.setState(tunnelHandle, Tunnel.State.UP, config)
            val metadata = extractMetadata(config)
            _wgStats.value = metadata
            startStatsMonitoring()
            Log.i(TAG, "WireGuard GoBackend tunnel brought UP successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring WireGuard tunnel UP", e)
            Result.failure(e)
        }
    }

    /**
     * Tears down the WireGuard tunnel.
     */
    suspend fun stopTunnel(context: Context) {
        stopTunnelBlocking(context)
    }

    fun stopTunnelBlocking(context: Context) {
        stopStatsMonitoring()
        try {
            val b = getOrCreateBackend(context)
            if (b.getState(tunnelHandle) != Tunnel.State.DOWN) {
                b.setState(tunnelHandle, Tunnel.State.DOWN, null)
            }
            _wgStats.value = _wgStats.value.copy(
                lastHandshakeEpochSeconds = 0L,
                rxBytes = 0L,
                txBytes = 0L
            )
            Log.i(TAG, "WireGuard GoBackend tunnel brought DOWN")
        } catch (e: Exception) {
            Log.e(TAG, "Error bringing WireGuard tunnel down", e)
        }
    }

    private fun startStatsMonitoring() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                try {
                    val b = backend ?: continue
                    if (b.getState(tunnelHandle) == Tunnel.State.UP) {
                        val stats: Statistics = b.getStatistics(tunnelHandle)
                        val totalRx = stats.totalRx()
                        val totalTx = stats.totalTx()
                        val peers = stats.peers()
                        val firstPeerKey = peers.firstOrNull()
                        val lastHandshake = if (firstPeerKey != null) {
                            stats.peer(firstPeerKey)?.latestHandshakeEpochMillis() ?: 0L
                        } else 0L

                        _wgStats.value = _wgStats.value.copy(
                            rxBytes = totalRx,
                            txBytes = totalTx,
                            lastHandshakeEpochSeconds = if (lastHandshake > 0) lastHandshake / 1000L else 0L
                        )

                        // Update VpnManager telemetry
                        com.example.advancedtunnel.vpn.VpnManager.updateTelemetry(totalRx, totalTx)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error polling WireGuard stats: ${e.message}")
                }
            }
        }
    }

    private fun stopStatsMonitoring() {
        statsJob?.cancel()
        statsJob = null
    }
}
