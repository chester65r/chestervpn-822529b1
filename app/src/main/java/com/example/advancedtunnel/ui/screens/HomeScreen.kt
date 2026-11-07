package com.example.advancedtunnel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.SatelliteAlt
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.model.ConnectionMode
import com.example.advancedtunnel.model.SAMPLE_VPS_SERVERS
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.model.TunnelUtils
import com.example.advancedtunnel.ui.components.ServerNodeSelectorCard
import com.example.advancedtunnel.ui.components.SpeedGraphCanvas
import com.example.advancedtunnel.ui.components.WireGuardLiveDetailsCard
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BackgroundDark
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.DestructiveRed
import com.example.advancedtunnel.ui.theme.InteractiveFocusRing
import com.example.advancedtunnel.ui.theme.StarlinkMonoFontFamily
import com.example.advancedtunnel.ui.theme.StarlinkSansFontFamily
import com.example.advancedtunnel.ui.theme.SuccessGreen
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import com.example.advancedtunnel.viewmodel.TunnelViewModel
import com.example.advancedtunnel.vpn.RealVpnState
import com.example.advancedtunnel.vpn.TlsFingerprintEngine
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private fun formatMbpsNumber(bytesPerSec: Double): String {
    val mbps = (bytesPerSec * 8.0) / 1_000_000.0
    return when {
        mbps >= 100.0 -> String.format(Locale.US, "%.0f", mbps)
        mbps >= 10.0 -> String.format(Locale.US, "%.1f", mbps)
        else -> String.format(Locale.US, "%.2f", mbps)
    }
}

