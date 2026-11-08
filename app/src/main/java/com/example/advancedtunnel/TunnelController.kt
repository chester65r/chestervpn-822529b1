package com.example.advancedtunnel

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.Executors

private class AppTunnel : Tunnel {
    @Volatile
    var state: Tunnel.State = Tunnel.State.DOWN
    override fun getName(): String = "console"
    override fun onStateChange(newState: Tunnel.State) {
        state = newState
    }
}

sealed interface WireGuardTunnelStatus {
    data object Down : WireGuardTunnelStatus
    data object WaitingForHandshake : WireGuardTunnelStatus
    data class Connected(val rxBytes: Long, val txBytes: Long) : WireGuardTunnelStatus
    data class Failed(val reason: String) : WireGuardTunnelStatus
}

enum class EngineState { DOWN, STARTING, UP, STOPPING }

/**
 * Wraps the WireGuard GoBackend packet engine for Hetzner CX23 (2.29.8.194:51820).
 * All backend calls and credential mutations run on one worker thread, so starts,
 * stops and credential changes cannot overlap. Fails closed if handshake is missing.
 */
object TunnelController {
    private const val REJECT_AFTER_MS = 180_000L
    private val worker = Executors.newSingleThreadExecutor()
    private val tunnel = AppTunnel()

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var serverKey: Key? = null

    @Volatile
    var engineState: EngineState = EngineState.DOWN
        private set

    private fun backend(context: Context): GoBackend =
        backend ?: synchronized(this) {
            backend ?: GoBackend(context.applicationContext).also { backend = it }
        }

    /**
     * Parses with the engine's own parser and enforces the enrolled full-tunnel profile shape:
     * one server peer, endpoint, preshared key, one /32 address, tunnel DNS, safe MTU, and both
     * default routes so no IPv4 or IPv6 traffic silently bypasses the tunnel.
     */
    fun parse(configText: String): Config {
        val config = ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)).use {
            Config.parse(it)
        }
        require(config.peers.size == 1) { "Profile must contain exactly one server peer" }
        val peer = config.peers[0]
        val iface = config.`interface`
        require(peer.endpoint.isPresent) { "Server endpoint is required" }
        require(peer.preSharedKey.isPresent) { "Preshared key is required" }
        require(iface.addresses.size == 1 && iface.addresses.first().mask == 32) {
            "Exactly one /32 address is required"
        }
        require(iface.dnsServers.isNotEmpty()) { "Tunnel DNS is required" }
        require(!iface.mtu.isPresent || iface.mtu.get() in 1280..1420) {
            "MTU must be 1280-1420"
        }
        require(
            peer.allowedIps.any { it.mask == 0 && it.address is Inet4Address } &&
                peer.allowedIps.any { it.mask == 0 && it.address is Inet6Address }
        ) {
            "Full-tunnel routes 0.0.0.0/0 and ::/0 are required"
        }
        return config
    }

    /** True only when the engine is confirmed down; credential changes are allowed only then. */
    fun isIdle(): Boolean = engineState == EngineState.DOWN && tunnel.state == Tunnel.State.DOWN

    /** Runs a credential mutation on the engine thread only while the engine is confirmed down. */
    fun whileIdle(action: () -> Unit, done: (Boolean) -> Unit) = worker.execute {
        val ok = isIdle() && try {
            action()
            true
        } catch (_: Exception) {
            false
        }
        done(ok)
    }

    fun up(context: Context, configText: String, done: (String?) -> Unit) = worker.execute {
        if (!isIdle()) return@execute done("Tunnel is already running")
        engineState = EngineState.STARTING
        val error = try {
            val config = parse(configText)
            serverKey = config.peers[0].publicKey
            backend(context).setState(tunnel, Tunnel.State.UP, config)
            engineState = EngineState.UP
            null
        } catch (e: Exception) {
            engineState = if (tunnel.state == Tunnel.State.DOWN) EngineState.DOWN else EngineState.UP
            if (engineState == EngineState.DOWN) serverKey = null
            if (e is IllegalArgumentException) "Invalid profile" else "Engine could not start the tunnel"
        }
        done(error)
    }

    /** Reports true only after the engine confirms DOWN; on failure state stays UP (fail closed). */
    fun down(context: Context, done: (Boolean) -> Unit) = worker.execute {
        engineState = EngineState.STOPPING
        try {
            backend(context).setState(tunnel, Tunnel.State.DOWN, null)
        } catch (_: Exception) {
        }
        val stopped = tunnel.state == Tunnel.State.DOWN
        engineState = if (stopped) EngineState.DOWN else EngineState.UP
        if (stopped) serverKey = null
        done(stopped)
    }

    /** CONNECTED only after a fresh authenticated handshake with the configured server key. */
    fun status(context: Context, done: (WireGuardTunnelStatus) -> Unit) = worker.execute {
        val key = serverKey
        val result = if (tunnel.state == Tunnel.State.DOWN) {
            engineState = EngineState.DOWN
            serverKey = null
            WireGuardTunnelStatus.Down
        } else if (key == null) {
            WireGuardTunnelStatus.Failed("Engine running without a tracked server; stop the tunnel")
        } else {
            try {
                val peer = backend(context).getStatistics(tunnel).peer(key)
                val age = System.currentTimeMillis() - (peer?.latestHandshakeEpochMillis() ?: 0L)
                if (peer == null || peer.latestHandshakeEpochMillis() == 0L || age > REJECT_AFTER_MS) {
                    WireGuardTunnelStatus.WaitingForHandshake
                } else {
                    WireGuardTunnelStatus.Connected(peer.rxBytes(), peer.txBytes())
                }
            } catch (_: Exception) {
                WireGuardTunnelStatus.Failed("Statistics unavailable")
            }
        }
        done(result)
    }
}
