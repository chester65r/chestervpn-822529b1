package com.example.advancedtunnel.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Candidate CDN or cloud edge endpoint to be tested across multiple ports.
 */
data class EdgeCandidateEndpoint(
    val id: String,
    val label: String,
    val hostOrIp: String,
    val sniHostname: String,
    val ports: List<Int> = listOf(443, 80, 8080)
)

/**
 * Result of probing a single (hostOrIp, port, sniHostname) target with a TCP/TLS + HTTP HEAD/GET check.
 */
data class EdgeProbeResult(
    val id: String,
    val label: String,
    val hostOrIp: String,
    val port: Int,
    val sniHostname: String,
    val transport: String,
    val reachable: Boolean,
    val tcpConnectMs: Long,
    val tlsHandshakeMs: Long,
    val totalRttMs: Long,
    val httpStatusCode: Int,
    val statusLine: String,
    val serverHeader: String
) {
    val httpStatusLine: String
        get() = statusLine

    val errorDetail: String?
        get() = if (reachable) null else statusLine.ifBlank { "Timeout" }
}

/**
 * Concurrent Kotlin Coroutines scanner that tests lists of CDN edge IPs and ports (443, 80, 8080)
 * with custom HTTP HEAD/GET probes and ranks endpoints by TCP/TLS handshake latency and HTTP status code.
 */
object EdgeHunterEngine {

    private const val MAX_CONCURRENT_PROBES = 8
    private const val DEFAULT_TIMEOUT_MS = 2800

    val DEFAULT_EDGE_CANDIDATES: List<EdgeCandidateEndpoint> = listOf(
        EdgeCandidateEndpoint(
            id = "cf-anycast-primary",
            label = "Cloudflare Edge Anycast",
            hostOrIp = "1.1.1.1",
            sniHostname = "cloudflare.com",
            ports = listOf(443, 80, 8080)
        ),
        EdgeCandidateEndpoint(
            id = "gcp-london-edge",
            label = "Google Cloud Edge (europe-west2)",
            hostOrIp = "europe-west2.gcp.cloud",
            sniHostname = "cloud.google.com",
            ports = listOf(443, 80)
        ),
        EdgeCandidateEndpoint(
            id = "gcp-us-central",
            label = "Google Cloud Edge (us-central1)",
            hostOrIp = "us-central1.gcp.cloud",
            sniHostname = "cloud.google.com",
            ports = listOf(443, 80)
        ),
        EdgeCandidateEndpoint(
            id = "hetzner-fsn-edge",
            label = "Hetzner Cloud (Falkenstein FSN1)",
            hostOrIp = "fsn1.hetzner.com",
            sniHostname = "hetzner.com",
            ports = listOf(443, 80, 8080)
        ),
        EdgeCandidateEndpoint(
            id = "hetzner-hel-edge",
            label = "Hetzner Cloud (Helsinki HEL1)",
            hostOrIp = "hel1.hetzner.com",
            sniHostname = "hetzner.com",
            ports = listOf(443, 80)
        ),
        EdgeCandidateEndpoint(
            id = "fastly-global-edge",
            label = "Fastly Global CDN Edge",
            hostOrIp = "151.101.1.57",
            sniHostname = "fastly.com",
            ports = listOf(443, 80)
        )
    )

    /**
     * Scans all candidate endpoints across their configured ports (`443`, `80`, `8080`) concurrently
     * and returns results sorted by reachability, HTTP status suitability, and lowest total RTT.
     */
    suspend fun scanEndpointsConcurrently(
        candidates: List<EdgeCandidateEndpoint> = DEFAULT_EDGE_CANDIDATES,
        requestMethod: String = "HEAD",
        customHostHeader: String = "",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): List<EdgeProbeResult> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        val probeJobs = coroutineScope {
            candidates.flatMap { candidate ->
                candidate.ports.map { port ->
                    async {
                        semaphore.withPermit {
                            probeSingleEndpoint(
                                candidate = candidate,
                                port = port,
                                requestMethod = requestMethod,
                                customHostHeader = customHostHeader,
                                timeoutMs = timeoutMs
                            )
                        }
                    }
                }
            }
        }

