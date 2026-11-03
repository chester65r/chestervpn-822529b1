package com.chester65r.chestervpncompanion

import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDiagnosticsTest {
    @Test
    fun reportContainsOnlyExpectedDiagnosticFields() {
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
        assertTrue(!report.contains("IMEI"))
        assertTrue(!report.contains("SIM"))
    }
}
