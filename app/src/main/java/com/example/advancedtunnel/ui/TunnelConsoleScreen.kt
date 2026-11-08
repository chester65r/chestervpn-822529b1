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
    onDisconnect: () -> Unit,
    devicePublicKey: String = "",
    wireGuardStatus: String = "DOWN (Idle)",
    onGenerateDeviceIdentity: () -> Unit = {},
    onSaveWireGuardEnrollment: (String, String) -> Unit = { _, _ -> },
    onToggleWireGuard: () -> Unit = {},
    onClearWireGuardCredentials: () -> Unit = {}
) {
    val connectionState by TunnelStateHolder.connectionState.collectAsState()
    val reconnectPhase by TunnelStateHolder.reconnectPhase.collectAsState()
    val dataTransferred by TunnelStateHolder.dataTransferred.collectAsState()

    var ip by remember { mutableStateOf(com.example.advancedtunnel.model.HETZNER_SERVER_IP) }
    var port by remember { mutableStateOf("443") }
    var sni by remember { mutableStateOf("static.194.8.29.2.clients.your-server.de") }
    var payload by remember { mutableStateOf(com.example.advancedtunnel.model.DEFAULT_PAYLOAD_TEMPLATE) }

    var trustedServerKey by remember { mutableStateOf("") }
    var wgEnrollmentResponse by remember { mutableStateOf("") }

    val isEstablished = connectionState is TunnelConnectionState.TunnelEstablished

    val isBusy = connectionState is TunnelConnectionState.Connecting ||
        connectionState is TunnelConnectionState.Authenticating ||
        connectionState is TunnelConnectionState.Reconnecting

    val statusText = when (val state = connectionState) {
        is TunnelConnectionState.Disconnected -> "Disconnected"
        is TunnelConnectionState.Connecting -> "Connecting..."
        is TunnelConnectionState.Authenticating -> "Authenticating..."
        is TunnelConnectionState.TunnelEstablished -> "Tunnel Established"
        is TunnelConnectionState.Reconnecting -> reconnectPhase ?: "Reconnecting..."
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
                        text = "Tunnel Console — ahmed-docker-manager",
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
                        text = "Hetzner CX23 (2.29.8.194 · Helsinki eu-central)",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        ip = com.example.advancedtunnel.model.HETZNER_SERVER_IP
                        port = "443"
                        sni = com.example.advancedtunnel.model.HETZNER_SERVER_IP
                        payload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]X-Tunnel-Profile: hetzner-tls-direct[crlf]"
                    },
                    enabled = !isEstablished && !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Direct TLS 443")
                }

                OutlinedButton(
                    onClick = {
                        ip = com.example.advancedtunnel.model.HETZNER_SERVER_IP
                        port = "443"
                        sni = com.example.advancedtunnel.model.HETZNER_SERVER_IP
                        payload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade, Keep-Alive[crlf]Upgrade: websocket[crlf]X-Tunnel-MTU: 1280[crlf]X-Tunnel-Profile: telecom-mobile-cgnat[crlf]"
                    },
                    enabled = !isEstablished && !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Mobile CGNAT")
                }

                OutlinedButton(
                    onClick = {
                        ip = com.example.advancedtunnel.model.HETZNER_SERVER_IP
                        port = "443"
                        sni = "static.194.8.29.2.clients.your-server.de"
                        payload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]X-Tunnel-Profile: enterprise-firewall-443[crlf]"
                    },
                    enabled = !isEstablished && !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("rDNS SNI")
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
                    .height(140.dp),
                maxLines = 6
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
                    Text("Connect TLS/WS")
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

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "WireGuard GoBackend + Keystore (2.29.8.194:51820)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Engine Status: $wireGuardStatus",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedButton(
                        onClick = onGenerateDeviceIdentity,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. Generate Device Public Key (Android Keystore)")
                    }

                    if (devicePublicKey.isNotBlank()) {
                        OutlinedTextField(
                            value = devicePublicKey,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Device Public Key (enroll with peer-admin.py)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = trustedServerKey,
                        onValueChange = { trustedServerKey = it },
                        label = { Text("Server Public Key (/etc/wireguard/server.pub)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = wgEnrollmentResponse,
                        onValueChange = { wgEnrollmentResponse = it },
                        label = { Text("Enrollment Config (from peer-admin.py)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 6
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onSaveWireGuardEnrollment(wgEnrollmentResponse, trustedServerKey)
                                wgEnrollmentResponse = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Enrollment")
                        }

                        Button(
                            onClick = onToggleWireGuard,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start / Stop WG")
                        }
                    }

                    OutlinedButton(
                        onClick = onClearWireGuardCredentials,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Keystore Credentials")
                    }
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
