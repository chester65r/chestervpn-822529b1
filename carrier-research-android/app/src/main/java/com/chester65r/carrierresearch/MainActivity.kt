package com.chester65r.carrierresearch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CarrierResearchApp(this) }
    }
}

@Composable
private fun CarrierResearchApp(activity: ComponentActivity) {
    val context = LocalContext.current
    var carrierLabel by remember { mutableStateOf("STC") }
    var selectedPlan by remember { mutableStateOf(DataPlan.SOCIAL_ONLY) }
    var network by remember { mutableStateOf(readNetworkSnapshot(context)) }
    var endpointUrl by remember { mutableStateOf("") }
    var authorizationConfirmed by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<EndpointProbeResult?>(null) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var policyResults by remember { mutableStateOf(PolicyEngine.runDefensiveSuite()) }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(modifier = Modifier.padding(top = 18.dp)) {
                        Text("ChesterVPN Carrier Research", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Real Android network observations + a defensive carrier-policy lab",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "RESEARCH MODE — NO VPN TUNNEL OR BILLING BYPASS",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Carrier label", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Choose the SIM network being tested. This label is manual; the app does not read SIM identifiers.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("STC", "Mobily", "Other").forEach { label ->
                                    FilterChip(
                                        selected = carrierLabel == label,
                                        onClick = { carrierLabel = label },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Declared package profile", style = MaterialTheme.typography.titleMedium)
                            DataPlan.entries.forEach { plan ->
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedPlan == plan,
                                        onClick = { selectedPlan = plan },
                                    )
                                    Text(planLabel(plan))
                                }
                            }
                            Text(
                                "Package profile is a research label selected by the tester; it is not read from the carrier billing system.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Live Android network snapshot", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            network.asLines().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { network = readNetworkSnapshot(context) }) {
                                Text("Refresh live network state")
                            }
                            Text(
                                "For a mobile-data measurement, temporarily turn Wi-Fi off yourself and refresh. The app does not change network settings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Authorized lab endpoint", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "This performs one HTTPS GET to /healthz on a server you own or are explicitly authorized to test. TLS certificate validation remains enabled; redirects are not followed.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = endpointUrl,
                                onValueChange = { endpointUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("HTTPS health URL") },
                                placeholder = { Text("https://your-lab-domain.example/healthz") },
                                singleLine = true,
                            )
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(
                                    checked = authorizationConfirmed,
                                    onCheckedChange = { authorizationConfirmed = it },
                                )
                                Text("I own or have permission to test this endpoint.")
                            }
                            Button(
                                enabled = authorizationConfirmed && !probing,
                                onClick = {
                                    probing = true
                                    probeError = null
                                    probeResult = null
                                    Thread {
                                        val outcome = runCatching {
                                            probeAuthorizedHealthEndpoint(endpointUrl)
                                        }
                                        activity.runOnUiThread {
                                            outcome.onSuccess { probeResult = it }
                                                .onFailure { probeError = it.message ?: "Endpoint check failed." }
                                            probing = false
                                        }
                                    }.start()
                                },
                            ) {
                                Text(if (probing) "Testing…" else "Run one HTTPS health check")
                            }
                            probeResult?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    (if (it.successful) "PASS — " else "FAIL — ") + it.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Host: " + it.host + " • DNS records: " + it.dnsRecordCount +
                                        " • HTTP: " + (it.httpStatus ?: "N/A") +
                                        " • " + it.elapsedMillis + " ms",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            probeError?.let {
                                Text("Check rejected or failed: " + it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Defensive policy test suite", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Deterministic lab cases test fail-closed policy controls. These results are not observations of STC or Mobily.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                policyResults.count { it.passed }.toString() + "/" +
                                    policyResults.size + " tests passed",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Button(onClick = { policyResults = PolicyEngine.runDefensiveSuite() }) {
                                Text("Run policy suite again")
                            }
                        }
                    }
                }

                items(policyResults) { result ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                (if (result.passed) "PASS  " else "FAIL  ") + result.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Expected: " + result.expected.name + " • Actual: " + result.actual.name,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val report = buildEvidenceReport(
                                carrierLabel = carrierLabel,
                                selectedPlan = selectedPlan,
                                network = network,
                                policyResults = policyResults,
                                endpointResult = probeResult,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            activity.startActivity(Intent.createChooser(send, "Export research evidence"))
                        },
                    ) {
                        Text("Export evidence report")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

private fun planLabel(plan: DataPlan): String = when (plan) {
    DataPlan.NO_ACTIVE_PLAN -> "No active data plan (declared)"
    DataPlan.SOCIAL_ONLY -> "Social-only bundle (declared)"
    DataPlan.GENERAL_DATA -> "General data plan (declared)"
}
