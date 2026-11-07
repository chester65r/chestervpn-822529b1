package com.example.advancedtunnel

import com.example.advancedtunnel.wireguard.WireGuardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfigTest {

    @Test
    fun testParseStandardWgQuickConfig() {
        val clientKeys = com.wireguard.crypto.KeyPair()
        val serverKeys = com.wireguard.crypto.KeyPair()
        val psk = com.wireguard.crypto.KeyPair().privateKey.toBase64()
        val clientPrivate = clientKeys.privateKey.toBase64()
        val clientPublic = clientKeys.publicKey.toBase64()
        val serverPublic = serverKeys.publicKey.toBase64()

        // Standard wg-quick configuration with [Interface] and [Peer]
        val configText = """
            [Interface]
            PrivateKey = $clientPrivate
            Address = 10.0.0.2/24
            DNS = 1.1.1.1, 8.8.8.8
            ListenPort = 51820
            MTU = 1420

            [Peer]
            PublicKey = $serverPublic
            PresharedKey = $psk
            Endpoint = 198.51.100.10:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent()

        val parsed = WireGuardManager.parseConfig(configText)
        assertNotNull(parsed)

        val meta = WireGuardManager.extractMetadata(parsed)
        assertTrue("Interface addresses should contain 10.0.0.2", meta.interfaceAddresses.contains("10.0.0.2"))
        assertTrue("DNS should contain 1.1.1.1", meta.dnsServers.contains("1.1.1.1"))
        assertEquals("Peer endpoint should match", "198.51.100.10:51820", meta.peerEndpoint)
        assertTrue("Allowed IPs should contain 0.0.0.0/0", meta.allowedIps.contains("0.0.0.0/0"))
        assertEquals("MTU should be 1420", 1420, meta.mtu)
        assertEquals("ListenPort should be 51820", 51820, meta.listenPort)
        assertEquals("Persistent keepalive should be 25", 25, meta.persistentKeepalive)
        assertEquals("Interface public key should match derived key", clientPublic, meta.interfacePublicKey)
        assertEquals("Peer public key should match", serverPublic, meta.peerPublicKey)
        assertTrue("PresharedKey should be detected", meta.hasPresharedKey)
    }

    @Test
    fun testParseConfig_invalidSyntaxThrowsException() {
        val invalidText = "Not a valid wireguard config at all"
        val result = runCatching { WireGuardManager.parseConfig(invalidText) }
        assertTrue("Parsing invalid config should fail", result.isFailure)
    }
}
