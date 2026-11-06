package com.example.advancedtunnel.vpn

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Defines how outbound TLS ClientHello packets are desynchronized across TLS record boundaries
 * and TCP segments to prevent stateful DPI reassembly.
 */
enum class TlsFragmentMode(val id: String, val label: String, val description: String) {
    TLS_RECORD_AND_TCP(
        id = "TLS_RECORD_AND_TCP",
        label = "Multi-Record TLS + TCP Split",
        description = "Splits ClientHello into multiple RFC 8446 0x16 records AND flushes randomized TCP chunks with jitter"
    ),
    SNI_BOUNDARY_SPLIT(
        id = "SNI_BOUNDARY_SPLIT",
        label = "Surgical SNI Boundary Split",
        description = "Locates the exact SNI extension offset (0x0000) and bisects the hostname across two TLS records & TCP writes"
    ),
    TLS_RECORD_ONLY(
        id = "TLS_RECORD_ONLY",
        label = "TLS Record-Layer Framing Only",
        description = "Rewrites single ClientHello into multiple 5-byte 0x16 TLS records without artificial TCP sleep delay"
    ),
    TCP_SEGMENT_ONLY(
        id = "TCP_SEGMENT_ONLY",
        label = "TCP Segment Chunking + Jitter",
        description = "Preserves single TLS record header while flushing 5–15 byte TCP segments with millisecond jitter"
    );

    companion object {
        fun fromId(id: String): TlsFragmentMode {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: TLS_RECORD_AND_TCP
        }
    }
}

/**
 * User-configurable parameters for TLS Record Desynchronization & JA3/JA4 Fingerprint Spoofing.
 */
data class TlsFragmentConfig(
    val enabled: Boolean = true,
    val mode: TlsFragmentMode = TlsFragmentMode.TLS_RECORD_AND_TCP,
    val minChunkBytes: Int = 5,
    val maxChunkBytes: Int = 15,
    val minJitterMs: Int = 3,
    val maxJitterMs: Int = 12,
    val fingerprintProfileId: String = "chrome_128_android",
    val alpnEnabled: Boolean = true
) {
    fun normalizedMinChunk(): Int = minChunkBytes.coerceIn(1, 256)
    fun normalizedMaxChunk(): Int = maxChunkBytes.coerceIn(normalizedMinChunk(), 512)
    fun normalizedMinJitter(): Int = minJitterMs.coerceIn(0, 100)
    fun normalizedMaxJitter(): Int = maxJitterMs.coerceIn(normalizedMinJitter(), 250)
}

/**
 * Telemetry report of an actual or simulated ClientHello packet desynchronization pass.
 */
data class FragmentDispatchStats(
    val originalBytes: Int,
    val emittedBytes: Int,
    val tlsRecordCount: Int,
    val tcpWriteChunks: Int,
    val sniSplitOffset: Int,
    val totalJitterDelayMs: Long,
    val chunkBreakdown: List<String>
)

/**
 * Core algorithmic engine for RFC-compliant TLS 1.2/1.3 Record Fragmentation,
 * SNI extension offset discovery, TCP segment chunking, and raw socket macro injection.
 */
object TlsFragmentationEngine {

    private const val TLS_RECORD_TYPE_HANDSHAKE: Byte = 0x16
    private const val TLS_HANDSHAKE_CLIENT_HELLO: Byte = 0x01
    private const val TLS_HEADER_LEN = 5

