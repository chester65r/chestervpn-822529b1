package com.chester65r.carrierresearch

import java.net.InetAddress
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class EndpointProbeResult(
    val successful: Boolean,
    val host: String,
    val dnsRecordCount: Int,
    val httpStatus: Int?,
    val elapsedMillis: Long,
    val detail: String,
)

internal fun validateHealthEndpoint(raw: String): URI {
    require(raw.length in 1..2048) { "Enter one HTTPS health-check URL." }

    val uri = URI(raw.trim())
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "Only HTTPS endpoints are accepted."
    }
    require(!uri.host.isNullOrBlank()) { "The endpoint must contain a DNS hostname." }
    require(uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) {
        "Credentials, fragments, and query strings are not accepted."
    }
    require(uri.port == -1 || uri.port == 443) { "Only the standard HTTPS port is accepted." }
    require(uri.rawPath == "/healthz") { "The path must be exactly /healthz." }

    val host = uri.host.lowercase()
    require(host.contains(".") && !host.endsWith(".local") && !host.endsWith(".internal")) {
        "Use the public DNS name of your authorized test server."
    }
    require(host != "localhost" && !host.matches(Regex("^[0-9.]+$")) && !host.contains(":")) {
        "IP literals and local hostnames are not accepted."
    }

    return uri
}

/**
 * Performs one read-only HTTPS GET against a user-owned /healthz endpoint.
 * It never follows redirects, sends credentials, or inspects response bodies.
 */
fun probeAuthorizedHealthEndpoint(raw: String): EndpointProbeResult {
    val uri = validateHealthEndpoint(raw)
    val started = System.nanoTime()

    val addresses = InetAddress.getAllByName(uri.host)
    require(addresses.isNotEmpty() && addresses.size <= 8) {
        "DNS returned an unsupported number of records."
    }
    require(addresses.all { isPublicAddress(it) }) {
        "The endpoint must resolve only to public addresses."
    }

    val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    connection.requestMethod = "GET"
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.setRequestProperty("Cache-Control", "no-store")
    connection.setRequestProperty("Accept", "application/health+json")

    return try {
        val status = connection.responseCode
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val successful = status in 200..299
        EndpointProbeResult(
            successful = successful,
            host = uri.host,
            dnsRecordCount = addresses.size,
            httpStatus = status,
            elapsedMillis = elapsed,
            detail = if (successful) {
                "TLS connection and HTTPS health check succeeded."
            } else {
                "TLS completed, but the endpoint returned a non-success status."
            },
        )
    } finally {
        connection.disconnect()
    }
}

private fun isPublicAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress &&
        !address.isMulticastAddress
