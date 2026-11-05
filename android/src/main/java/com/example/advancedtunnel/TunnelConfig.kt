package com.example.advancedtunnel

import java.io.Serializable

/**
 * Tunnel configuration data class
 * Stores all settings for a VPN tunnel connection
 */
data class TunnelConfig(
    val serverIp: String,
    val serverPort: Int,
    val sniHost: String,
    val customPayload: String,
    val protocol: String = "tls",
    val proxyType: String? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val allowInsecure: Boolean = false,
    val ipv6: Boolean = false,
    val mtu: Int = 1500,
    val keepalive: Int = 25
) : Serializable {
    companion object {
        fun createDefault(): TunnelConfig {
            return TunnelConfig(
                serverIp = "",
                serverPort = 443,
                sniHost = "",
                customPayload = "",
                protocol = "tls"
            )
        }
    }

    /**
     * Validate the configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (serverIp.isBlank()) {
            errors.add("Server IP cannot be empty")
        }
        
        if (serverPort !in 1..65535) {
            errors.add("Port must be between 1 and 65535")
        }
        
        if (sniHost.isBlank()) {
            errors.add("SNI host cannot be empty")
        }
        
        if (protocol !in listOf("tls", "websocket", "wss", "http-connect", "socks5")) {
            errors.add("Invalid protocol")
        }
        
        if (proxyType != null && proxyType !in listOf("http", "socks5")) {
            errors.add("Invalid proxy type")
        }
        
        if (proxyType != null && proxyHost.isNullOrBlank()) {
            errors.add("Proxy host is required when proxy is enabled")
        }
        
        if (proxyPort != null && proxyPort !in 1..65535) {
            errors.add("Proxy port must be between 1 and 65535")
        }
        
        if (mtu !in 1280..1500) {
            errors.add("MTU must be between 1280 and 1500")
        }
        
        return errors
    }

    /**
     * Convert to map for intent extras
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            "SERVER_IP" to serverIp,
            "SERVER_PORT" to serverPort,
            "SNI_HOST" to sniHost,
            "PAYLOAD" to customPayload,
            "PROTOCOL" to protocol,
            "PROXY_TYPE" to (proxyType ?: ""),
            "PROXY_HOST" to (proxyHost ?: ""),
            "PROXY_PORT" to (proxyPort ?: 0),
            "ALLOW_INSECURE" to allowInsecure,
            "IPV6" to ipv6,
            "MTU" to mtu,
            "KEEPALIVE" to keepalive
        )
    }
}

/**
 * Payload engine for parsing and generating HTTP payloads
 */
object PayloadEngine {
    fun parse(rawPayload: String, sniHost: String): String {
        if (rawPayload.isBlank()) {
            return "GET / HTTP/1.1\r\nHost: $sniHost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"
        }

        return rawPayload
            .replace("[host]", sniHost)
            .replace("[crlf]", "\r\n")
            .replace("\\r\\n", "\r\n")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
    }
}

/**
 * Configuration storage for tunnel profiles
 */
class ConfigStorage(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("TunnelPrefs", android.content.Context.MODE_PRIVATE)

    fun saveConfig(config: TunnelConfig) {
        prefs.edit().apply {
            putString("SERVER_IP", config.serverIp)
            putInt("SERVER_PORT", config.serverPort)
            putString("SNI_HOST", config.sniHost)
            putString("PAYLOAD", config.customPayload)
            putString("PROTOCOL", config.protocol)
            putString("PROXY_TYPE", config.proxyType)
            putString("PROXY_HOST", config.proxyHost)
            putInt("PROXY_PORT", config.proxyPort ?: 0)
            putBoolean("ALLOW_INSECURE", config.allowInsecure)
            putBoolean("IPV6", config.ipv6)
            putInt("MTU", config.mtu)
            putInt("KEEPALIVE", config.keepalive)
            apply()
        }
    }

    fun loadActiveConfig(): TunnelConfig {
        return TunnelConfig(
            serverIp = prefs.getString("SERVER_IP", "") ?: "",
            serverPort = prefs.getInt("SERVER_PORT", 443),
            sniHost = prefs.getString("SNI_HOST", "") ?: "",
            customPayload = prefs.getString("PAYLOAD", "") ?: "",
            protocol = prefs.getString("PROTOCOL", "tls") ?: "tls",
            proxyType = prefs.getString("PROXY_TYPE", null),
            proxyHost = prefs.getString("PROXY_HOST", null),
            proxyPort = if (prefs.contains("PROXY_PORT")) prefs.getInt("PROXY_PORT", 0) else null,
            allowInsecure = prefs.getBoolean("ALLOW_INSECURE", false),
            ipv6 = prefs.getBoolean("IPV6", false),
            mtu = prefs.getInt("MTU", 1500),
            keepalive = prefs.getInt("KEEPALIVE", 25)
        )
    }
}
