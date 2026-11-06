package com.example.advancedtunnel.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.advancedtunnel.ui.findActivity
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.DestructiveRed
import com.example.advancedtunnel.ui.theme.SuccessGreen
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.SurfaceVariantDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import com.example.advancedtunnel.viewmodel.TunnelViewModel

@Composable
fun ServersScreen(
    viewModel: TunnelViewModel,
    onServerApplied: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val servers by viewModel.servers.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val serverLatencies by viewModel.serverLatencies.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val isAuthLoading by viewModel.isAuthLoading.collectAsState()

    val savedProfiles by viewModel.savedProfiles.collectAsState()
    val exportedCode by viewModel.exportedCode.collectAsState()
    val exportedVaultCode by viewModel.exportedVaultCode.collectAsState()
    val hasKeystoreVaultBackup by viewModel.hasKeystoreVaultBackup.collectAsState()
    val edgeProbeResults by viewModel.edgeProbeResults.collectAsState()
    val isScanningEdges by viewModel.isScanningEdges.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Relay Nodes, 1 = Vault & Profiles
    var showAddServerForm by remember { mutableStateOf(false) }

    var newName by remember { mutableStateOf("") }
    var newHost by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("443") }
    var newTransport by remember { mutableStateOf("TLS") }
    var newNotes by remember { mutableStateOf("") }

    var profileName by remember { mutableStateOf("") }
    var importCodeText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var showQrScannerDialog by remember { mutableStateOf(false) }

    var vaultPassphrase by remember { mutableStateOf("") }
    var vaultImportInput by remember { mutableStateOf("") }
    var vaultStatusMessage by remember { mutableStateOf<String?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            showQrScannerDialog = true
        } else {
            Toast.makeText(context, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top 2-Section Switcher (Relay Nodes | Vault & Profiles)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBackground)
                .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                },
                label = {
                    Text(
                        text = "Relay Nodes (${servers.size})",
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanPrimary,
                    selectedLabelColor = SurfaceDark,
                    selectedLeadingIconColor = SurfaceDark,
                    containerColor = SurfaceDark,
                    labelColor = TextSecondary
                ),
                border = null,
                modifier = Modifier
                    .weight(1f)
                    .testTag("nodes_subtab_servers")
            )

            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                },
                label = {
                    Text(
                        text = "Vault & Share",
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanPrimary,
                    selectedLabelColor = SurfaceDark,
                    selectedLeadingIconColor = SurfaceDark,
                    containerColor = SurfaceDark,
                    labelColor = TextSecondary
                ),
                border = null,
                modifier = Modifier
                    .weight(1f)
                    .testTag("nodes_subtab_vault")
            )
        }

        if (selectedTab == 0) {
            // TAB 0: RELAY NODES & CLOUD SYNC
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AVAILABLE RELAY ENDPOINTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 0.8.sp
                )

                Button(
                    onClick = { showAddServerForm = !showAddServerForm },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showAddServerForm) SurfaceVariantDark else CyanPrimary,
                        contentColor = if (showAddServerForm) TextPrimary else SurfaceDark
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("toggle_add_server_button")
                ) {
                    Icon(
                        imageVector = if (showAddServerForm) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showAddServerForm) "Close" else "Add Node",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Collapsible Add Server Form
            if (showAddServerForm) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_server_form_card"),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "ADD CUSTOM RELAY SERVER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 0.8.sp
                        )

                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("SERVER NAME", fontSize = 10.sp) },
                            placeholder = { Text("Frankfurt VPS Node") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_server_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = newHost,
                                onValueChange = { newHost = it },
                                label = { Text("HOST / IP", fontSize = 10.sp) },
                                placeholder = { Text("relay.example.com") },
                                modifier = Modifier
                                    .weight(0.7f)
                                    .testTag("add_server_host_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedContainerColor = SurfaceDark,
                                    unfocusedContainerColor = SurfaceDark
                                ),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = newPort,
                                onValueChange = { newPort = it },
                                label = { Text("PORT", fontSize = 10.sp) },
                                placeholder = { Text("443") },
                                modifier = Modifier
                                    .weight(0.3f)
                                    .testTag("add_server_port_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedContainerColor = SurfaceDark,
                                    unfocusedContainerColor = SurfaceDark
                                ),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }

                        Button(
                            onClick = {
                                val added = viewModel.addServer(newName, newHost, newPort, newTransport, newNotes)
                                if (added) {
                                    Toast.makeText(context, "Added $newName", Toast.LENGTH_SHORT).show()
                                    newName = ""
                                    newHost = ""
                                    newPort = "443"
                                    newNotes = ""
                                    showAddServerForm = false
                                } else {
                                    Toast.makeText(context, "Enter a valid name, host, and port", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("submit_add_server_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Relay Node", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Unified Server Nodes List
            servers.forEach { server ->
                val isSelected = profile.ip == server.host
                val lat = serverLatencies[server.id]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.applyServer(server)
                            Toast.makeText(context, "Selected ${server.name}", Toast.LENGTH_SHORT).show()
                            onServerApplied()
                        }
                        .testTag("relay_server_${server.id}"),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) CyanPrimary else BorderDark
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) CyanPrimary.copy(alpha = 0.2f) else SurfaceDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = if (isSelected) CyanPrimary else TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = server.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    if (lat != null) {
                                        Text(
                                            text = "${lat}ms",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = SuccessGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = "${server.host}:${server.port} • ${server.transport}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    viewModel.applyServer(server)
                                    Toast.makeText(context, "Selected ${server.name}", Toast.LENGTH_SHORT).show()
                                    onServerApplied()
                                },
                                modifier = Modifier.testTag("apply_sample_${server.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Select server",
                                    tint = CyanPrimary
                                )
                            }
                            IconButton(
                                onClick = {
                                    viewModel.deleteServer(server.id)
                                    Toast.makeText(context, "Removed ${server.name}", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete server",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Edge Endpoint Latency & Availability Scanner Card (443 / 80 / 8080)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edge_hunter_scanner_card"),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "EDGE ENDPOINT SCANNER (443 / 80 / 8080)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Concurrent HTTP HEAD/TLS probe across CDN edge IPs",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        Button(
                            onClick = { viewModel.runEdgeHunterScan("HEAD") },
                            enabled = !isScanningEdges,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("run_edge_hunter_button")
                        ) {
                            if (isScanningEdges) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = SurfaceDark,
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scanning...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("Scan Edges", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (edgeProbeResults.isNotEmpty()) {
                        val topResults = edgeProbeResults.take(6)
                        topResults.forEach { probe ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceDark)
                                    .clickable(enabled = probe.reachable) {
                                        viewModel.applyEdgeProbeResult(probe)
                                        Toast.makeText(
                                            context,
                                            "Applied ${probe.hostOrIp}:${probe.port}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onServerApplied()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${probe.label} • ${probe.hostOrIp}:${probe.port}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = if (probe.reachable) {
                                            "HTTP ${probe.httpStatusCode} (${probe.statusLine.ifBlank { "OK" }}) • TCP ${probe.tcpConnectMs}ms"
                                        } else {
                                            "Unreachable (${probe.statusLine.ifBlank { "Timeout" }})"
                                        },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = if (probe.reachable) TextSecondary else DestructiveRed
                                    )
                                }
                                Text(
                                    text = if (probe.reachable) "${probe.totalRttMs}ms" else "TIMEOUT",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (probe.reachable) SuccessGreen else DestructiveRed
                                )
                            }
                        }
                    }
                }
            }

            // Compact Cloud Sync Card (Firebase Auth & Firestore)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_account_card"),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (currentUser != null) {
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
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = "Cloud Connected",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = currentUser?.email ?: "Signed in",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Cloud Relay Sync Active",
                                        fontSize = 11.sp,
                                        color = SuccessGreen
                                    )
                                }
                            }

                            Button(
                                onClick = { viewModel.signOut() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceDark,
                                    contentColor = TextSecondary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = "Sign out",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sign out", fontSize = 11.sp)
                            }
                        }
                    } else {
                        Column {
                            Text(
                                text = "CLOUD ACCOUNT SYNC",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Sign in to sync your custom relay nodes across devices.",
                                fontSize = 11.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.signInWithGoogle(context.findActivity() ?: context) { success, error ->
                                            if (success) {
                                                Toast.makeText(context, "Signed in with Google", Toast.LENGTH_SHORT).show()
                                            } else if (error != null) {
                                                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    enabled = !isAuthLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanPrimary,
                                        contentColor = SurfaceDark
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sign_in_google_button")
                                ) {
                                    if (isAuthLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = SurfaceDark,
                                            strokeWidth = 1.5.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Login,
                                            contentDescription = "Google Sign In",
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Google", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        val activity = context.findActivity()
                                        if (activity != null) {
                                            viewModel.signInWithMicrosoft(activity) { success, error ->
                                                if (success) {
                                                    Toast.makeText(context, "Signed in with Microsoft", Toast.LENGTH_SHORT).show()
                                                } else if (error != null) {
                                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isAuthLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SurfaceVariantDark,
                                        contentColor = TextPrimary
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sign_in_microsoft_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Work,
                                        contentDescription = "Microsoft Sign In",
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Microsoft", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // TAB 1: VAULT, QUICK SHARE & SAVED PROFILES

            // 1. Quick Share (TUNNEL:// Export, Import & QR Scan)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "QUICK PROFILE SHARE (TUNNEL://)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Copy, paste, or scan a TUNNEL:// configuration URI",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        Button(
                            onClick = {
                                val code = viewModel.exportCurrentProfile()
                                if (code != null) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Tunnel Profile", code))
                                    Toast.makeText(context, "TUNNEL:// code copied", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Set a valid server IP first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("export_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy URI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (exportedCode != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = exportedCode!!,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = CyanPrimary,
                                maxLines = 2
                            )
                        }
                    }

                    OutlinedTextField(
                        value = importCodeText,
                        onValueChange = {
                            importCodeText = it
                            importError = null
                        },
                        placeholder = { Text("Paste TUNNEL://eyJpcCI6...", fontSize = 11.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_code_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = BorderDark,
                            focusedContainerColor = SurfaceDark,
                            unfocusedContainerColor = SurfaceDark
                        ),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                    )

                    if (importError != null) {
                        Text(text = importError!!, color = DestructiveRed, fontSize = 11.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (hasCameraPermission) {
                                    showQrScannerDialog = true
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("scan_qr_code_button"),
                            border = BorderStroke(1.dp, CyanPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan QR", fontSize = 12.sp, color = CyanPrimary)
                        }

                        Button(
                            onClick = {
                                val success = viewModel.importProfileCode(importCodeText)
                                if (success) {
                                    Toast.makeText(context, "Profile imported!", Toast.LENGTH_SHORT).show()
                                    importCodeText = ""
                                    onServerApplied()
                                } else {
                                    importError = "Invalid TUNNEL:// profile string."
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("submit_import_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceVariantDark,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import URI", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 2. AES-256-GCM Encrypted Vault Card (TCFG:// + Android Keystore)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("encrypted_vault_card"),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AES-256-GCM ENCRYPTED VAULT (TCFG://)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = if (hasKeystoreVaultBackup) "KEYSTORE BACKED" else "READY",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }

                    OutlinedTextField(
                        value = vaultPassphrase,
                        onValueChange = {
                            vaultPassphrase = it
                            vaultStatusMessage = null
                        },
                        placeholder = { Text("Vault passphrase (min 4 chars)", fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_passphrase_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = BorderDark,
                            focusedContainerColor = SurfaceDark,
                            unfocusedContainerColor = SurfaceDark
                        ),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val code = viewModel.exportEncryptedVault(vaultPassphrase)
                                if (code != null) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Encrypted TCFG Vault", code))
                                    vaultStatusMessage = "Encrypted TCFG:// bundle copied & backed up to Keystore."
                                    Toast.makeText(context, "Encrypted TCFG:// bundle copied", Toast.LENGTH_SHORT).show()
                                } else {
                                    vaultStatusMessage = "Enter a passphrase of at least 4 characters."
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("export_encrypted_vault_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Encrypt & Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        if (hasKeystoreVaultBackup) {
                            OutlinedButton(
                                onClick = {
                                    val res = viewModel.restoreFromKeystoreVault()
                                    vaultStatusMessage = res.getOrElse { "Restore failed: ${it.localizedMessage}" }
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "Restored from Android Keystore", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .height(42.dp)
                                    .testTag("restore_keystore_vault_button"),
                                border = BorderStroke(1.dp, CyanPrimary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Restore Keystore", fontSize = 11.sp, color = CyanPrimary)
                            }
                        }
                    }

                    if (exportedVaultCode != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = exportedVaultCode!!,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = CyanPrimary,
                                maxLines = 3
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = vaultImportInput,
                            onValueChange = {
                                vaultImportInput = it
                                vaultStatusMessage = null
                            },
                            placeholder = { Text("Paste TCFG:// bundle", fontSize = 11.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_vault_code_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        )

                        Button(
                            onClick = {
                                val res = viewModel.importEncryptedVault(vaultImportInput, vaultPassphrase)
                                if (res.isSuccess) {
                                    vaultStatusMessage = res.getOrNull()
                                    vaultImportInput = ""
                                    Toast.makeText(context, "Vault decrypted!", Toast.LENGTH_SHORT).show()
                                    onServerApplied()
                                } else {
                                    vaultStatusMessage = "Decryption failed: check passphrase."
                                }
                            },
                            enabled = vaultImportInput.isNotBlank() && vaultPassphrase.isNotBlank(),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("decrypt_import_vault_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceVariantDark,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Decrypt", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (vaultStatusMessage != null) {
                        Text(
                            text = vaultStatusMessage!!,
                            fontSize = 11.sp,
                            color = if (vaultStatusMessage!!.startsWith("Decryption failed") || vaultStatusMessage!!.startsWith("Enter")) DestructiveRed else CyanPrimary
                        )
                    }
                }
            }

            // 3. Saved Profiles Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "SAVED LOCAL PROFILES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary,
                        letterSpacing = 0.8.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            placeholder = { Text("Profile name (e.g. Fast Node)", fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_profile_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                val ok = viewModel.saveCurrentProfile(profileName)
                                if (ok) {
                                    Toast.makeText(context, "Saved profile", Toast.LENGTH_SHORT).show()
                                    profileName = ""
                                } else {
                                    Toast.makeText(context, "Set a valid server IP first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("save_profile_button")
                        ) {
                            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }

                    savedProfiles.forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${s.profile.ip}:${s.profile.port} • ${s.profile.sni}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }

                            Row {
                                IconButton(
                                    onClick = {
                                        viewModel.loadSavedProfile(s.id)
                                        Toast.makeText(context, "Loaded \"${s.name}\"", Toast.LENGTH_SHORT).show()
                                        onServerApplied()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Upload,
                                        contentDescription = "Load profile",
                                        tint = CyanPrimary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.deleteSavedProfile(s.id)
                                        Toast.makeText(context, "Deleted \"${s.name}\"", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete profile",
                                        tint = DestructiveRed
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showQrScannerDialog) {
            Dialog(onDismissRequest = { showQrScannerDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "QR PROFILE SCANNER",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            IconButton(onClick = { showQrScannerDialog = false }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(190.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark)
                                .border(2.dp, CyanPrimary, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(42.dp)
                                )
                                Text(
                                    text = "Point camera at TUNNEL:// QR Code",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        var qrScannedText by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = qrScannedText,
                            onValueChange = { qrScannedText = it },
                            placeholder = { Text("TUNNEL://...", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showQrScannerDialog = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Cancel", color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    val ok = viewModel.importProfileCode(qrScannedText)
                                    if (ok) {
                                        Toast.makeText(context, "QR Profile imported!", Toast.LENGTH_SHORT).show()
                                        showQrScannerDialog = false
                                        onServerApplied()
                                    } else {
                                        Toast.makeText(context, "Invalid TUNNEL:// payload", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = SurfaceDark),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Import", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
