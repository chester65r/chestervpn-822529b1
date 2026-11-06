package com.example.advancedtunnel.vpn

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Configuration for chunked HTTP/WebSocket handshake transmission over TCP or TLS sockets.
 */
data class HandshakeStreamConfig(
    val minSegmentBytes: Int = 6,
    val maxSegmentBytes: Int = 12,
    val interSegmentDelayMs: Long = 4L,
    val readResponseTimeoutMs: Int = 5000,
    val expectHttpUpgradeResponse: Boolean = false
) {
    fun validMinBytes(): Int = minSegmentBytes.coerceIn(1, 128)
    fun validMaxBytes(): Int = maxSegmentBytes.coerceIn(validMinBytes(), 256)
    fun validDelayMs(): Long = interSegmentDelayMs.coerceIn(0L, 100L)
}

/**
 * Structured result of an upstream HTTP/WebSocket handshake exchange.
 */
data class UpstreamHandshakeResult(
    val statusCode: Int,
    val statusLine: String,
    val responseHeaders: Map<String, String>,
    val bytesTransmitted: Int,
    val segmentsFlushed: Int,
    val handshakeDurationMs: Long,
    val isStreamUpgraded: Boolean
)

/**
 * Custom Socket Header & Chunked Stream Engine.
 *
 * Supports:
 * - Raw TCP and TLS socket establishment with explicit SNI (`SNIHostName`).
 * - Dynamic HTTP/WebSocket header template expansion (`[host]`, `[host_port]`, `[sni]`, `[ua]`, `[crlf]`, `[split]`).
 * - Outbound byte-stream chunking (6-to-12 byte segments with millisecond micro-delays between `flush()` calls).
 * - Upstream HTTP status line & header parsing (`200 OK` / `101 Switching Protocols`).
 */
object CarrierTunnelOptimizer {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /**
     * Opens a raw TCP or TLS socket to [remoteHost]:[remotePort] and applies [sniHost] when TLS is enabled.
     */
    @Throws(IOException::class)
    fun openOptimizedSocket(
        remoteHost: String,
        remotePort: Int,
        sniHost: String,
        useTls: Boolean = true,
        connectTimeoutMs: Int = 7000,
        protectSocket: (Socket) -> Boolean = { true }
    ): Socket {
        val rawSocket = Socket()
        protectSocket(rawSocket)
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true
        rawSocket.soTimeout = 20_000
        rawSocket.connect(InetSocketAddress(remoteHost, remotePort), connectTimeoutMs)

        if (!useTls) {
            return rawSocket
        }

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = sslFactory.createSocket(rawSocket, remoteHost, remotePort, true) as SSLSocket
        val effectiveSni = sniHost.trim().ifEmpty { remoteHost.trim() }

        val sslParams = sslSocket.sslParameters ?: SSLParameters()
        if (effectiveSni.isNotEmpty()) {
            try {
                sslParams.serverNames = listOf(SNIHostName(effectiveSni))
            } catch (_: IllegalArgumentException) {
                // IP literal does not require SNIHostName wrapper
            }
        }
        sslSocket.sslParameters = sslParams
        sslSocket.startHandshake()
        return sslSocket
    }

