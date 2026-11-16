package com.example.advancedtunnel.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates validated sing-box (`io.nekohasekai.libbox`) client JSON configurations using:
 * - gVisor TUN inbound (`172.19.0.1/30`, MTU 1280–1500, auto_route, strict_route, sniff)
 * - STC Carrier Profile: VLESS + XTLS-Vision + Reality over port 443 with configurable SNI
 *   (`www.stc.com.sa`) and ClientHello fragmentation at the SNI boundary (offset 5–15 bytes).
 * - Mobily Carrier Profile: VLESS over WebSocket + TLS 1.3 (`vless-ws-tls`) fronted by CDN
 *   (Cloudflare/Fastly) on port 443 with explicit custom `Host` header, WebSocket path, and
 *   uTLS Chrome JA4 fingerprint (`chrome`).
 * - Trojan + TLS 1.3 fallback on port 8443.
 */
object SingBoxConfigBuilder {

    const val DEFAULT_SERVER_HOST = "2.29.8.194"
    const val DEFAULT_VLESS_PORT = 443
    const val DEFAULT_TROJAN_PORT = 8443
    const val DEFAULT_REALITY_SNI = "www.microsoft.com"
    const val DEFAULT_UUID = "2df2fc0f-f185-47ad-bc88-d2182c52d1b0"
    const val DEFAULT_REALITY_PUBLIC_KEY = "2I0TJ_Jp7TufY2ZyYWW97BRuHoCAiKrUDWjRGXvC8Es"
    const val DEFAULT_REALITY_SHORT_ID = "2f09854ba76a8389"
    const val DEFAULT_STC_SNI = "www.stc.com.sa"
    const val DEFAULT_MOBILY_SNI = "www.mobily.com.sa"
    const val DEFAULT_MOBILY_CDN_HOST = "cdn.mobily.com.sa"
    const val DEFAULT_MOBILY_WS_PATH = "/cdn-ws"
    const val DEFAULT_TUN_CIDR = "172.19.0.1/30"
    const val DEFAULT_TUN_MTU = 1500
    const val DEFAULT_DNS_SERVER = "1.1.1.1"

    enum class Protocol(val id: String) {
        VLESS_REALITY("vless-reality"),
        VLESS_WS_TLS("vless-ws-tls"),
        TROJAN_TLS("trojan-tls");

        companion object {
            fun fromId(raw: String?): Protocol =
                entries.find { it.id.equals(raw?.trim(), ignoreCase = true) } ?: VLESS_REALITY
        }
    }

    data class ClientConfig(
        val protocol: Protocol = Protocol.VLESS_REALITY,
        val serverHost: String = DEFAULT_SERVER_HOST,
        val serverPort: Int = DEFAULT_VLESS_PORT,
        val sniHost: String = DEFAULT_REALITY_SNI,
        val uuid: String = DEFAULT_UUID,
        val realityPublicKey: String = DEFAULT_REALITY_PUBLIC_KEY,
        val realityShortId: String = DEFAULT_REALITY_SHORT_ID,
        val trojanPassword: String = "",
        val wsHost: String = DEFAULT_MOBILY_CDN_HOST,
        val wsPath: String = DEFAULT_MOBILY_WS_PATH,
        val utlsFingerprint: String = "chrome",
        val tlsFragmentEnabled: Boolean = true,
        val tlsRecordFragmentEnabled: Boolean = true,
        val sniSplitMinBytes: Int = 5,
        val sniSplitMaxBytes: Int = 15,
        val fragmentFallbackDelay: String = "500ms",
        val tunAddressCidr: String = DEFAULT_TUN_CIDR,
        val mtu: Int = DEFAULT_TUN_MTU,
        val strictRoute: Boolean = true,
        val dnsServer: String = DEFAULT_DNS_SERVER,
        val excludePackages: List<String> = emptyList()
    )

