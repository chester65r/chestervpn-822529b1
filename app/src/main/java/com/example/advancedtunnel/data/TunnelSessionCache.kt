package com.example.advancedtunnel.data

import android.content.Context
import android.content.SharedPreferences
import com.example.advancedtunnel.model.TunnelProfile
import org.json.JSONObject

class TunnelSessionCache(context: Context) {

    companion object {
        private const val PREFS_NAME = "tunnel_console_session_cache"
        private const val KEY_LAST_KNOWN_PROFILE = "last_known_profile"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun save(profile: TunnelProfile) {
        val json = JSONObject()

        json.put("ip", profile.ip)
        json.put("port", profile.port)
        json.put("sni", profile.sni)
        json.put("payload", profile.payload)

        prefs.edit()
            .putString(KEY_LAST_KNOWN_PROFILE, json.toString())
            .apply()
    }

    fun load(): TunnelProfile? {
        val raw = prefs.getString(KEY_LAST_KNOWN_PROFILE, null) ?: return null

        return try {
            val json = JSONObject(raw)

            TunnelProfile(
                ip = json.optString("ip", ""),
                port = json.optString("port", "443"),
                sni = json.optString("sni", ""),
                payload = json.optString("payload", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_LAST_KNOWN_PROFILE)
            .apply()
    }
}
