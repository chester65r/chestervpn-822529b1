package com.chester65r.carrierresearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointProbeValidationTest {
    @Test
    fun acceptsOnlyHttpsHealthEndpointOnStandardPort() {
        val uri = validateHealthEndpoint("https://lab.example.org/healthz")
        assertEquals("https", uri.scheme)
        assertEquals("lab.example.org", uri.host)
        assertEquals("/healthz", uri.path)
    }

    @Test
    fun rejectsHttpAndNonHealthPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            validateHealthEndpoint("http://lab.example.org/healthz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateHealthEndpoint("https://lab.example.org/")
        }
    }

    @Test
    fun rejectsCredentialsQueryFragmentsAndNonstandardPorts() {
        val invalidUrls = listOf(
            "https://user:pass@lab.example.org/healthz",
            "https://lab.example.org/healthz?x=1",
            "https://lab.example.org/healthz#fragment",
            "https://lab.example.org:8443/healthz",
        )
        invalidUrls.forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                validateHealthEndpoint(raw)
            }
        }
    }

    @Test
    fun rejectsIpLiteralsAndLocalHostnames() {
        val invalidUrls = listOf(
            "https://127.0.0.1/healthz",
            "https://localhost/healthz",
            "https://router.local/healthz",
        )
        invalidUrls.forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                validateHealthEndpoint(raw)
            }
        }
    }
}
