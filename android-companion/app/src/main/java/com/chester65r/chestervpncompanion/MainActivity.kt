package com.chester65r.chestervpncompanion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompanionHome()
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CompanionHome() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Ready. Diagnostics stay on this device.") }
    var events by remember { mutableStateOf(listOf("App opened")) }

    fun log(event: String) {
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        events = (listOf("$timestamp  $event") + events).take(12)
    }

    fun copyReport() {
        val content = report ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Local diagnostics", content))
        message = "Technical report copied to clipboard."
        log("Diagnostics report copied")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ChesterVPN Companion", style = MaterialTheme.typography.titleLarge)
                        Text("Device tools • local-only", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Companion workspace", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A native Android shell for app information, local diagnostics, and support-ready reports.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "LOCAL MODE",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Device readiness", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Check basic Android compatibility and copy a technical report. The report contains only the Android release, API level, and app version.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching { collectDeviceDiagnostics(context) }
                                    .onSuccess {
                                        report = formatDeviceDiagnostics(it)
                                        message = "Diagnostics completed locally."
                                        log("Device diagnostics completed")
                                    }
                                    .onFailure {
                                        report = null
                                        message = "Diagnostics could not be collected."
                                        log("Diagnostics check failed")
                                    }
                            }) {
                                Text("Run diagnostics")
                            }
                            OutlinedButton(onClick = ::copyReport, enabled = report != null) {
                                Text("Copy report")
                            }
                        }
                        if (report != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(report.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Privacy and permissions", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This companion build requests no internet, VPN, location, phone, or contacts permissions. It does not create a tunnel or send diagnostics to a server.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {
                        events = emptyList()
                        message = "Local activity log cleared."
                    }) {
                        Text("Clear")
                    }
                }
            }

            items(events) { event ->
                Text(event, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }

            item {
                Text(message, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