    fun mapFingerprintProfileToUtls(profileId: String?): String {
        return when (profileId?.trim()?.lowercase()) {
            "safari_17_ios", "ios" -> "ios"
            "firefox_130_android", "firefox" -> "firefox"
            "safari" -> "safari"
            "randomized_rotation", "randomized" -> "randomized"
            else -> "chrome"
        }
    }

    /**
     * Builds a real carrier-specific `ClientConfig` mapped directly to the native `sing-box` engine:
     * - `"stc"` / `"stc-ksa-5g-cgnat"`: VLESS + Reality over port 443, SNI `www.stc.com.sa`,
     *   TLS 1.3 handshake, and ClientHello SNI boundary fragmentation (offset 5–15 bytes).
     * - `"mobily"` / `"mobily-ksa-lte-bypass"`: VLESS + WebSocket over TLS (WSS) fronted by CDN
     *   over port 443, explicit CDN `Host` header (`cdn.mobily.com.sa`), path `/cdn-ws`, and
     *   uTLS Chrome JA4 profile (`chrome`).
     */
    fun buildCarrierProfileConfig(
        carrierId: String,
        serverHost: String = DEFAULT_SERVER_HOST,
        serverPort: Int = DEFAULT_VLESS_PORT,
        customSni: String? = null,
        customCdnHost: String? = null,
        customWsPath: String? = null,
        uuid: String = DEFAULT_UUID,
        realityPublicKey: String = DEFAULT_REALITY_PUBLIC_KEY,
        realityShortId: String = DEFAULT_REALITY_SHORT_ID,
        trojanPassword: String = "",
        excludePackages: List<String> = emptyList()
    ): ClientConfig {
        val normalized = carrierId.trim().lowercase()
        val host = serverHost.trim().ifEmpty { DEFAULT_SERVER_HOST }
        return when {
            normalized.contains("stc") -> {
                val sni = customSni?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_STC_SNI
                ClientConfig(
                    protocol = Protocol.VLESS_REALITY,
                    serverHost = host,
                    serverPort = if (serverPort in 1..65535) serverPort else DEFAULT_VLESS_PORT,
                    sniHost = sni,
                    uuid = uuid,
                    realityPublicKey = realityPublicKey,
                    realityShortId = realityShortId,
                    utlsFingerprint = "chrome",
                    tlsFragmentEnabled = true,
                    tlsRecordFragmentEnabled = true,
                    sniSplitMinBytes = 5,
                    sniSplitMaxBytes = 15,
                    fragmentFallbackDelay = "500ms",
                    mtu = 1280,
                    strictRoute = true,
                    excludePackages = excludePackages
                )
            }
            normalized.contains("mobily") -> {
                val sni = customSni?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MOBILY_SNI
                val cdnHost = customCdnHost?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MOBILY_CDN_HOST
                val wsPath = customWsPath?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MOBILY_WS_PATH
                ClientConfig(
                    protocol = Protocol.VLESS_WS_TLS,
                    serverHost = host,
                    serverPort = if (serverPort in 1..65535) serverPort else DEFAULT_VLESS_PORT,
                    sniHost = sni,
                    uuid = uuid,
                    wsHost = cdnHost,
                    wsPath = if (wsPath.startsWith("/")) wsPath else "/$wsPath",
                    utlsFingerprint = "chrome",
                    tlsFragmentEnabled = true,
                    tlsRecordFragmentEnabled = true,
                    sniSplitMinBytes = 5,
                    sniSplitMaxBytes = 15,
                    fragmentFallbackDelay = "500ms",
                    mtu = 1280,
                    strictRoute = true,
                    excludePackages = excludePackages
                )
            }
            normalized.contains("8443") || normalized.contains("trojan") -> {
                val sni = customSni?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "static.194.8.29.2.clients.your-server.de"
                ClientConfig(
                    protocol = Protocol.TROJAN_TLS,
                    serverHost = host,
                    serverPort = if (serverPort == 443) DEFAULT_TROJAN_PORT else serverPort,
                    sniHost = sni,
                    trojanPassword = trojanPassword,
                    utlsFingerprint = "chrome",
                    tlsFragmentEnabled = true,
                    tlsRecordFragmentEnabled = true,
                    sniSplitMinBytes = 5,
                    sniSplitMaxBytes = 15,
                    mtu = 1280,
                    strictRoute = true,
                    excludePackages = excludePackages
                )
            }
            else -> {
                val sni = customSni?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REALITY_SNI
                ClientConfig(
                    protocol = Protocol.VLESS_REALITY,
                    serverHost = host,
                    serverPort = if (serverPort in 1..65535) serverPort else DEFAULT_VLESS_PORT,
                    sniHost = sni,
                    uuid = uuid,
                    realityPublicKey = realityPublicKey,
                    realityShortId = realityShortId,
                    utlsFingerprint = "chrome",
                    tlsFragmentEnabled = true,
                    tlsRecordFragmentEnabled = true,
                    sniSplitMinBytes = 5,
                    sniSplitMaxBytes = 15,
                    mtu = DEFAULT_TUN_MTU,
                    strictRoute = true,
                    excludePackages = excludePackages
                )
            }
        }
    }

