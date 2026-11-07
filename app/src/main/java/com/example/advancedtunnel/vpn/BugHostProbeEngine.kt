package com.example.advancedtunnel.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object BugHostProbeEngine {

    data class BypassCorridor(
        val host: String,
        val port: Int,
        val latencyMs: Long,
        val transport: String = if (port == 443 || port == 8443) "TLS" else "HTTP"
    )

    suspend fun scanBypassCorridors(
        candidateHosts: List<String>,
        ports: List<Int> = listOf(443, 80, 8080),
        timeoutMs: Int = 2500
    ): List<BypassCorridor> = withContext(Dispatchers.IO) {
        val uniqueHosts = candidateHosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val uniquePorts = ports.distinct()

        val results = coroutineScope {
            uniqueHosts.flatMap { host ->
                uniquePorts.map { port ->
                    async {
                        probeHostPort(host, port, timeoutMs)
                    }
                }
            }.awaitAll()
        }

        results.filterNotNull().sortedBy { it.latencyMs }
    }

    private fun probeHostPort(host: String, port: Int, timeoutMs: Int): BypassCorridor? {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val latencyMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            BypassCorridor(
                host = host,
                port = port,
                latencyMs = latencyMs
            )
        } catch (_: Exception) {
            null
        }
    }
}
