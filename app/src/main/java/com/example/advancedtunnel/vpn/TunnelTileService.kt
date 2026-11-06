package com.example.advancedtunnel.vpn

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.advancedtunnel.MainActivity
import com.example.advancedtunnel.data.TunnelRepository
import com.example.advancedtunnel.model.TunnelUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android System Quick Settings TileService (`TunnelTileService`) allowing one-tap VPN
 * connection toggling directly from the notification shade without opening the app.
 */
class TunnelTileService : TileService() {

    private var tileScope: CoroutineScope? = null
    private var stateCollectorJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        tileScope = scope
        stateCollectorJob = scope.launch {
            VpnManager.vpnState.collect { state ->
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
        val currentState = VpnManager.vpnState.value
        if (currentState is RealVpnState.Connected || currentState is RealVpnState.Connecting) {
            VpnManager.stopVpn(applicationContext)
            syncTileWithState(RealVpnState.Disconnected)
            return
        }

        val repository = TunnelRepository(applicationContext)
        val (profile, _) = repository.loadActiveProfile()
        val validationErrors = TunnelUtils.validateProfile(profile)

        if (validationErrors.isNotEmpty() || !VpnManager.isVpnPrepared(applicationContext)) {
            openMainActivityFromTile()
            return
        }

        val killswitch = repository.loadKillswitchEnabled()
        val splitEnabled = repository.loadSplitTunnelingEnabled()
        val disallowed = if (splitEnabled) repository.loadDisallowedApps().toList() else emptyList()

        VpnManager.startVpn(
            context = applicationContext,
            profile = profile,
            killswitchEnabled = killswitch,
            disallowedApps = disallowed
        )
    }

    private fun syncTileWithState(state: RealVpnState) {
        val tile = qsTile ?: return
        when (state) {
            is RealVpnState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Tunnel Active"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = state.serverEndpoint
                }
            }
            is RealVpnState.Connecting, is RealVpnState.Reconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Connecting…"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Establishing TUN"
                }
            }
            is RealVpnState.Disconnected, is RealVpnState.Failed -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Tunnel Console"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to connect"
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
