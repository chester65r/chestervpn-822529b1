package com.chester65r.chestervpncompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDiagnosticsTest {
    @Test
    fun reportContainsOnlyTheExpectedDiagnosticFields() {
        val report = formatDeviceDiagnostics(
            DeviceDiagnostics(
                androidRelease = "15",
                apiLevel = 35,
                appVersion = "1.0.0",
            ),
        )

        assertTrue(report.contains("Android release: 15"))
        assertTrue(report.contains("Android API level: 35"))
        assertTrue(report.contains("App version: 1.0.0"))
        assertTrue(report.contains("generated locally"))
        assertTrue(report.contains("SIM data"))
        assertFalse(report.contains("IMEI:"))
        assertFalse(report.contains("phoneNumber:"))
        assertFalse(report.contains("subscriberId:"))
    }
}
