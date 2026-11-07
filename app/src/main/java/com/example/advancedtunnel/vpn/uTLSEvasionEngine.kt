package com.example.advancedtunnel.vpn

import kotlinx.coroutines.delay
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket

object uTLSEvasionEngine {

    suspend fun sendFragmentedClientHello(
        secureSocket: SSLSocket,
        sniHost: String,
        payloadTemplate: String,
        chunkSize: Int = 8,
        jitterDelayMs: Long = 12L
    ) {
        val effectiveHost = sniHost.ifBlank {
            secureSocket.inetAddress?.hostName ?: "127.0.0.1"
        }
        val port = if (secureSocket.port > 0) secureSocket.port else 443

        val expanded = payloadTemplate
            .replace("[host_port]", "$effectiveHost:$port", ignoreCase = true)
            .replace("[host]", effectiveHost, ignoreCase = true)
            .replace("[sni]", effectiveHost, ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("\\r\\n", "\r\n")

        val normalizedPayload = when {
            expanded.endsWith("\r\n\r\n") -> expanded
            expanded.endsWith("\r\n") -> "$expanded\r\n"
            else -> "$expanded\r\n\r\n"
        }

        val payloadBytes = normalizedPayload.toByteArray(StandardCharsets.UTF_8)
        val output = secureSocket.outputStream
        val safeChunkSize = chunkSize.coerceAtLeast(1)

        var offset = 0
        while (offset < payloadBytes.size) {
            val len = minOf(safeChunkSize, payloadBytes.size - offset)
            output.write(payloadBytes, offset, len)
            output.flush()
            offset += len
            if (offset < payloadBytes.size && jitterDelayMs > 0L) {
                delay(jitterDelayMs)
            }
        }
    }
}
