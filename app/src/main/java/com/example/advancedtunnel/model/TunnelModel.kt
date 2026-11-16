package com.example.advancedtunnel.model

import android.util.Base64
import com.example.advancedtunnel.vpn.SingBoxConfigBuilder
import org.json.JSONObject
import java.util.UUID

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class RoutePreset(
    val id: String,
    val label: String,
    val sni: String,
    val protocol: String = "vless-reality",
    val cdnHost: String = "",
    val wsPath: String = "/cdn-ws",
    val utlsFingerprint: String = "chrome",
    val sniSplitMinBytes: Int = 5,
    val sniSplitMaxBytes: Int = 15
)

const val HETZNER_SERVER_IP = "2.29.8.194"

val ROUTE_PRESETS = listOf(
    RoutePreset(
        id = "stc",
        label = "STC KSA (VLESS + Reality · 5-15B SNI Split)",
        sni = "www.stc.com.sa",
        protocol = "vless-reality",
        cdnHost = "",
        wsPath = "",
        utlsFingerprint = "chrome",
        sniSplitMinBytes = 5,
        sniSplitMaxBytes = 15
    ),
    RoutePreset(
        id = "mobily",
        label = "Mobily KSA (WSS + CDN Fronted · Chrome JA4)",
        sni = "www.mobily.com.sa",
        protocol = "vless-ws-tls",
        cdnHost = "cdn.mobily.com.sa",
        wsPath = "/cdn-ws",
        utlsFingerprint = "chrome",
        sniSplitMinBytes = 5,
        sniSplitMaxBytes = 15
    ),
    RoutePreset(
        id = "hetzner",
        label = "Hetzner CX23 Direct (VLESS + Reality 443)",
        sni = "static.194.8.29.2.clients.your-server.de",
        protocol = "vless-reality"
    ),
    RoutePreset(
        id = "gcp",
        label = "Google Cloud Edge (VLESS + Reality 443)",
        sni = "cloud.google.com",
        protocol = "vless-reality"
    ),
    RoutePreset(
        id = "alpha",
        label = "Route Preset Alpha (VLESS + Reality)",
        sni = "alpha.example.com",
        protocol = "vless-reality"
    ),
    RoutePreset(
        id = "beta",
        label = "Route Preset Beta (WSS + CDN Fronted)",
        sni = "beta.example.com",
        protocol = "vless-ws-tls",
        cdnHost = "cdn.beta.example.com",
        wsPath = "/cdn-ws"
    ),
    RoutePreset(
        id = "gamma",
        label = "Route Preset Gamma (Trojan + TLS 8443)",
        sni = "gamma.example.com",
        protocol = "trojan-tls"
    ),
    RoutePreset(
        id = "custom",
        label = "Custom Infrastructure Gateway",
        sni = "",
        protocol = "vless-reality"
    )
)

val DEFAULT_PAYLOAD_TEMPLATE: String = SingBoxConfigBuilder.buildCarrierConfigJson(
    carrierId = "stc",
    serverHost = HETZNER_SERVER_IP,
    serverPort = 443,
    customSni = "www.stc.com.sa"
)

const val PROFILE_PREFIX = "TUNNEL://"

data class SavedProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val presetId: String,
    val profile: TunnelProfile,
    val savedAt: Long = System.currentTimeMillis()
)

data class RelayServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val transport: String,
    val notes: String = ""
)

val SAMPLE_VPS_SERVERS = listOf(
    RelayServer("hetzner-cx23-stc", "Hetzner CX23 STC (VLESS + Reality)", HETZNER_SERVER_IP, 443, "VLESS-Reality", "STC KSA 4G/5G VLESS + XTLS-Vision + Reality over 443 with 5-15B SNI boundary split"),
    RelayServer("hetzner-cx23-mobily", "Hetzner CX23 Mobily (WSS + CDN)", HETZNER_SERVER_IP, 443, "VLESS-WSS", "Mobily KSA WebSocket over TLS 1.3 fronted by Cloudflare/Fastly CDN with uTLS Chrome JA4"),
    RelayServer("hetzner-cx23-wg", "Hetzner CX23 WireGuard VPN (wg0)", HETZNER_SERVER_IP, 51820, "WireGuard", "Hetzner CX23 UDP 51820 WireGuard endpoint (10.66.66.1/24, MTU 1280)"),
    RelayServer("hetzner-cx23-alt", "Hetzner CX23 Trojan TLS (8443)", HETZNER_SERVER_IP, 8443, "Trojan-TLS", "Hetzner CX23 secondary Trojan + TLS 1.3 listener on TCP 8443"),
    RelayServer("gcp-london", "Google Cloud (London - europe-west2)", "europe-west2.gcp.cloud", 443, "VLESS-Reality", "GCP Compute Engine europe-west2 datacenter"),
    RelayServer("hetzner-fsn", "Hetzner Cloud (Germany - Falkenstein)", "fsn1.hetzner.com", 443, "VLESS-Reality", "Hetzner Falkenstein FSN1 datacenter"),
    RelayServer("hetzner-hel", "Hetzner Cloud (Finland - Helsinki)", "hel1.hetzner.com", 443, "VLESS-WSS", "Hetzner Helsinki HEL1 datacenter")
)

