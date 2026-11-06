package com.example.advancedtunnel.data

import android.content.Context
import android.content.SharedPreferences
import com.example.advancedtunnel.model.RelayServer
import com.example.advancedtunnel.model.SAMPLE_VPS_SERVERS
import com.example.advancedtunnel.model.SavedProfile
import com.example.advancedtunnel.model.TunnelProfile
import org.json.JSONArray
import org.json.JSONObject

class TunnelRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tunnel_console_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_PROFILE_IP = "active_ip"
        private const val KEY_ACTIVE_PROFILE_PORT = "active_port"
        private const val KEY_ACTIVE_PROFILE_SNI = "active_sni"
        private const val KEY_ACTIVE_PROFILE_PAYLOAD = "active_payload"
        private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
        private const val KEY_KILLSWITCH = "killswitch_enabled"
        private const val KEY_SPLIT_TUNNELING = "split_tunneling_enabled"
        private const val KEY_DISALLOWED_APPS = "disallowed_apps_set"
        private const val KEY_SAVED_PROFILES = "saved_profiles_json"
        private const val KEY_CUSTOM_SERVERS = "custom_servers_json"
    }

    fun loadKillswitchEnabled(): Boolean = prefs.getBoolean(KEY_KILLSWITCH, false)
    fun saveKillswitchEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_KILLSWITCH, enabled).apply()

    fun loadSplitTunnelingEnabled(): Boolean = prefs.getBoolean(KEY_SPLIT_TUNNELING, false)
    fun saveSplitTunnelingEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SPLIT_TUNNELING, enabled).apply()

    fun loadDisallowedApps(): Set<String> = prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet()) ?: emptySet()
    fun saveDisallowedApps(apps: Set<String>) = prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).apply()

    fun loadActiveProfile(): Pair<TunnelProfile, String> {
        var ip = prefs.getString(KEY_ACTIVE_PROFILE_IP, "") ?: ""
        if (ip == "203.0.113.10" || ip == "your-vps-ny.example.com") {
            ip = ""
        }
        val port = prefs.getString(KEY_ACTIVE_PROFILE_PORT, "443") ?: "443"
        val sni = prefs.getString(KEY_ACTIVE_PROFILE_SNI, "hetzner.cloud") ?: "hetzner.cloud"
        val payload = prefs.getString(KEY_ACTIVE_PROFILE_PAYLOAD, "") ?: ""
        val presetId = prefs.getString(KEY_ACTIVE_PRESET_ID, "hetzner") ?: "hetzner"
        return Pair(TunnelProfile(ip, port, sni, payload), presetId)
    }

    fun saveActiveProfile(profile: TunnelProfile, presetId: String) {
        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_IP, profile.ip)
            .putString(KEY_ACTIVE_PROFILE_PORT, profile.port)
            .putString(KEY_ACTIVE_PROFILE_SNI, profile.sni)
            .putString(KEY_ACTIVE_PROFILE_PAYLOAD, profile.payload)
            .putString(KEY_ACTIVE_PRESET_ID, presetId)
            .apply()
    }

    fun loadSavedProfiles(): List<SavedProfile> {
        val raw = prefs.getString(KEY_SAVED_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SavedProfile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val profObj = obj.getJSONObject("profile")
                list.add(
                    SavedProfile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        presetId = obj.optString("presetId", "custom"),
                        profile = TunnelProfile(
                            ip = profObj.getString("ip"),
                            port = profObj.getString("port"),
                            sni = profObj.getString("sni"),
                            payload = profObj.optString("payload", "")
                        ),
                        savedAt = obj.optLong("savedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSavedProfiles(profiles: List<SavedProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("presetId", p.presetId)
                put("savedAt", p.savedAt)
                put("profile", JSONObject().apply {
                    put("ip", p.profile.ip)
                    put("port", p.profile.port)
                    put("sni", p.profile.sni)
                    put("payload", p.profile.payload)
                })
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SAVED_PROFILES, array.toString()).apply()
    }

    fun loadServers(): List<RelayServer> {
        val raw = prefs.getString(KEY_CUSTOM_SERVERS, null)
        if (raw == null) {
            // Seed with sample VPS servers on first launch
            saveServers(SAMPLE_VPS_SERVERS)
            return SAMPLE_VPS_SERVERS
        }
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<RelayServer>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    RelayServer(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        host = obj.getString("host"),
                        port = obj.getInt("port"),
                        transport = obj.getString("transport"),
                        notes = obj.optString("notes", "")
                    )
                )
            }
            if (list.any { it.host.contains("your-vps-ny.example.com") }) {
                saveServers(SAMPLE_VPS_SERVERS)
                SAMPLE_VPS_SERVERS
            } else {
                list
            }
        } catch (_: Exception) {
            SAMPLE_VPS_SERVERS
        }
    }

    fun saveServers(servers: List<RelayServer>) {
        val array = JSONArray()
        servers.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("host", s.host)
                put("port", s.port)
                put("transport", s.transport)
                put("notes", s.notes)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_SERVERS, array.toString()).apply()
    }
}
