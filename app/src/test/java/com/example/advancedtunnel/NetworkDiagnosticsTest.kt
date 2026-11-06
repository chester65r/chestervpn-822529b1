package com.example.advancedtunnel

import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.model.TunnelUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsTest {

    @Test
    fun testProfileValidation_validProfile() {
        val profile = TunnelProfile(
            ip = "1.1.1.1",
            port = "443",
            sni = "cloudflare.com",
            payload = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"
        )
        val errors = TunnelUtils.validateProfile(profile)
        assertTrue("Valid profile should produce no validation errors", errors.isEmpty())
    }

    @Test
    fun testProfileValidation_invalidIpAndPort() {
        val profile = TunnelProfile(
            ip = "",
            port = "99999",
            sni = "",
            payload = ""
        )
        val errors = TunnelUtils.validateProfile(profile)
        assertTrue("Empty IP should trigger error", errors.containsKey("ip"))
        assertTrue("Port out of range should trigger error", errors.containsKey("port"))
    }

    @Test
    fun testProfileExportImportRoundtrip() {
        val original = TunnelProfile(
            ip = "203.0.113.5",
            port = "8443",
            sni = "tunnel.example.com",
            payload = "CONNECT [host] HTTP/1.1[crlf][crlf]"
        )
        val code = TunnelUtils.exportProfile(original)
        assertNotNull("Exported code should not be null", code)
        assertTrue("Exported code should start with TUNNEL://", code!!.startsWith("TUNNEL://"))

        val imported = TunnelUtils.importProfile(code)
        assertNotNull("Imported profile should not be null", imported)
        assertEquals("IP should match after import", original.ip, imported?.ip)
        assertEquals("Port should match after import", original.port, imported?.port)
        assertEquals("SNI should match after import", original.sni, imported?.sni)
        assertEquals("Payload should match after import", original.payload, imported?.payload)
    }

    @Test
    fun testPayloadVisualization() {
        val rawPayload = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"
        val sni = "example.com"
        val parsed = TunnelUtils.parsePayload(rawPayload, sni)
        val visual = TunnelUtils.visualizePayload(parsed)

        assertTrue("Visual preview should show CR LF escape labels", visual.contains("\\r\\n"))
        assertTrue("Visual preview should replace [host] with actual SNI", visual.contains(sni))
    }
}
