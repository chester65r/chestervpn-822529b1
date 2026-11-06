package com.example.advancedtunnel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.advancedtunnel.vpn.NodeHealthTier

@Composable
fun ServerNodeSelectorCard(
    viewModel: TunnelViewModel,
    modifier: Modifier = Modifier
) {
    val servers by viewModel.servers.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val serverLatencies by viewModel.serverLatencies.collectAsState()
    val nodeBenchmarks by viewModel.nodeBenchmarks.collectAsState()
    val isBenchmarking by viewModel.isBenchmarkingNodes.collectAsState()
    val autoFailoverEnabled by viewModel.autoFailoverEnabled.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()

    var dropdownExpanded by remember { mutableStateOf(false) }

    val activeServer = selectedServer ?: servers.find { it.host == profile.ip } ?: servers.firstOrNull()
    val activeMetric = activeServer?.let { nodeBenchmarks[it.id] }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("server_node_selector_card"),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f))
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
                        imageVector = Icons.Default.Public,
                        contentDescription = "Server Node",
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "MULTI-ENDPOINT BENCHMARK & FAILOVER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = CyanPrimary
                    )
                }

                Button(
                    onClick = { viewModel.benchmarkAndRankAllServers(applyBestIfAutoFailover = true) },
                    enabled = !isBenchmarking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = CyanPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("benchmark_all_nodes_button")
                ) {
                    if (isBenchmarking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = CyanPrimary,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Probing…", fontSize = 10.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Benchmark All",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rank Nodes", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                        .clickable { dropdownExpanded = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("server_dropdown_trigger"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )

                        Column {
                            Text(
                                text = activeServer?.name ?: if (profile.ip.isNotBlank()) profile.ip else "Select VPN Node",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (activeServer != null) "${activeServer.host}:${activeServer.port} • ${activeServer.transport}"
                                else if (profile.ip.isNotBlank()) "${profile.ip}:${profile.port}" else "Tap to choose region",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            if (activeMetric != null && activeMetric.avgRttMs >= 0) {
                                Text(
                                    text = "Jitter: ${activeMetric.jitterMs}ms • Loss: ${activeMetric.packetLossPercent}% • ${activeMetric.tier.name}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = when (activeMetric.tier) {
                                        NodeHealthTier.HEALTHY -> CyanPrimary
                                        NodeHealthTier.DEGRADED -> AccentOrange
                                        NodeHealthTier.UNREACHABLE -> DestructiveRed
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val lat = activeServer?.let { serverLatencies[it.id] }
                        if (lat != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceVariantDark)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${lat}ms",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = CyanPrimary
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Expand nodes",
                            tint = CyanPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .background(SurfaceVariantDark)
                ) {
                    Text(
                        text = "RANKED RELAY NODES (FASTEST FIRST)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )

                    servers.forEach { server ->
                        val lat = serverLatencies[server.id]
                        val metric = nodeBenchmarks[server.id]
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = server.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${server.host}:${server.port} • ${server.transport}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }

                                    if (lat != null) {
                                        Text(
                                            text = "${lat}ms" + (metric?.let { " (±${it.jitterMs}ms)" } ?: ""),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = CyanPrimary,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    } else if (metric?.tier == NodeHealthTier.UNREACHABLE) {
                                        Text(
                                            text = "OFFLINE",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = DestructiveRed,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                viewModel.selectServerNode(server)
                                dropdownExpanded = false
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Select",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Auto-Failover Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatic Relay Failover",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Automatically switch to lowest-RTT healthy node on connection drop",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                Switch(
                    checked = autoFailoverEnabled,
                    onCheckedChange = { viewModel.toggleAutoFailover(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SurfaceDark,
                        checkedTrackColor = CyanPrimary,
                        uncheckedTrackColor = SurfaceDark
                    ),
                    modifier = Modifier.testTag("auto_failover_switch")
                )
            }
        }
    }
}
