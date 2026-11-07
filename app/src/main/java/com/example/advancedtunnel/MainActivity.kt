package com.example.advancedtunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.ui.TunnelConsoleScreen
import com.example.advancedtunnel.vpn.TunnelVpnService

class MainActivity : ComponentActivity() {

    private var pendingProfile: TunnelProfile? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val profile = pendingProfile
            pendingProfile = null

            if (result.resultCode == RESULT_OK && profile != null) {
                startVpnService(profile)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // Notification permission granted.
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                TunnelConsoleScreen(
                    onConnect = ::requestVpnPermissionAndConnect,
                    onDisconnect = ::disconnectVpn
                )
            }
        }
    }

    private fun requestVpnPermissionAndConnect(profile: TunnelProfile) {
        pendingProfile = profile

        val vpnIntent = VpnService.prepare(this)

        if (vpnIntent == null) {
            startVpnService(profile)
        } else {
            vpnPermissionLauncher.launch(vpnIntent)
        }
    }

    private fun startVpnService(profile: TunnelProfile) {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_CONNECT
            putExtra("tunnel_ip", profile.ip)
            putExtra("tunnel_port", profile.port)
            putExtra("tunnel_sni", profile.sni)
            putExtra("tunnel_payload", profile.payload)
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

            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }
}
