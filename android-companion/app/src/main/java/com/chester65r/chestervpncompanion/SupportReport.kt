package com.chester65r.chestervpncompanion

fun buildSupportReport(
    device: DeviceDiagnostics,
    network: NetworkReadiness,
    events: List<String>,
): String = buildString {
    appendLine("ChesterVPN Companion — support report")
    appendLine("Generated locally at the user's request")
    appendLine()
    appendLine("Device")
    appendLine("Android release: ${device.androidRelease}")
    appendLine("Android API level: ${device.apiLevel}")
    appendLine("App version: ${device.appVersion}")
    appendLine()
    appendLine("Connectivity summary")
    network.asLines().forEach(::appendLine)
    appendLine()
    appendLine("Recent app activity")
    if (events.isEmpty()) {
        appendLine("No activity events recorded")
    } else {
        events.take(50).forEach(::appendLine)
    }
    appendLine()
    appendLine("Privacy")
    appendLine("This report excludes identifiers, SIM details, phone numbers, carrier names,")
    appendLine("web destinations, payloads, account credentials, and network traffic.")
}
