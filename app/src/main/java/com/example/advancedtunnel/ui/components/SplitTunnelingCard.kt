package com.example.advancedtunnel.ui.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

private data class AppEntry(
    val label: String,
    val packageName: String,
    val category: String,
    val isSystem: Boolean
)

private fun inferCategory(pkg: String, label: String): String {
    val combined = "${pkg.lowercase()} ${label.lowercase()}"
    return when {
        combined.contains("chrome") || combined.contains("browser") || combined.contains("firefox") || combined.contains("opera") || combined.contains("brave") -> "Browsers"
        combined.contains("whatsapp") || combined.contains("telegram") || combined.contains("messenger") || combined.contains("signal") || combined.contains("discord") || combined.contains("chat") -> "Messaging"
        combined.contains("youtube") || combined.contains("spotify") || combined.contains("netflix") || combined.contains("music") || combined.contains("video") || combined.contains("media") -> "Media"
        else -> "General"
    }
}

@Composable
fun SplitTunnelingCard(
    viewModel: TunnelViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val killswitchEnabled by viewModel.killswitchEnabled.collectAsState()
    val splitTunnelingEnabled by viewModel.splitTunnelingEnabled.collectAsState()
    val disallowedApps by viewModel.disallowedApps.collectAsState()

    var showAppPicker by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("split_tunneling_card"),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Killswitch Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Killswitch",
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "ALWAYS-ON KILLSWITCH",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            text = "Block unencrypted internet traffic if VPN drops",
                            fontFamily = StarlinkSansFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }

                Switch(
                    checked = killswitchEnabled,
                    onCheckedChange = { viewModel.toggleKillswitch(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BackgroundDark,
                        checkedTrackColor = CyanPrimary,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = BackgroundDark,
                        uncheckedBorderColor = BorderDark
                    ),
                    modifier = Modifier.testTag("killswitch_switch")
                )
            }

            // Split Tunneling Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = "Split Tunneling",
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "PER-APP SPLIT TUNNELING",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            text = "Select apps to bypass the VPN tunnel (${disallowedApps.size} excluded)",
                            fontFamily = StarlinkSansFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }

                Switch(
                    checked = splitTunnelingEnabled,
                    onCheckedChange = { viewModel.toggleSplitTunneling(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BackgroundDark,
                        checkedTrackColor = CyanPrimary,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = BackgroundDark,
                        uncheckedBorderColor = BorderDark
                    ),
                    modifier = Modifier.testTag("split_tunneling_switch")
                )
            }

            if (splitTunnelingEnabled) {
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    border = BorderStroke(1.dp, CyanPrimary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manage_excluded_apps_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CONFIGURE BYPASSED APPS (${disallowedApps.size})",
                        fontFamily = StarlinkMonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                }
            }
        }
    }

    if (showAppPicker) {
        var searchQuery by remember { mutableStateOf("") }
        var selectedCategory by remember { mutableStateOf("All") }
        var showSystemApps by remember { mutableStateOf(false) }

        val allApps = remember(context) {
            val defaults = listOf(
                AppEntry("Google Chrome", "com.android.chrome", "Browsers", false),
                AppEntry("Firefox Browser", "org.mozilla.firefox", "Browsers", false),
                AppEntry("WhatsApp", "com.whatsapp", "Messaging", false),
                AppEntry("Telegram", "org.telegram.messenger", "Messaging", false),
                AppEntry("YouTube", "com.google.android.youtube", "Media", false),
                AppEntry("Spotify", "com.spotify.music", "Media", false)
            )
            try {
                val pm = context.packageManager
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { info ->
                        val label = try {
                            pm.getApplicationLabel(info).toString()
                        } catch (_: Exception) {
                            info.packageName
                        }
                        val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppEntry(
                            label = label,
                            packageName = info.packageName,
                            category = inferCategory(info.packageName, label),
                            isSystem = isSys
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                if (installed.isEmpty()) defaults else (defaults + installed).distinctBy { it.packageName }
            } catch (_: Exception) {
                defaults
            }
        }

        val filteredApps = remember(allApps, searchQuery, selectedCategory, showSystemApps) {
            allApps.filter { app ->
                val matchesSystem = showSystemApps || !app.isSystem || app.category != "General"
                val matchesCategory = selectedCategory == "All" || app.category.equals(selectedCategory, ignoreCase = true)
                val q = searchQuery.trim().lowercase()
                val matchesSearch = q.isEmpty() ||
                    app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q)
                matchesSystem && matchesCategory && matchesSearch
            }
        }

        Dialog(onDismissRequest = { showAppPicker = false }) {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PER-APP SPLIT TUNNELING PICKER",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.6.sp
                        )
                        IconButton(onClick = { showAppPicker = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextMuted
                            )
                        }
                    }

                    Text(
                        text = "Checked apps bypass the VPN tunnel (addDisallowedApplication) and route directly over Wi-Fi/Cellular:",
                        fontFamily = StarlinkSansFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )

                    // Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search app name or package…", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("split_tunnel_search_input"),
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

                    // Category Filter Chips + System App Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("All", "Browsers", "Messaging", "Media").forEach { cat ->
                            val selected = selectedCategory == cat
                            FilterChip(
                                selected = selected,
                                onClick = { selectedCategory = cat },
                                label = {
                                    Text(
                                        text = cat.uppercase(),
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 10.sp
                                    )
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary,
                                    selectedLabelColor = BackgroundDark,
                                    containerColor = BackgroundDark,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                        FilterChip(
                            selected = showSystemApps,
                            onClick = { showSystemApps = !showSystemApps },
                            label = {
                                Text(
                                    text = "SYSTEM APPS",
                                    fontFamily = StarlinkMonoFontFamily,
                                    fontSize = 10.sp
                                )
                            },
                            shape = RoundedCornerShape(6.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.25f),
                                selectedLabelColor = CyanPrimary,
                                containerColor = BackgroundDark,
                                labelColor = TextMuted
                            )
                        )
                    }

                    // Bulk Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.setDisallowedAppsBulk(filteredApps.map { it.packageName }.toSet())
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, CyanPrimary)
                        ) {
                            Text(
                                text = "EXCLUDE VISIBLE (${filteredApps.size})",
                                fontFamily = StarlinkMonoFontFamily,
                                fontSize = 10.sp,
                                color = CyanPrimary
                            )
                        }

                        if (disallowedApps.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { viewModel.clearDisallowedApps() },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, DestructiveRed)
                            ) {
                                Text(
                                    text = "CLEAR ALL (${disallowedApps.size})",
                                    fontFamily = StarlinkMonoFontFamily,
                                    fontSize = 10.sp,
                                    color = DestructiveRed
                                )
                            }
                        }
                    }

                    // Filtered App List
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filteredApps.take(40).forEach { app ->
                            val isChecked = disallowedApps.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleDisallowedApp(app.packageName) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { viewModel.toggleDisallowedApp(app.packageName) },
                                    colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = app.label,
                                        fontFamily = StarlinkSansFontFamily,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${app.packageName} • ${app.category}",
                                        fontFamily = StarlinkMonoFontFamily,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = { showAppPicker = false },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = BackgroundDark),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "APPLY SPLIT TUNNELING (${disallowedApps.size} EXCLUDED)",
                            fontFamily = StarlinkMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
