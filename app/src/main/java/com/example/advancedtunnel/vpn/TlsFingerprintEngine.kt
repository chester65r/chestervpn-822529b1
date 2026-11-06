package com.example.advancedtunnel.vpn

import android.os.Build
import java.security.MessageDigest
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import kotlin.random.Random

/**
 * Represents a uTLS / JA3 / JA4 browser TLS ClientHello profile.
 */
data class BrowserTlsProfile(
    val id: String,
    val displayName: String,
    val browserEngine: String,
    val tlsVersionCode: Int, // 771 = TLS 1.2 record version with TLS 1.3 supported_versions ext
    val tlsVersions: List<String>,
    val preferredCipherNames: List<String>,
    val ja3CipherIds: List<Int>,
    val ja3ExtensionIds: List<Int>,
    val ja3EllipticCurves: List<Int>,
    val ja3PointFormats: List<Int>,
    val alpnProtocols: List<String>,
    val userAgent: String
)

/**
 * Computed JA3 + JA4 fingerprint metadata for telemetry, verification, and UI inspection.
 */
data class ComputedTlsFingerprint(
    val profileId: String,
    val profileName: String,
    val ja3RawString: String,
    val ja3HashMd5: String,
    val ja4String: String,
    val negotiatedAlpn: String,
    val activeCipherSuitesCount: Int,
    val sniConfigured: Boolean
)

/**
 * Engine for configuring outbound `SSLSocket` handshakes to emulate authentic mobile/desktop
 * browser TLS signatures (JA3 & JA4) and prevent behavioral TLS fingerprinting by network middleboxes.
 */
object TlsFingerprintEngine {

