package com.chester65r.chestervpncompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportTest {
    private fun sampleNetwork() = NetworkReadiness(
        status = "Internet connectivity validated by Android",
        transport = "Wi-Fi",
        metered = false,
    )

    @Test
    fun reportContainsOnlyTheIntendedLocalDiagnostics() {
        val report = buildSupportReport(
            device = DeviceDiagnostics("15", 35, "1.0.0"),
            network = sampleNetwork(),
            events = listOf("08:00:00  App opened"),
        )

        assertTrue(report.contains("Android release: 15"))
        assertTrue(report.contains("Android API level: 35"))
        assertTrue(report.contains("App version: 1.0.0"))
        assertTrue(report.contains("Connection type: Wi-Fi"))
        assertTrue(report.contains("App opened"))
        assertTrue(report.contains("Generated locally"))
        assertTrue(report.contains("SIM details"))
        assertFalse(report.contains("IMEI:"))
        assertFalse(report.contains("password="))
    }

    @Test
    fun reportHandlesAnEmptyActivityLog() {
        val report = buildSupportReport(
            device = DeviceDiagnostics("Unknown", 0, "Unknown"),
            network = NetworkReadiness("No active network", "None", null),
            events = emptyList(),
        )

        assertTrue(report.contains("No activity events recorded"))
        assertTrue(report.contains("Metered connection: Unknown"))
    }

    @Test
    fun reportIncludesAtMostFiftyRecentEvents() {
        val events = (1..60).map { "event-$it" }
        val report = buildSupportReport(
            device = DeviceDiagnostics("16", 36, "1.0.0"),
            network = sampleNetwork(),
            events = events,
        )

        assertTrue(report.contains("event-1"))
        assertTrue(report.contains("event-50"))
        assertFalse(report.contains("event-51"))
        assertFalse(report.contains("event-60"))
    }
}
