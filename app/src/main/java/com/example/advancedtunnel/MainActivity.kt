package com.example.advancedtunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class MainActivity : ComponentActivity() {

    private companion object {
        const val HETZNER_HOST = "2.29.8.194"
        const val HETZNER_PORT = 443
        const val HETZNER_PROTOCOL = "vless-reality"
        const val HETZNER_SNI = "www.microsoft.com"
        const val HETZNER_PUBLIC_KEY = "2I0TJ_Jp7TufY2ZyYWW97BRuHoCAiKrUDWjRGXvC8Es"
        const val HETZNER_SHORT_ID = "2f09854ba76a8389"
        const val HETZNER_UUID = "2df2fc0f-f185-47ad-bc88-d2182c52d1b0"
        const val HETZNER_UTLS = "chrome"
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startPreconfiguredVpnService()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF070B14),
                    surface = Color(0xFF101827),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC),
                    primary = Color(0xFF10B981)
                )
            ) {
                ChesterVpnHomeScreen(
                    onConnectTap = ::connectWithOneTap,
                    onDisconnectTap = ::disconnectVpn
                )
            }
        }
    }

    private fun connectWithOneTap() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent == null) {
            startPreconfiguredVpnService()
        } else {
            vpnPermissionLauncher.launch(vpnIntent)
        }
    }

    private fun startPreconfiguredVpnService() {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_CONNECT
            putExtra(TunnelVpnService.EXTRA_HOST, HETZNER_HOST)
            putExtra(TunnelVpnService.EXTRA_PORT, HETZNER_PORT)
            putExtra(TunnelVpnService.EXTRA_PROTOCOL, HETZNER_PROTOCOL)
            putExtra(TunnelVpnService.EXTRA_SNI, HETZNER_SNI)
            putExtra(TunnelVpnService.EXTRA_UUID, HETZNER_UUID)
            putExtra(TunnelVpnService.EXTRA_REALITY_PUBLIC_KEY, HETZNER_PUBLIC_KEY)
            putExtra(TunnelVpnService.EXTRA_REALITY_SHORT_ID, HETZNER_SHORT_ID)
            putExtra(TunnelVpnService.EXTRA_UTLS_FINGERPRINT, HETZNER_UTLS)
            putExtra(TunnelVpnService.EXTRA_FRAGMENT_ENABLED, true)
            putExtra(TunnelVpnService.EXTRA_RECORD_FRAGMENT_ENABLED, true)
            putExtra("tunnel_ip", HETZNER_HOST)
            putExtra("tunnel_port", HETZNER_PORT.toString())
            putExtra("tunnel_sni", HETZNER_SNI)
            putExtra("tunnel_protocol", HETZNER_PROTOCOL)
            putExtra("tunnel_payload", buildDefaultConfigJson())
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun buildDefaultConfigJson(): String {
        return SingBoxConfigBuilder.buildClientConfigJson(
            SingBoxConfigBuilder.ClientConfig(
                protocol = SingBoxConfigBuilder.Protocol.VLESS_REALITY,
                serverHost = HETZNER_HOST,
                serverPort = HETZNER_PORT,
                sniHost = HETZNER_SNI,
                uuid = HETZNER_UUID,
                realityPublicKey = HETZNER_PUBLIC_KEY,
                realityShortId = HETZNER_SHORT_ID,
                utlsFingerprint = HETZNER_UTLS,
                tlsFragmentEnabled = true,
                tlsRecordFragmentEnabled = true
            )
        )
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
}

private enum class UiTunnelPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@Composable
private fun ChesterVpnHomeScreen(
    onConnectTap: () -> Unit,
    onDisconnectTap: () -> Unit
) {
    val connectionState by TunnelStateHolder.connectionState.collectAsState()
    val connectedAtMillis by TunnelStateHolder.connectedAtMillis.collectAsState()
    val downloadBps by TunnelStateHolder.downloadBytesPerSec.collectAsState()
    val uploadBps by TunnelStateHolder.uploadBytesPerSec.collectAsState()

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
        pingMs = measureServerPingMs("2.29.8.194", 443)
        isProbingPing = false
    }

    LaunchedEffect(Unit) {
        refreshPing()
    }

    val bgColor = Color(0xFF070B14)
    val surfaceColor = Color(0xFF101827)
    val borderColor = Color(0xFF1E293B)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFF94A3B8)

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
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0B1220), bgColor, Color(0xFF050810))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Bar: Logo + Title + Subtitle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
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
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "ChesterVPN",
                    color = textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                )
                Text(
                    text = "Anti-DPI Secure Tunnel",
                    color = textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Center: Prominent Circular Power/Connect Button & Status Text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(220.dp),
                contentAlignment = Alignment.Center
            ) {
                if (phase != UiTunnelPhase.DISCONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(168.dp)
                            .scale(pulseScale)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.35f))
                    )
                }

                // Outer ring
                Box(
                    modifier = Modifier
                        .size(182.dp)
                        .clip(CircleShape)
                        .border(2.dp, accentColor.copy(alpha = 0.35f), CircleShape)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Main Power Toggle Button
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
                                contentDescription = if (phase == UiTunnelPhase.DISCONNECTED) {
                                    "Connect VPN"
                                } else {
                                    "Disconnect VPN"
                                },
                                tint = if (phase == UiTunnelPhase.DISCONNECTED) textPrimary else accentColor,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when (phase) {
                                    UiTunnelPhase.CONNECTED -> "DISCONNECT"
                                    UiTunnelPhase.CONNECTING -> "CANCEL"
                                    UiTunnelPhase.DISCONNECTED -> "CONNECT"
                                },
                                color = if (phase == UiTunnelPhase.DISCONNECTED) textSecondary else accentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Status Badge + Primary Status Text
            Surface(
                color = accentColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
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
                        color = if (phase == UiTunnelPhase.DISCONNECTED) textPrimary else accentColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!errorMessage.isNullOrBlank() && phase == UiTunnelPhase.DISCONNECTED) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage,
                    color = Color(0xFFF87171),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Bottom: Server Card & Live Stats (Stats shown ONLY when connected)
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
                    color = surfaceColor,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, emerald.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatCell(
                            icon = Icons.Default.Timer,
                            label = "DURATION",
                            value = formatDuration(sessionSeconds),
                            tint = emerald
                        )
                        StatCell(
                            icon = Icons.Default.ArrowDownward,
                            label = "DOWNLOAD",
                            value = formatSpeed(downloadBps),
                            tint = Color(0xFF38BDF8)
                        )
                        StatCell(
                            icon = Icons.Default.ArrowUpward,
                            label = "UPLOAD",
                            value = formatSpeed(uploadBps),
                            tint = Color(0xFFA78BFA)
                        )
                    }
                }
            }

            // Server Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isProbingPing) {
                        pingScope.launch { refreshPing() }
                    },
                color = surfaceColor,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, borderColor)
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
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF172235)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = "Server Location",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Hetzner Frankfurt (2.29.8.194)",
                                    color = textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "VLESS-Reality · TLS 1.3 Chrome",
                                    color = textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Automatic Ping / Location Badge
                        val badgeColor = when {
                            pingMs != null -> emerald
                            isProbingPing -> amber
                            else -> textSecondary
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = when {
                                    isProbingPing -> "DE · …"
                                    pingMs != null -> "DE · ${pingMs}ms"
                                    else -> "DE · Auto"
                                },
                                color = badgeColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = borderColor
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "One-tap encrypted routing",
                            color = textSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Port 443",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

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
                modifier = Modifier.size(13.dp)
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = Color(0xFFF8FAFC),
            fontSize = 14.sp,
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
