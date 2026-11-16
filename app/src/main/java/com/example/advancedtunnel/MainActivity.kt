package com.example.advancedtunnel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.advancedtunnel.vpn.SingBoxConfigBuilder
import com.example.advancedtunnel.vpn.TunnelConnectionState
import com.example.advancedtunnel.vpn.TunnelStateHolder
import com.example.advancedtunnel.vpn.TunnelVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startActiveVpnService()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        prefs = getSharedPreferences("chestervpn_settings", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF070B14),
                    surface = Color(0xFF101827),
                    surfaceVariant = Color(0xFF162032),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC),
                    primary = Color(0xFF10B981),
                    secondary = Color(0xFF38BDF8)
                )
            ) {
                ChesterVpnMainApp(
                    prefs = prefs,
                    onConnectRequest = ::connectWithVpnConsent,
                    onDisconnectRequest = ::disconnectVpn
                )
            }
        }
    }

    private fun connectWithVpnConsent() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent == null) {
            startActiveVpnService()
        } else {
            vpnPermissionLauncher.launch(vpnIntent)
        }
    }

    private fun startActiveVpnService() {
        val activeConfig = loadActiveServerConfig(prefs)
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_CONNECT
            putExtra(TunnelVpnService.EXTRA_HOST, activeConfig.serverHost)
            putExtra(TunnelVpnService.EXTRA_PORT, activeConfig.serverPort)
            putExtra(TunnelVpnService.EXTRA_PROTOCOL, activeConfig.protocol.id)
            putExtra(TunnelVpnService.EXTRA_SNI, activeConfig.sniHost)
            putExtra(TunnelVpnService.EXTRA_UUID, activeConfig.uuid)
            putExtra(TunnelVpnService.EXTRA_REALITY_PUBLIC_KEY, activeConfig.realityPublicKey)
            putExtra(TunnelVpnService.EXTRA_REALITY_SHORT_ID, activeConfig.realityShortId)
            putExtra(TunnelVpnService.EXTRA_UTLS_FINGERPRINT, activeConfig.utlsFingerprint)
            putExtra(TunnelVpnService.EXTRA_DNS_SERVER, activeConfig.dnsServer)
            putExtra(TunnelVpnService.EXTRA_MTU, activeConfig.mtu)
            putExtra(TunnelVpnService.EXTRA_SERVER_NAME, activeConfig.name)
            putExtra("tunnel_ip", activeConfig.serverHost)
            putExtra("tunnel_port", activeConfig.serverPort.toString())
            putExtra("tunnel_sni", activeConfig.sniHost)
            putExtra("tunnel_protocol", activeConfig.protocol.id)
            putExtra("tunnel_payload", SingBoxConfigBuilder.buildClientConfigJson(activeConfig))
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun disconnectVpn() {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    companion object {
        fun loadActiveServerConfig(prefs: SharedPreferences): SingBoxConfigBuilder.ClientConfig {
            val rawJson = prefs.getString("active_server_json", null)
            if (!rawJson.isNullOrBlank()) {
                val parsed = parseConfigFromStorageJson(rawJson)
                if (parsed != null) return parsed
            }
            return SingBoxConfigBuilder.buildPresetProfiles()[0]
        }

        fun saveActiveServerConfig(prefs: SharedPreferences, config: SingBoxConfigBuilder.ClientConfig) {
            val json = serializeConfigToStorageJson(config)
            prefs.edit().putString("active_server_json", json).apply()
            TunnelStateHolder.setActiveServerName(config.name)
        }

        fun loadCustomServers(prefs: SharedPreferences): List<SingBoxConfigBuilder.ClientConfig> {
            val raw = prefs.getString("custom_servers_list", null) ?: return emptyList()
            return try {
                val array = JSONArray(raw)
                val list = mutableListOf<SingBoxConfigBuilder.ClientConfig>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseConfigFromStorageJson(obj.toString())?.let { list.add(it) }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun saveCustomServers(prefs: SharedPreferences, servers: List<SingBoxConfigBuilder.ClientConfig>) {
            val array = JSONArray()
            servers.forEach { s ->
                try {
                    array.put(JSONObject(serializeConfigToStorageJson(s)))
                } catch (_: Exception) {}
            }
            prefs.edit().putString("custom_servers_list", array.toString()).apply()
        }

        private fun serializeConfigToStorageJson(config: SingBoxConfigBuilder.ClientConfig): String {
            return JSONObject().apply {
                put("name", config.name)
                put("protocol", config.protocol.id)
                put("serverHost", config.serverHost)
                put("serverPort", config.serverPort)
                put("sniHost", config.sniHost)
                put("uuid", config.uuid)
                put("realityPublicKey", config.realityPublicKey)
                put("realityShortId", config.realityShortId)
                put("utlsFingerprint", config.utlsFingerprint)
                put("dnsServer", config.dnsServer)
                put("mtu", config.mtu)
                put("strictRoute", config.strictRoute)
            }.toString()
        }

        private fun parseConfigFromStorageJson(json: String): SingBoxConfigBuilder.ClientConfig? {
            return try {
                val o = JSONObject(json)
                SingBoxConfigBuilder.ClientConfig(
                    name = o.optString("name", "Custom Server"),
                    protocol = SingBoxConfigBuilder.Protocol.fromId(o.optString("protocol", "vless-reality")),
                    serverHost = o.optString("serverHost", SingBoxConfigBuilder.DEFAULT_SERVER_HOST),
                    serverPort = o.optInt("serverPort", SingBoxConfigBuilder.DEFAULT_VLESS_PORT),
                    sniHost = o.optString("sniHost", SingBoxConfigBuilder.DEFAULT_REALITY_SNI),
                    uuid = o.optString("uuid", SingBoxConfigBuilder.DEFAULT_UUID),
                    realityPublicKey = o.optString("realityPublicKey", SingBoxConfigBuilder.DEFAULT_REALITY_PUBLIC_KEY),
                    realityShortId = o.optString("realityShortId", SingBoxConfigBuilder.DEFAULT_REALITY_SHORT_ID),
                    utlsFingerprint = o.optString("utlsFingerprint", "chrome"),
                    dnsServer = o.optString("dnsServer", SingBoxConfigBuilder.DEFAULT_DNS_SERVER),
                    mtu = o.optInt("mtu", SingBoxConfigBuilder.DEFAULT_TUN_MTU),
                    strictRoute = o.optBoolean("strictRoute", true)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

private enum class UiNavTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Default.Shield),
    SERVERS("Servers", Icons.Default.Dns),
    SETTINGS("Settings", Icons.Default.Tune),
    LOGS("Live Logs", Icons.Default.Terminal)
}

private enum class UiTunnelPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@Composable
private fun ChesterVpnMainApp(
    prefs: SharedPreferences,
    onConnectRequest: () -> Unit,
    onDisconnectRequest: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(UiNavTab.HOME) }
    var activeConfig by remember { mutableStateOf(MainActivity.loadActiveServerConfig(prefs)) }
    val customServers = remember { mutableStateListOf<SingBoxConfigBuilder.ClientConfig>().apply {
        addAll(MainActivity.loadCustomServers(prefs))
    } }

    Scaffold(
        containerColor = Color(0xFF070B14),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0B1322),
                contentColor = Color(0xFF94A3B8),
                tonalElevation = 0.dp
            ) {
                UiNavTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF10B981),
                            selectedTextColor = Color(0xFF10B981),
                            unselectedIconColor = Color(0xFF64748B),
                            unselectedTextColor = Color(0xFF64748B),
                            indicatorColor = Color(0xFF102A24)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when (selectedTab) {
                UiNavTab.HOME -> HomeScreen(
                    activeConfig = activeConfig,
                    onConnectTap = onConnectRequest,
                    onDisconnectTap = onDisconnectRequest,
                    onChangeServerTap = { selectedTab = UiNavTab.SERVERS }
                )
                UiNavTab.SERVERS -> ServersScreen(
                    activeConfig = activeConfig,
                    customServers = customServers,
                    onSelectServer = { config ->
                        activeConfig = config
                        MainActivity.saveActiveServerConfig(prefs, config)
                        selectedTab = UiNavTab.HOME
                    },
                    onAddCustomServer = { newConfig ->
                        customServers.add(newConfig)
                        MainActivity.saveCustomServers(prefs, customServers)
                        activeConfig = newConfig
                        MainActivity.saveActiveServerConfig(prefs, newConfig)
                    },
                    onDeleteCustomServer = { config ->
                        customServers.remove(config)
                        MainActivity.saveCustomServers(prefs, customServers)
                        if (activeConfig == config) {
                            activeConfig = SingBoxConfigBuilder.buildPresetProfiles()[0]
                            MainActivity.saveActiveServerConfig(prefs, activeConfig)
                        }
                    }
                )
                UiNavTab.SETTINGS -> SettingsScreen(
                    activeConfig = activeConfig,
                    onSaveSettings = { updated ->
                        activeConfig = updated
                        MainActivity.saveActiveServerConfig(prefs, updated)
                    }
                )
                UiNavTab.LOGS -> LogsScreen()
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TAB 1: HOME SCREEN
// -------------------------------------------------------------------------------------------------
@Composable
private fun HomeScreen(
    activeConfig: SingBoxConfigBuilder.ClientConfig,
    onConnectTap: () -> Unit,
    onDisconnectTap: () -> Unit,
    onChangeServerTap: () -> Unit
) {
    val connectionState by TunnelStateHolder.connectionState.collectAsState()
    val connectedAtMillis by TunnelStateHolder.connectedAtMillis.collectAsState()
    val downloadBps by TunnelStateHolder.downloadBytesPerSec.collectAsState()
    val uploadBps by TunnelStateHolder.uploadBytesPerSec.collectAsState()
    val totalData by TunnelStateHolder.dataTransferred.collectAsState()

    val phase = when (connectionState) {
        is TunnelConnectionState.TunnelEstablished -> UiTunnelPhase.CONNECTED
        is TunnelConnectionState.Connecting,
        is TunnelConnectionState.Authenticating,
        is TunnelConnectionState.Reconnecting -> UiTunnelPhase.CONNECTING
        is TunnelConnectionState.Disconnected,
        is TunnelConnectionState.Error -> UiTunnelPhase.DISCONNECTED
    }

    val errorMessage = (connectionState as? TunnelConnectionState.Error)?.message

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase, connectedAtMillis) {
        if (phase == UiTunnelPhase.CONNECTED && connectedAtMillis != null) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }
    val sessionSeconds = if (phase == UiTunnelPhase.CONNECTED && connectedAtMillis != null) {
        ((nowMillis - (connectedAtMillis ?: nowMillis)) / 1000L).coerceAtLeast(0L)
    } else {
        0L
    }

    var pingMs by remember { mutableStateOf<Long?>(null) }
    var isProbingPing by remember { mutableStateOf(false) }
    val pingScope = rememberCoroutineScope()

    suspend fun refreshPing() {
        isProbingPing = true
        pingMs = measureServerPingMs(activeConfig.serverHost, activeConfig.serverPort)
        isProbingPing = false
    }

    LaunchedEffect(activeConfig) {
        refreshPing()
    }

    val emerald = Color(0xFF10B981)
    val amber = Color(0xFFF59E0B)
    val slate = Color(0xFF475569)

    val accentColor by animateColorAsState(
        targetValue = when (phase) {
            UiTunnelPhase.CONNECTED -> emerald
            UiTunnelPhase.CONNECTING -> amber
            UiTunnelPhase.DISCONNECTED -> slate
        },
        animationSpec = tween(durationMillis = 350),
        label = "accentColor"
    )

    val buttonFillColor by animateColorAsState(
        targetValue = when (phase) {
            UiTunnelPhase.CONNECTED -> Color(0xFF064E3B)
            UiTunnelPhase.CONNECTING -> Color(0xFF451A03)
            UiTunnelPhase.DISCONNECTED -> Color(0xFF162032)
        },
        animationSpec = tween(durationMillis = 350),
        label = "buttonFillColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F1E2E))
                        .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "ChesterVPN Logo",
                        tint = if (phase == UiTunnelPhase.CONNECTED) emerald else Color(0xFF38BDF8),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "ChesterVPN",
                        color = Color(0xFFF8FAFC),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    )
                    Text(
                        text = "sing-box Anti-DPI Core",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = activeConfig.protocol.id.uppercase(),
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Center: Power Button & Status
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(210.dp),
                contentAlignment = Alignment.Center
            ) {
                if (phase != UiTunnelPhase.DISCONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(165.dp)
                            .scale(pulseScale)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.35f))
                    )
                }

                Box(
                    modifier = Modifier
                        .size(178.dp)
                        .clip(CircleShape)
                        .border(2.dp, accentColor.copy(alpha = 0.35f), CircleShape)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(buttonFillColor)
                            .border(2.5.dp, accentColor, CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (phase == UiTunnelPhase.DISCONNECTED) {
                                    onConnectTap()
                                } else {
                                    onDisconnectTap()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = if (phase == UiTunnelPhase.DISCONNECTED) "Connect" else "Disconnect",
                                tint = if (phase == UiTunnelPhase.DISCONNECTED) Color(0xFFF8FAFC) else accentColor,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when (phase) {
                                    UiTunnelPhase.CONNECTED -> "DISCONNECT"
                                    UiTunnelPhase.CONNECTING -> "CANCEL"
                                    UiTunnelPhase.DISCONNECTED -> "CONNECT"
                                },
                                color = if (phase == UiTunnelPhase.DISCONNECTED) Color(0xFF94A3B8) else accentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                color = accentColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (phase) {
                            UiTunnelPhase.CONNECTED -> "Protected & Encrypted"
                            UiTunnelPhase.CONNECTING -> "Establishing Secure Tunnel…"
                            UiTunnelPhase.DISCONNECTED -> "Tap to Connect"
                        },
                        color = if (phase == UiTunnelPhase.DISCONNECTED) Color(0xFFF8FAFC) else accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!errorMessage.isNullOrBlank() && phase == UiTunnelPhase.DISCONNECTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = Color(0xFFF87171),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Bottom: Live Stats + Active Server Card
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedVisibility(
                visible = phase == UiTunnelPhase.CONNECTED,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF101827),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, emerald.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatCell(icon = Icons.Default.Timer, label = "DURATION", value = formatDuration(sessionSeconds), tint = emerald)
                        StatCell(icon = Icons.Default.ArrowDownward, label = "DOWNLOAD", value = formatSpeed(downloadBps), tint = Color(0xFF38BDF8))
                        StatCell(icon = Icons.Default.ArrowUpward, label = "UPLOAD", value = formatSpeed(uploadBps), tint = Color(0xFFA78BFA))
                    }
                }
            }

            // Server Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChangeServerTap() },
                color = Color(0xFF101827),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF172235)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = "Server",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = activeConfig.name,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${activeConfig.serverHost}:${activeConfig.serverPort} · SNI: ${activeConfig.sniHost}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val badgeColor = when {
                            pingMs != null -> emerald
                            isProbingPing -> amber
                            else -> Color(0xFF94A3B8)
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable { pingScope.launch { refreshPing() } }
                        ) {
                            Text(
                                text = when {
                                    isProbingPing -> "PING …"
                                    pingMs != null -> "${pingMs}ms"
                                    else -> "TEST"
                                },
                                color = badgeColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFF1E293B))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tap to switch server profile",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "uTLS ${activeConfig.utlsFingerprint.uppercase()}",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TAB 2: SERVERS SCREEN
// -------------------------------------------------------------------------------------------------
@Composable
private fun ServersScreen(
    activeConfig: SingBoxConfigBuilder.ClientConfig,
    customServers: List<SingBoxConfigBuilder.ClientConfig>,
    onSelectServer: (SingBoxConfigBuilder.ClientConfig) -> Unit,
    onAddCustomServer: (SingBoxConfigBuilder.ClientConfig) -> Unit,
    onDeleteCustomServer: (SingBoxConfigBuilder.ClientConfig) -> Unit
) {
    val presets = remember { SingBoxConfigBuilder.buildPresetProfiles() }
    val allServers = remember(customServers) { presets + customServers }
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importUrlInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Server Profiles",
                    color = Color(0xFFF8FAFC),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${allServers.size} Anti-DPI routes configured",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (clip.startsWith("vless://", ignoreCase = true)) {
                        importUrlInput = clip
                    }
                    showImportDialog = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Import", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(allServers) { server ->
                val isSelected = server.serverHost == activeConfig.serverHost &&
                        server.serverPort == activeConfig.serverPort &&
                        server.sniHost == activeConfig.sniHost &&
                        server.protocol == activeConfig.protocol

                ServerItemCard(
                    config = server,
                    isSelected = isSelected,
                    isCustom = customServers.contains(server),
                    onSelect = { onSelectServer(server) },
                    onDelete = { onDeleteCustomServer(server) }
                )
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = Color(0xFF101827),
            titleColor = Color(0xFFF8FAFC),
            title = { Text("Import VLESS-Reality Link") },
            text = {
                Column {
                    Text(
                        "Paste a standard vless:// link exported from sing-box, Xray, or your server:",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importUrlInput,
                        onValueChange = { importUrlInput = it },
                        placeholder = { Text("vless://uuid@host:443?...", color = Color(0xFF64748B), fontSize = 12.sp) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color(0xFFF8FAFC),
                            unfocusedTextColor = Color(0xFFF8FAFC)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = SingBoxConfigBuilder.parseVlessUrl(importUrlInput)
                        if (parsed != null) {
                            onAddCustomServer(parsed)
                            showImportDialog = false
                            Toast.makeText(context, "Server imported: ${parsed.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid VLESS URL format", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Save Server")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

@Composable
private fun ServerItemCard(
    config: SingBoxConfigBuilder.ClientConfig,
    isSelected: Boolean,
    isCustom: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var pingMs by remember { mutableStateOf<Long?>(null) }
    var isCheckingPing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        color = if (isSelected) Color(0xFF0F2321) else Color(0xFF101827),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Public,
                    contentDescription = null,
                    tint = if (isSelected) Color(0xFF10B981) else Color(0xFF64748B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = config.name,
                        color = Color(0xFFF8FAFC),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${config.serverHost}:${config.serverPort} · SNI: ${config.sniHost}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFF162032),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable {
                        scope.launch {
                            isCheckingPing = true
                            pingMs = measureServerPingMs(config.serverHost, config.serverPort)
                            isCheckingPing = false
                        }
                    }
                ) {
                    Text(
                        text = when {
                            isCheckingPing -> "…"
                            pingMs != null -> "${pingMs}ms"
                            else -> "Ping"
                        },
                        color = if (pingMs != null) Color(0xFF10B981) else Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                if (isCustom) {
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TAB 3: SETTINGS SCREEN
// -------------------------------------------------------------------------------------------------
@Composable
private fun SettingsScreen(
    activeConfig: SingBoxConfigBuilder.ClientConfig,
    onSaveSettings: (SingBoxConfigBuilder.ClientConfig) -> Unit
) {
    val context = LocalContext.current
    var sniInput by remember(activeConfig) { mutableStateOf(activeConfig.sniHost) }
    var selectedUtls by remember(activeConfig) { mutableStateOf(activeConfig.utlsFingerprint) }
    var mtuValue by remember(activeConfig) { mutableFloatStateOf(activeConfig.mtu.toFloat()) }
    var dnsInput by remember(activeConfig) { mutableStateOf(activeConfig.dnsServer) }
    var strictRoute by remember(activeConfig) { mutableStateOf(activeConfig.strictRoute) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Anti-DPI & Protocol Settings",
            color = Color(0xFFF8FAFC),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Tune camouflage parameters for STC, Mobily, or international networks",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // SNI Camouflage Section
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101827)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("TLS SNI Camouflage", color = Color(0xFFF8FAFC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Fronting domain presented to network inspection probes", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("www.microsoft.com", "www.stc.com.sa", "www.mobily.com.sa").forEach { preset ->
                                Surface(
                                    color = if (sniInput == preset) Color(0xFF0F2E23) else Color(0xFF162032),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (sniInput == preset) Color(0xFF10B981) else Color(0xFF1E293B)),
                                    modifier = Modifier.clickable { sniInput = preset }
                                ) {
                                    Text(
                                        text = preset.substringAfter("www.").substringBefore("."),
                                        color = if (sniInput == preset) Color(0xFF10B981) else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = sniInput,
                            onValueChange = { sniInput = it },
                            label = { Text("Custom SNI Hostname") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF10B981),
                                unfocusedBorderColor = Color(0xFF1E293B),
                                focusedTextColor = Color(0xFFF8FAFC),
                                unfocusedTextColor = Color(0xFFF8FAFC)
                            )
                        )
                    }
                }
            }

            // uTLS Fingerprint Section
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101827)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("uTLS JA4 ClientHello Profile", color = Color(0xFFF8FAFC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Mimics real browser TLS signatures to defeat machine learning DPI", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("chrome", "ios", "firefox", "safari").forEach { fp ->
                                Surface(
                                    color = if (selectedUtls == fp) Color(0xFF0F2E23) else Color(0xFF162032),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (selectedUtls == fp) Color(0xFF10B981) else Color(0xFF1E293B)),
                                    modifier = Modifier.clickable { selectedUtls = fp }
                                ) {
                                    Text(
                                        text = fp.uppercase(),
                                        color = if (selectedUtls == fp) Color(0xFF10B981) else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // MTU and DNS Section
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101827)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tunnel MTU: ${mtuValue.toInt()}", color = Color(0xFFF8FAFC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Default: 1400", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Slider(
                            value = mtuValue,
                            onValueChange = { mtuValue = it },
                            valueRange = 1280f..1500f,
                            steps = 21,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF10B981),
                                activeTrackColor = Color(0xFF10B981),
                                inactiveTrackColor = Color(0xFF1E293B)
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Hijacked DNS Resolver", color = Color(0xFFF8FAFC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google", "9.9.9.9" to "Quad9").forEach { (ip, label) ->
                                Surface(
                                    color = if (dnsInput == ip) Color(0xFF0F2E23) else Color(0xFF162032),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (dnsInput == ip) Color(0xFF10B981) else Color(0xFF1E293B)),
                                    modifier = Modifier.clickable { dnsInput = ip }
                                ) {
                                    Text(
                                        text = "$label ($ip)",
                                        color = if (dnsInput == ip) Color(0xFF10B981) else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Strict Route / Kill Switch
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101827)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Strict Routing (Kill Switch)", color = Color(0xFFF8FAFC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Blocks traffic leaks if tunnel unexpectedly drops", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                        Switch(
                            checked = strictRoute,
                            onCheckedChange = { strictRoute = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF10B981),
                                checkedTrackColor = Color(0xFF0F2E23),
                                uncheckedThumbColor = Color(0xFF64748B),
                                uncheckedTrackColor = Color(0xFF1E293B)
                            )
                        )
                    }
                }
            }

            // Save Buttons
            item {
                Button(
                    onClick = {
                        val updated = activeConfig.copy(
                            sniHost = sniInput.trim().ifEmpty { SingBoxConfigBuilder.DEFAULT_REALITY_SNI },
                            utlsFingerprint = selectedUtls,
                            mtu = mtuValue.toInt(),
                            dnsServer = dnsInput.trim().ifEmpty { "1.1.1.1" },
                            strictRoute = strictRoute
                        )
                        onSaveSettings(updated)
                        Toast.makeText(context, "Settings saved. Reconnect to apply.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Apply & Save Settings", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TAB 4: LIVE LOGS SCREEN
// -------------------------------------------------------------------------------------------------
@Composable
private fun LogsScreen() {
    val logs by TunnelStateHolder.liveLogs.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Live sing-box Logs",
                    color = Color(0xFFF8FAFC),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${logs.size} log entries captured from libbox core",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }

            Row {
                IconButton(onClick = {
                    val text = logs.joinToString("\n")
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ChesterVPN Logs", text))
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF38BDF8))
                }
                IconButton(onClick = { TunnelStateHolder.clearLogs() }) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Clear", tint = Color(0xFFF87171))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
            color = Color(0xFF050810),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No logs yet.\nConnect VPN to observe sing-box core runtime logs.",
                        color = Color(0xFF475569),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    items(logs) { line ->
                        val isError = line.contains("error", ignoreCase = true) || line.contains("FATAL", ignoreCase = true)
                        val isWarn = line.contains("warn", ignoreCase = true)
                        Text(
                            text = line,
                            color = when {
                                isError -> Color(0xFFF87171)
                                isWarn -> Color(0xFFFBBF24)
                                else -> Color(0xFF94A3B8)
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// HELPERS
// -------------------------------------------------------------------------------------------------
@Composable
private fun StatCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = Color(0xFFF8FAFC),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

private suspend fun measureServerPingMs(host: String, port: Int): Long? = withContext(Dispatchers.IO) {
    try {
        val start = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2500)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        elapsedMs.coerceAtLeast(1L)
    } catch (_: Exception) {
        null
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB/s", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB/s", kb)
        else -> "$bytesPerSec B/s"
    }
}
