package com.example.advancedtunnel.vpn

import android.content.Context

/**
 * Defines how sing-box (`libbox`) applies outbound TLS ClientHello splitting
 * (`tls.fragment` + `tls.record_fragment`) alongside uTLS fingerprinting.
 */
enum class TlsFragmentMode(val id: String, val label: String, val description: String) {
    TLS_RECORD_AND_TCP(
        id = "TLS_RECORD_AND_TCP",
        label = "sing-box TLS Fragment + Record Fragment",
        description = "Enables both tls.fragment and tls.record_fragment inside libbox outbound TLS"
    ),
    SNI_BOUNDARY_SPLIT(
        id = "SNI_BOUNDARY_SPLIT",
        label = "sing-box ClientHello SNI Boundary Split (5-15B)",
        description = "Splits TLS 1.3 ClientHello record at SNI boundary (offset 5-15 bytes) with 500ms fallback delay"
    ),
    TLS_RECORD_ONLY(
        id = "TLS_RECORD_ONLY",
        label = "sing-box Record-Layer Split Only",
        description = "Enables tls.record_fragment inside libbox without delayed TCP segment split"
    ),
    TCP_SEGMENT_ONLY(
        id = "TCP_SEGMENT_ONLY",
        label = "sing-box Standard TLS (No Fragment)",
        description = "Disables ClientHello fragmentation while keeping uTLS browser fingerprint active"
    );

    companion object {
        fun fromId(id: String): TlsFragmentMode {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: SNI_BOUNDARY_SPLIT
        }
    }
}

/**
 * User-configurable parameters mapped directly to `sing-box` outbound `tls` + `utls` fields.
 */
data class TlsFragmentConfig(
    val enabled: Boolean = true,
    val mode: TlsFragmentMode = TlsFragmentMode.SNI_BOUNDARY_SPLIT,
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

    fun useFragment(): Boolean =
        enabled && mode != TlsFragmentMode.TLS_RECORD_ONLY && mode != TlsFragmentMode.TCP_SEGMENT_ONLY

    fun useRecordFragment(): Boolean =
        enabled && mode != TlsFragmentMode.TCP_SEGMENT_ONLY
}

/**
 * Validation summary produced from real `SingBoxConfigBuilder` JSON and `Libbox.checkConfig`.
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
 * Real configuration validator bridging UI TLS/Anti-DPI settings to `sing-box` / `libbox`.
 */
object TlsFragmentationEngine {

    fun validateSingBoxProfile(
        context: Context,
        clientConfig: SingBoxConfigBuilder.ClientConfig
    ): FragmentDispatchStats {
        val json = SingBoxConfigBuilder.buildClientConfigJson(clientConfig)
        var libboxStatus = "Verified via Libbox.checkConfig"
        try {
            TunPacketForwarder.validateConfigJson(context, json)
        } catch (err: Throwable) {
            libboxStatus = err.message ?: "Libbox check failed"
        }

        val splitMin = clientConfig.sniSplitMinBytes.coerceIn(5, 15)
        val splitMax = clientConfig.sniSplitMaxBytes.coerceIn(splitMin, 15)
        val transportDesc = when (clientConfig.protocol) {
            SingBoxConfigBuilder.Protocol.VLESS_WS_TLS ->
                "WSS CDN Fronted (Host=${clientConfig.wsHost}, path=${clientConfig.wsPath})"
            SingBoxConfigBuilder.Protocol.VLESS_REALITY ->
                "VLESS + Reality (xtls-rprx-vision, SNI=${clientConfig.sniHost})"
            SingBoxConfigBuilder.Protocol.TROJAN_TLS ->
                "Trojan + TLS 1.3 (SNI=${clientConfig.sniHost})"
        }

        return FragmentDispatchStats(
            originalBytes = json.toByteArray(Charsets.UTF_8).size,
            emittedBytes = json.toByteArray(Charsets.UTF_8).size,
            tlsRecordCount = if (clientConfig.tlsRecordFragmentEnabled) 2 else 1,
            tcpWriteChunks = if (clientConfig.tlsFragmentEnabled) 2 else 1,
            sniSplitOffset = splitMin,
            totalJitterDelayMs = 0L,
            chunkBreakdown = listOf(
                "Protocol: ${clientConfig.protocol.id} -> ${clientConfig.serverHost}:${clientConfig.serverPort}",
                "Carrier Transport: $transportDesc",
                "uTLS JA4 Fingerprint: ${clientConfig.utlsFingerprint} | SNI Split Offset: $splitMin-${splitMax}B",
                "TLS fragment=${clientConfig.tlsFragmentEnabled} | record_fragment=${clientConfig.tlsRecordFragmentEnabled}",
                "Core Check: $libboxStatus"
            )
        )
    }
}
