package com.example.advancedtunnel.model

import java.io.Serializable

data class TunnelProfile(
    val ip: String = "",
    val port: String = "443",
    val sni: String = "",
    val payload: String = ""
) : Serializable
