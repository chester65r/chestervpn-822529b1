package com.example.advancedtunnel.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.model.ConnectionMode
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BackgroundDark
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.DestructiveRed
import com.example.advancedtunnel.ui.theme.StarlinkMonoFontFamily
import com.example.advancedtunnel.ui.theme.StarlinkSansFontFamily
import com.example.advancedtunnel.ui.theme.SuccessGreen
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import com.example.advancedtunnel.viewmodel.TunnelViewModel
import com.example.advancedtunnel.wireguard.WireGuardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private fun generateSampleWgConfig(): String {
    val clientKey = com.wireguard.crypto.KeyPair()
    val serverKey = com.wireguard.crypto.KeyPair()
    return """[Interface]
PrivateKey = ${clientKey.privateKey.toBase64()}
Address = 10.0.0.2/24
DNS = 1.1.1.1, 8.8.8.8

[Peer]
PublicKey = ${serverKey.publicKey.toBase64()}
Endpoint = your-wireguard-server.example.com:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
"""
}

@Composable
fun WireGuardConfigCard(
    viewModel: TunnelViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val rawConfigText by viewModel.wireguardConfigText.collectAsState()
    val connectionMode by viewModel.connectionMode.collectAsState()
    val parsedConfig by viewModel.wireguardParsedConfig.collectAsState()
    val wgStats by viewModel.wireguardStats.collectAsState()

    var configInput by remember(rawConfigText) { mutableStateOf(rawConfigText) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // File picker launcher using Android Storage Access Framework (SAF)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            BufferedReader(InputStreamReader(stream)).readText()
                        }
                    }
                    if (!content.isNullOrBlank()) {
                        configInput = content
                        val res = viewModel.setWireGuardConfig(content)
                        if (res.isSuccess) {
                            validationError = null
                            Toast.makeText(context, "WireGuard .conf loaded successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            validationError = res.exceptionOrNull()?.localizedMessage
                        }
                    }
                } catch (e: Exception) {
                    validationError = "Failed to read file: ${e.localizedMessage}"
                }
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("wireguard_config_card"),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "WIREGUARD CONFIGURATION (.CONF)",
                            fontFamily = StarlinkMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.6.sp,
                            color = CyanPrimary
                        )
                        Text(
                            text = "Official GoBackend Layer 3 Tunnel • wg-quick syntax",
                            fontFamily = StarlinkSansFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                if (connectionMode != ConnectionMode.WIREGUARD) {
                    OutlinedButton(
                        onClick = { viewModel.setConnectionMode(ConnectionMode.WIREGUARD) },
                        border = BorderStroke(1.dp, CyanPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = "SET ACTIVE",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary
                        )
                    }
                }
            }

            // Quick Import Buttons (Paste, Import File, Sample Template)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).text?.toString() ?: ""
                            if (text.isNotBlank()) {
                                configInput = text
                                val res = viewModel.setWireGuardConfig(text)
                                validationError = res.exceptionOrNull()?.localizedMessage
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Pasted and validated .conf", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, BorderDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "PASTE",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, BorderDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "IMPORT FILE",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                OutlinedButton(
                    onClick = {
                        val sample = generateSampleWgConfig()
                        configInput = sample
                        val res = viewModel.setWireGuardConfig(sample)
                        validationError = res.exceptionOrNull()?.localizedMessage
                    },
                    modifier = Modifier.weight(1.1f),
                    border = BorderStroke(1.dp, BorderDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "TEMPLATE",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }
            }

            // Monospace Config Text Field
            OutlinedTextField(
                value = configInput,
                onValueChange = { newVal ->
                    configInput = newVal
                    val res = viewModel.setWireGuardConfig(newVal)
                    validationError = res.exceptionOrNull()?.localizedMessage
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .testTag("wireguard_conf_input"),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = StarlinkMonoFontFamily,
                    fontSize = 11.sp,
                    color = TextPrimary,
                    lineHeight = 16.sp
                ),
                placeholder = {
                    Text(
                        text = "[Interface]\nPrivateKey = ...\nAddress = 10.0.0.2/24\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = ...\nEndpoint = server:51820\nAllowedIPs = 0.0.0.0/0",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BackgroundDark,
                    unfocusedContainerColor = BackgroundDark,
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = BorderDark,
                    cursorColor = CyanPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            )

            // Validation & Parsed Config Readout
            if (validationError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DestructiveRed.copy(alpha = 0.12f))
                        .border(1.dp, DestructiveRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = DestructiveRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = validationError ?: "Syntax error in .conf file",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        color = DestructiveRed
                    )
                }
            } else if (parsedConfig != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark)
                        .border(1.dp, SuccessGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "PARSED WG-QUICK CONFIG (VALID)",
                                fontFamily = StarlinkMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 1.4.sp,
                                color = SuccessGreen
                            )
                        }
                        Text(
                            text = "GOBACKEND READY",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary
                        )
                    }

                    HorizontalDivider(color = BorderDark, thickness = 1.dp)

                    Text(
                        text = "[Interface] Address: ${wgStats.interfaceAddresses.ifBlank { "Unset" }} | DNS: ${wgStats.dnsServers.ifBlank { "Unset" }}${wgStats.mtu?.let { " | MTU: $it" } ?: ""}",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        color = TextPrimary
                    )

                    Text(
                        text = "[Peer] Endpoint: ${wgStats.peerEndpoint.ifBlank { "Unset" }} | AllowedIPs: ${wgStats.allowedIps.ifBlank { "Unset" }}${wgStats.persistentKeepalive?.let { " | Keepalive: ${it}s" } ?: ""}${if (wgStats.hasPresharedKey) " | PSK: Enabled" else ""}",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        color = TextSecondary
                    )

                    if (wgStats.interfacePublicKey.isNotBlank()) {
                        Text(
                            text = "Client Public Key: ${wgStats.interfacePublicKey}",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 9.sp,
                            color = TextMuted
                        )
                    }

                    if (wgStats.peerPublicKey.isNotBlank()) {
                        Text(
                            text = "Peer Public Key: ${wgStats.peerPublicKey}",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 9.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}
