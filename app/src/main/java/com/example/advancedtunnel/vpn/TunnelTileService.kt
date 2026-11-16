package com.example.advancedtunnel.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.example.advancedtunnel.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android System Quick Settings TileService (`TunnelTileService`) allowing one-tap VPN
 * connection toggling for ChesterVPN directly from the notification shade.
 */
class TunnelTileService : TileService() {

    private var tileScope: CoroutineScope? = null
    private var stateCollectorJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        tileScope = scope
        stateCollectorJob = scope.launch {
            TunnelStateHolder.connectionState.collect { state ->
                syncTileWithState(state)
            }
        }
    }

    override fun onStopListening() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        tileScope?.cancel()
        tileScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentState = TunnelStateHolder.connectionState.value
        val isActiveOrConnecting = currentState is TunnelConnectionState.TunnelEstablished ||
            currentState is TunnelConnectionState.Connecting ||
            currentState is TunnelConnectionState.Authenticating ||
            currentState is TunnelConnectionState.Reconnecting

        if (isActiveOrConnecting) {
            val stopIntent = Intent(applicationContext, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_DISCONNECT
            }
            applicationContext.startService(stopIntent)
            syncTileWithState(TunnelConnectionState.Disconnected)
            return
        }

        if (VpnService.prepare(applicationContext) != null) {
            openMainActivityFromTile()
            return
        }

        val connectIntent = Intent(applicationContext, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_CONNECT
            putExtra(TunnelVpnService.EXTRA_HOST, SingBoxConfigBuilder.DEFAULT_SERVER_HOST)
            putExtra(TunnelVpnService.EXTRA_PORT, SingBoxConfigBuilder.DEFAULT_VLESS_PORT)
            putExtra(TunnelVpnService.EXTRA_PROTOCOL, SingBoxConfigBuilder.Protocol.VLESS_REALITY.id)
            putExtra(TunnelVpnService.EXTRA_SNI, SingBoxConfigBuilder.DEFAULT_REALITY_SNI)
            putExtra(TunnelVpnService.EXTRA_UUID, SingBoxConfigBuilder.DEFAULT_UUID)
            putExtra(TunnelVpnService.EXTRA_REALITY_PUBLIC_KEY, SingBoxConfigBuilder.DEFAULT_REALITY_PUBLIC_KEY)
            putExtra(TunnelVpnService.EXTRA_REALITY_SHORT_ID, SingBoxConfigBuilder.DEFAULT_REALITY_SHORT_ID)
            putExtra(TunnelVpnService.EXTRA_UTLS_FINGERPRINT, "chrome")
        }
        ContextCompat.startForegroundService(applicationContext, connectIntent)
    }

    private fun syncTileWithState(state: TunnelConnectionState) {
        val tile = qsTile ?: return
        when (state) {
            is TunnelConnectionState.TunnelEstablished -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "ChesterVPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Protected & Encrypted"
                }
            }
            is TunnelConnectionState.Connecting,
            is TunnelConnectionState.Authenticating,
            is TunnelConnectionState.Reconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "ChesterVPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connecting…"
                }
            }
            is TunnelConnectionState.Disconnected,
            is TunnelConnectionState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "ChesterVPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to Connect"
                }
            }
        }
        tile.updateTile()
    }

    private fun openMainActivityFromTile() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}
