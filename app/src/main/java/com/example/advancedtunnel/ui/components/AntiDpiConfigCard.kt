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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BackgroundDark
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.StarlinkMonoFontFamily
import com.example.advancedtunnel.ui.theme.StarlinkSansFontFamily
import com.example.advancedtunnel.ui.theme.SuccessGreen
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
        shape = RoundedCornerShape(12.dp),
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
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            text = "Splits outbound ClientHello & SNI across records/segments",
                            fontFamily = StarlinkSansFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                Switch(
                    checked = config.enabled,
                    onCheckedChange = { viewModel.updateTlsFragmentConfig(enabled = it) },
                    enabled = !isLocked,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BackgroundDark,
                        checkedTrackColor = CyanPrimary,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = BackgroundDark,
                        uncheckedBorderColor = BorderDark
                    ),
                    modifier = Modifier.testTag("tls_frag_enable_switch")
                )
            }

            if (config.enabled) {
                // Mode Dropdown
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "DESYNCHRONIZATION ENGINE MODE",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.4.sp
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BackgroundDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                                .clickable(enabled = !isLocked) { modeMenuExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("tls_frag_mode_dropdown"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = config.mode.label,
                                    fontFamily = StarlinkSansFontFamily,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = config.mode.description,
                                    fontFamily = StarlinkSansFontFamily,
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
                                                fontFamily = StarlinkSansFontFamily,
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = modeOption.description,
                                                fontFamily = StarlinkSansFontFamily,
                                                color = TextSecondary,
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
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "${config.normalizedMinChunk()}–${config.normalizedMaxChunk()} BYTES",
                            fontSize = 11.sp,
                            fontFamily = StarlinkMonoFontFamily,
                            color = CyanPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val chunkPresets = listOf(
                        Triple("1–8 B (MICRO)", 1, 8),
                        Triple("5–15 B (DEFAULT)", 5, 15),
                        Triple("8–24 B (BALANCED)", 8, 24),
                        Triple("16–48 B (WIDE)", 16, 48)
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
                                label = {
                                    Text(
                                        text = label,
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                    selectedLabelColor = CyanPrimary,
                                    containerColor = BackgroundDark,
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
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "${config.normalizedMinJitter()}–${config.normalizedMaxJitter()} MS",
                            fontSize = 11.sp,
                            fontFamily = StarlinkMonoFontFamily,
                            color = CyanPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val jitterPresets = listOf(
                        Triple("0–2 MS (FAST)", 0, 2),
                        Triple("3–12 MS (STANDARD)", 3, 12),
                        Triple("8–22 MS (DEEP DPI)", 8, 22),
                        Triple("15–40 MS (STRICT)", 15, 40)
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
                                label = {
                                    Text(
                                        text = label,
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                    selectedLabelColor = CyanPrimary,
                                    containerColor = BackgroundDark,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = BorderDark, thickness = 1.dp)

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
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "JA3 / JA4 TLS FINGERPRINT SPOOFING",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            text = "Emulates mobile browser cipher suites, ALPN & curves",
                            fontFamily = StarlinkSansFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary
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
                    label = {
                        Text(
                            text = if (config.alpnEnabled) "ALPN H2" else "ALPN OFF",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                        selectedLabelColor = CyanPrimary,
                        containerColor = BackgroundDark,
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .clickable(enabled = !isLocked) { fpMenuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("ja3_profile_dropdown"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = resolvedBrowser.displayName,
                            fontFamily = StarlinkSansFontFamily,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${resolvedBrowser.browserEngine} • ALPN: ${activeFp.negotiatedAlpn}",
                            fontFamily = StarlinkMonoFontFamily,
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select JA3/JA4 browser profile",
                        tint = CyanPrimary
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
                                        fontFamily = StarlinkSansFontFamily,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${browserProfile.browserEngine} (${browserProfile.ja3CipherIds.size} ciphers)",
                                        fontFamily = StarlinkMonoFontFamily,
                                        color = TextSecondary,
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(BackgroundDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
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
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary
                        )
                        Text(
                            text = "JA3 MD5: ${activeFp.ja3HashMd5}",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary
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
                    text = "VECTOR: ${activeFp.ja3RawString}",
                    fontFamily = StarlinkMonoFontFamily,
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
                    fontFamily = StarlinkSansFontFamily,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                OutlinedButton(
                    onClick = { viewModel.runClientHelloDesyncSimulation() },
                    border = BorderStroke(1.dp, CyanPrimary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("test_tls_desync_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "TEST DESYNC FRAMING",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                }
            }

            simStats?.let { stats ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark)
                        .border(1.dp, CyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "CLIENT HELLO DESYNC TRACE (${stats.originalBytes}B -> ${stats.emittedBytes}B)",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "TLS 0x16 Records: ${stats.tlsRecordCount} • TCP Segments: ${stats.tcpWriteChunks} • SNI Split @ +${stats.sniSplitOffset}B • Jitter: ${stats.totalJitterDelayMs}ms",
                        fontSize = 10.sp,
                        fontFamily = StarlinkMonoFontFamily,
                        color = SuccessGreen
                    )
                    stats.chunkBreakdown.forEach { line ->
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            fontFamily = StarlinkMonoFontFamily,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
