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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.advancedtunnel.model.DEFAULT_PAYLOAD_TEMPLATE
import com.example.advancedtunnel.model.PayloadGeneratorConfig
import com.example.advancedtunnel.model.ROUTE_PRESETS
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.model.TunnelUtils
import com.example.advancedtunnel.ui.components.AntiDpiConfigCard
import com.example.advancedtunnel.ui.components.NetworkDiagnosticsCard
import com.example.advancedtunnel.ui.components.SplitTunnelingCard
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

@Composable
fun ConfigScreen(
    viewModel: TunnelViewModel,
    modifier: Modifier = Modifier
) {
    val profile by viewModel.profile.collectAsState()
    val presetId by viewModel.presetId.collectAsState()
    val errors by viewModel.errors.collectAsState()
    val status by viewModel.status.collectAsState()

    val isLocked = status != TunnelStatus.DISCONNECTED
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Endpoint, 1 = Anti-DPI & JA3, 2 = Routing & Split
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showPayloadGenerator by remember { mutableStateOf(false) }

    val activePreset = ROUTE_PRESETS.find { it.id == presetId } ?: ROUTE_PRESETS.last()
    val parsedPreview = remember(profile.payload, profile.sni) {
        TunnelUtils.visualizePayload(TunnelUtils.parsePayload(profile.payload, profile.sni))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (isLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = CyanPrimary
                )
                Text(
                    text = "Disconnect tunnel to edit configuration.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Clean 3-Section Switcher Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBackground)
                .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val sections = listOf(
                Triple(0, "Endpoint", Icons.Default.Tune),
                Triple(1, "Anti-DPI & TLS", Icons.Default.Security),
                Triple(2, "Routing & Apps", Icons.Default.Route)
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
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary,
                        selectedLabelColor = SurfaceDark,
                        selectedLeadingIconColor = SurfaceDark,
                        containerColor = SurfaceDark,
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
                // SECTION 0: ENDPOINT, SNI & PAYLOAD
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "GATEWAY & SNI ENDPOINT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 0.8.sp
                        )

                        // Preset Selector
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceDark)
                                    .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                    .clickable(enabled = !isLocked) { dropdownExpanded = true }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("preset_dropdown_trigger"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Route Preset",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = activePreset.label,
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
                                        text = { Text(p.label, color = TextPrimary, fontSize = 13.sp) },
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
                                label = { Text("SERVER IP / HOST", fontSize = 10.sp) },
                                placeholder = { Text("e.g. fsn1.hetzner.com") },
                                isError = errors.containsKey("ip"),
                                supportingText = errors["ip"]?.let { { Text(it, color = DestructiveRed, fontSize = 11.sp) } },
                                modifier = Modifier
                                    .weight(0.68f)
                                    .testTag("server_ip_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedContainerColor = SurfaceDark,
                                    unfocusedContainerColor = SurfaceDark,
                                    disabledContainerColor = SurfaceDark.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = profile.port,
                                onValueChange = { viewModel.updateProfile(port = it) },
                                enabled = !isLocked,
                                label = { Text("PORT", fontSize = 10.sp) },
                                placeholder = { Text("443") },
                                isError = errors.containsKey("port"),
                                supportingText = errors["port"]?.let { { Text(it, color = DestructiveRed, fontSize = 11.sp) } },
                                modifier = Modifier
                                    .weight(0.32f)
                                    .testTag("server_port_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedContainerColor = SurfaceDark,
                                    unfocusedContainerColor = SurfaceDark,
                                    disabledContainerColor = SurfaceDark.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }

                        // Server SNI
                        OutlinedTextField(
                            value = profile.sni,
                            onValueChange = { viewModel.updateProfile(sni = it) },
                            enabled = !isLocked,
                            label = { Text("SERVER NAME INDICATION (SNI)", fontSize = 10.sp) },
                            placeholder = { Text("cloud.google.com") },
                            isError = errors.containsKey("sni"),
                            supportingText = errors["sni"]?.let { { Text(it, color = DestructiveRed, fontSize = 11.sp) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_sni_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                disabledContainerColor = SurfaceDark.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                    }
                }

                // Handshake Payload Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(16.dp),
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
                                    text = "HANDSHAKE PAYLOAD & MACROS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = "Custom HTTP/WS headers & socket split macros",
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }

                            Button(
                                onClick = { showPayloadGenerator = true },
                                enabled = !isLocked,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceDark,
                                    contentColor = CyanPrimary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("open_payload_generator_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Builder", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedTextField(
                            value = profile.payload,
                            onValueChange = { viewModel.updateProfile(payload = it) },
                            enabled = !isLocked,
                            placeholder = { Text(DEFAULT_PAYLOAD_TEMPLATE, fontSize = 11.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(104.dp)
                                .testTag("payload_textarea"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                disabledContainerColor = SurfaceDark.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
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
                            listOf("[host]", "[crlf]", "[split]", "[sni]").forEach { macro ->
                                SuggestionChip(
                                    onClick = {
                                        if (!isLocked) {
                                            viewModel.updateProfile(payload = profile.payload + macro)
                                        }
                                    },
                                    label = { Text(macro, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = SurfaceDark,
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
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = parsedPreview,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = CyanPrimary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            1 -> {
                // SECTION 1: TLS RECORD FRAGMENTATION & JA3/JA4 FINGERPRINT SPOOFING
                AntiDpiConfigCard(viewModel = viewModel)
            }

            2 -> {
                // SECTION 2: KILLSWITCH, SPLIT TUNNELING & NETWORK DIAGNOSTICS
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
    var urlHost by remember { mutableStateOf(initialHost.ifBlank { "[host]" }) }
    var requestMethod by remember { mutableStateOf("GET") }
    var injectionMode by remember { mutableStateOf("Normal") }
    var enableWebSocket by remember { mutableStateOf(true) }
    var enableKeepAlive by remember { mutableStateOf(true) }
    var enableUserAgent by remember { mutableStateOf(true) }
    var enableXForwardedFor by remember { mutableStateOf(false) }
    var enableXOnlineHost by remember { mutableStateOf(false) }
    var enableReferer by remember { mutableStateOf(false) }
    var customHeaders by remember { mutableStateOf("") }

    var methodDropdownExpanded by remember { mutableStateOf(false) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    val generatedPayload = remember(
        urlHost, requestMethod, injectionMode, enableWebSocket,
        enableKeepAlive, enableUserAgent, enableXForwardedFor,
        enableXOnlineHost, enableReferer, customHeaders
    ) {
        TunnelUtils.generatePayload(
            PayloadGeneratorConfig(
                urlHost = urlHost,
                requestMethod = requestMethod,
                injectionMode = injectionMode,
                enableWebSocket = enableWebSocket,
                enableKeepAlive = enableKeepAlive,
                enableUserAgent = enableUserAgent,
                enableXForwardedFor = enableXForwardedFor,
                enableXOnlineHost = enableXOnlineHost,
                enableReferer = enableReferer,
                customHeaders = customHeaders
            )
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(20.dp),
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
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "PAYLOAD BUILDER",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.sp
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

                OutlinedTextField(
                    value = urlHost,
                    onValueChange = { urlHost = it },
                    label = { Text("URL / Host Domain", fontSize = 11.sp) },
                    placeholder = { Text("[host]") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payload_gen_host_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = requestMethod,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Method", fontSize = 11.sp) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.clickable { methodDropdownExpanded = true }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { methodDropdownExpanded = true },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        DropdownMenu(
                            expanded = methodDropdownExpanded,
                            onDismissRequest = { methodDropdownExpanded = false },
                            modifier = Modifier.background(SurfaceVariantDark)
                        ) {
                            listOf("GET", "POST", "CONNECT", "HEAD", "OPTIONS", "PUT").forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m, color = TextPrimary) },
                                    onClick = {
                                        requestMethod = m
                                        methodDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = injectionMode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Injection Mode", fontSize = 11.sp) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.clickable { modeDropdownExpanded = true }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { modeDropdownExpanded = true },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        DropdownMenu(
                            expanded = modeDropdownExpanded,
                            onDismissRequest = { modeDropdownExpanded = false },
                            modifier = Modifier.background(SurfaceVariantDark)
                        ) {
                            listOf("Normal", "Front Query", "Back Query", "SSL SNI", "Dual Split").forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode, color = TextPrimary) },
                                    onClick = {
                                        injectionMode = mode
                                        modeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { enableWebSocket = !enableWebSocket }
                    ) {
                        Checkbox(
                            checked = enableWebSocket,
                            onCheckedChange = { enableWebSocket = it },
                            colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                        )
                        Text("WebSocket Upgrade", fontSize = 12.sp, color = TextPrimary)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { enableKeepAlive = !enableKeepAlive }
                    ) {
                        Checkbox(
                            checked = enableKeepAlive,
                            onCheckedChange = { enableKeepAlive = it },
                            colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                        )
                        Text("Keep-Alive Header", fontSize = 12.sp, color = TextPrimary)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { enableUserAgent = !enableUserAgent }
                    ) {
                        Checkbox(
                            checked = enableUserAgent,
                            onCheckedChange = { enableUserAgent = it },
                            colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                        )
                        Text("Chrome User-Agent Header", fontSize = 12.sp, color = TextPrimary)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { enableXOnlineHost = !enableXOnlineHost }
                    ) {
                        Checkbox(
                            checked = enableXOnlineHost,
                            onCheckedChange = { enableXOnlineHost = it },
                            colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                        )
                        Text("X-Online-Host Header", fontSize = 12.sp, color = TextPrimary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = generatedPayload,
                        fontFamily = FontFamily.Monospace,
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
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onApplyPayload(generatedPayload)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = SurfaceDark
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("apply_generated_payload_button")
                    ) {
                        Text("Apply Payload", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