    /**
     * Locates the byte index inside a TLS record payload where the SNI hostname string begins,
     * and returns the midpoint inside the SNI string so a record/segment boundary bisects it.
     */
    fun locateSniSplitOffset(payload: ByteArray, sniHost: String): Int {
        if (sniHost.isNotBlank()) {
            val sniBytes = sniHost.trim().toByteArray(Charsets.US_ASCII)
            if (sniBytes.size >= 2) {
                val matchIdx = indexOfSubArray(payload, sniBytes)
                if (matchIdx >= 0) {
                    return matchIdx + (sniBytes.size / 2).coerceAtLeast(1)
                }
            }
        }
        // Fallback: scan for TLS Extension 0x00 0x00 (server_name) followed by host_name type 0x00
        for (i in 40 until (payload.size - 9)) {
            if (payload[i] == 0x00.toByte() &&
                payload[i + 1] == 0x00.toByte() &&
                payload[i + 6] == 0x00.toByte()
            ) {
                val nameLen = ((payload[i + 7].toInt() and 0xFF) shl 8) or (payload[i + 8].toInt() and 0xFF)
                if (nameLen in 2..253 && i + 9 + nameLen <= payload.size) {
                    return i + 9 + (nameLen / 2)
                }
            }
        }
        return (payload.size / 3).coerceAtLeast(1)
    }

    /**
     * Rewrites a single TLS `ClientHello` record (`0x16 0x03 0x01/0x03 [len_hi] [len_lo] [payload...]`)
     * into multiple smaller valid `0x16` TLS records.
     *
     * Every emitted record has its own 5-byte TLS record header (`0x16`, major, minor, 2-byte length),
     * ensuring full RFC 8446 / RFC 5246 compliance with upstream Nginx / Xray / OpenSSL servers
     * while preventing single-record SNI signature matching by stateful DPI middleboxes.
     */
    fun rewriteClientHelloIntoMultipleTlsRecords(
        rawPacket: ByteArray,
        sniHost: String,
        minChunkBytes: Int,
        maxChunkBytes: Int,
        surgicalSniOnly: Boolean
    ): Triple<ByteArray, Int, Int> {
        if (rawPacket.size <= TLS_HEADER_LEN + 4 || rawPacket[0] != TLS_RECORD_TYPE_HANDSHAKE) {
            return Triple(rawPacket, 1, -1)
        }

        val verMajor = rawPacket[1]
        val verMinor = rawPacket[2]
        val declaredLen = ((rawPacket[3].toInt() and 0xFF) shl 8) or (rawPacket[4].toInt() and 0xFF)
        if (declaredLen <= 1 || TLS_HEADER_LEN + declaredLen > rawPacket.size) {
            return Triple(rawPacket, 1, -1)
        }

        val handshakeType = rawPacket[5]
        if (handshakeType != TLS_HANDSHAKE_CLIENT_HELLO) {
            return Triple(rawPacket, 1, -1)
        }

        val recordPayload = rawPacket.copyOfRange(TLS_HEADER_LEN, TLS_HEADER_LEN + declaredLen)
        val sniMidpoint = locateSniSplitOffset(recordPayload, sniHost)

        val splitPoints = sortedSetOf<Int>()
        if (sniMidpoint in 1 until recordPayload.size) {
            splitPoints.add(sniMidpoint)
        }

        if (!surgicalSniOnly) {
            val safeMin = minChunkBytes.coerceIn(4, 128)
            val safeMax = maxChunkBytes.coerceIn(safeMin, 256)
            var cursor = 0
            // Fragment the first 160 bytes (covering ClientHello header + SNI + ALPN) into small records
            val activeZoneLimit = (sniMidpoint + 48).coerceAtMost(recordPayload.size)
            while (cursor < activeZoneLimit) {
                val step = if (safeMax > safeMin) Random.nextInt(safeMin, safeMax + 1) else safeMin
                cursor += step
                if (cursor in 1 until recordPayload.size) {
                    splitPoints.add(cursor)
                }
            }
        }

        val boundaries = listOf(0) + splitPoints.toList() + listOf(recordPayload.size)
        val out = ByteArrayOutputStream(rawPacket.size + boundaries.size * TLS_HEADER_LEN)
        var recordCount = 0

        for (idx in 0 until boundaries.size - 1) {
            val start = boundaries[idx]
            val end = boundaries[idx + 1]
            val sliceLen = end - start
            if (sliceLen <= 0) continue

            // Write 5-byte TLS Record Header
            out.write(TLS_RECORD_TYPE_HANDSHAKE.toInt())
            out.write(verMajor.toInt())
            out.write(verMinor.toInt())
            out.write((sliceLen ushr 8) and 0xFF)
            out.write(sliceLen and 0xFF)
            // Write TLS Record Fragment Body
            out.write(recordPayload, start, sliceLen)
            recordCount++
        }

        // Preserve any coalesced trailing records after the first ClientHello record
        val trailingOffset = TLS_HEADER_LEN + declaredLen
        if (trailingOffset < rawPacket.size) {
            out.write(rawPacket, trailingOffset, rawPacket.size - trailingOffset)
        }

        return Triple(out.toByteArray(), recordCount, sniMidpoint)
    }

