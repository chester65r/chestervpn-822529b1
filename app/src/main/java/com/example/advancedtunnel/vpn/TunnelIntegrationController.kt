package com.example.advancedtunnel.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.advancedtunnel.model.TunnelProfile

class TunnelIntegrationController(private val context: Context) {

    fun startTunnel(profile: TunnelProfile) {
        val intent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_CONNECT
            putExtra("tunnel_ip", profile.ip)
            putExtra("tunnel_port", profile.port)
            putExtra("tunnel_sni", profile.sni)
            putExtra("tunnel_payload", profile.payload)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopTunnel() {
        val intent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }
}