    /**
     * Expands dynamic macros (`[host]`, `[host_port]`, `[sni]`, `[ua]`, `[crlf]`) and splits
     * the template across `[split]` boundaries into ordered transmission blocks.
     */
    fun expandHeaderTemplate(
        rawTemplate: String,
        host: String,
        port: Int,
        sni: String = "",
        userAgent: String = DEFAULT_USER_AGENT
    ): List<String> {
        val targetHost = sni.trim().ifEmpty { host.trim() }
        val expanded = rawTemplate
            .replace("[host_port]", "$targetHost:$port", ignoreCase = true)
            .replace("[host]", targetHost, ignoreCase = true)
            .replace("[sni]", targetHost, ignoreCase = true)
            .replace("[ua]", userAgent, ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("\\r\\n", "\r\n")
            .replace("[instant_split]", "[split]", ignoreCase = true)
            .replace("[delay_split]", "[split]", ignoreCase = true)

        val blocks = expanded
            .split("[split]", ignoreCase = true)
            .filter { it.isNotEmpty() }
            .toMutableList()

        if (blocks.isEmpty()) {
            return emptyList()
        }

        val lastIdx = blocks.lastIndex
        val tail = blocks[lastIdx]
        blocks[lastIdx] = when {
            tail.endsWith("\r\n\r\n") -> tail
            tail.endsWith("\r\n") -> "$tail\r\n"
            else -> "$tail\r\n\r\n"
        }

        return blocks
    }

    /**
     * Writes the expanded handshake blocks in randomized [HandshakeStreamConfig.minSegmentBytes]..[HandshakeStreamConfig.maxSegmentBytes]
     * byte chunks (default 6..12 bytes) with millisecond micro-delays between `flush()` calls.
     *
     * @return Pair of (totalBytesWritten, totalSegmentsFlushed)
     */
    @Throws(IOException::class)
    fun writeChunkedHandshake(
        output: OutputStream,
        payloadBlocks: List<String>,
        config: HandshakeStreamConfig = HandshakeStreamConfig()
    ): Pair<Int, Int> {
        val minBytes = config.validMinBytes()
        val maxBytes = config.validMaxBytes()
        val delayMs = config.validDelayMs()

        var totalBytes = 0
        var totalSegments = 0

        for (block in payloadBlocks) {
            val rawBytes = block.toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < rawBytes.size) {
                val step = if (maxBytes > minBytes) {
                    Random.nextInt(minBytes, maxBytes + 1)
                } else {
                    minBytes
                }
                val chunkLength = step.coerceAtMost(rawBytes.size - offset)
                output.write(rawBytes, offset, chunkLength)
                output.flush()

                totalBytes += chunkLength
                totalSegments++
                offset += chunkLength

                if (offset < rawBytes.size && delayMs > 0L) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        return totalBytes to totalSegments
    }

    /**
     * Executes the complete outbound chunked handshake and optionally reads and validates
     * the upstream HTTP response header (`200 OK` or `101 Switching Protocols`).
     */
    @Throws(IOException::class)
    fun performUpgradedHandshake(
        socket: Socket,
        rawTemplate: String,
        host: String,
        port: Int,
        sni: String,
        userAgent: String = DEFAULT_USER_AGENT,
        config: HandshakeStreamConfig = HandshakeStreamConfig()
    ): UpstreamHandshakeResult {
        val startNanos = System.nanoTime()
        try {
            socket.tcpNoDelay = true
        } catch (_: Exception) {
        }

        val blocks = expandHeaderTemplate(
            rawTemplate = rawTemplate,
            host = host,
            port = port,
            sni = sni,
            userAgent = userAgent
        )

        if (blocks.isEmpty()) {
            return UpstreamHandshakeResult(
                statusCode = 200,
                statusLine = "DIRECT_STREAM",
                responseHeaders = emptyMap(),
                bytesTransmitted = 0,
                segmentsFlushed = 0,
                handshakeDurationMs = 0L,
                isStreamUpgraded = true
            )
        }

        val (bytesSent, segmentsSent) = writeChunkedHandshake(
            output = socket.getOutputStream(),
            payloadBlocks = blocks,
            config = config
        )

        if (!config.expectHttpUpgradeResponse) {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            return UpstreamHandshakeResult(
                statusCode = 101,
                statusLine = "CHUNKED_HANDSHAKE_FLUSHED",
                responseHeaders = emptyMap(),
                bytesTransmitted = bytesSent,
                segmentsFlushed = segmentsSent,
                handshakeDurationMs = elapsedMs,
                isStreamUpgraded = true
            )
        }

        val previousTimeout = socket.soTimeout
        return try {
            socket.soTimeout = config.readResponseTimeoutMs
            val (statusLine, statusCode, headers) = readHttpResponseHeaders(socket.getInputStream())
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            val upgraded = statusCode == 101 || statusCode == 200
            UpstreamHandshakeResult(
                statusCode = statusCode,
                statusLine = statusLine,
                responseHeaders = headers,
                bytesTransmitted = bytesSent,
                segmentsFlushed = segmentsSent,
                handshakeDurationMs = elapsedMs,
                isStreamUpgraded = upgraded
            )
        } finally {
            try {
                socket.soTimeout = previousTimeout
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Reads up to `\r\n\r\n` from [input] without consuming any subsequent tunnel payload bytes,
     * and parses the HTTP status line and response headers.
     */
    @Throws(IOException::class)
    fun readHttpResponseHeaders(input: InputStream, maxHeaderBytes: Int = 8192): Triple<String, Int, Map<String, String>> {
        val buffer = ByteArrayOutputStream(512)
        var matchState = 0

        while (buffer.size() < maxHeaderBytes) {
            val byteRead = input.read()
            if (byteRead == -1) break
            buffer.write(byteRead)

            matchState = when {
                byteRead == '\r'.code && (matchState == 0 || matchState == 2) -> matchState + 1
                byteRead == '\n'.code && (matchState == 1 || matchState == 3) -> matchState + 1
                else -> 0
            }
            if (matchState == 4) break
        }

        val rawHeaderText = buffer.toString(Charsets.UTF_8.name())
        val lines = rawHeaderText.split("\r\n").filter { it.isNotBlank() }
        val statusLine = lines.firstOrNull()?.trim() ?: "HTTP/1.1 000 Empty Response"
        val statusCode = parseHttpStatusCode(statusLine)

        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        return Triple(statusLine, statusCode, headers)
    }

    fun parseHttpStatusCode(statusLine: String): Int {
        val parts = statusLine.trim().split(Regex("\\s+"))
        if (parts.size >= 2 && parts[0].startsWith("HTTP/", ignoreCase = true)) {
            return parts[1].toIntOrNull() ?: 0
        }
        return 0
    }
}
