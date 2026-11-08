package com.example.advancedtunnel

import android.content.Context
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * On-device WireGuard identity generator and enrollment verifier for
 * Hetzner CX23 (ahmed-docker-manager / 2.29.8.194).
 * Only the public identity leaves the device; the private key stays wrapped in Keystore.
 */
class DeviceEnrollment(context: Context) {
    private val identity = SecureConfigStore(context, "device_identity")
    private val profile = SecureConfigStore(context)

    @Synchronized
    fun publicKey(): String {
        val existing = identity.load()
        val pair = if (existing == null) {
            KeyPair().also {
                profile.clear()
                identity.save(it.privateKey.toBase64())
            }
        } else {
            KeyPair(Key.fromBase64(existing))
        }
        return pair.publicKey.toBase64()
    }

    /** The server key is confirmed out-of-band; the response contains a per-device PSK. */
    @Synchronized
    fun enroll(response: String, trustedServerKey: String) {
        require(response.length in 1..65536) { "Invalid enrollment size" }
        require(!Regex("(?im)^\\s*PrivateKey\\s*=").containsMatchIn(response)) {
            "Enrollment must not contain a private key"
        }
        val privateKey = identity.load() ?: error("Generate a device identity first")
        val marker = Regex("(?m)^\\[Interface]\\s*$")
        require(marker.findAll(response).count() == 1) { "Invalid enrollment response" }
        val combined = marker.replaceFirst(response, "[Interface]\nPrivateKey = $privateKey")
        val config = TunnelController.parse(combined)
        require(config.peers[0].publicKey == Key.fromBase64(trustedServerKey.trim())) {
            "Server identity does not match"
        }
        profile.save(combined)
    }

    @Synchronized
    fun loadEnrolledConfig(): String? = profile.load()

    @Synchronized
    fun clear() {
        profile.clear()
        identity.clear()
    }
}