    val PROFILES: List<BrowserTlsProfile> = listOf(
        BrowserTlsProfile(
            id = "chrome_128_android",
            displayName = "Chrome 128 Mobile (Android 14 / BoringSSL)",
            browserEngine = "BoringSSL / Chromium 128",
            tlsVersionCode = 771,
            tlsVersions = listOf("TLSv1.3", "TLSv1.2"),
            preferredCipherNames = listOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"
            ),
            ja3CipherIds = listOf(
                4865, 4866, 4867, 49195, 49199, 49196, 49200, 52393, 52392, 49171, 49172, 156, 157, 47, 53
            ),
            ja3ExtensionIds = listOf(
                0, 23, 65281, 10, 11, 35, 16, 5, 13, 18, 51, 45, 43, 27, 17513, 21
            ),
            ja3EllipticCurves = listOf(29, 23, 24), // X25519 (29), secp256r1 (23), secp384r1 (24)
            ja3PointFormats = listOf(0),
            alpnProtocols = listOf("h2", "http/1.1"),
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.127 Mobile Safari/537.36"
        ),
        BrowserTlsProfile(
            id = "firefox_130_android",
            displayName = "Firefox 130 Mobile (Gecko / NSS 3.103)",
            browserEngine = "Mozilla NSS 3.103",
            tlsVersionCode = 771,
            tlsVersions = listOf("TLSv1.3", "TLSv1.2"),
            preferredCipherNames = listOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            ),
            ja3CipherIds = listOf(
                4865, 4867, 4866, 49195, 49199, 52393, 52392, 49196, 49200, 49162, 49161, 49171, 49172
            ),
            ja3ExtensionIds = listOf(
                0, 23, 65281, 10, 11, 35, 16, 5, 34, 51, 43, 13, 45, 28, 21
            ),
            ja3EllipticCurves = listOf(29, 23, 24, 25),
            ja3PointFormats = listOf(0),
            alpnProtocols = listOf("h2", "http/1.1"),
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"
        ),
        BrowserTlsProfile(
            id = "safari_17_ios",
            displayName = "Safari 17.6 Mobile (iOS WebKit / CoreTLS)",
            browserEngine = "Apple CoreTLS / WebKit",
            tlsVersionCode = 771,
            tlsVersions = listOf("TLSv1.3", "TLSv1.2"),
            preferredCipherNames = listOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
            ),
            ja3CipherIds = listOf(
                4865, 4866, 4867, 49196, 49195, 52393, 49200, 49199, 52392, 49162, 49161, 49172, 49171
            ),
            ja3ExtensionIds = listOf(
                0, 23, 65281, 10, 11, 16, 5, 13, 18, 51, 45, 43, 27, 21
            ),
            ja3EllipticCurves = listOf(29, 23, 24, 25),
            ja3PointFormats = listOf(0),
            alpnProtocols = listOf("h2", "http/1.1"),
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        ),
        BrowserTlsProfile(
            id = "cronet_quic_bridge",
            displayName = "Google Cronet / Play Services HTTP2",
            browserEngine = "Conscrypt / Cronet Native",
            tlsVersionCode = 771,
            tlsVersions = listOf("TLSv1.3", "TLSv1.2"),
            preferredCipherNames = listOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
            ),
            ja3CipherIds = listOf(
                4865, 4866, 4867, 49195, 49199, 52393, 52392, 49196, 49200
            ),
            ja3ExtensionIds = listOf(
                0, 23, 65281, 10, 11, 35, 16, 5, 13, 51, 45, 43, 27
            ),
            ja3EllipticCurves = listOf(29, 23),
            ja3PointFormats = listOf(0),
            alpnProtocols = listOf("h2"),
            userAgent = "Cronet/128.0.6613.88 (Linux; U; Android 14; en-US)"
        ),
        BrowserTlsProfile(
            id = "randomized_rotation",
            displayName = "Dynamic Session JA3/JA4 Rotation",
            browserEngine = "Polymorphic Mobile TLS Shuffler",
            tlsVersionCode = 771,
            tlsVersions = listOf("TLSv1.3", "TLSv1.2"),
            preferredCipherNames = listOf(
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            ),
            ja3CipherIds = listOf(
                4867, 4865, 4866, 52393, 52392, 49195, 49199, 49196, 49200, 156, 157
            ),
            ja3ExtensionIds = listOf(
                0, 23, 65281, 10, 11, 35, 16, 5, 13, 51, 45, 43, 21
            ),
            ja3EllipticCurves = listOf(29, 23, 24),
            ja3PointFormats = listOf(0),
            alpnProtocols = listOf("h2", "http/1.1"),
            userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        )
    )

    fun resolveProfile(profileId: String): BrowserTlsProfile {
        return PROFILES.find { it.id == profileId } ?: PROFILES.first()
    }

    /**
     * Computes the canonical JA3 raw vector, MD5 digest, and FoxIO JA4 fingerprint string
     * for the given browser profile and SNI/ALPN settings.
     */
    fun computeFingerprint(
        profileId: String,
        sniHost: String,
        alpnEnabled: Boolean = true,
        sessionSalt: Int = 0
    ): ComputedTlsFingerprint {
        val baseProfile = resolveProfile(profileId)
        val isRotated = baseProfile.id == "randomized_rotation"

        val ciphers = if (isRotated) {
            val mutable = baseProfile.ja3CipherIds.toMutableList()
            if (sessionSalt != 0 && mutable.size > 4) {
                val tail = mutable.subList(3, mutable.size).shuffled(Random(sessionSalt))
                mutable.subList(0, 3) + tail
            } else {
                mutable
            }
        } else {
            baseProfile.ja3CipherIds
        }

        val extensions = baseProfile.ja3ExtensionIds.toMutableList().apply {
            if (sniHost.isBlank()) remove(0) // Extension 0 = server_name
            if (!alpnEnabled) remove(16)     // Extension 16 = application_layer_protocol_negotiation
        }

        val ja3Raw = buildString {
            append(baseProfile.tlsVersionCode)
            append(",")
            append(ciphers.joinToString("-"))
            append(",")
            append(extensions.joinToString("-"))
            append(",")
            append(baseProfile.ja3EllipticCurves.joinToString("-"))
            append(",")
            append(baseProfile.ja3PointFormats.joinToString("-"))
        }

        val ja3Md5 = md5Hex(ja3Raw)

        // Compute FoxIO JA4 fingerprint:
        // Format: [q/t][version][d/i][cipher_count_2d][ext_count_2d][alpn_first_last]_[12char_sorted_cipher_sha256]_[12char_sorted_ext_sha256]
        val transportChar = 't' // TCP TLS
        val versionStr = "13"   // TLS 1.3 supported
        val sniChar = if (sniHost.isNotBlank()) 'd' else 'i'
        val cipherCountStr = ciphers.size.coerceAtMost(99).toString().padStart(2, '0')
        val extCountStr = extensions.size.coerceAtMost(99).toString().padStart(2, '0')
        val primaryAlpn = if (alpnEnabled && baseProfile.alpnProtocols.isNotEmpty()) {
            baseProfile.alpnProtocols.first()
        } else {
            "00"
        }
        val alpnToken = if (primaryAlpn == "h2") "h2" else if (primaryAlpn.startsWith("http/1.1")) "h1" else "00"

        val ja4PartA = "$transportChar$versionStr$sniChar$cipherCountStr$extCountStr$alpnToken"
        val sortedCipherHex = ciphers
            .sorted()
            .joinToString(",") { it.toString(16).padStart(4, '0') }
        val sortedExtHex = extensions
            .filter { it != 0 && it != 16 } // Per JA4 spec, SNI (0000) and ALPN (0010) are excluded from sorted extension hash
            .sorted()
            .joinToString(",") { it.toString(16).padStart(4, '0') }

        val partB = sha256Hex(sortedCipherHex).take(12)
        val partC = sha256Hex(sortedExtHex).take(12)
        val ja4 = "${ja4PartA}_${partB}_${partC}"

        return ComputedTlsFingerprint(
            profileId = baseProfile.id,
            profileName = baseProfile.displayName,
            ja3RawString = ja3Raw,
            ja3HashMd5 = ja3Md5,
            ja4String = ja4,
            negotiatedAlpn = if (alpnEnabled) baseProfile.alpnProtocols.joinToString(", ") else "disabled",
            activeCipherSuitesCount = ciphers.size,
            sniConfigured = sniHost.isNotBlank()
        )
    }

    /**
     * Applies the chosen JA3/JA4 Browser TLS Profile to an Android [SSLSocket] prior to `startHandshake()`.
     * Orders enabled TLS versions, cipher suites, SNI server_name, and ALPN protocols.
     */
    fun applyFingerprintToSocket(
        sslSocket: SSLSocket,
        profileId: String,
        sniHost: String,
        alpnEnabled: Boolean = true
    ): ComputedTlsFingerprint {
        val profile = resolveProfile(profileId)
        val isRotated = profile.id == "randomized_rotation"
        val salt = if (isRotated) Random.nextInt(1, 100_000) else 0

        // 1. Configure TLS protocol versions (TLSv1.3, TLSv1.2)
        val supportedProtocols = sslSocket.supportedProtocols?.toSet() ?: emptySet()
        val desiredProtocols = profile.tlsVersions.filter { it in supportedProtocols }
        if (desiredProtocols.isNotEmpty()) {
            sslSocket.enabledProtocols = desiredProtocols.toTypedArray()
        }

        // 2. Order and filter Cipher Suites to match the target Browser Profile
        val supportedCiphers = sslSocket.supportedCipherSuites ?: emptyArray()
        val supportedMap = supportedCiphers.associateBy { it.uppercase() }
        val orderedCiphers = mutableListOf<String>()

        val preferredList = if (isRotated && profile.preferredCipherNames.size > 3) {
            val head = profile.preferredCipherNames.take(3).shuffled()
            val tail = profile.preferredCipherNames.drop(3).shuffled()
            head + tail
        } else {
            profile.preferredCipherNames
        }

        for (cipherName in preferredList) {
            val matched = supportedMap[cipherName.uppercase()]
            if (matched != null && matched !in orderedCiphers) {
                orderedCiphers.add(matched)
            }
        }

        // Include remaining strong AEAD suites supported by the socket as fallback
        for (existing in sslSocket.enabledCipherSuites) {
            if (existing !in orderedCiphers && (existing.contains("GCM") || existing.contains("CHACHA20"))) {
                orderedCiphers.add(existing)
            }
        }

        if (orderedCiphers.isNotEmpty()) {
            sslSocket.enabledCipherSuites = orderedCiphers.toTypedArray()
        }

        // 3. Configure SSLParameters: SNI & ALPN
        val sslParams: SSLParameters = sslSocket.sslParameters ?: SSLParameters()
        if (sniHost.isNotBlank()) {
            try {
                sslParams.serverNames = listOf(SNIHostName(sniHost.trim()))
            } catch (_: IllegalArgumentException) {
                // Ignore if sniHost is an IP literal not valid for SNIHostName
            }
        }

        if (alpnEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sslParams.applicationProtocols = profile.alpnProtocols.toTypedArray()
        }

        sslSocket.sslParameters = sslParams

        return computeFingerprint(
            profileId = profile.id,
            sniHost = sniHost,
            alpnEnabled = alpnEnabled,
            sessionSalt = salt
        )
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
