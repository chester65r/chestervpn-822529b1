package com.example.advancedtunnel

import com.example.advancedtunnel.vpn.SingBoxConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderTest {
    private fun tls(config: SingBoxConfigBuilder.ClientConfig): JSONObject =
        JSONObject(SingBoxConfigBuilder.buildClientConfigJson(config))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")

    @Test
    fun defaultProfilesDoNotEnableFragmentation() {
        for (profile in SingBoxConfigBuilder.buildPresetProfiles()) {
            val json = tls(profile)
            assertFalse(json.getBoolean("fragment"))
            assertFalse(json.getBoolean("record_fragment"))
            assertFalse(json.has("fragment_fallback_delay"))
        }
    }

    @Test
    fun serializesEachFragmentationChoiceForEveryProtocol() {
        for (protocol in SingBoxConfigBuilder.Protocol.entries) {
            for (fragment in listOf(false, true)) {
                for (record in listOf(false, true)) {
                    val config = SingBoxConfigBuilder.ClientConfig(
                        protocol = protocol,
                        tlsFragmentEnabled = fragment,
                        tlsRecordFragmentEnabled = record,
                        fragmentFallbackDelay = "750ms"
                    )
                    val json = tls(config)
                    assertEquals(fragment, json.getBoolean("fragment"))
                    assertEquals(record, json.getBoolean("record_fragment"))
                    assertEquals(fragment, json.has("fragment_fallback_delay"))
                    if (fragment) assertEquals("750ms", json.getString("fragment_fallback_delay"))
                }
            }
        }
    }

    @Test
    fun payloadRoundTripPreservesFragmentationInsteadOfSilentlyDisablingIt() {
        val original = SingBoxConfigBuilder.ClientConfig(
            tlsFragmentEnabled = true,
            tlsRecordFragmentEnabled = true,
            fragmentFallbackDelay = "750ms"
        )
        val parsed = SingBoxConfigBuilder.parseClientConfigFromJson(
            SingBoxConfigBuilder.buildClientConfigJson(original)
        )
        assertNotNull(parsed)
        assertTrue(parsed!!.tlsFragmentEnabled)
        assertTrue(parsed.tlsRecordFragmentEnabled)
        assertEquals("750ms", parsed.fragmentFallbackDelay)
        assertEquals("750ms", tls(parsed).getString("fragment_fallback_delay"))
    }

    @Test
    fun missingOptionsRemainDisabledAndBlankDelayFallsBackToDefault() {
        val json = JSONObject(SingBoxConfigBuilder.buildClientConfigJson(SingBoxConfigBuilder.ClientConfig()))
        val tlsJson = json.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
        tlsJson.remove("fragment")
        tlsJson.remove("record_fragment")
        val parsed = SingBoxConfigBuilder.parseClientConfigFromJson(json.toString())!!
        assertFalse(parsed.tlsFragmentEnabled)
        assertFalse(parsed.tlsRecordFragmentEnabled)
        assertEquals("500ms", parsed.fragmentFallbackDelay)

        tlsJson.put("fragment", true)
        tlsJson.put("fragment_fallback_delay", "")
        val blankDelay = SingBoxConfigBuilder.parseClientConfigFromJson(json.toString())!!
        assertEquals("500ms", tls(blankDelay).getString("fragment_fallback_delay"))
    }
}