/**
 * Carrier Core Outbound Builder configuration that generates a full, validated
 * `sing-box` (`io.nekohasekai.libbox`) JSON configuration instead of legacy HTTP strings.
 */
data class PayloadGeneratorConfig(
    val carrierPreset: String = "stc",
    val protocol: String = "vless-reality",
    val serverHost: String = HETZNER_SERVER_IP,
    val serverPort: Int = 443,
    val sniHost: String = "www.stc.com.sa",
    val cdnHost: String = "cdn.mobily.com.sa",
    val wsPath: String = "/cdn-ws",
    val utlsFingerprint: String = "chrome",
    val enableTlsFragment: Boolean = true,
    val enableRecordFragment: Boolean = true,
    val sniSplitMinBytes: Int = 5,
    val sniSplitMaxBytes: Int = 15
)

object TunnelUtils {

    fun generatePayload(config: PayloadGeneratorConfig): String {
        val proto = when {
            config.carrierPreset.equals("mobily", ignoreCase = true) ||
                config.protocol.equals("vless-ws-tls", ignoreCase = true) ->
                SingBoxConfigBuilder.Protocol.VLESS_WS_TLS
            config.protocol.equals("trojan-tls", ignoreCase = true) ->
                SingBoxConfigBuilder.Protocol.TROJAN_TLS
            else ->
                SingBoxConfigBuilder.Protocol.VLESS_REALITY
        }
        val effectiveSni = config.sniHost.trim().ifBlank {
            if (proto == SingBoxConfigBuilder.Protocol.VLESS_WS_TLS) {
                SingBoxConfigBuilder.DEFAULT_MOBILY_SNI
            } else {
                SingBoxConfigBuilder.DEFAULT_STC_SNI
            }
        }
        val effectiveCdnHost = config.cdnHost.trim().ifBlank { SingBoxConfigBuilder.DEFAULT_MOBILY_CDN_HOST }
        val effectiveWsPath = config.wsPath.trim().ifBlank { SingBoxConfigBuilder.DEFAULT_MOBILY_WS_PATH }

        return SingBoxConfigBuilder.buildClientConfigJson(
            SingBoxConfigBuilder.ClientConfig(
                protocol = proto,
                serverHost = config.serverHost.trim().ifBlank { HETZNER_SERVER_IP },
                serverPort = config.serverPort.coerceIn(1, 65535),
                sniHost = effectiveSni,
                wsHost = effectiveCdnHost,
                wsPath = effectiveWsPath,
                utlsFingerprint = config.utlsFingerprint.ifBlank { "chrome" },
                tlsFragmentEnabled = config.enableTlsFragment,
                tlsRecordFragmentEnabled = config.enableRecordFragment,
                sniSplitMinBytes = config.sniSplitMinBytes.coerceIn(5, 15),
                sniSplitMaxBytes = config.sniSplitMaxBytes.coerceIn(5, 15),
                mtu = 1280
            )
        )
    }

