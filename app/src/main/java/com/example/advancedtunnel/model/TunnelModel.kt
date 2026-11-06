package com.example.advancedtunnel.model

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class TunnelProfile(
    val ip: String = "",
    val port: String = "443",
    val sni: String = "",
    val payload: String = ""
)

data class RoutePreset(
    val id: String,
    val label: String,
    val sni: String
)

val ROUTE_PRESETS = listOf(
    RoutePreset("stc", "STC Enterprise Gateway (Saudi Telecom)", "stc.com.sa"),
    RoutePreset("mobily", "Mobily Corporate Gateway (Saudi Arabia)", "mobily.com.sa"),
    RoutePreset("gcp", "Google Cloud Compute Engine (GCP Node)", "cloud.google.com"),
    RoutePreset("hetzner", "Hetzner Cloud VPS (Dedicated Node)", "hetzner.com"),
    RoutePreset("alpha", "Route Preset Alpha (TLS Virtualization)", "alpha.example.com"),
    RoutePreset("beta", "Route Preset Beta (Edge CDN Routing)", "beta.example.com"),
    RoutePreset("gamma", "Route Preset Gamma (Cloud Delivery)", "gamma.example.com"),
    RoutePreset("custom", "Custom Infrastructure Gateway", "")
)

const val DEFAULT_PAYLOAD_TEMPLATE =
    "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]"

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
    RelayServer("gcp-london", "Google Cloud (London - europe-west2)", "europe-west2.gcp.cloud", 443, "TLS", "GCP Compute Engine europe-west2 datacenter"),
    RelayServer("gcp-iowa", "Google Cloud (US Central - us-central1)", "us-central1.gcp.cloud", 443, "TLS", "GCP Compute Engine us-central1 datacenter"),
    RelayServer("gcp-tokyo", "Google Cloud (Asia - asia-northeast1)", "asia-northeast1.gcp.cloud", 443, "WebSocket", "GCP Compute Engine asia-northeast1 datacenter"),
    RelayServer("hetzner-fsn", "Hetzner Cloud (Germany - Falkenstein)", "fsn1.hetzner.com", 443, "TLS", "Hetzner Falkenstein FSN1 datacenter"),
    RelayServer("hetzner-hel", "Hetzner Cloud (Finland - Helsinki)", "hel1.hetzner.com", 443, "WebSocket", "Hetzner Helsinki HEL1 datacenter"),
    RelayServer("hetzner-ash", "Hetzner Cloud (US East - Ashburn)", "ash.hetzner.com", 443, "TLS", "Hetzner Ashburn ASH datacenter"),
    RelayServer("sample-vps-1", "Custom VPS Node", "127.0.0.1", 443, "TLS", "Dedicated Ubuntu 22.04 tunnel node")
)

data class PayloadGeneratorConfig(
    val urlHost: String = "[host]",
    val requestMethod: String = "GET",
    val injectionMode: String = "Normal", // Normal, Front Query, Back Query, SSL SNI, Dual Split
    val enableWebSocket: Boolean = true,
    val enableKeepAlive: Boolean = true,
    val enableUserAgent: Boolean = true,
    val enableXForwardedFor: Boolean = false,
    val enableXOnlineHost: Boolean = false,
    val enableReferer: Boolean = false,
    val customHeaders: String = ""
)

object TunnelUtils {

    fun generatePayload(config: PayloadGeneratorConfig): String {
        val targetHost = config.urlHost.ifBlank { "[host]" }
        val method = config.requestMethod.uppercase()
        val crlf = "[crlf]"
        val builder = StringBuilder()

        when (config.injectionMode) {
            "Front Query" -> {
                builder.append("$method http://$targetHost/ HTTP/1.1$crlf")
                builder.append("Host: $targetHost$crlf")
            }
            "Back Query" -> {
                builder.append("$method /?$targetHost HTTP/1.1$crlf")
                builder.append("Host: $targetHost$crlf")
            }
            "SSL SNI" -> {
                builder.append("$method / HTTP/1.1$crlf")
                builder.append("Host: $targetHost$crlf")
            }
            "Dual Split" -> {
                builder.append("CONNECT [host_port] HTTP/1.1$crlf[split]$method / HTTP/1.1$crlf")
                builder.append("Host: $targetHost$crlf")
            }
            else -> { // Normal
                if (method == "CONNECT") {
                    builder.append("CONNECT [host_port] HTTP/1.1$crlf")
                } else {
                    builder.append("$method / HTTP/1.1$crlf")
                }
                builder.append("Host: $targetHost$crlf")
            }
        }

        if (config.enableWebSocket) {
            builder.append("Upgrade: websocket$crlf")
            builder.append("Connection: Upgrade$crlf")
        } else if (config.enableKeepAlive) {
            builder.append("Connection: Keep-Alive$crlf")
            builder.append("Proxy-Connection: Keep-Alive$crlf")
        }

        if (config.enableUserAgent) {
            builder.append("User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36$crlf")
        }

        if (config.enableXForwardedFor) {
            builder.append("X-Forwarded-For: $targetHost$crlf")
        }

        if (config.enableXOnlineHost) {
            builder.append("X-Online-Host: $targetHost$crlf")
        }

        if (config.enableReferer) {
            builder.append("Referer: https://$targetHost/$crlf")
        }

        if (config.customHeaders.isNotBlank()) {
            config.customHeaders.lineSequence().forEach { line ->
                if (line.isNotBlank()) {
                    val formattedLine = line.replace("\r\n", "").replace("\n", "")
                    builder.append("$formattedLine$crlf")
                }
            }
        }

        return builder.toString()
    }

    fun parsePayload(raw: String, host: String): String {
        val template = if (raw.isBlank()) DEFAULT_PAYLOAD_TEMPLATE else raw
        var out = template
            .replace("[host]", if (host.isBlank()) "[host]" else host, ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("\\r\\n", "\r\n")

        if (out.endsWith("\r\n\r\n")) return out
        out += if (out.endsWith("\r\n")) "\r\n" else "\r\n\r\n"
        return out
    }

    fun visualizePayload(parsed: String): String {
        return parsed.replace("\r\n", "\\r\\n\n")
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
        val json = JSONObject().apply {
            put("ip", p.ip.trim())
            put("port", p.port.trim().toIntOrNull() ?: 443)
            put("sni", p.sni.trim())
            put("payload", p.payload)
        }
        val encoded = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return PROFILE_PREFIX + encoded
    }

    fun importProfile(code: String): TunnelProfile? {
        val trimmed = code.trim()
        if (!trimmed.startsWith(PROFILE_PREFIX)) return null
        return try {
            val b64 = trimmed.substring(PROFILE_PREFIX.length)
            val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val profile = TunnelProfile(
                ip = json.optString("ip", ""),
                port = json.opt("port")?.toString() ?: "443",
                sni = json.optString("sni", ""),
                payload = json.optString("payload", "")
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
