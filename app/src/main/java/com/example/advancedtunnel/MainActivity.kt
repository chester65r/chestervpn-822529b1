package com.example.advancedtunnel

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.advancedtunnel.firebase.configureAppCheck
import com.example.advancedtunnel.ui.TunnelApp
import com.example.advancedtunnel.ui.theme.TunnelTheme
import com.example.advancedtunnel.viewmodel.TunnelViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TunnelViewModel by viewModels()

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onVpnPermissionGranted(this)
            } else {
                Toast.makeText(this, "VPN permission required to create secure tunnel", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory App Check initialization with launch intent before UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            TunnelTheme {
                TunnelApp(
                    viewModel = viewModel,
                    onRequestVpnPermission = {
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            vpnConsentLauncher.launch(prepareIntent)
                        } else {
                            viewModel.onVpnPermissionGranted(this)
                        }
                    }
                )
            }
        }
    }
}
