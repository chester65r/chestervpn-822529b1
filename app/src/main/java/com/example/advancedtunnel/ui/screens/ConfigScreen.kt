package com.example.advancedtunnel.ui.screens

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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.advancedtunnel.model.DEFAULT_PAYLOAD_TEMPLATE
import com.example.advancedtunnel.model.ConnectionMode
import com.example.advancedtunnel.model.PayloadGeneratorConfig
import com.example.advancedtunnel.model.ROUTE_PRESETS
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.model.TunnelUtils
import com.example.advancedtunnel.ui.components.AntiDpiConfigCard
import com.example.advancedtunnel.ui.components.NetworkDiagnosticsCard
import com.example.advancedtunnel.ui.components.SplitTunnelingCard
import com.example.advancedtunnel.ui.components.WireGuardConfigCard
import com.example.advancedtunnel.ui.theme.BackgroundDark
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.DestructiveRed
import com.example.advancedtunnel.ui.theme.StarlinkMonoFontFamily
import com.example.advancedtunnel.ui.theme.StarlinkSansFontFamily
import com.example.advancedtunnel.ui.theme.SurfaceVariantDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import com.example.advancedtunnel.viewmodel.TunnelViewModel

@Composable
fun ConfigScreen(
    viewModel: TunnelViewModel,
    modifier: Modifier = Modifier
) {
    val profile by viewModel.profile.collectAsState()
    val presetId by viewModel.presetId.collectAsState()
    val errors by viewModel.errors.collectAsState()
    val status by viewModel.status.collectAsState()
    val connectionMode by viewModel.connectionMode.collectAsState()

    val isLocked = status != TunnelStatus.DISCONNECTED
    var selectedSection by remember { mutableIntStateOf(if (connectionMode == ConnectionMode.WIREGUARD) 1 else 0) } // 0 = PROTOCOLS, 1 = WIREGUARD, 2 = ENCRYPTION, 3 = SPLIT ROUTE
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showPayloadGenerator by remember { mutableStateOf(false) }

    val activePreset = ROUTE_PRESETS.find { it.id == presetId } ?: ROUTE_PRESETS.last()
    val parsedPreview = remember(profile.payload, profile.sni) {
        TunnelUtils.visualizePayload(TunnelUtils.parsePayload(profile.payload, profile.sni))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (isLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardBackground)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "TERMINAL LOCKED WHILE UPLINK ACTIVE • DISCONNECT TO EDIT",
                    fontFamily = StarlinkMonoFontFamily,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp
                )
            }
        }

        // Clean 3-Section Switcher Bar (Starlink Monospace Uppercase Headers)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val sections = listOf(
                Triple(0, "PROTOCOLS", Icons.Default.Tune),
                Triple(1, "WIREGUARD", Icons.Default.Security),
                Triple(2, "ENCRYPTION", Icons.Default.Security),
                Triple(3, "SPLIT ROUTE", Icons.Default.Route)
            )
            sections.forEach { (idx, label, icon) ->
                val isSelected = selectedSection == idx
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedSection = idx },
                    leadingIcon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                    },
                    label = {
                        Text(
                            text = label,
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
                        .testTag("config_section_tab_$idx")
                )
            }
        }

        when (selectedSection) {
            0 -> {
                // SECTION 0: TUNNEL PROTOCOLS (ENDPOINT, SNI & HANDSHAKE PAYLOAD)
                Text(
                    text = "TUNNEL PROTOCOLS",
                    fontFamily = StarlinkMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.8.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "GATEWAY & SNI ENDPOINT",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.6.sp
                        )

                        // Preset Selector
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BackgroundDark)
                                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isLocked) { dropdownExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("preset_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "CARRIER / ROUTE PRESET",
                                        fontFamily = StarlinkMonoFontFamily,
                                        color = TextMuted,
                                        fontSize = 9.sp,
                                        letterSpacing = 1.4.sp
                                    )
                                    Text(
                                        text = activePreset.label,
                                        fontFamily = StarlinkSansFontFamily,
                                        color = if (isLocked) TextMuted else TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Expand presets",
                                    tint = CyanPrimary
                                )
                            }

                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false },
                                modifier = Modifier.background(SurfaceVariantDark)
                            ) {
                                ROUTE_PRESETS.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = p.label,
                                                fontFamily = StarlinkSansFontFamily,
                                                color = TextPrimary,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            viewModel.selectPreset(p.id)
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // IP and Port
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = profile.ip,
                                onValueChange = { viewModel.updateProfile(ip = it) },
                                enabled = !isLocked,
                                label = { Text("SERVER IP / HOST", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                                placeholder = { Text("fsn1.hetzner.com") },
                                isError = errors.containsKey("ip"),
                                supportingText = errors["ip"]?.let { { Text(it, color = DestructiveRed, fontSize = 11.sp) } },
                                modifier = Modifier
                                    .weight(0.68f)
                                    .testTag("server_ip_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedContainerColor = BackgroundDark,
                                    unfocusedContainerColor = BackgroundDark,
                                    disabledContainerColor = BackgroundDark.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = profile.port,
                                onValueChange = { viewModel.updateProfile(port = it) },
                                enabled = !isLocked,
                                label = { Text("PORT", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                                placeholder = { Text("443") },
                                isError = errors.containsKey("port"),
                                supportingText = errors["port"]?.let { { Text(it, color = DestructiveRed, fontSize = 11.sp) } },
                                modifier = Modifier
                                    .weight(0.32f)
                                    .testTag("server_port_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedContainerColor = BackgroundDark,
                                    unfocusedContainerColor = BackgroundDark,
                                    disabledContainerColor = BackgroundDark.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }

                        // Server SNI
                        OutlinedTextField(
                            value = profile.sni,
                            onValueChange = { viewModel.updateProfile(sni = it) },
                            enabled = !isLocked,
                            label = { Text("SERVER NAME INDICATION (TLS 1.3 SNI)", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                            placeholder = { Text("www.stc.com.sa") },
                            isError = errors.containsKey("sni"),
                            supportingText = errors["sni"]?.let { { Text(it, color = DestructiveRed, fontSize = 11.sp) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_sni_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = BackgroundDark,
                                unfocusedContainerColor = BackgroundDark,
                                disabledContainerColor = BackgroundDark.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        if (profile.protocol == "vless-ws-tls" || presetId == "mobily") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = profile.cdnHost,
                                    onValueChange = { viewModel.updateProfile(cdnHost = it) },
                                    enabled = !isLocked,
                                    label = { Text("CDN EDGE HOST HEADER", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                                    placeholder = { Text("cdn.mobily.com.sa") },
                                    modifier = Modifier.weight(0.62f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CyanPrimary,
                                        unfocusedBorderColor = BorderDark,
                                        focusedContainerColor = BackgroundDark,
                                        unfocusedContainerColor = BackgroundDark,
                                        disabledContainerColor = BackgroundDark.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = profile.wsPath,
                                    onValueChange = { viewModel.updateProfile(wsPath = it) },
                                    enabled = !isLocked,
                                    label = { Text("WSS PATH", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                                    placeholder = { Text("/cdn-ws") },
                                    modifier = Modifier.weight(0.38f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CyanPrimary,
                                        unfocusedBorderColor = BorderDark,
                                        focusedContainerColor = BackgroundDark,
                                        unfocusedContainerColor = BackgroundDark,
                                        disabledContainerColor = BackgroundDark.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                // Sing-Box Core Outbound JSON Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "SING-BOX CORE OUTBOUND JSON",
                                    fontFamily = StarlinkMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary,
                                    letterSpacing = 1.6.sp
                                )
                                Text(
                                    text = "VLESS+Reality (STC 5-15B split) & VLESS+WSS CDN (Mobily Chrome JA4)",
                                    fontFamily = StarlinkSansFontFamily,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }

                            OutlinedButton(
                                onClick = { showPayloadGenerator = true },
                                enabled = !isLocked,
                                border = BorderStroke(1.dp, CyanPrimary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("open_payload_generator_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "BUILDER",
                                    fontFamily = StarlinkMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary
                                )
                            }
                        }

                        OutlinedTextField(
                            value = profile.payload,
                            onValueChange = { viewModel.updateProfile(payload = it) },
                            enabled = !isLocked,
                            placeholder = { Text(DEFAULT_PAYLOAD_TEMPLATE, fontSize = 11.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .testTag("payload_textarea"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = BackgroundDark,
                                unfocusedContainerColor = BackgroundDark,
                                disabledContainerColor = BackgroundDark.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                "stc" to "STC Reality (443)",
                                "mobily" to "Mobily WSS CDN (443)",
                                "hetzner" to "Hetzner Direct (443)"
                            ).forEach { (presetKey, chipLabel) ->
                                SuggestionChip(
                                    onClick = {
                                        if (!isLocked) {
                                            viewModel.selectPreset(presetKey)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = chipLabel,
                                            fontFamily = StarlinkMonoFontFamily,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = BackgroundDark,
                                        labelColor = CyanPrimary
                                    ),
                                    border = SuggestionChipDefaults.suggestionChipBorder(
                                        enabled = true,
                                        borderColor = BorderDark
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BackgroundDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = parsedPreview,
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 10.sp,
                                color = CyanPrimary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            1 -> {
                // SECTION 1: WIREGUARD CONFIGURATION (.CONF IMPORT & VALIDATION)
                Text(
                    text = "WIREGUARD LAYER 3 TUNNEL",
                    fontFamily = StarlinkMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.8.sp
                )
                WireGuardConfigCard(viewModel = viewModel)
            }

            2 -> {
                // SECTION 2: SECURITY & ENCRYPTION (TLS RECORD FRAGMENTATION & JA3/JA4)
                Text(
                    text = "SECURITY & ENCRYPTION",
                    fontFamily = StarlinkMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.8.sp
                )
                AntiDpiConfigCard(viewModel = viewModel)
            }

            3 -> {
                // SECTION 3: SPLIT TUNNELING & DIAGNOSTICS
                Text(
                    text = "SPLIT TUNNELING",
                    fontFamily = StarlinkMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.8.sp
                )
                SplitTunnelingCard(viewModel = viewModel)
                NetworkDiagnosticsCard(viewModel = viewModel)
            }
        }

        if (showPayloadGenerator) {
            PayloadGeneratorDialog(
                initialHost = profile.sni.ifBlank { profile.ip },
                onDismiss = { showPayloadGenerator = false },
                onApplyPayload = { generated ->
                    viewModel.updateProfile(payload = generated)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun PayloadGeneratorDialog(
    initialHost: String,
    onDismiss: () -> Unit,
    onApplyPayload: (String) -> Unit
) {
    var carrierPreset by remember { mutableStateOf("stc") }
    var protocol by remember { mutableStateOf("vless-reality") }
    var sniHost by remember { mutableStateOf(initialHost.ifBlank { "www.stc.com.sa" }) }
    var cdnHost by remember { mutableStateOf("cdn.mobily.com.sa") }
    var wsPath by remember { mutableStateOf("/cdn-ws") }
    var utlsFingerprint by remember { mutableStateOf("chrome") }
    var enableTlsFragment by remember { mutableStateOf(true) }
    var enableRecordFragment by remember { mutableStateOf(true) }

    var protocolDropdownExpanded by remember { mutableStateOf(false) }
    var carrierDropdownExpanded by remember { mutableStateOf(false) }

    val generatedPayload = remember(
        carrierPreset, protocol, sniHost, cdnHost, wsPath,
        utlsFingerprint, enableTlsFragment, enableRecordFragment
    ) {
        TunnelUtils.generatePayload(
            PayloadGeneratorConfig(
                carrierPreset = carrierPreset,
                protocol = protocol,
                sniHost = sniHost,
                cdnHost = cdnHost,
                wsPath = wsPath,
                utlsFingerprint = utlsFingerprint,
                enableTlsFragment = enableTlsFragment,
                enableRecordFragment = enableRecordFragment,
                sniSplitMinBytes = 5,
                sniSplitMaxBytes = 15
            )
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CyanPrimary)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
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
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "SING-BOX CARRIER CORE BUILDER",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.6.sp
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = carrierPreset.uppercase(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("CARRIER PROFILE", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.clickable { carrierDropdownExpanded = true }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { carrierDropdownExpanded = true },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = BackgroundDark,
                                unfocusedContainerColor = BackgroundDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        DropdownMenu(
                            expanded = carrierDropdownExpanded,
                            onDismissRequest = { carrierDropdownExpanded = false },
                            modifier = Modifier.background(SurfaceVariantDark)
                        ) {
                            listOf(
                                Triple("stc", "vless-reality", "www.stc.com.sa"),
                                Triple("mobily", "vless-ws-tls", "www.mobily.com.sa"),
                                Triple("hetzner", "trojan-tls", "static.194.8.29.2.clients.your-server.de")
                            ).forEach { (cid, proto, defaultSni) ->
                                DropdownMenuItem(
                                    text = { Text("${cid.uppercase()} ($proto)", fontFamily = StarlinkMonoFontFamily, color = TextPrimary) },
                                    onClick = {
                                        carrierPreset = cid
                                        protocol = proto
                                        sniHost = defaultSni
                                        carrierDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = protocol,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("CORE PROTOCOL", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.clickable { protocolDropdownExpanded = true }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { protocolDropdownExpanded = true },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = BackgroundDark,
                                unfocusedContainerColor = BackgroundDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        DropdownMenu(
                            expanded = protocolDropdownExpanded,
                            onDismissRequest = { protocolDropdownExpanded = false },
                            modifier = Modifier.background(SurfaceVariantDark)
                        ) {
                            listOf("vless-reality", "vless-ws-tls", "trojan-tls").forEach { proto ->
                                DropdownMenuItem(
                                    text = { Text(proto, fontFamily = StarlinkSansFontFamily, color = TextPrimary) },
                                    onClick = {
                                        protocol = proto
                                        protocolDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = sniHost,
                    onValueChange = { sniHost = it },
                    label = { Text("TLS 1.3 SNI SPOOF HOST", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                    placeholder = { Text("www.stc.com.sa") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payload_gen_host_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = BorderDark,
                        focusedContainerColor = BackgroundDark,
                        unfocusedContainerColor = BackgroundDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                if (protocol == "vless-ws-tls") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = cdnHost,
                            onValueChange = { cdnHost = it },
                            label = { Text("CDN EDGE HOST HEADER", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                            placeholder = { Text("cdn.mobily.com.sa") },
                            singleLine = true,
                            modifier = Modifier.weight(0.6f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = BackgroundDark,
                                unfocusedContainerColor = BackgroundDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = wsPath,
                            onValueChange = { wsPath = it },
                            label = { Text("WSS PATH", fontFamily = StarlinkMonoFontFamily, fontSize = 10.sp) },
                            placeholder = { Text("/cdn-ws") },
                            singleLine = true,
                            modifier = Modifier.weight(0.4f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = BackgroundDark,
                                unfocusedContainerColor = BackgroundDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { enableTlsFragment = !enableTlsFragment }
                    ) {
                        Checkbox(
                            checked = enableTlsFragment,
                            onCheckedChange = { enableTlsFragment = it },
                            colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                        )
                        Text("ClientHello SNI Boundary Split (Offset 5-15B)", fontFamily = StarlinkSansFontFamily, fontSize = 12.sp, color = TextPrimary)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { enableRecordFragment = !enableRecordFragment }
                    ) {
                        Checkbox(
                            checked = enableRecordFragment,
                            onCheckedChange = { enableRecordFragment = it },
                            colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                        )
                        Text("TLS 1.3 Record Layer Fragmentation", fontFamily = StarlinkSansFontFamily, fontSize = 12.sp, color = TextPrimary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = generatedPayload,
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 11.sp,
                        color = CyanPrimary,
                        lineHeight = 16.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, BorderDark),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "CANCEL",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onApplyPayload(generatedPayload)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = BackgroundDark
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("apply_generated_payload_button")
                    ) {
                        Text(
                            text = "APPLY CORE CONFIG",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }
                }
            }
        }
    }
}
