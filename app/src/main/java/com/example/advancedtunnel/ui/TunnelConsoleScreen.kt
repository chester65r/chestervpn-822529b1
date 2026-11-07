package com.example.advancedtunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.vpn.TunnelConnectionState
import com.example.advancedtunnel.vpn.TunnelStateHolder
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelConsoleScreen(
    onConnect: (TunnelProfile) -> Unit,
    onDisconnect: () -> Unit
) {
    val connectionState by TunnelStateHolder.connectionState.collectAsState()
    val dataTransferred by TunnelStateHolder.dataTransferred.collectAsState()

    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var sni by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }

    val isEstablished = connectionState is TunnelConnectionState.TunnelEstablished

    val isBusy = connectionState is TunnelConnectionState.Connecting ||
        connectionState is TunnelConnectionState.Authenticating ||
        connectionState is TunnelConnectionState.Reconnecting

    val statusText = when (val state = connectionState) {
        is TunnelConnectionState.Disconnected -> "Disconnected"
        is TunnelConnectionState.Connecting -> "Connecting..."
        is TunnelConnectionState.Authenticating -> "Authenticating..."
        is TunnelConnectionState.TunnelEstablished -> "Tunnel Established"
        is TunnelConnectionState.Reconnecting -> "Reconnecting..."
        is TunnelConnectionState.Error -> "Error: ${state.message}"
    }

    val statusColor = when (connectionState) {
        is TunnelConnectionState.TunnelEstablished -> MaterialTheme.colorScheme.primary
        is TunnelConnectionState.Error -> MaterialTheme.colorScheme.error
        is TunnelConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.secondary
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tunnel Console",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Connection Status",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = statusColor
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Data transferred: ${formatBytes(dataTransferred)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("Server IP / Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = sni,
                onValueChange = { sni = it },
                label = { Text("SNI Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                label = { Text("Custom Payload") },
                placeholder = {
                    Text("GET / HTTP/1.1[crlf]Host: [host][crlf]...")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                maxLines = 8
            )

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        val profile = TunnelProfile(
                            ip = ip.trim(),
                            port = port.trim(),
                            sni = sni.trim(),
                            payload = payload.trim()
                        )
                        onConnect(profile)
                    },
                    enabled = !isEstablished && !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connect")
                }

                Spacer(modifier = Modifier.width(12.dp))

                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = isEstablished || isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }

    return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
}
