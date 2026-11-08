package com.example.advancedtunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.ui.TunnelConsoleScreen
import com.example.advancedtunnel.vpn.TunnelVpnService

class MainActivity : ComponentActivity() {

    private var pendingProfile: TunnelProfile? = null
    private var pendingWireGuardStart: Boolean = false

    private lateinit var store: SecureConfigStore
    private lateinit var enrollment: DeviceEnrollment

    private var devicePublicKeyState by mutableStateOf("")
    private var wireGuardStatusState by mutableStateOf("DOWN (Idle)")

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val profile = pendingProfile
            val startWg = pendingWireGuardStart
            pendingProfile = null
            pendingWireGuardStart = false

            if (result.resultCode == RESULT_OK) {
                if (startWg) {
                    startWireGuardEngine()
                } else if (profile != null) {
                    startVpnService(profile)
                }
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        store = SecureConfigStore(this)
        enrollment = DeviceEnrollment(this)

        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                TunnelConsoleScreen(
                    onConnect = ::requestVpnPermissionAndConnect,
                    onDisconnect = ::disconnectVpn,
                    devicePublicKey = devicePublicKeyState,
                    wireGuardStatus = wireGuardStatusState,
                    onGenerateDeviceIdentity = ::generateDeviceIdentity,
                    onSaveWireGuardEnrollment = ::saveWireGuardEnrollment,
                    onToggleWireGuard = ::toggleWireGuardEngine,
                    onClearWireGuardCredentials = ::clearWireGuardCredentials
                )
            }
        }
    }

    private fun generateDeviceIdentity() {
        var pubKey = ""
        TunnelController.whileIdle({ pubKey = enrollment.publicKey() }) { ok ->
            runOnUiThread {
                if (ok) {
                    devicePublicKeyState = pubKey
                    Toast.makeText(this, "Device public key generated in Keystore", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Stop the tunnel before generating identity", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveWireGuardEnrollment(enrollmentResponse: String, trustedServerKey: String) {
        TunnelController.whileIdle({
            enrollment.enroll(enrollmentResponse, trustedServerKey)
        }) { ok ->
            runOnUiThread {
                if (ok) {
                    wireGuardStatusState = "ENROLLED (Ready)"
                    Toast.makeText(this, "Enrollment verified and encrypted in Keystore", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Enrollment rejected: check server key, full-tunnel routes, or stop active tunnel",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun toggleWireGuardEngine() {
        when (TunnelController.engineState) {
            EngineState.UP -> {
                TunnelController.down(this) { stopped ->
                    runOnUiThread {
                        wireGuardStatusState = if (stopped) {
                            "DOWN (Disconnected)"
                        } else {
                            "STOP FAILED: tunnel still active and blocking traffic"
                        }
                    }
                }
            }
            EngineState.DOWN -> {
                if (store.load() == null) {
                    Toast.makeText(this, "Enroll this device first", Toast.LENGTH_SHORT).show()
                    return
                }
                pendingWireGuardStart = true
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent == null) {
                    pendingWireGuardStart = false
                    startWireGuardEngine()
                } else {
                    vpnPermissionLauncher.launch(vpnIntent)
                }
            }
            else -> {
                Toast.makeText(this, "Engine busy, please wait", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startWireGuardEngine() {
        val configText = store.load() ?: return
        wireGuardStatusState = "STARTING..."
        TunnelController.up(this, configText) { error ->
            runOnUiThread {
                wireGuardStatusState = error ?: "UP (WireGuard Active on 2.29.8.194:51820)"
            }
        }
    }

    private fun clearWireGuardCredentials() {
        TunnelController.whileIdle({ enrollment.clear() }) { ok ->
            runOnUiThread {
                if (ok) {
                    devicePublicKeyState = ""
                    wireGuardStatusState = "DOWN (Credentials Cleared)"
                    Toast.makeText(this, "Keystore credentials deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Stop the tunnel before clearing credentials", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestVpnPermissionAndConnect(profile: TunnelProfile) {
        pendingProfile = profile
        pendingWireGuardStart = false

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
