package com.example.advancedtunnel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.model.SAMPLE_VPS_SERVERS
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.model.TunnelUtils
import com.example.advancedtunnel.ui.components.ServerNodeSelectorCard
import com.example.advancedtunnel.ui.components.SpeedGraphCanvas
import com.example.advancedtunnel.ui.theme.AccentOrange
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
import com.example.advancedtunnel.vpn.RealVpnState
import com.example.advancedtunnel.vpn.TlsFingerprintEngine

@Composable
fun HomeScreen(
    viewModel: TunnelViewModel,
    onRequestVpnPermission: () -> Unit,
    onNavigateToConfig: () -> Unit,
    onNavigateToServers: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vpnState by viewModel.vpnState.collectAsState()
    val status by viewModel.status.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val tlsConfig by viewModel.tlsFragmentConfig.collectAsState()
    val errors by viewModel.errors.collectAsState()
    val downHistory by viewModel.downHistory.collectAsState()
    val upHistory by viewModel.upHistory.collectAsState()
    val realDownSpeed by viewModel.realDownSpeed.collectAsState()
    val realUpSpeed by viewModel.realUpSpeed.collectAsState()
    val peakDown by viewModel.peakDownSpeed.collectAsState()
    val peakUp by viewModel.peakUpSpeed.collectAsState()
    val realRxBytes by viewModel.realRxBytes.collectAsState()
    val realTxBytes by viewModel.realTxBytes.collectAsState()
    val uptimeSeconds by viewModel.uptimeSeconds.collectAsState()
    val latencyMs by viewModel.serverLatencyMs.collectAsState()
    val aiText by viewModel.aiHelperText.collectAsState()
    val isAnalyzingAi by viewModel.isAnalyzingAi.collectAsState()
    val telemetryLogs by viewModel.telemetryLogs.collectAsState()

    val isConnected = status == TunnelStatus.CONNECTED
    val isConnecting = status == TunnelStatus.CONNECTING

    var activeBottomSection by remember { mutableIntStateOf(0) } // 0 = Event Log, 1 = AI Advisor

    val activeBrowserProfile = remember(tlsConfig.fingerprintProfileId) {
        TlsFingerprintEngine.resolveProfile(tlsConfig.fingerprintProfileId)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Connection & 1-Tap Control Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("status_hero_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(
                1.dp,
                when {
                    isConnected -> SuccessGreen.copy(alpha = 0.7f)
                    isConnecting -> AccentOrange.copy(alpha = 0.6f)
                    else -> BorderDark
                }
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                if (isConnected) SuccessGreen.copy(alpha = 0.12f)
                                else CyanPrimary.copy(alpha = 0.08f),
                                CardBackground
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Quick Info Bar inside Hero
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = if (tlsConfig.enabled) CyanPrimary else TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (tlsConfig.enabled) {
                                    "${activeBrowserProfile.displayName.substringBefore(" (")} • ${tlsConfig.normalizedMinChunk()}-${tlsConfig.normalizedMaxChunk()}B"
                                } else {
                                    "Standard TLS"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
                                .clickable { onNavigateToConfig() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Configure",
                                tint = CyanPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Tune",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Interactive Glowing Power Orb
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clickable(enabled = !isConnecting) {
                                if (isConnected) {
                                    viewModel.disconnectVpn(context)
                                } else {
                                    viewModel.requestConnectVpn(context, onRequestVpnPermission)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isConnected || isConnecting) {
                            Box(
                                modifier = Modifier
                                    .size(104.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(
                                        (if (isConnected) SuccessGreen else AccentOrange).copy(alpha = pulseAlpha)
                                    )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isConnected -> SuccessGreen
                                        isConnecting -> AccentOrange
                                        else -> SurfaceDark
                                    }
                                )
                                .border(
                                    width = 2.dp,
                                    color = when {
                                        isConnected -> SuccessGreen
                                        isConnecting -> AccentOrange
                                        else -> CyanPrimary.copy(alpha = 0.7f)
                                    },
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(34.dp),
                                    color = SurfaceDark,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.Security else Icons.Default.PowerSettingsNew,
                                    contentDescription = "Toggle VPN",
                                    tint = if (isConnected) SurfaceDark else CyanPrimary,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Connection Status Title
                    Text(
                        text = when (vpnState) {
                            is RealVpnState.Connected -> "PROTECTED & CONNECTED"
                            is RealVpnState.Connecting -> "ESTABLISHING TUNNEL…"
                            is RealVpnState.Reconnecting -> "RECONNECTING…"
                            is RealVpnState.Failed -> "CONNECTION FAILED"
                            is RealVpnState.Disconnected -> "TAP TO CONNECT"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 1.5.sp,
                        color = when {
                            isConnected -> SuccessGreen
                            isConnecting -> AccentOrange
                            vpnState is RealVpnState.Failed -> DestructiveRed
                            else -> TextPrimary
                        }
                    )

                    // Active Server & SNI Subtitle
                    Text(
                        text = if (profile.ip.isNotBlank()) {
                            "${profile.ip}:${profile.port} • SNI: ${profile.sni.ifBlank { profile.ip }}"
                        } else {
                            "Select a server node below or tap Quick Connect"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (vpnState is RealVpnState.Failed) {
                        Text(
                            text = (vpnState as RealVpnState.Failed).error,
                            color = DestructiveRed,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Uptime & Latency Pills
                    Row(
                        modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceDark)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Session duration",
                                tint = TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = TunnelUtils.formatDuration(uptimeSeconds),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceDark)
                                .clickable { viewModel.pingServer() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = "Ping",
                                tint = CyanPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (latencyMs != null) "${latencyMs}ms" else "Ping Test",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = CyanPrimary
                            )
                        }
                    }

                    // Primary 1-Tap Connect / Disconnect Button right inside Hero Card
                    Button(
                        onClick = {
                            if (isConnected) {
                                viewModel.disconnectVpn(context)
                            } else {
                                if (profile.ip.isBlank()) {
                                    // Auto-select first sample node if none selected so 1-tap connect works smoothly
                                    val defaultNode = SAMPLE_VPS_SERVERS.first()
                                    viewModel.selectServerNode(defaultNode)
                                }
                                viewModel.requestConnectVpn(context, onRequestVpnPermission)
                            }
                        },
                        enabled = !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("toggle_tunnel_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) DestructiveRed else CyanPrimary,
                            contentColor = if (isConnected) TextPrimary else SurfaceDark
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isConnecting -> "CONNECTING…"
                                isConnected -> "DISCONNECT TUNNEL"
                                else -> "CONNECT VPN"
                            },
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Inline Validation Banner if needed
        if (errors.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("validation_errors_banner"),
                colors = CardDefaults.cardColors(containerColor = DestructiveRed.copy(alpha = 0.14f)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DestructiveRed)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = DestructiveRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            errors.values.forEach { errMsg ->
                                Text(
                                    text = errMsg,
                                    color = TextPrimary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = onNavigateToConfig,
                        border = BorderStroke(1.dp, CyanPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Fix", fontSize = 11.sp, color = CyanPrimary)
                    }
                }
            }
        }

        // 2. Active Node Switcher & Failover Card
        ServerNodeSelectorCard(viewModel = viewModel)

        // 3. Unified Live Traffic & Throughput Graph Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, BorderDark)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Download Metric Box
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .padding(12.dp)
                            .testTag("download_stat_card")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Download",
                                tint = CyanPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "DOWNLOAD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.8.sp
                            )
                        }
                        Text(
                            text = TunnelUtils.formatSpeed(realDownSpeed),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = CyanPrimary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = "Total: ${TunnelUtils.formatBytes(realRxBytes.toDouble())} • Peak: ${TunnelUtils.formatSpeed(peakDown)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Upload Metric Box
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .padding(12.dp)
                            .testTag("upload_stat_card")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Upload",
                                tint = AccentOrange,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "UPLOAD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.8.sp
                            )
                        }
                        Text(
                            text = TunnelUtils.formatSpeed(realUpSpeed),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = AccentOrange,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = "Total: ${TunnelUtils.formatBytes(realTxBytes.toDouble())} • Peak: ${TunnelUtils.formatSpeed(peakUp)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                SpeedGraphCanvas(
                    downHistory = downHistory,
                    upHistory = upHistory,
                    active = isConnected
                )
            }
        }

        // 4. Unified Tabbed Console & Gemini AI Diagnostics Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("realtime_telemetry_diagnostics_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(18.dp),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = activeBottomSection == 0,
                            onClick = { activeBottomSection = 0 },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = { Text("Event Log (${telemetryLogs.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.18f),
                                selectedLabelColor = CyanPrimary,
                                selectedLeadingIconColor = CyanPrimary,
                                containerColor = SurfaceDark,
                                labelColor = TextSecondary
                            )
                        )

                        FilterChip(
                            selected = activeBottomSection == 1,
                            onClick = { activeBottomSection = 1 },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = { Text("AI Advisor", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.18f),
                                selectedLabelColor = CyanPrimary,
                                selectedLeadingIconColor = CyanPrimary,
                                containerColor = SurfaceDark,
                                labelColor = TextSecondary
                            )
                        )
                    }

                    if (activeBottomSection == 0) {
                        Button(
                            onClick = {
                                val report = viewModel.exportSanitizedDiagnostics()
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Tunnel Diagnostics", report))
                                Toast.makeText(context, "Diagnostics report copied", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceDark,
                                contentColor = CyanPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("export_diagnostics_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Log", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.runAiDiagnostics() },
                            enabled = !isAnalyzingAi,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = SurfaceDark
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("ai_explain_button")
                        ) {
                            if (isAnalyzingAi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = SurfaceDark,
                                    strokeWidth = 1.5.dp
                                )
                            } else {
                                Text("Analyze", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                HorizontalDivider(color = BorderDark.copy(alpha = 0.6f))

                if (activeBottomSection == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val timeFormatter = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }
                        telemetryLogs.take(6).forEach { entry ->
                            val badgeColor = when (entry.level) {
                                "ERROR", "WARN" -> DestructiveRed
                                "SUCCESS", "PROBE", "DESYNC", "JA3/JA4" -> CyanPrimary
                                else -> TextSecondary
                            }
                            Text(
                                text = "[${timeFormatter.format(java.util.Date(entry.timestampMillis))}] [${entry.level}] ${entry.message}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = badgeColor,
                                lineHeight = 14.sp
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .padding(12.dp)
                            .testTag("gemini_ai_helper_card")
                    ) {
                        Text(
                            text = aiText
                                ?: "Tap 'Analyze' to inspect your active endpoint (${profile.ip.ifBlank { "not set" }}), SNI (${profile.sni}), TLS fragmentation, and latency with Gemini AI.",
                            fontSize = 12.sp,
                            color = if (aiText != null) TextPrimary else TextMuted,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
