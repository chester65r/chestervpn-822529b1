package com.chester65r.carrierresearch

fun buildEvidenceReport(
    carrierLabel: String,
    selectedPlan: DataPlan,
    network: NetworkSnapshot,
    policyResults: List<PolicyTestResult>,
    endpointResult: EndpointProbeResult?,
): String = buildString {
    appendLine("ChesterVPN Carrier Research — Evidence Record")
    appendLine("Generated UTC: " + java.time.Instant.now())
    appendLine("Carrier label: " + carrierLabel + " (user-selected, not SIM-verified)")
    appendLine("Selected plan label: " + selectedPlan.name + " (user-declared, not carrier-verified)")
    appendLine()
    appendLine("Live Android network snapshot")
    network.asLines().forEach { appendLine(it) }
    appendLine()
    appendLine("Local defensive policy test suite")
    appendLine("These are deterministic laboratory cases, not carrier-network findings.")
    policyResults.forEach {
        appendLine(
            (if (it.passed) "PASS" else "FAIL") + " | " + it.name +
                " | expected=" + it.expected.name + " | actual=" + it.actual.name
        )
    }
    appendLine(
        "Policy suite result: " + policyResults.count { it.passed } +
            "/" + policyResults.size + " passed"
    )
    appendLine()
    appendLine("Authorized HTTPS endpoint check")
    if (endpointResult == null) {
        appendLine("NOT RUN")
    } else {
        appendLine("Result: " + if (endpointResult.successful) "PASS" else "FAIL")
        appendLine("Endpoint host: " + endpointResult.host)
        appendLine("DNS records: " + endpointResult.dnsRecordCount)
        appendLine("HTTPS status: " + (endpointResult.httpStatus ?: "unavailable"))
        appendLine("Elapsed milliseconds: " + endpointResult.elapsedMillis)
        appendLine("Detail: " + endpointResult.detail)
    }
    appendLine()
    appendLine("Interpretation limits")
    appendLine("- Carrier and plan labels are manually selected and are not verified against the SIM or billing system.")
    appendLine("- Android network validation does not prove package entitlement or data accounting.")
    appendLine("- Policy-suite outcomes are local deterministic tests, not evidence of a carrier vulnerability.")
    appendLine("- The HTTPS check targets only the endpoint entered by the tester, after authorization confirmation.")
    appendLine("- No subscriber identifiers, phone numbers, SIM identifiers, packet contents, or private credentials are collected.")
}
