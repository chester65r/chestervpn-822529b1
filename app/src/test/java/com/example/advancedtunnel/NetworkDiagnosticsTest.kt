package com.example.advancedtunnel

import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.model.TunnelUtils
import com.example.advancedtunnel.vpn.SingBoxConfigBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsTest {

    @Test
    fun testProfileValidation_validProfile() {
        val stcJson = SingBoxConfigBuilder.buildCarrierConfigJson(
            carrierId = "stc",
            serverHost = "2.29.8.194",
            serverPort = 443,
            customSni = "www.stc.com.sa"
        )
        val profile = TunnelProfile(
            ip = "2.29.8.194",
            port = "443",
            sni = "www.stc.com.sa",
            payload = stcJson,
            protocol = "vless-reality"
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
        val mobilyJson = SingBoxConfigBuilder.buildCarrierConfigJson(
            carrierId = "mobily",
            serverHost = "2.29.8.194",
            serverPort = 443,
            customSni = "www.mobily.com.sa",
            customCdnHost = "cdn.mobily.com.sa",
            customWsPath = "/cdn-ws"
        )
        val original = TunnelProfile(
            ip = "2.29.8.194",
            port = "443",
            sni = "www.mobily.com.sa",
            payload = mobilyJson,
            protocol = "vless-ws-tls",
            cdnHost = "cdn.mobily.com.sa",
            wsPath = "/cdn-ws"
        )
        val code = TunnelUtils.exportProfile(original)
        assertNotNull("Exported code should not be null", code)
        assertTrue("Exported code should start with TUNNEL://", code!!.startsWith("TUNNEL://"))

        val imported = TunnelUtils.importProfile(code)
        assertNotNull("Imported profile should not be null", imported)
        assertEquals("IP should match after import", original.ip, imported?.ip)
        assertEquals("Port should match after import", original.port, imported?.port)
        assertEquals("SNI should match after import", original.sni, imported?.sni)
        assertEquals("Protocol should match after import", original.protocol, imported?.protocol)
        assertEquals("Payload should match after import", original.payload, imported?.payload)
    }

    @Test
    fun testPayloadVisualization() {
        val rawPayload = SingBoxConfigBuilder.buildCarrierConfigJson(
            carrierId = "stc",
            serverHost = "2.29.8.194",
            serverPort = 443,
            customSni = "www.stc.com.sa"
        )
        val sni = "www.stc.com.sa"
        val parsed = TunnelUtils.parsePayload(rawPayload, sni)
        val visual = TunnelUtils.visualizePayload(parsed)

        assertTrue("Visual preview should contain valid sing-box outbounds JSON", visual.contains("\"outbounds\""))
        assertTrue("Visual preview should include configured SNI", visual.contains(sni))
    }
}
