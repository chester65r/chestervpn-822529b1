package com.example.advancedtunnel

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.advancedtunnel.databinding.ActivityMainBinding

/**
 * Main Activity for Advanced Tunnel VPN
 * Handles VPN configuration and service management
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isVpnActive = false
    private val VPN_REQUEST_CODE = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved configuration
        loadSavedProfile()

        // Set up button click listener
        binding.btnToggleVpn.setOnClickListener {
            if (isVpnActive) {
                stopVpnService()
            } else {
                if (validateInputs()) {
                    saveCurrentProfile()
                    startVpnAuthorization()
                }
            }
        }
    }

    /**
     * Validate user inputs
     */
    private fun validateInputs(): Boolean {
        val ip = binding.etServerIp.text.toString().trim()
        val port = binding.etServerPort.text.toString().trim()
        val sni = binding.etSniHost.text.toString().trim()

        if (ip.isEmpty()) {
            binding.etServerIp.error = "Server IP cannot be empty"
            return false
        }

        if (port.isEmpty() || port.toIntOrNull() == null || port.toInt() !in 1..65535) {
            binding.etServerPort.error = "Enter a valid port (1-65535)"
            return false
        }

        if (sni.isEmpty()) {
            binding.etSniHost.error = "SNI target host required"
            return false
        }

        return true
    }

    /**
     * Load saved profile from SharedPreferences
     */
    private fun loadSavedProfile() {
        val prefs = getSharedPreferences("TunnelPrefs", MODE_PRIVATE)
        binding.etServerIp.setText(prefs.getString("SERVER_IP", "") ?: "")
        binding.etServerPort.setText(prefs.getInt("SERVER_PORT", 443).toString())
        binding.etSniHost.setText(prefs.getString("SNI_HOST", "") ?: "")
        binding.etPayload.setText(prefs.getString("PAYLOAD", "") ?: "")
    }

    /**
     * Save current profile to SharedPreferences
     */
    private fun saveCurrentProfile() {
        val prefs = getSharedPreferences("TunnelPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("SERVER_IP", binding.etServerIp.text.toString().trim())
            putInt("SERVER_PORT", binding.etServerPort.text.toString().trim().toInt())
            putString("SNI_HOST", binding.etSniHost.text.toString().trim())
            putString("PAYLOAD", binding.etPayload.text.toString())
            apply()
        }
    }

    /**
     * Start VPN authorization
     */
    private fun startVpnAuthorization() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission required", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Start the VPN service
     */
    private fun startVpnService() {
        val prefs = getSharedPreferences("TunnelPrefs", MODE_PRIVATE)
        val serviceIntent = Intent(this, AdvancedVpnService::class.java).apply {
            putExtra("SERVER_IP", prefs.getString("SERVER_IP", "") ?: "")
            putExtra("SERVER_PORT", prefs.getInt("SERVER_PORT", 443))
            putExtra("SNI_HOST", prefs.getString("SNI_HOST", "") ?: "")
            putExtra("PAYLOAD", prefs.getString("PAYLOAD", "") ?: "")
            putExtra("PROTOCOL", "tls") // Default protocol
        }
        startService(serviceIntent)
        binding.btnToggleVpn.text = "STOP TUNNEL"
        isVpnActive = true
        Toast.makeText(this, "Tunnel started", Toast.LENGTH_SHORT).show()
    }

    /**
     * Stop the VPN service
     */
    private fun stopVpnService() {
        val serviceIntent = Intent(this, AdvancedVpnService::class.java).apply {
            action = "ACTION_STOP"
        }
        startService(serviceIntent)
        binding.btnToggleVpn.text = "START TUNNEL"
        isVpnActive = false
        Toast.makeText(this, "Tunnel stopped", Toast.LENGTH_SHORT).show()
    }
}
