package com.example.advancedtunnel.model

import java.io.Serializable

data class TunnelProfile(
    val ip: String = "",
    val port: String = "443",
    val sni: String = "",
    val payload: String = "",
    val protocol: String = "vless-reality",
    val cdnHost: String = "cdn.mobily.com.sa",
    val wsPath: String = "/cdn-ws",
    val utlsFingerprint: String = "chrome",
    val sniSplitMinBytes: Int = 5,
    val sniSplitMaxBytes: Int = 15
) : Serializable
