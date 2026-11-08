package com.example.advancedtunnel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wireguard.crypto.KeyPair
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Android instrumentation test suite verifying real Android Keystore AES-256-GCM
 * encryption, tamper detection, and WireGuard enrollment for Hetzner CX23 (2.29.8.194).
 */
@RunWith(AndroidJUnit4::class)
class NativeCredentialsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SecureConfigStore(context, "test_credentials")
    private val file = File(context.noBackupFilesDir, "test_credentials.enc")

    @After
    fun cleanup() {
        store.clear()
        DeviceEnrollment(context).clear()
    }

    @Test
    fun encryptedRoundTripAndFreshNonce() {
        store.save("credential-test-value")
        val first = file.readBytes()
        assertEquals("credential-test-value", store.load())
        assertFalse(String(first).contains("credential-test-value"))
        store.save("credential-test-value")
        assertFalse(first.copyOfRange(1, 13).contentEquals(file.readBytes().copyOfRange(1, 13)))
    }

    @Test
    fun tamperFailsClosed() {
        store.save("test")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun truncationFailsClosed() {
        store.save("test")
        file.writeBytes(byteArrayOf(1))
        assertNull(store.load())
    }

    @Test
    fun keyLossRequiresReEnrollment() {
        store.save("test")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("tunnel_credential_test_credentials")
        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun clearDeletesCiphertextAndKeystoreKey() {
        store.save("test")
        store.clear()
        assertNull(store.load())
        assertFalse(
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias("tunnel_credential_test_credentials")
        )
    }

    @Test
    fun enrollmentUsesLocalPrivateKeyAndPinnedServerIdentity() {
        val enrollment = DeviceEnrollment(context)
        val devicePublic = enrollment.publicKey()
        assertEquals(devicePublic, enrollment.publicKey())
        val server = KeyPair().publicKey.toBase64()
        val response =
            "[Interface]\nAddress = 10.66.66.2/32\nDNS = 10.66.66.1\nMTU = 1280\n\n[Peer]\nPublicKey = " +
                server +
                "\nPresharedKey = " +
                KeyPair().privateKey.toBase64() +
                "\nEndpoint = 2.29.8.194:51820\nAllowedIPs = 0.0.0.0/0, ::/0\n"
        enrollment.enroll(response, server)
        val stored = SecureConfigStore(context).load()
        assertNotNull(stored)
        val config = TunnelController.parse(requireNotNull(stored))
        assertEquals(devicePublic, config.`interface`.keyPair.publicKey.toBase64())
        try {
            enrollment.enroll(response, KeyPair().publicKey.toBase64())
            fail("Wrong server accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(stored, SecureConfigStore(context).load())
        try {
            enrollment.enroll(
                response.replace(
                    "[Interface]",
                    "[Interface]\nPrivateKey = " + KeyPair().privateKey.toBase64()
                ),
                server
            )
            fail("Remote private key accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