private fun resolveServerCoordinates(host: String): String {
    val lower = host.lowercase()
    return when {
        lower.contains("london") || lower.contains("west2") -> "51.5072° N, 0.1276° W"
        lower.contains("central1") || lower.contains("iowa") -> "41.2619° N, 95.8608° W"
        lower.contains("tokyo") || lower.contains("northeast1") -> "35.6762° N, 139.6503° E"
        lower.contains("fsn") || lower.contains("de") -> "50.4750° N, 12.3713° E"
        lower.contains("hel") || lower.contains("fi") -> "60.1699° N, 24.9384° E"
        lower.contains("ash") || lower.contains("us") -> "39.0438° N, 77.4874° W"
        host.isNotBlank() -> "24.7136° N, 46.6753° E"
        else -> "00.0000° N, 00.0000° E"
    }
}

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
    val nodeBenchmarks by viewModel.nodeBenchmarks.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val aiText by viewModel.aiHelperText.collectAsState()
    val isAnalyzingAi by viewModel.isAnalyzingAi.collectAsState()
    val telemetryLogs by viewModel.telemetryLogs.collectAsState()
    val connectionMode by viewModel.connectionMode.collectAsState()
    val wgStats by viewModel.wireguardStats.collectAsState()
    val wireguardConfigText by viewModel.wireguardConfigText.collectAsState()

    val isConnected = status == TunnelStatus.CONNECTED
    val isConnecting = status == TunnelStatus.CONNECTING

    var activeBottomSection by remember { mutableIntStateOf(0) }

    val activeBrowserProfile = remember(tlsConfig.fingerprintProfileId) {
        TlsFingerprintEngine.resolveProfile(tlsConfig.fingerprintProfileId)
    }

    val activeLossPercent = remember(selectedServer, nodeBenchmarks) {
        val metric = selectedServer?.let { nodeBenchmarks[it.id] }
        metric?.packetLossPercent ?: 0
    }

    // 1-second repeat infinite alpha animation for Starlink dish status ring
    val infiniteTransition = rememberInfiniteTransition(label = "starlinkRadarPulse")
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Connection Mode Selector: Purely additive mode switch between Tunnel & WireGuard
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConnectionMode.values().forEach { mode ->
                val isSelected = connectionMode == mode
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (!isConnected && !isConnecting) {
                            viewModel.setConnectionMode(mode)
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (mode == ConnectionMode.TUNNEL) Icons.Default.Tune else Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                    },
                    label = {
                        Text(
                            text = mode.label.uppercase(),
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary,
                        selectedLabelColor = BackgroundDark,
                        selectedLeadingIconColor = BackgroundDark,
                        containerColor = BackgroundDark,
                        labelColor = TextSecondary,
                        iconColor = TextMuted
                    ),
                    border = null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("connection_mode_${mode.name.lowercase()}")
                )
            }
        }

        // A.1 Status Header & Central Dish Alignment / Radar Telemetry Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("status_hero_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(
                width = 1.dp,
                color = when {
                    isConnected -> CyanPrimary.copy(alpha = 0.65f)
                    isConnecting -> AccentOrange.copy(alpha = 0.65f)
                    else -> BorderDark
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Status Capsule & Quick Protocol Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsing Status Indicator Capsule
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundDark)
                            .border(
                                width = 1.dp,
                                color = when {
                                    isConnected -> CyanPrimary.copy(alpha = ringAlpha)
                                    isConnecting -> AccentOrange.copy(alpha = ringAlpha)
                                    else -> BorderDark
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isConnected -> CyanPrimary.copy(alpha = ringAlpha)
                                        isConnecting -> AccentOrange.copy(alpha = ringAlpha)
                                        else -> DestructiveRed
                                    }
                                )
                        )
                        Crossfade(
                            targetState = vpnState,
                            animationSpec = tween(250, easing = FastOutSlowInEasing),
                            label = "heroStatusText"
                        ) { state ->
                            val statusText = when (state) {
                                is RealVpnState.Connected -> "CONNECTED"
                                is RealVpnState.Connecting, is RealVpnState.Reconnecting -> "SEARCHING"
                                is RealVpnState.Failed -> "OFFLINE • FAULT"
                                is RealVpnState.Disconnected -> "STANDBY"
                            }
                            Text(
                                text = statusText,
                                fontFamily = StarlinkMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 1.8.sp,
                                color = when (state) {
                                    is RealVpnState.Connected -> CyanPrimary
                                    is RealVpnState.Connecting, is RealVpnState.Reconnecting -> AccentOrange
                                    is RealVpnState.Failed -> DestructiveRed
                                    is RealVpnState.Disconnected -> TextSecondary
                                }
                            )
                        }
                    }

                    // Protocol & Tune Trigger
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundDark)
                            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                            .clickable { onNavigateToConfig() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (connectionMode == ConnectionMode.WIREGUARD) Icons.Default.Security else Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (connectionMode == ConnectionMode.WIREGUARD) {
                                "WIREGUARD • GOBACKEND"
                            } else if (tlsConfig.enabled) {
                                "${activeBrowserProfile.displayName.substringBefore(" (").uppercase()} • ${tlsConfig.normalizedMinChunk()}–${tlsConfig.normalizedMaxChunk()}B"
                            } else {
                                "STANDARD TLS"
                            },
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = TextSecondary
                        )
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Tune",
                            tint = CyanPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                // Main Visual Canvas / Central Radar & Dish Alignment Widget
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BackgroundDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Minimalist Orbital Radar Visualization
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clickable(enabled = !isConnecting) {
                                if (isConnected) {
                                    viewModel.disconnectVpn(context)
                                } else {
                                    viewModel.requestConnectVpn(context, onRequestVpnPermission)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val activeAccent = when {
                            isConnected -> CyanPrimary
                            isConnecting -> AccentOrange
                            else -> TextMuted
                        }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val maxRadius = size.minDimension / 2f

                            // Outer pulsing ring
                            drawCircle(
                                color = activeAccent.copy(alpha = if (isConnected || isConnecting) ringAlpha * 0.45f else 0.2f),
                                radius = maxRadius,
                                center = center,
                                style = Stroke(width = 1.5.dp.toPx())
                            )

                            // Mid dashed telemetry ring
                            drawCircle(
                                color = BorderDark,
                                radius = maxRadius * 0.68f,
                                center = center,
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                )
                            )

                            // Crosshair axes
                            drawLine(
                                color = BorderDark.copy(alpha = 0.7f),
                                start = Offset(center.x - maxRadius, center.y),
                                end = Offset(center.x + maxRadius, center.y),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = BorderDark.copy(alpha = 0.7f),
                                start = Offset(center.x, center.y - maxRadius),
                                end = Offset(center.x, center.y + maxRadius),
                                strokeWidth = 1.dp.toPx()
                            )

                            // Dish / Satellite vector sweep when active or connecting
                            if (isConnected || isConnecting) {
                                val rad = Math.toRadians(sweepAngle.toDouble())
                                val endX = center.x + (maxRadius * 0.88f * cos(rad)).toFloat()
                                val endY = center.y + (maxRadius * 0.88f * sin(rad)).toFloat()
                                drawLine(
                                    brush = Brush.linearGradient(
                                        colors = listOf(activeAccent.copy(alpha = 0.8f), Color.Transparent),
                                        start = center,
                                        end = Offset(endX, endY)
                                    ),
                                    start = center,
                                    end = Offset(endX, endY),
                                    strokeWidth = 2.dp.toPx()
                                )
                                drawCircle(
                                    color = activeAccent,
                                    radius = 3.5.dp.toPx(),
                                    center = Offset(endX, endY)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(CardBackground)
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        isConnected -> CyanPrimary
                                        isConnecting -> AccentOrange
                                        else -> BorderDark
                                    },
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = AccentOrange,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.SatelliteAlt else Icons.Default.Security,
                                    contentDescription = "Terminal Dish",
                                    tint = if (isConnected) CyanPrimary else TextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // Right Telemetry Readout Column (WireGuard or Tunnel)
                    if (connectionMode == ConnectionMode.WIREGUARD) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "WIREGUARD PEER TELEMETRY",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                color = TextSecondary
                            )

                            Text(
                                text = if (wgStats.peerEndpoint.isNotBlank()) {
                                    wgStats.peerEndpoint
                                } else if (wireguardConfigText.isNotBlank()) {
                                    "CONFIG LOADED"
                                } else {
                                    "NO PEER CONFIGURED"
                                },
                                fontFamily = StarlinkSansFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                letterSpacing = (-0.3).sp,
                                color = TextPrimary
                            )

                            Text(
                                text = "ALLOWED: ${wgStats.allowedIps.ifBlank { "0.0.0.0/0" }}",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 11.sp,
                                color = CyanPrimary
                            )

                            Text(
                                text = "INTERFACE: ${wgStats.interfaceAddresses.ifBlank { "10.0.0.2/24" }}${if (wgStats.dnsServers.isNotBlank()) " • DNS: ${wgStats.dnsServers}" else ""}",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 10.sp,
                                color = TextSecondary
                            )

                            HorizontalDivider(color = BorderDark, thickness = 1.dp)

                            // WireGuard Live Handshake / Uptime / Bytes Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "LAST HANDSHAKE",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = if (isConnected) wgStats.formatLastHandshake() else "STANDBY",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isConnected && wgStats.lastHandshakeEpochSeconds > 0) CyanPrimary else TextSecondary
                                    )
                                }

                                Column {
                                    Text(
                                        text = "PEER RX",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = TunnelUtils.formatBytes(wgStats.rxBytes.toDouble()),
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }

                                Column {
                                    Text(
                                        text = "UPTIME",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = TunnelUtils.formatDuration(uptimeSeconds),
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "TERMINAL TELEMETRY",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                color = TextSecondary
                            )

                            Text(
                                text = if (profile.ip.isNotBlank()) {
                                    "${profile.ip}:${profile.port}"
                                } else {
                                    "NO ACTIVE RELAY NODE"
                                },
                                fontFamily = StarlinkSansFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                letterSpacing = (-0.3).sp,
                                color = TextPrimary
                            )

                            Text(
                                text = "SNI: ${profile.sni.ifBlank { profile.ip.ifBlank { "UNSET" } }}",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 11.sp,
                                color = CyanPrimary
                            )

                            Text(
                                text = resolveServerCoordinates(profile.ip),
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 10.sp,
                                color = TextSecondary
                            )

                            HorizontalDivider(color = BorderDark, thickness = 1.dp)

                            // Ping / Loss / Uptime Telemetry Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.clickable { viewModel.pingServer() }
                                ) {
                                    Text(
                                        text = "LATENCY",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = if (latencyMs != null) "${latencyMs} ms" else "-- ms",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanPrimary
                                    )
                                }

                                Column {
                                    Text(
                                        text = "LOSS",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1f%%", activeLossPercent.toDouble()),
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (activeLossPercent == 0) TextPrimary else AccentOrange
                                    )
                                }

                                Column {
                                    Text(
                                        text = "UPTIME",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = TunnelUtils.formatDuration(uptimeSeconds),
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                if (vpnState is RealVpnState.Failed) {
                    Text(
                        text = (vpnState as RealVpnState.Failed).error,
                        fontFamily = StarlinkMonoFontFamily,
                        color = DestructiveRed,
                        fontSize = 11.sp
                    )
                }

                // A.4 The Master Action Button (Starlink Aerospace Geometry: 8dp-10dp radius, 1px border, outer focus glow)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = when {
                                isConnecting -> AccentOrange.copy(alpha = ringAlpha)
                                isConnected -> InteractiveFocusRing.copy(alpha = ringAlpha * 0.7f)
                                else -> InteractiveFocusRing.copy(alpha = 0.45f)
                            },
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(2.dp)
                ) {
                    Button(
                        onClick = {
                            if (isConnected) {
                                viewModel.disconnectVpn(context)
                            } else {
                                if (connectionMode == ConnectionMode.TUNNEL && profile.ip.isBlank()) {
                                    val defaultNode = SAMPLE_VPS_SERVERS.first()
                                    viewModel.selectServerNode(defaultNode)
                                }
                                viewModel.requestConnectVpn(context, onRequestVpnPermission)
                            }
                        },
                        enabled = !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("toggle_tunnel_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                isConnected -> BackgroundDark
                                isConnecting -> CardBackground
                                else -> CyanPrimary
                            },
                            contentColor = when {
                                isConnected -> CyanPrimary
                                isConnecting -> AccentOrange
                                else -> BackgroundDark
                            }
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = when {
                                isConnected -> CyanPrimary
                                isConnecting -> AccentOrange
                                else -> CyanPrimary
                            }
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Crossfade(
                            targetState = status,
                            animationSpec = tween(250, easing = FastOutSlowInEasing),
                            label = "buttonLabelCrossfade"
                        ) { curStatus ->
                            val actionText = when (curStatus) {
                                TunnelStatus.CONNECTING -> "ESTABLISHING UPLINK…"
                                TunnelStatus.CONNECTED -> "DISCONNECT TERMINAL"
                                TunnelStatus.DISCONNECTED -> "INITIATE CONNECTION"
                            }
                            Text(
                                text = actionText,
                                fontFamily = StarlinkMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.8.sp,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Validation Banner if needed
        if (errors.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("validation_errors_banner"),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(12.dp),
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
                                    fontFamily = StarlinkSansFontFamily,
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
                        Text(
                            text = "CONFIGURE",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            color = CyanPrimary
                        )
                    }
                }
            }
        }

        // A.3 Speed Telemetry Panel (Two side-by-side 32sp Monospace Cards + Minimalist SVG-like Line Graph)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "THROUGHPUT TELEMETRY",
                fontFamily = StarlinkMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.8.sp,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // DOWNLOAD CARD
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("download_stat_card"),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Download",
                                tint = CyanPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "DOWNLOAD",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                letterSpacing = 1.6.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = formatMbpsNumber(realDownSpeed),
                                fontFamily = StarlinkMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp,
                                color = TextPrimary,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = "MBPS",
                                fontFamily = StarlinkMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = CyanPrimary,
                                letterSpacing = 1.4.sp,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }

                        Text(
                            text = "RX ${TunnelUtils.formatBytes(realRxBytes.toDouble())} • PEAK ${TunnelUtils.formatSpeed(peakDown)}",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 9.sp,
                            color = TextMuted
                        )
                    }
                }

                // UPLOAD CARD
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("upload_stat_card"),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Upload",
                                tint = AccentOrange,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "UPLOAD",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                letterSpacing = 1.6.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = formatMbpsNumber(realUpSpeed),
                                fontFamily = StarlinkMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp,
                                color = TextPrimary,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = "MBPS",
                                fontFamily = StarlinkMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = AccentOrange,
                                letterSpacing = 1.4.sp,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }

                        Text(
                            text = "TX ${TunnelUtils.formatBytes(realTxBytes.toDouble())} • PEAK ${TunnelUtils.formatSpeed(peakUp)}",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 9.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // Minimalist Real-Time Line Graph Underneath
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "REAL-TIME LINK WAVEFORM",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp,
                            color = TextSecondary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "● DOWNLINK",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 9.sp,
                                color = CyanPrimary
                            )
                            Text(
                                text = "● UPLINK",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 9.sp,
                                color = AccentOrange
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
        }

        // Active Node Switcher & Failover Card OR WireGuard Live Details Card
        if (connectionMode == ConnectionMode.TUNNEL) {
            ServerNodeSelectorCard(viewModel = viewModel)
        } else {
            WireGuardLiveDetailsCard(
                wgStats = wgStats,
                hasConfig = wireguardConfigText.isNotBlank(),
                isConnected = isConnected,
                onEditConfig = onNavigateToConfig
            )
        }

        // Diagnostics & Event Log Console Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("realtime_telemetry_diagnostics_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(12.dp),
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
                                    modifier = Modifier.size(13.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = "TELEMETRY LOG (${telemetryLogs.size})",
                                    fontFamily = StarlinkMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.16f),
                                selectedLabelColor = CyanPrimary,
                                selectedLeadingIconColor = CyanPrimary,
                                containerColor = BackgroundDark,
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
                                    modifier = Modifier.size(13.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = "AI DIAGNOSTICS",
                                    fontFamily = StarlinkMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.16f),
                                selectedLabelColor = CyanPrimary,
                                selectedLeadingIconColor = CyanPrimary,
                                containerColor = BackgroundDark,
                                labelColor = TextSecondary
                            )
                        )
                    }

                    if (activeBottomSection == 0) {
                        OutlinedButton(
                            onClick = {
                                val report = viewModel.exportSanitizedDiagnostics()
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Tunnel Diagnostics", report))
                                Toast.makeText(context, "Diagnostics report copied", Toast.LENGTH_SHORT).show()
                            },
                            border = BorderStroke(1.dp, BorderDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("export_diagnostics_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "COPY",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                        }
                    } else {
                        Button(
                            onClick = { viewModel.runAiDiagnostics() },
                            enabled = !isAnalyzingAi,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = BackgroundDark
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("ai_explain_button")
                        ) {
                            if (isAnalyzingAi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = BackgroundDark,
                                    strokeWidth = 1.5.dp
                                )
                            } else {
                                Text(
                                    text = "ANALYZE",
                                    fontFamily = StarlinkMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = BorderDark, thickness = 1.dp)

                if (activeBottomSection == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundDark)
                            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val timeFormatter = remember { java.text.SimpleDateFormat("HH:mm:ss", Locale.US) }
                        telemetryLogs.take(6).forEach { entry ->
                            val badgeColor = when (entry.level) {
                                "ERROR", "WARN" -> DestructiveRed
                                "SUCCESS", "PROBE", "DESYNC", "JA3/JA4" -> CyanPrimary
                                else -> TextSecondary
                            }
                            Text(
                                text = "[${timeFormatter.format(java.util.Date(entry.timestampMillis))}] [${entry.level}] ${entry.message}",
                                fontFamily = StarlinkMonoFontFamily,
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundDark)
                            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .testTag("gemini_ai_helper_card")
                    ) {
                        Text(
                            text = aiText
                                ?: "Tap 'ANALYZE' to inspect active endpoint (${profile.ip.ifBlank { "UNSET" }}), SNI (${profile.sni}), TLS fragmentation, and latency with Gemini AI.",
                            fontFamily = StarlinkSansFontFamily,
                            fontSize = 12.sp,
                            color = if (aiText != null) TextPrimary else TextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