    /**
     * Executes a dry-run simulation of TLS ClientHello fragmentation and TCP chunking
     * using a synthetic RFC 8446 ClientHello frame containing [sniHost] so users can
     * inspect exact byte boundaries and jitter timing in the UI.
     */
    fun simulateClientHelloDesync(
        sniHost: String,
        config: TlsFragmentConfig
    ): FragmentDispatchStats {
        val effectiveSni = sniHost.ifBlank { "cloud.google.com" }
        val syntheticClientHello = buildSyntheticClientHelloRecord(effectiveSni)
        val minChunk = config.normalizedMinChunk()
        val maxChunk = config.normalizedMaxChunk()
        val minJitter = config.normalizedMinJitter()
        val maxJitter = config.normalizedMaxJitter()

        val (recordFramedBytes, tlsRecordsCount, sniOffset) = when (config.mode) {
            TlsFragmentMode.TLS_RECORD_AND_TCP,
            TlsFragmentMode.TLS_RECORD_ONLY -> rewriteClientHelloIntoMultipleTlsRecords(
                rawPacket = syntheticClientHello,
                sniHost = effectiveSni,
                minChunkBytes = minChunk,
                maxChunkBytes = maxChunk,
                surgicalSniOnly = false
            )
            TlsFragmentMode.SNI_BOUNDARY_SPLIT -> rewriteClientHelloIntoMultipleTlsRecords(
                rawPacket = syntheticClientHello,
                sniHost = effectiveSni,
                minChunkBytes = minChunk,
                maxChunkBytes = maxChunk,
                surgicalSniOnly = true
            )
            TlsFragmentMode.TCP_SEGMENT_ONLY -> Triple(
                syntheticClientHello,
                1,
                locateSniSplitOffset(
                    syntheticClientHello.copyOfRange(5, syntheticClientHello.size),
                    effectiveSni
                )
            )
        }

        val breakdown = mutableListOf<String>()
        var totalJitter = 0L
        var tcpChunks = 0

        if (config.mode == TlsFragmentMode.TLS_RECORD_ONLY) {
            tcpChunks = tlsRecordsCount
            breakdown.add(
                "Record-Layer Split: ${syntheticClientHello.size}B -> ${recordFramedBytes.size}B across $tlsRecordsCount TLS 0x16 records (SNI split @ +${sniOffset}B)"
            )
        } else {
            var offset = 0
            var chunkIdx = 1
            while (offset < recordFramedBytes.size) {
                val size = if (config.mode == TlsFragmentMode.SNI_BOUNDARY_SPLIT && chunkIdx <= 2) {
                    val firstRecLen = (((recordFramedBytes[3].toInt() and 0xFF) shl 8) or (recordFramedBytes[4].toInt() and 0xFF)) + 5
                    if (chunkIdx == 1) firstRecLen.coerceAtMost(recordFramedBytes.size - offset)
                    else (recordFramedBytes.size - offset)
                } else {
                    val step = if (maxChunk > minChunk) Random.nextInt(minChunk, maxChunk + 1) else minChunk
                    if (offset > 140) (recordFramedBytes.size - offset) else step.coerceAtMost(recordFramedBytes.size - offset)
                }

                val jitter = if (offset + size < recordFramedBytes.size && maxJitter > 0) {
                    if (maxJitter > minJitter) Random.nextInt(minJitter, maxJitter + 1).toLong() else minJitter.toLong()
                } else 0L

                totalJitter += jitter
                tcpChunks++

                if (breakdown.size < 8) {
                    val hexPreview = recordFramedBytes
                        .copyOfRange(offset, (offset + size).coerceAtMost(offset + 6))
                        .joinToString(" ") { "%02X".format(it) }
                    breakdown.add(
                        "Chunk #$chunkIdx [${offset}..${offset + size - 1}] (${size}B) • +${jitter}ms • $hexPreview…"
                    )
                }
                offset += size
                chunkIdx++
            }
        }

        return FragmentDispatchStats(
            originalBytes = syntheticClientHello.size,
            emittedBytes = recordFramedBytes.size,
            tlsRecordCount = tlsRecordsCount,
            tcpWriteChunks = tcpChunks,
            sniSplitOffset = sniOffset,
            totalJitterDelayMs = totalJitter,
            chunkBreakdown = breakdown
        )
    }

