package com.chester65r.chestervpncompanion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
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
        enableEdgeToEdge()
        LocalAppStore.addEvent(this, "App opened")
        setContent {
            CompanionApp()
        }
    }
}

@Composable
private fun CompanionApp() {
    val context = LocalContext.current
    var darkTheme by remember { mutableStateOf(LocalAppStore.isDarkTheme(context)) }

    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
    ) {
        CompanionHome(
            darkTheme = darkTheme,
            onDarkThemeChange = { enabled ->
                darkTheme = enabled
                LocalAppStore.setDarkTheme(context, enabled)
                LocalAppStore.addEvent(context, if (enabled) "Dark theme enabled" else "Light theme enabled")
            },
        )
    }
}

private enum class AppScreen(val title: String) {
    HOME("Home"),
    ACTIVITY("Activity"),
    SETTINGS("Settings"),
    ABOUT("About"),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CompanionHome(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var report by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Ready. Your app data stays on this device.") }
    var events by remember { mutableStateOf(LocalAppStore.loadEvents(context)) }
    var network by remember { mutableStateOf(inspectNetworkReadiness(context)) }
    var diagnostics by remember { mutableStateOf<DeviceDiagnostics?>(null) }
    var logFilter by remember { mutableStateOf("") }

    fun record(event: String) {
        events = LocalAppStore.addEvent(context, event)
    }

    fun refreshNetwork() {
        network = inspectNetworkReadiness(context)
        record("Connectivity summary refreshed")
        message = "Connectivity summary refreshed locally."
    }

    fun runDiagnostics() {
        runCatching { collectDeviceDiagnostics(context) }
            .onSuccess {
                diagnostics = it
                message = "Device diagnostics completed locally."
                record("Device diagnostics completed")
            }
            .onFailure {
                message = "Device diagnostics could not be collected."
                record("Device diagnostics check failed")
            }
    }

    fun generateSupportReport() {
        runCatching {
            val latestNetwork = inspectNetworkReadiness(context)
            val latestEvents = LocalAppStore.loadEvents(context)
            val latestDevice = collectDeviceDiagnostics(context)
            network = latestNetwork
            events = latestEvents
            buildSupportReport(
                device = latestDevice,
                network = latestNetwork,
                events = latestEvents,
            )
        }.onSuccess {
            report = it
            message = "Support report generated. Review it before sharing."
            record("Support report generated")
        }.onFailure {
            report = null
            message = "Support report could not be generated."
        }
    }

    fun copyReport() {
        val content = report ?: return
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Local support report", content))
        }.onSuccess {
            message = "Support report copied to clipboard."
            record("Support report copied")
        }.onFailure {
            message = "The report could not be copied. You can still review and share it."
        }
    }

    fun shareReport() {
        val content = report ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent, "Share support report"))
        }.onSuccess {
            message = "Choose an app to share the report."
            record("Support report share sheet opened")
        }.onFailure {
            message = "No compatible sharing app is available."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ChesterVPN Companion", style = MaterialTheme.typography.titleLarge)
                        Text("Device tools • local-first", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppScreen.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { screen = destination },
                        icon = { Text(destination.title.take(1)) },
                        label = { Text(destination.title) },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (screen) {
                AppScreen.HOME -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("Companion workspace", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "A native Android utility for device readiness, local diagnostics, and support reports.",
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
                                Text("Network readiness", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(network.status, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("Connection type: ${network.transport}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Metered connection: ${network.metered?.let { if (it) "Yes" else "No" } ?: "Unknown"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = ::refreshNetwork) {
                                    Text("Refresh status")
                                }
                            }
                        }
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("Device diagnostics", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Read the Android release, API level, and app version. Nothing is uploaded.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = ::runDiagnostics) {
                                    Text("Run diagnostics")
                                }
                                diagnostics?.let { info ->
                                    Spacer(Modifier.height(10.dp))
                                    Text(formatDeviceDiagnostics(info), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("Support report", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Generate a report containing selected device details, Android's connectivity summary, and recent app events. Review it before sharing.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = ::generateSupportReport) {
                                        Text("Generate")
                                    }
                                    OutlinedButton(onClick = ::copyReport, enabled = report != null) {
                                        Text("Copy")
                                    }
                                    OutlinedButton(onClick = ::shareReport, enabled = report != null) {
                                        Text("Share")
                                    }
                                }
                                report?.let { text ->
                                    Spacer(Modifier.height(10.dp))
                                    Text(text, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                AppScreen.ACTIVITY -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = {
                                LocalAppStore.clearEvents(context)
                                events = emptyList()
                                report = null
                                message = "Local activity history cleared."
                            }) {
                                Text("Clear history")
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = logFilter,
                            onValueChange = { logFilter = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Filter activity") },
                            singleLine = true,
                        )
                    }
                    val visibleEvents = events.filter { it.contains(logFilter, ignoreCase = true) }
                    if (visibleEvents.isEmpty()) {
                        item {
                            Text(
                                if (events.isEmpty()) "No events recorded yet." else "No matching events.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        items(visibleEvents) { event ->
                            Text(event, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }

                AppScreen.SETTINGS -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Dark theme", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "Your selection is saved on this device.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Switch(
                                        checked = darkTheme,
                                        onCheckedChange = onDarkThemeChange,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("Local data", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Activity history and appearance preferences are stored in this app's private local storage. Clearing activity history does not affect your Android settings.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = {
                                    LocalAppStore.clearEvents(context)
                                    events = emptyList()
                                    report = null
                                    message = "Local activity history cleared."
                                }) {
                                    Text("Clear activity history")
                                }
                            }
                        }
                    }
                }

                AppScreen.ABOUT -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("About this build", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text("ChesterVPN Companion", style = MaterialTheme.typography.bodyLarge)
                                Text("Version 1.0.0 • Android 8.0 and later", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "This build is a companion utility, not a VPN client. It does not create tunnels, proxy traffic, intercept packets, or change network routes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "It does not request Internet, VPN, location, phone, or contacts permissions. The only network-related permission is Android's read-only ACCESS_NETWORK_STATE permission.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Online accounts, cloud synchronisation, and remote diagnostics are not enabled in this build.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(message, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