        val results = probeJobs.awaitAll()
        results.sortedWith(
            compareBy<EdgeProbeResult> { !it.reachable }
                .thenBy { statusRankScore(it.httpStatusCode) }
                .thenBy { if (it.totalRttMs >= 0) it.totalRttMs else Long.MAX_VALUE }
        )
    }

    /**
     * Probes a single (hostOrIp, port) endpoint using a real TCP connection, optional TLS handshake
     * on port 443, and an HTTP HEAD/GET probe request.
     */
    fun probeSingleEndpoint(
        candidate: EdgeCandidateEndpoint,
        port: Int,
        requestMethod: String = "HEAD",
        customHostHeader: String = "",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): EdgeProbeResult {
        val useTls = (port == 443 || port == 8443)
        val transportLabel = if (useTls) "TLS" else "HTTP"
        val effectiveSni = customHostHeader.trim().ifEmpty {
            candidate.sniHostname.trim().ifEmpty { candidate.hostOrIp.trim() }
        }
        val method = requestMethod.trim().uppercase().ifEmpty { "HEAD" }

        var rawSocket: Socket? = null
        var activeSocket: Socket? = null
        val startTotal = System.nanoTime()

        return try {
            val tcpStart = System.nanoTime()
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = timeoutMs
            rawSocket.connect(InetSocketAddress(candidate.hostOrIp.trim(), port), timeoutMs)
            val tcpConnectMs = ((System.nanoTime() - tcpStart) / 1_000_000L).coerceAtLeast(1L)

            var tlsHandshakeMs = 0L
            if (useTls) {
                val tlsStart = System.nanoTime()
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(
                    rawSocket,
                    candidate.hostOrIp.trim(),
                    port,
                    true
                ) as SSLSocket
                val params = sslSocket.sslParameters ?: SSLParameters()
                if (effectiveSni.isNotEmpty()) {
                    try {
                        params.serverNames = listOf(SNIHostName(effectiveSni))
                    } catch (_: IllegalArgumentException) {
                    }
                }
                sslSocket.sslParameters = params
                sslSocket.startHandshake()
                tlsHandshakeMs = ((System.nanoTime() - tlsStart) / 1_000_000L).coerceAtLeast(1L)
                activeSocket = sslSocket
            } else {
                activeSocket = rawSocket
            }

            val probeRequest = buildString {
                append("$method / HTTP/1.1\r\n")
                append("Host: $effectiveSni\r\n")
                append("User-Agent: Mozilla/5.0 (Linux; Android 14) Chrome/128.0.0.0 Mobile\r\n")
                append("Connection: close\r\n\r\n")
            }

            val out = activeSocket.getOutputStream()
            out.write(probeRequest.toByteArray(Charsets.UTF_8))
            out.flush()

            val (statusLine, statusCode, headers) = CarrierTunnelOptimizer.readHttpResponseHeaders(
                input = activeSocket.getInputStream(),
                maxHeaderBytes = 4096
            )
            val totalRttMs = ((System.nanoTime() - startTotal) / 1_000_000L).coerceAtLeast(tcpConnectMs)
            val serverHeader = headers.entries
                .firstOrNull { it.key.equals("Server", ignoreCase = true) }
                ?.value ?: transportLabel

            EdgeProbeResult(
                id = "${candidate.id}-$port",
                label = candidate.label,
                hostOrIp = candidate.hostOrIp,
                port = port,
                sniHostname = effectiveSni,
                transport = transportLabel,
                reachable = true,
                tcpConnectMs = tcpConnectMs,
                tlsHandshakeMs = tlsHandshakeMs,
                totalRttMs = totalRttMs,
                httpStatusCode = if (statusCode > 0) statusCode else 200,
                statusLine = statusLine,
                serverHeader = serverHeader
            )
        } catch (e: Exception) {
            EdgeProbeResult(
                id = "${candidate.id}-$port",
                label = candidate.label,
                hostOrIp = candidate.hostOrIp,
                port = port,
                sniHostname = effectiveSni,
                transport = transportLabel,
                reachable = false,
                tcpConnectMs = -1L,
                tlsHandshakeMs = -1L,
                totalRttMs = -1L,
                httpStatusCode = 0,
                statusLine = e.javaClass.simpleName,
                serverHeader = "Unreachable"
            )
        } finally {
            try { activeSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun statusRankScore(statusCode: Int): Int {
        return when (statusCode) {
            101, 200, 204 -> 0
            301, 302, 307, 308 -> 1
            400, 403, 404 -> 2
            else -> 3
        }
    }
}
