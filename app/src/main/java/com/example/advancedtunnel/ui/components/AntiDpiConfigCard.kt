package com.example.advancedtunnel.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.SuccessGreen
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.SurfaceVariantDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import com.example.advancedtunnel.viewmodel.TunnelViewModel
import com.example.advancedtunnel.vpn.TlsFingerprintEngine
import com.example.advancedtunnel.vpn.TlsFragmentMode

@Composable
fun AntiDpiConfigCard(
    viewModel: TunnelViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by viewModel.tlsFragmentConfig.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val status by viewModel.status.collectAsState()
    val simStats by viewModel.lastDesyncSimStats.collectAsState()

    val isLocked = status != TunnelStatus.DISCONNECTED
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var fpMenuExpanded by remember { mutableStateOf(false) }

    val activeFp = remember(config.fingerprintProfileId, profile.sni, config.alpnEnabled) {
        TlsFingerprintEngine.computeFingerprint(
            profileId = config.fingerprintProfileId,
            sniHost = profile.sni.ifBlank { "cloud.google.com" },
            alpnEnabled = config.alpnEnabled
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("anti_dpi_config_card"),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section 1: TLS Record Fragmentation & Desynchronization Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "TLS Record Desynchronization",
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "TLS RECORD FRAGMENTATION & DESYNC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Splits outbound ClientHello & SNI across records/segments",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                Switch(
                    checked = config.enabled,
                    onCheckedChange = { viewModel.updateTlsFragmentConfig(enabled = it) },
                    enabled = !isLocked,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SurfaceDark,
                        checkedTrackColor = CyanPrimary
                    ),
                    modifier = Modifier.testTag("tls_frag_enable_switch")
                )
            }

            if (config.enabled) {
                // Mode Dropdown
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "DESYNCHRONIZATION ENGINE MODE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        letterSpacing = 0.8.sp
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .clickable(enabled = !isLocked) { modeMenuExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("tls_frag_mode_dropdown"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = config.mode.label,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = config.mode.description,
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select fragmentation mode",
                                tint = CyanPrimary
                            )
                        }

                        DropdownMenu(
                            expanded = modeMenuExpanded,
                            onDismissRequest = { modeMenuExpanded = false },
                            modifier = Modifier.background(SurfaceVariantDark)
                        ) {
                            TlsFragmentMode.entries.forEach { modeOption ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = modeOption.label,
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = modeOption.description,
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateTlsFragmentConfig(mode = modeOption)
                                        modeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Chunk Byte Range Selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "FRAGMENT CHUNK WINDOW",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${config.normalizedMinChunk()}–${config.normalizedMaxChunk()} bytes",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyanPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val chunkPresets = listOf(
                        Triple("1–8 B (Micro)", 1, 8),
                        Triple("5–15 B (Default)", 5, 15),
                        Triple("8–24 B (Balanced)", 8, 24),
                        Triple("16–48 B (Wide)", 16, 48)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        chunkPresets.forEach { (label, minB, maxB) ->
                            val selected = config.minChunkBytes == minB && config.maxChunkBytes == maxB
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (!isLocked) {
                                        viewModel.updateTlsFragmentConfig(
                                            minChunkBytes = minB,
                                            maxChunkBytes = maxB
                                        )
                                    }
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary.copy(alpha = 0.18f),
                                    selectedLabelColor = CyanPrimary,
                                    containerColor = SurfaceDark,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                }

                // Jitter Delay Window Selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "INTER-PACKET JITTER DELAY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${config.normalizedMinJitter()}–${config.normalizedMaxJitter()} ms",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val jitterPresets = listOf(
                        Triple("0–2 ms (Fast)", 0, 2),
                        Triple("3–12 ms (Standard)", 3, 12),
                        Triple("8–22 ms (Deep DPI)", 8, 22),
                        Triple("15–40 ms (Strict)", 15, 40)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        jitterPresets.forEach { (label, minJ, maxJ) ->
                            val selected = config.minJitterMs == minJ && config.maxJitterMs == maxJ
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (!isLocked) {
                                        viewModel.updateTlsFragmentConfig(
                                            minJitterMs = minJ,
                                            maxJitterMs = maxJ
                                        )
                                    }
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SuccessGreen.copy(alpha = 0.18f),
                                    selectedLabelColor = SuccessGreen,
                                    containerColor = SurfaceDark,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = BorderDark)

            // Section 2: JA3 / JA4 TLS Fingerprint Spoofing Engine
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
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "JA3/JA4 Fingerprint Spoofing",
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "JA3 / JA4 TLS FINGERPRINT SPOOFING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Emulates mobile browser cipher suites, ALPN & curves",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                FilterChip(
                    selected = config.alpnEnabled,
                    onClick = {
                        if (!isLocked) {
                            viewModel.updateTlsFragmentConfig(alpnEnabled = !config.alpnEnabled)
                        }
                    },
                    label = { Text(if (config.alpnEnabled) "ALPN h2" else "ALPN Off", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SuccessGreen.copy(alpha = 0.18f),
                        selectedLabelColor = SuccessGreen,
                        containerColor = SurfaceDark,
                        labelColor = TextMuted
                    ),
                    modifier = Modifier.testTag("tls_alpn_toggle_chip")
                )
            }

            // Browser TLS Profile Selector
            Box(modifier = Modifier.fillMaxWidth()) {
                val resolvedBrowser = TlsFingerprintEngine.resolveProfile(config.fingerprintProfileId)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                        .clickable(enabled = !isLocked) { fpMenuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("ja3_profile_dropdown"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = resolvedBrowser.displayName,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${resolvedBrowser.browserEngine} • ALPN: ${activeFp.negotiatedAlpn}",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select JA3/JA4 browser profile",
                        tint = SuccessGreen
                    )
                }

                DropdownMenu(
                    expanded = fpMenuExpanded,
                    onDismissRequest = { fpMenuExpanded = false },
                    modifier = Modifier.background(SurfaceVariantDark)
                ) {
                    TlsFingerprintEngine.PROFILES.forEach { browserProfile ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = browserProfile.displayName,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${browserProfile.browserEngine} (${browserProfile.ja3CipherIds.size} ciphers)",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            },
                            onClick = {
                                viewModel.updateTlsFragmentConfig(fingerprintProfileId = browserProfile.id)
                                fpMenuExpanded = false
                            }
                        )
                    }
                }
            }

            // Live JA4 & JA3 Signature Readout Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "JA4: ${activeFp.ja4String}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                        Text(
                            text = "JA3 MD5: ${activeFp.ja3HashMd5}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyanPrimary
                        )
                    }
                    IconButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipText = "JA4: ${activeFp.ja4String}\nJA3_MD5: ${activeFp.ja3HashMd5}\nJA3_RAW: ${activeFp.ja3RawString}"
                            cb.setPrimaryClip(ClipData.newPlainText("TLS Fingerprint", clipText))
                            Toast.makeText(context, "Copied JA4 & JA3 signature", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("copy_ja3_ja4_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy JA3/JA4 fingerprint",
                            tint = TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    text = "Vector: ${activeFp.ja3RawString}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = TextMuted,
                    lineHeight = 13.sp
                )
            }

            // Dry-Run Packet Desynchronization Simulator Button & Output
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Verify record framing & SNI split offset:",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Button(
                    onClick = { viewModel.runClientHelloDesyncSimulation() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = CyanPrimary
                    ),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("test_tls_desync_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Desync Framing", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            simStats?.let { stats ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(1.dp, CyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "CLIENT HELLO DESYNC TRACE (${stats.originalBytes}B -> ${stats.emittedBytes}B)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = "TLS 0x16 Records: ${stats.tlsRecordCount} • TCP Segments: ${stats.tcpWriteChunks} • SNI Split @ +${stats.sniSplitOffset}B • Jitter: ${stats.totalJitterDelayMs}ms",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SuccessGreen
                    )
                    stats.chunkBreakdown.forEach { line ->
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
