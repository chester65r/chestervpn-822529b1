package com.example.advancedtunnel.vpn

import com.example.advancedtunnel.model.RelayServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

enum class NodeHealthTier {
    HEALTHY,
    DEGRADED,
    UNREACHABLE
}

data class NodeBenchmarkMetric(
    val serverId: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val avgRttMs: Long,
    val jitterMs: Long,
    val packetLossPercent: Int,
    val tier: NodeHealthTier,
    val measuredAtMillis: Long = System.currentTimeMillis()
)

/**
 * Multi-endpoint TCP/TLS handshake latency benchmark and automatic failover engine.
 * Probes configured relay nodes concurrently to compute average RTT, jitter, and health tier.
 */
object ServerHealthMonitor {

    private const val PROBE_SAMPLES = 3
    private const val PROBE_TIMEOUT_MS = 2200

    /**
     * Benchmarks all provided [servers] concurrently and returns a list sorted with
     * healthy, lowest-latency nodes first.
     */
    suspend fun benchmarkAllNodes(servers: List<RelayServer>): List<NodeBenchmarkMetric> =
        coroutineScope {
            servers.map { server ->
                async(Dispatchers.IO) {
                    benchmarkSingleNode(server)
                }
            }.awaitAll().sortedWith(
                compareBy<NodeBenchmarkMetric> {
                    when (it.tier) {
                        NodeHealthTier.HEALTHY -> 0
                        NodeHealthTier.DEGRADED -> 1
                        NodeHealthTier.UNREACHABLE -> 2
                    }
                }.thenBy { if (it.avgRttMs >= 0) it.avgRttMs else Long.MAX_VALUE }
            )
        }

    /**
     * Performs [PROBE_SAMPLES] sequential TCP connect probes to measure RTT, jitter, and loss.
     */
    suspend fun benchmarkSingleNode(server: RelayServer): NodeBenchmarkMetric =
        withContext(Dispatchers.IO) {
            val rttSamples = mutableListOf<Long>()
            var failures = 0

            repeat(PROBE_SAMPLES) {
                val start = System.currentTimeMillis()
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(server.host, server.port), PROBE_TIMEOUT_MS)
                        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                        rttSamples.add(elapsed)
                    }
                } catch (_: Exception) {
                    failures++
                }
            }

            if (rttSamples.isEmpty()) {
                return@withContext NodeBenchmarkMetric(
                    serverId = server.id,
                    serverName = server.name,
                    host = server.host,
                    port = server.port,
                    avgRttMs = -1L,
                    jitterMs = 0L,
                    packetLossPercent = 100,
                    tier = NodeHealthTier.UNREACHABLE
                )
            }

            val avgRtt = rttSamples.sum() / rttSamples.size
            val jitter = if (rttSamples.size >= 2) {
                rttSamples.zipWithNext { a, b -> abs(b - a) }.average().toLong()
            } else {
                0L
            }
            val lossPercent = (failures * 100) / PROBE_SAMPLES

            val tier = when {
                lossPercent == 0 && avgRtt <= 220L -> NodeHealthTier.HEALTHY
                lossPercent < 100 -> NodeHealthTier.DEGRADED
                else -> NodeHealthTier.UNREACHABLE
            }

            NodeBenchmarkMetric(
                serverId = server.id,
                serverName = server.name,
                host = server.host,
                port = server.port,
                avgRttMs = avgRtt,
                jitterMs = jitter,
                packetLossPercent = lossPercent,
                tier = tier
            )
        }

    /**
     * Selects the optimal failover candidate from a list of servers excluding [failedHost].
     */
    suspend fun selectBestFailoverServer(
        servers: List<RelayServer>,
        failedHost: String? = null
    ): Pair<RelayServer, NodeBenchmarkMetric>? {
        val candidates = if (failedHost.isNullOrBlank()) {
            servers
        } else {
            servers.filter { !it.host.equals(failedHost, ignoreCase = true) }.ifEmpty { servers }
        }
        if (candidates.isEmpty()) return null

        val ranked = benchmarkAllNodes(candidates)
        val bestMetric = ranked.firstOrNull { it.tier != NodeHealthTier.UNREACHABLE } ?: return null
        val matchedServer = candidates.find { it.id == bestMetric.serverId } ?: return null
        return matchedServer to bestMetric
    }
}
