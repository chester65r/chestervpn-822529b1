package com.example.advancedtunnel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.ui.components.ConnectionStatusIndicator
import com.example.advancedtunnel.ui.screens.ConfigScreen
import com.example.advancedtunnel.ui.screens.HomeScreen
import com.example.advancedtunnel.ui.screens.ServersScreen
import com.example.advancedtunnel.ui.theme.BackgroundDark
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.viewmodel.TunnelViewModel

enum class NavTab(val title: String, val icon: ImageVector) {
    HOME("Connect", Icons.Default.PowerSettingsNew),
    CONFIG("Configure", Icons.Default.Tune),
    SERVERS("Nodes & Vault", Icons.Default.Dns)
}

@Composable
fun TunnelApp(
    viewModel: TunnelViewModel,
    onRequestVpnPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentTab by remember { mutableStateOf(NavTab.HOME) }
    val status by viewModel.status.collectAsState()
    val tlsConfig by viewModel.tlsFragmentConfig.collectAsState()

    if (currentTab != NavTab.HOME) {
        BackHandler {
            currentTab = NavTab.HOME
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark),
        containerColor = BackgroundDark,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundDark)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardBackground)
                                .border(1.dp, CyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Tunnel Console",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                letterSpacing = (-0.3).sp
                            )
                            Text(
                                text = if (tlsConfig.enabled) "Anti-DPI ${tlsConfig.normalizedMinChunk()}–${tlsConfig.normalizedMaxChunk()}B • JA4 Active" else "Standard TLS Tunnel",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }

                    ConnectionStatusIndicator(status = status)
                }
                HorizontalDivider(color = BorderDark.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = BorderDark.copy(alpha = 0.6f), thickness = 0.5.dp)
                NavigationBar(
                    containerColor = CardBackground,
                    tonalElevation = 0.dp,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    NavTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = SurfaceDark,
                                selectedTextColor = CyanPrimary,
                                indicatorColor = CyanPrimary,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            ),
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                NavTab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onRequestVpnPermission = onRequestVpnPermission,
                    onNavigateToConfig = { currentTab = NavTab.CONFIG },
                    onNavigateToServers = { currentTab = NavTab.SERVERS }
                )
                NavTab.CONFIG -> ConfigScreen(viewModel = viewModel)
                NavTab.SERVERS -> ServersScreen(
                    viewModel = viewModel,
                    onServerApplied = { currentTab = NavTab.HOME }
                )
            }
        }
    }
}