    /**
     * Resolves the outbound `sing-box` JSON configuration for the given profile & SNI host,
     * replacing any `"server_name"` placeholder or generating a valid `sing-box` JSON config when blank.
     */
    fun parsePayload(raw: String, host: String): String {
        val cleanHost = host.trim().ifBlank { SingBoxConfigBuilder.DEFAULT_STC_SNI }
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val root = JSONObject(trimmed)
                val outbounds = root.optJSONArray("outbounds")
                if (outbounds != null) {
                    for (i in 0 until outbounds.length()) {
                        val out = outbounds.optJSONObject(i) ?: continue
                        val tls = out.optJSONObject("tls") ?: continue
                        if (host.isNotBlank()) {
                            tls.put("server_name", cleanHost)
                        }
                    }
                }
                root.toString(2)
            } catch (_: Exception) {
                SingBoxConfigBuilder.buildCarrierConfigJson(
                    carrierId = "stc",
                    serverHost = HETZNER_SERVER_IP,
                    serverPort = 443,
                    customSni = cleanHost
                )
            }
        }
        return SingBoxConfigBuilder.buildCarrierConfigJson(
            carrierId = if (cleanHost.contains("mobily", ignoreCase = true)) "mobily" else "stc",
            serverHost = HETZNER_SERVER_IP,
            serverPort = 443,
            customSni = cleanHost
        )
    }

    fun visualizePayload(parsed: String): String {
        return parsed.trim()
    }

    fun validateProfile(p: TunnelProfile): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        val ip = p.ip.trim()
        val port = p.port.trim()
        val sni = p.sni.trim()

        if (ip.isEmpty()) {
            errors["ip"] = "Server IP or domain is required"
        }

        if (port.isEmpty()) {
            errors["port"] = "Port is required"
        } else {
            val pNum = port.toIntOrNull()
            if (pNum == null || pNum !in 1..65535) {
                errors["port"] = "Enter a valid port (1-65535)"
            }
        }

        if (sni.isEmpty()) {
            errors["sni"] = "Server name (SNI) is required"
        }

        return errors
    }

    fun exportProfile(p: TunnelProfile): String {
        val obj = JSONObject().apply {
            put("ip", p.ip.trim())
            put("port", p.port.trim())
            put("sni", p.sni.trim())
            put("payload", p.payload)
            put("protocol", p.protocol)
            put("cdnHost", p.cdnHost)
            put("wsPath", p.wsPath)
            put("utlsFingerprint", p.utlsFingerprint)
            put("sniSplitMinBytes", p.sniSplitMinBytes)
            put("sniSplitMaxBytes", p.sniSplitMaxBytes)
        }
        val jsonBytes = obj.toString().toByteArray(Charsets.UTF_8)
        val encoded = try {
            java.util.Base64.getEncoder().encodeToString(jsonBytes)
        } catch (_: Throwable) {
            Base64.encodeToString(jsonBytes, Base64.NO_WRAP)
        }
        return PROFILE_PREFIX + encoded
    }

    fun importProfile(code: String): TunnelProfile? {
        val trimmed = code.trim()
        if (!trimmed.startsWith(PROFILE_PREFIX)) return null
        return try {
            val b64 = trimmed.substring(PROFILE_PREFIX.length)
            val jsonBytes = try {
                java.util.Base64.getDecoder().decode(b64)
            } catch (_: Throwable) {
                Base64.decode(b64, Base64.DEFAULT)
            }
            val jsonStr = String(jsonBytes, Charsets.UTF_8)
            val obj = JSONObject(jsonStr)
            val ip = obj.optString("ip", "")
            val port = obj.optString("port", "443").ifBlank { "443" }
            val sni = obj.optString("sni", "")
            val protocol = obj.optString("protocol", "vless-reality").ifBlank { "vless-reality" }
            val cdnHost = obj.optString("cdnHost", "cdn.mobily.com.sa")
            val wsPath = obj.optString("wsPath", "/cdn-ws")
            val utlsFingerprint = obj.optString("utlsFingerprint", "chrome")
            val sniSplitMin = obj.optInt("sniSplitMinBytes", 5).coerceIn(5, 15)
            val sniSplitMax = obj.optInt("sniSplitMaxBytes", 15).coerceIn(sniSplitMin, 15)
            val rawPayload = obj.optString("payload", "")

            val profile = TunnelProfile(
                ip = ip,
                port = port,
                sni = sni,
                payload = rawPayload,
                protocol = protocol,
                cdnHost = cdnHost,
                wsPath = wsPath,
                utlsFingerprint = utlsFingerprint,
                sniSplitMinBytes = sniSplitMin,
                sniSplitMaxBytes = sniSplitMax
            )
            if (validateProfile(profile).isEmpty()) profile else null
        } catch (_: Exception) {
            null
        }
    }

    fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.2f MB/s", bytesPerSec / 1024.0 / 1024.0)
            else -> String.format("%.2f KB/s", bytesPerSec / 1024.0)
        }
    }

    fun formatBytes(bytes: Double): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
            else -> String.format("%.1f KB", bytes / 1024.0)
        }
    }

    fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