    fun buildCarrierConfigJson(
        carrierId: String,
        serverHost: String = DEFAULT_SERVER_HOST,
        serverPort: Int = DEFAULT_VLESS_PORT,
        customSni: String? = null,
        customCdnHost: String? = null,
        customWsPath: String? = null
    ): String {
        return buildClientConfigJson(
            buildCarrierProfileConfig(
                carrierId = carrierId,
                serverHost = serverHost,
                serverPort = serverPort,
                customSni = customSni,
                customCdnHost = customCdnHost,
                customWsPath = customWsPath
            )
        )
    }

    /**
     * Parses a full `sing-box` JSON configuration string (if present in `TunnelProfile.payload`)
     * back into a `ClientConfig` so edits or carrier presets in `payload` drive the core engine directly.
     */
    fun parseClientConfigFromJson(
        rawJson: String,
        fallbackHost: String = DEFAULT_SERVER_HOST,
        fallbackPort: Int = DEFAULT_VLESS_PORT,
        fallbackSni: String = DEFAULT_REALITY_SNI,
        excludePackages: List<String> = emptyList()
    ): ClientConfig? {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val root = JSONObject(trimmed)
            val inbounds = root.optJSONArray("inbounds")
            var mtu = DEFAULT_TUN_MTU
            var tunCidr = DEFAULT_TUN_CIDR
            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inb = inbounds.optJSONObject(i) ?: continue
                    if (inb.optString("type") == "tun") {
                        mtu = inb.optInt("mtu", DEFAULT_TUN_MTU).coerceIn(1280, 1500)
                        val addrArr = inb.optJSONArray("address")
                        if (addrArr != null && addrArr.length() > 0) {
                            tunCidr = addrArr.optString(0, DEFAULT_TUN_CIDR)
                        }
                    }
                }
            }

            val outbounds = root.optJSONArray("outbounds") ?: return null
            var proxyObj: JSONObject? = null
            for (i in 0 until outbounds.length()) {
                val candidate = outbounds.optJSONObject(i) ?: continue
                if (candidate.optString("tag") == "proxy" ||
                    candidate.optString("type") == "vless" ||
                    candidate.optString("type") == "trojan"
                ) {
                    proxyObj = candidate
                    break
                }
            }
            val proxy = proxyObj ?: return null
            val outType = proxy.optString("type", "vless")
            val server = proxy.optString("server", fallbackHost).ifBlank { fallbackHost }
            val port = proxy.optInt("server_port", fallbackPort).coerceIn(1, 65535)
            val tls = proxy.optJSONObject("tls")
            val sni = tls?.optString("server_name", fallbackSni)?.ifBlank { fallbackSni } ?: fallbackSni
            val fragment = tls?.optBoolean("fragment", true) ?: true
            val recordFragment = tls?.optBoolean("record_fragment", true) ?: true
            val fallbackDelay = tls?.optString("fragment_fallback_delay", "500ms") ?: "500ms"
            val utlsFp = tls?.optJSONObject("utls")?.optString("fingerprint", "chrome") ?: "chrome"
            val realityObj = tls?.optJSONObject("reality")
            val transportObj = proxy.optJSONObject("transport")

            val protocol = when {
                outType == "trojan" -> Protocol.TROJAN_TLS
                transportObj?.optString("type") == "ws" -> Protocol.VLESS_WS_TLS
                realityObj?.optBoolean("enabled", false) == true -> Protocol.VLESS_REALITY
                else -> Protocol.VLESS_REALITY
            }

            val wsHeaders = transportObj?.optJSONObject("headers")
            val wsHost = wsHeaders?.optString("Host", DEFAULT_MOBILY_CDN_HOST)
                ?.ifBlank { DEFAULT_MOBILY_CDN_HOST }
                ?: DEFAULT_MOBILY_CDN_HOST
            val wsPath = transportObj?.optString("path", DEFAULT_MOBILY_WS_PATH)
                ?.ifBlank { DEFAULT_MOBILY_WS_PATH }
                ?: DEFAULT_MOBILY_WS_PATH

            ClientConfig(
                protocol = protocol,
                serverHost = server,
                serverPort = port,
                sniHost = sni,
                uuid = proxy.optString("uuid", ""),
                realityPublicKey = realityObj?.optString("public_key", "") ?: "",
                realityShortId = realityObj?.optString("short_id", "") ?: "",
                trojanPassword = proxy.optString("password", ""),
                wsHost = wsHost,
                wsPath = wsPath,
                utlsFingerprint = utlsFp,
                tlsFragmentEnabled = fragment,
                tlsRecordFragmentEnabled = recordFragment,
                fragmentFallbackDelay = fallbackDelay,
                tunAddressCidr = tunCidr,
                mtu = mtu,
                strictRoute = true,
                excludePackages = excludePackages
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildClientConfigJson(config: ClientConfig): String {
        val effectiveHost = config.serverHost.trim().ifEmpty { DEFAULT_SERVER_HOST }
        val effectiveSni = config.sniHost.trim().ifEmpty {
            when (config.protocol) {
                Protocol.VLESS_WS_TLS -> DEFAULT_MOBILY_SNI
                Protocol.VLESS_REALITY -> DEFAULT_REALITY_SNI
                Protocol.TROJAN_TLS -> "static.194.8.29.2.clients.your-server.de"
            }
        }
        val effectivePort = config.serverPort.coerceIn(1, 65535)
        val effectiveMtu = config.mtu.coerceIn(1280, 1500)
        val effectiveUtls = mapFingerprintProfileToUtls(config.utlsFingerprint)
        val effectiveUuid = config.uuid.trim().ifEmpty { DEFAULT_UUID }

        val tlsObject = JSONObject().apply {
            put("enabled", true)
            put("server_name", effectiveSni)
            put("fragment", config.tlsFragmentEnabled)
            put("fragment_fallback_delay", config.fragmentFallbackDelay)
            put("record_fragment", config.tlsRecordFragmentEnabled)
            put(
                "utls",
                JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", effectiveUtls)
                }
            )
            when (config.protocol) {
                Protocol.VLESS_REALITY -> {
                    put(
                        "reality",
                        JSONObject().apply {
                            put("enabled", true)
                            put("public_key", config.realityPublicKey.trim().ifEmpty { DEFAULT_REALITY_PUBLIC_KEY })
                            put("short_id", config.realityShortId.trim().ifEmpty { DEFAULT_REALITY_SHORT_ID })
                        }
                    )
                }
                Protocol.VLESS_WS_TLS -> {
                    put("alpn", JSONArray().apply { put("http/1.1") })
                }
                Protocol.TROJAN_TLS -> {
                    put("alpn", JSONArray().apply { put("h2"); put("http/1.1") })
                }
            }
        }

        val proxyOutbound = when (config.protocol) {
            Protocol.VLESS_REALITY -> {
                JSONObject().apply {
                    put("type", "vless")
                    put("tag", "proxy")
                    put("server", effectiveHost)
                    put("server_port", effectivePort)
                    put("uuid", effectiveUuid)
                    put("flow", "xtls-rprx-vision")
                    put("packet_encoding", "xudp")
                    put("tls", tlsObject)
                }
            }
            Protocol.VLESS_WS_TLS -> {
                val cdnHost = config.wsHost.trim().ifEmpty { effectiveSni }
                val cleanPath = config.wsPath.trim().ifEmpty { DEFAULT_MOBILY_WS_PATH }.let {
                    if (it.startsWith("/")) it else "/$it"
                }
                JSONObject().apply {
                    put("type", "vless")
                    put("tag", "proxy")
                    put("server", effectiveHost)
                    put("server_port", effectivePort)
                    put("uuid", effectiveUuid)
                    put("packet_encoding", "xudp")
                    put("tls", tlsObject)
                    put(
                        "transport",
                        JSONObject().apply {
                            put("type", "ws")
                            put("path", cleanPath)
                            put(
                                "headers",
                                JSONObject().apply {
                                    put("Host", cdnHost)
                                }
                            )
                            put("max_early_data", 2048)
                            put("early_data_header_name", "Sec-WebSocket-Protocol")
                        }
                    )
                }
            }
            Protocol.TROJAN_TLS -> {
                JSONObject().apply {
                    put("type", "trojan")
                    put("tag", "proxy")
                    put("server", effectiveHost)
                    put("server_port", effectivePort)
                    put("password", config.trojanPassword.ifEmpty { "<TROJAN_PASSWORD>" })
                    put("tls", tlsObject)
                }
            }
        }

        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("address", JSONArray().apply { put(config.tunAddressCidr) })
            put("mtu", effectiveMtu)
            put("auto_route", true)
            put("strict_route", config.strictRoute)
            put("stack", "gvisor")
            put("sniff", true)
            if (config.excludePackages.isNotEmpty()) {
                put("exclude_package", JSONArray(config.excludePackages))
            }
        }

        val dnsConfig = JSONObject().apply {
            put(
                "servers",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("tag", "remote-dns")
                            put("type", "udp")
                            put("server", config.dnsServer)
                            put("detour", "proxy")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("tag", "local-dns")
                            put("type", "local")
                        }
                    )
                }
            )
            put("final", "remote-dns")
        }

        val routeConfig = JSONObject().apply {
            put(
                "rules",
                JSONArray().apply {
                    put(JSONObject().apply { put("action", "sniff") })
                    put(
                        JSONObject().apply {
                            put("protocol", "dns")
                            put("action", "hijack-dns")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("ip_is_private", true)
                            put("outbound", "direct")
                        }
                    )
                }
            )
            put("final", "proxy")
            put("auto_detect_interface", true)
            put("default_domain_resolver", "local-dns")
        }

        val root = JSONObject().apply {
            put(
                "log",
                JSONObject().apply {
                    put("disabled", false)
                    put("level", "info")
                    put("timestamp", true)
                }
            )
            put("dns", dnsConfig)
            put("inbounds", JSONArray().apply { put(tunInbound) })
            put(
                "outbounds",
                JSONArray().apply {
                    put(proxyOutbound)
                    put(
                        JSONObject().apply {
                            put("type", "direct")
                            put("tag", "direct")
                        }
                    )
                }
            )
            put("route", routeConfig)
        }

        return root.toString(2)
    }
}