    /**
     * Expands raw socket macros (`[split]`, `[instant_split]`, `[delay_split]`, `[host]`,
     * `[host_port]`, `[sni]`, `[ua]`, `[crlf]`) and dispatches each segment across the socket
     * with immediate flush and optional millisecond jitter.
     */
    @Throws(IOException::class)
    fun writePayloadWithMacros(
        outputStream: OutputStream,
        rawPayload: String,
        host: String,
        port: Int,
        sni: String,
        userAgent: String,
        config: TlsFragmentConfig
    ): Int {
        val effectiveHost = sni.ifBlank { host }
        val normalized = rawPayload
            .replace("[host_port]", "$effectiveHost:$port", ignoreCase = true)
            .replace("[host]", effectiveHost, ignoreCase = true)
            .replace("[sni]", effectiveHost, ignoreCase = true)
            .replace("[ua]", userAgent, ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("\\r\\n", "\r\n")
            .replace("[instant_split]", "[split]", ignoreCase = true)
            .replace("[delay_split]", "[split]", ignoreCase = true)

        val finalPayload = if (normalized.endsWith("\r\n\r\n")) {
            normalized
        } else if (normalized.endsWith("\r\n")) {
            "$normalized\r\n"
        } else {
            "$normalized\r\n\r\n"
        }

        val segments = finalPayload.split("[split]", ignoreCase = true).filter { it.isNotEmpty() }
        var totalWritten = 0

        for ((index, seg) in segments.withIndex()) {
            val bytes = seg.toByteArray(Charsets.UTF_8)
            outputStream.write(bytes)
            outputStream.flush()
            totalWritten += bytes.size

            if (index < segments.size - 1 && config.enabled) {
                val delayMs = Random.nextInt(
                    config.normalizedMinJitter(),
                    config.normalizedMaxJitter() + 1
                ).toLong()
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        return totalWritten
    }

    private fun buildSyntheticClientHelloRecord(sniHost: String): ByteArray {
        val sniBytes = sniHost.toByteArray(Charsets.US_ASCII)
        val body = ByteArrayOutputStream(220)
        // Handshake Type: ClientHello (0x01) + 3-byte length placeholder + Version 0x0303 + 32-byte random
        body.write(0x01)
        body.write(byteArrayOf(0x00, 0x00, 0x90.toByte()))
        body.write(byteArrayOf(0x03, 0x03))
        body.write(ByteArray(32) { (it * 7 + 3).toByte() })
        // Session ID (32 bytes) + Cipher Suites (30 bytes)
        body.write(0x20)
        body.write(ByteArray(32) { (it + 1).toByte() })
        body.write(byteArrayOf(0x00, 0x06, 0x13, 0x01, 0x13, 0x02, 0x13, 0x03))
        // Compression methods
        body.write(byteArrayOf(0x01, 0x00))
        // Extensions block containing SNI (0x0000)
        val sniExtLen = sniBytes.size + 5
        val sniListLen = sniBytes.size + 3
        body.write(byteArrayOf(0x00, (sniExtLen + 16).toByte()))
        // Extension: server_name (0x00 0x00)
        body.write(byteArrayOf(0x00, 0x00))
        body.write(byteArrayOf(((sniExtLen ushr 8) and 0xFF).toByte(), (sniExtLen and 0xFF).toByte()))
        body.write(byteArrayOf(((sniListLen ushr 8) and 0xFF).toByte(), (sniListLen and 0xFF).toByte()))
        body.write(0x00) // host_name type
        body.write(byteArrayOf(((sniBytes.size ushr 8) and 0xFF).toByte(), (sniBytes.size and 0xFF).toByte()))
        body.write(sniBytes)
        // Trailing ALPN & Supported Versions extensions
        body.write(byteArrayOf(0x00, 0x10, 0x00, 0x05, 0x00, 0x03, 0x02, 0x68, 0x32))
        body.write(byteArrayOf(0x00, 0x2B, 0x00, 0x03, 0x02, 0x03, 0x04))

        val payloadBytes = body.toByteArray()
        val fullRecord = ByteBuffer.allocate(TLS_HEADER_LEN + payloadBytes.size)
        fullRecord.put(TLS_RECORD_TYPE_HANDSHAKE)
        fullRecord.put(0x03.toByte())
        fullRecord.put(0x01.toByte())
        fullRecord.putShort(payloadBytes.size.toShort())
        fullRecord.put(payloadBytes)
        return fullRecord.array()
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

/**
 * Low-level [Socket] wrapper that hooks `getOutputStream()` during the TLS handshake phase.
 *
 * When Android's `SSLSocket` invokes `startHandshake()` over this wrapped socket, outbound
 * `ClientHello` packets (`0x16`) are intercepted, rewritten into multiple TLS records and/or
 * dispatched in randomized byte chunks with millisecond jitter over `TCP_NODELAY`.
 *
 * Once [markHandshakeCompleted] is called, all subsequent tunnel traffic flows directly
 * through the underlying socket stream with zero overhead.
 */
class DesyncSocketWrapper(
    private val delegate: Socket,
    private val sniHost: String,
    private val config: TlsFragmentConfig,
    private val onStatsRecorded: (FragmentDispatchStats) -> Unit = {}
) : Socket() {

    private val handshakePhase = AtomicBoolean(true)
    private var wrappedOut: FragmentingHandshakeOutputStream? = null

    init {
        try {
            delegate.tcpNoDelay = true
        } catch (_: Exception) {
        }
    }

    fun markHandshakeCompleted() {
        handshakePhase.set(false)
    }

    override fun getInputStream(): InputStream = delegate.getInputStream()

    override fun getOutputStream(): OutputStream {
        val existing = wrappedOut
        if (existing != null) return existing
        val created = FragmentingHandshakeOutputStream(
            rawOut = delegate.getOutputStream(),
            handshakePhase = handshakePhase,
            sniHost = sniHost,
            config = config,
            onStatsRecorded = onStatsRecorded
        )
        wrappedOut = created
        return created
    }

    override fun connect(endpoint: SocketAddress?) = delegate.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    @Synchronized
    override fun close() {
        delegate.close()
    }
}

/**
 * Intercepts outbound TLS Handshake records (`0x16`) during the initial `SSLSocket.startHandshake()`
 * exchange and applies RFC-compliant TLS Record Fragmentation and TCP segment desynchronization.
 */
private class FragmentingHandshakeOutputStream(
    private val rawOut: OutputStream,
    private val handshakePhase: AtomicBoolean,
    private val sniHost: String,
    private val config: TlsFragmentConfig,
    private val onStatsRecorded: (FragmentDispatchStats) -> Unit
) : FilterOutputStream(rawOut) {

    private val clientHelloIntercepted = AtomicBoolean(false)

    override fun write(b: Int) {
        rawOut.write(b)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!config.enabled || !handshakePhase.get() || len <= 5) {
            rawOut.write(b, off, len)
            return
        }

        val slice = b.copyOfRange(off, off + len)
        val isTlsHandshake = slice[0] == 0x16.toByte() && slice[1] == 0x03.toByte()
        val isClientHello = isTlsHandshake && slice.size > 5 && slice[5] == 0x01.toByte()

        if (!isClientHello || !clientHelloIntercepted.compareAndSet(false, true)) {
            rawOut.write(b, off, len)
            return
        }

        val minChunk = config.normalizedMinChunk()
        val maxChunk = config.normalizedMaxChunk()
        val minJitter = config.normalizedMinJitter()
        val maxJitter = config.normalizedMaxJitter()

        // Step 1: Apply TLS Record-Layer Fragmentation if selected
        val (framedBytes, recordCount, sniOffset) = when (config.mode) {
            TlsFragmentMode.TLS_RECORD_AND_TCP,
            TlsFragmentMode.TLS_RECORD_ONLY -> TlsFragmentationEngine.rewriteClientHelloIntoMultipleTlsRecords(
                rawPacket = slice,
                sniHost = sniHost,
                minChunkBytes = minChunk,
                maxChunkBytes = maxChunk,
                surgicalSniOnly = false
            )
            TlsFragmentMode.SNI_BOUNDARY_SPLIT -> TlsFragmentationEngine.rewriteClientHelloIntoMultipleTlsRecords(
                rawPacket = slice,
                sniHost = sniHost,
                minChunkBytes = minChunk,
                maxChunkBytes = maxChunk,
                surgicalSniOnly = true
            )
            TlsFragmentMode.TCP_SEGMENT_ONLY -> Triple(
                slice,
                1,
                TlsFragmentationEngine.locateSniSplitOffset(slice.copyOfRange(5, slice.size), sniHost)
            )
        }

        // Step 2: Dispatch over TCP with optional segment chunking & jitter
        val breakdown = mutableListOf<String>()
        var totalJitter = 0L
        var tcpChunks = 0

        if (config.mode == TlsFragmentMode.TLS_RECORD_ONLY) {
            rawOut.write(framedBytes)
            rawOut.flush()
            tcpChunks = 1
            breakdown.add("Flushed $recordCount TLS 0x16 records (${framedBytes.size}B) in single TCP write")
        } else {
            var cursor = 0
            val activeSplitLimit = (sniOffset + 64).coerceIn(64, framedBytes.size)
            while (cursor < framedBytes.size) {
                val chunkLen = if (cursor < activeSplitLimit) {
                    val step = if (maxChunk > minChunk) Random.nextInt(minChunk, maxChunk + 1) else minChunk
                    step.coerceAtMost(framedBytes.size - cursor)
                } else {
                    framedBytes.size - cursor
                }

                rawOut.write(framedBytes, cursor, chunkLen)
                rawOut.flush()
                tcpChunks++

                val jitter = if (cursor + chunkLen < framedBytes.size && maxJitter > 0) {
                    if (maxJitter > minJitter) Random.nextInt(minJitter, maxJitter + 1).toLong()
                    else minJitter.toLong()
                } else 0L

                if (jitter > 0L) {
                    totalJitter += jitter
                    try {
                        Thread.sleep(jitter)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }

                if (breakdown.size < 6) {
                    breakdown.add("TCP seg #$tcpChunks: ${chunkLen}B (+${jitter}ms)")
                }
                cursor += chunkLen
            }
        }

        onStatsRecorded(
            FragmentDispatchStats(
                originalBytes = slice.size,
                emittedBytes = framedBytes.size,
                tlsRecordCount = recordCount,
                tcpWriteChunks = tcpChunks,
                sniSplitOffset = sniOffset,
                totalJitterDelayMs = totalJitter,
                chunkBreakdown = breakdown
            )
        )
    }
}
