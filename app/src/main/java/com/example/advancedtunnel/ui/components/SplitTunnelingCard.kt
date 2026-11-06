package com.example.advancedtunnel.ui.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.SurfaceVariantDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import com.example.advancedtunnel.viewmodel.TunnelViewModel

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
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "ALWAYS-ON KILLSWITCH",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Block unencrypted internet traffic if VPN drops",
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )
                    }
                }

                Switch(
                    checked = killswitchEnabled,
                    onCheckedChange = { viewModel.toggleKillswitch(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SurfaceDark,
                        checkedTrackColor = CyanPrimary,
                        uncheckedTrackColor = SurfaceDark
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
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "PER-APP SPLIT TUNNELING",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Select apps to bypass the VPN tunnel (${disallowedApps.size} excluded)",
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )
                    }
                }

                Switch(
                    checked = splitTunnelingEnabled,
                    onCheckedChange = { viewModel.toggleSplitTunneling(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SurfaceDark,
                        checkedTrackColor = CyanPrimary,
                        uncheckedTrackColor = SurfaceDark
                    ),
                    modifier = Modifier.testTag("split_tunneling_switch")
                )
            }

            if (splitTunnelingEnabled) {
                Button(
                    onClick = { showAppPicker = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manage_excluded_apps_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select Apps to Bypass VPN (${disallowedApps.size})", fontSize = 12.sp)
                }
            }
        }
    }

    if (showAppPicker) {
        Dialog(onDismissRequest = { showAppPicker = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary)
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
                            text = "EXCLUDED APPS (SPLIT TUNNELING)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.sp
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
                        text = "Checked applications will bypass the VPN tunnel and connect directly through your physical Wi-Fi/Cellular interface:",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )

                    val installedApps = remember(context) {
                        try {
                            val pm = context.packageManager
                            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("chrome") || it.packageName.contains("browser") }
                                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    if (installedApps.isEmpty()) {
                        listOf(
                            Triple("Google Chrome", "com.android.chrome", "Browser"),
                            Triple("YouTube", "com.google.android.youtube", "Video"),
                            Triple("Spotify", "com.spotify.music", "Audio"),
                            Triple("WhatsApp", "com.whatsapp", "Messaging")
                        ).forEach { (appName, pkg, category) ->
                            val isChecked = disallowedApps.contains(pkg)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleDisallowedApp(pkg) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { viewModel.toggleDisallowedApp(pkg) },
                                    colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(appName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text("$pkg • $category", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    } else {
                        installedApps.forEach { appInfo ->
                            val pkg = appInfo.packageName
                            val label = try { context.packageManager.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg }
                            val isChecked = disallowedApps.contains(pkg)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleDisallowedApp(pkg) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { viewModel.toggleDisallowedApp(pkg) },
                                    colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(pkg, fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { showAppPicker = false },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = SurfaceDark),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
