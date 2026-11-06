package com.example.advancedtunnel.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.model.TunnelUtils
import com.example.advancedtunnel.ui.components.SpeedGraphCanvas
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.DestructiveRed
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.SurfaceVariantDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import com.example.advancedtunnel.viewmodel.TunnelViewModel
import com.example.advancedtunnel.vpn.RealVpnState

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun HomeScreen(
    viewModel: TunnelViewModel,
    onRequestVpnPermission: () -> Unit,
    onNavigateToConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vpnState by viewModel.vpnState.collectAsState()
    val status by viewModel.status.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val errors by viewModel.errors.collectAsState()
    val downHistory by viewModel.downHistory.collectAsState()
    val upHistory by viewModel.upHistory.collectAsState()
    val realDownSpeed by viewModel.realDownSpeed.collectAsState()
    val realUpSpeed by viewModel.realUpSpeed.collectAsState()
    val realRxBytes by viewModel.realRxBytes.collectAsState()
    val realTxBytes by viewModel.realTxBytes.collectAsState()
    val uptimeSeconds by viewModel.uptimeSeconds.collectAsState()
    val latencyMs by viewModel.serverLatencyMs.collectAsState()
    val aiText by viewModel.aiHelperText.collectAsState()
    val isAnalyzingAi by viewModel.isAnalyzingAi.collectAsState()

    var hetznerIpInput by remember(profile.ip) { mutableStateOf(profile.ip) }
    var hetznerPortInput by remember(profile.port) { mutableStateOf(profile.port.ifEmpty { "443" }) }
    var showSetupScript by remember { mutableStateOf(false) }

    val isConnected = status == TunnelStatus.CONNECTED
    val isConnecting = status == TunnelStatus.CONNECTING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Hero Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("status_hero_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isConnected) CyanPrimary.copy(alpha = 0.6f) else BorderDark
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(CyanPrimary.copy(alpha = pulseAlpha))
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isConnected -> CyanPrimary
                                    isConnecting -> AccentOrange
                                    else -> SurfaceVariantDark
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Tunnel Status",
                            tint = if (isConnected || isConnecting) SurfaceDark else DestructiveRed,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = when (val s = vpnState) {
                        is RealVpnState.Connected -> "CONNECTED"
                        is RealVpnState.Connecting -> "CONNECTING…"
                        is RealVpnState.Reconnecting -> "RECONNECTING…"
                        is RealVpnState.Failed -> "FAILED"
                        is RealVpnState.Disconnected -> "DISCONNECTED"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = 2.sp,
                    color = when {
                        isConnected -> CyanPrimary
                        isConnecting -> AccentOrange
                        else -> DestructiveRed
                    }
                )

                if (vpnState is RealVpnState.Failed) {
                    Text(
                        text = (vpnState as RealVpnState.Failed).error,
                        color = DestructiveRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        text = if (profile.ip.isNotBlank()) "${profile.ip}:${profile.port}" else "No server configured",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Session duration",
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = TunnelUtils.formatDuration(uptimeSeconds),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    if (latencyMs != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = "Ping",
                                tint = CyanPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "${latencyMs}ms",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = CyanPrimary
                            )
                        }
                    }
                }
            }
        }

        // Real Telemetry Speed Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Real Download Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .testTag("download_stat_card"),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Download",
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "DOWNLOAD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = TunnelUtils.formatSpeed(realDownSpeed),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = CyanPrimary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        text = "${TunnelUtils.formatBytes(realRxBytes.toDouble())} total",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Real Upload Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .testTag("upload_stat_card"),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Upload",
                            tint = AccentOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "UPLOAD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = TunnelUtils.formatSpeed(realUpSpeed),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = AccentOrange,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        text = "${TunnelUtils.formatBytes(realTxBytes.toDouble())} total",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // Real Bandwidth Graph
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NETWORK THROUGHPUT (INTERFACE TUN)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )

                    Button(
                        onClick = { viewModel.pingServer() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = CyanPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Probe Ping", fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                SpeedGraphCanvas(
                    downHistory = downHistory,
                    upHistory = upHistory,
                    active = isConnected
                )
            }
        }

        // Validation Errors Banner
        if (errors.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("validation_errors_banner"),
                colors = CardDefaults.cardColors(containerColor = DestructiveRed.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DestructiveRed)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Configuration required",
                        tint = DestructiveRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Server Configuration Needed",
                            color = DestructiveRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        errors.values.forEach { errMsg ->
                            Text(
                                text = "• $errMsg",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Hetzner Cloud VPS Quick Connect Card
        if (!isConnected) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hetzner_quick_connect_card"),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (hetznerIpInput.isNotBlank()) CyanPrimary.copy(alpha = 0.5f) else BorderDark
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                                contentDescription = "Hetzner Cloud",
                                tint = CyanPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "HETZNER CLOUD VPS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = CyanPrimary
                            )
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceDark)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Germany / Finland / US",
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Text(
                        text = "Connect directly to your Hetzner Cloud VPS instance. Enter your server IPv4 or select a datacenter preset:",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )

                    // Datacenter quick preset chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Triple("FSN1", "Falkenstein", "fsn1.hetzner.com"),
                            Triple("HEL1", "Helsinki", "hel1.hetzner.com"),
                            Triple("ASH", "Ashburn", "ash.hetzner.com")
                        ).forEach { (code, city, host) ->
                            Button(
                                onClick = {
                                    hetznerIpInput = host
                                    viewModel.updateProfile(ip = host, sni = host)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hetznerIpInput == host) CyanPrimary else SurfaceDark,
                                    contentColor = if (hetznerIpInput == host) SurfaceDark else TextSecondary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                            ) {
                                Text("$code ($city)", fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = hetznerIpInput,
                            onValueChange = {
                                hetznerIpInput = it
                                viewModel.updateProfile(ip = it, sni = if (it.contains(".")) it else profile.sni)
                            },
                            label = { Text("Hetzner IP / Domain") },
                            placeholder = { Text("e.g. 159.69.10.20") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(2f)
                                .testTag("hetzner_ip_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        OutlinedTextField(
                            value = hetznerPortInput,
                            onValueChange = {
                                hetznerPortInput = it
                                viewModel.updateProfile(port = it)
                            },
                            label = { Text("Port") },
                            placeholder = { Text("443") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hetzner_port_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(
                                    "Hetzner Setup Script",
                                    "curl -fsSL https://raw.githubusercontent.com/chester65r/chestervpn-822529b1/main/docs/setup-server.sh | bash"
                                )
                                cm.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied Hetzner VPS installer script to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceDark,
                                contentColor = TextSecondary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("copy_setup_command_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("VPS Setup Script", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                viewModel.connectHetznerServer(
                                    ip = hetznerIpInput,
                                    port = hetznerPortInput,
                                    sni = if (hetznerIpInput.contains(".")) hetznerIpInput else "hetzner.cloud",
                                    context = context,
                                    onRequestPermission = onRequestVpnPermission
                                )
                            },
                            enabled = hetznerIpInput.isNotBlank() && !isConnecting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("connect_hetzner_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Connect Hetzner", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Action Connect / Disconnect Button (Genuine VPNService)
        Button(
            onClick = {
                if (isConnected) {
                    viewModel.disconnectVpn(context)
                } else {
                    viewModel.requestConnectVpn(context, onRequestVpnPermission)
                }
            },
            enabled = !isConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("toggle_tunnel_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) DestructiveRed else CyanPrimary,
                contentColor = if (isConnected) TextPrimary else SurfaceDark
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SurfaceDark,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "ESTABLISHING VPN…",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (isConnected) "DISCONNECT VPN" else "START VPN TUNNEL",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // Gemini AI Connection Advisor
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gemini_ai_helper_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
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
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Gemini AI",
                            tint = CyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "GEMINI AI ADVISOR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.sp
                        )
                    }

                    Button(
                        onClick = { viewModel.runAiDiagnostics() },
                        enabled = !isAnalyzingAi,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariantDark,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("ai_explain_button")
                    ) {
                        if (isAnalyzingAi) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = CyanPrimary,
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Analyzing…", fontSize = 12.sp)
                        } else {
                            Text("Diagnose", fontSize = 12.sp)
                        }
                    }
                }

                if (aiText != null) {
                    Text(
                        text = aiText!!,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = TextPrimary,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark)
                            .padding(10.dp)
                    )
                } else {
                    Text(
                        text = "Diagnoses your real VPN endpoint, TLS handshake validity, packet routing efficiency, and latency using Gemini 3.8 Flash via Firebase AI Logic.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
