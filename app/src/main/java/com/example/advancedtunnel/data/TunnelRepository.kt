package com.example.advancedtunnel.data

import android.content.Context
import android.content.SharedPreferences
import com.example.advancedtunnel.model.RelayServer
import com.example.advancedtunnel.model.SAMPLE_VPS_SERVERS
import com.example.advancedtunnel.model.SavedProfile
import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.vpn.TlsFragmentConfig
import com.example.advancedtunnel.vpn.TlsFragmentMode
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
        private const val KEY_ACTIVE_PROFILE_PROTOCOL = "active_protocol"
        private const val KEY_ACTIVE_PROFILE_CDN_HOST = "active_cdn_host"
        private const val KEY_ACTIVE_PROFILE_WS_PATH = "active_ws_path"
        private const val KEY_ACTIVE_PROFILE_UTLS = "active_utls_fp"
        private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
        private const val KEY_KILLSWITCH = "killswitch_enabled"
        private const val KEY_SPLIT_TUNNELING = "split_tunneling_enabled"
        private const val KEY_DISALLOWED_APPS = "disallowed_apps_set"
        private const val KEY_SAVED_PROFILES = "saved_profiles_json"
        private const val KEY_CUSTOM_SERVERS = "custom_servers_json"

        private const val KEY_FRAG_ENABLED = "tls_frag_enabled"
        private const val KEY_FRAG_MODE = "tls_frag_mode"
        private const val KEY_FRAG_MIN_CHUNK = "tls_frag_min_chunk"
        private const val KEY_FRAG_MAX_CHUNK = "tls_frag_max_chunk"
        private const val KEY_FRAG_MIN_JITTER = "tls_frag_min_jitter"
        private const val KEY_FRAG_MAX_JITTER = "tls_frag_max_jitter"
        private const val KEY_JA3_PROFILE_ID = "tls_ja3_profile_id"
        private const val KEY_ALPN_ENABLED = "tls_alpn_enabled"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_WIREGUARD_CONFIG = "wireguard_config_text"
    }

    fun loadConnectionMode(): com.example.advancedtunnel.model.ConnectionMode {
        val name = prefs.getString(KEY_CONNECTION_MODE, com.example.advancedtunnel.model.ConnectionMode.TUNNEL.name)
        return try {
            com.example.advancedtunnel.model.ConnectionMode.valueOf(name ?: com.example.advancedtunnel.model.ConnectionMode.TUNNEL.name)
        } catch (_: Exception) {
            com.example.advancedtunnel.model.ConnectionMode.TUNNEL
        }
    }

    fun saveConnectionMode(mode: com.example.advancedtunnel.model.ConnectionMode) {
        prefs.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
    }

    fun loadWireGuardConfig(): String = prefs.getString(KEY_WIREGUARD_CONFIG, "") ?: ""
    fun saveWireGuardConfig(configText: String) = prefs.edit().putString(KEY_WIREGUARD_CONFIG, configText).apply()

    fun loadKillswitchEnabled(): Boolean = prefs.getBoolean(KEY_KILLSWITCH, false)
    fun saveKillswitchEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_KILLSWITCH, enabled).apply()

    fun loadSplitTunnelingEnabled(): Boolean = prefs.getBoolean(KEY_SPLIT_TUNNELING, false)
    fun saveSplitTunnelingEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SPLIT_TUNNELING, enabled).apply()

    fun loadDisallowedApps(): Set<String> = prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet()) ?: emptySet()
    fun saveDisallowedApps(apps: Set<String>) = prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).apply()

    fun loadTlsFragmentConfig(): TlsFragmentConfig {
        val enabled = prefs.getBoolean(KEY_FRAG_ENABLED, true)
        val modeId = prefs.getString(KEY_FRAG_MODE, TlsFragmentMode.SNI_BOUNDARY_SPLIT.id)
            ?: TlsFragmentMode.SNI_BOUNDARY_SPLIT.id
        val minChunk = prefs.getInt(KEY_FRAG_MIN_CHUNK, 5)
        val maxChunk = prefs.getInt(KEY_FRAG_MAX_CHUNK, 15)
        val minJitter = prefs.getInt(KEY_FRAG_MIN_JITTER, 3)
        val maxJitter = prefs.getInt(KEY_FRAG_MAX_JITTER, 12)
        val profileId = prefs.getString(KEY_JA3_PROFILE_ID, "chrome_128_android") ?: "chrome_128_android"
        val alpn = prefs.getBoolean(KEY_ALPN_ENABLED, true)
        return TlsFragmentConfig(
            enabled = enabled,
            mode = TlsFragmentMode.fromId(modeId),
            minChunkBytes = minChunk,
            maxChunkBytes = maxChunk,
            minJitterMs = minJitter,
            maxJitterMs = maxJitter,
            fingerprintProfileId = profileId,
            alpnEnabled = alpn
        )
    }

    fun saveTlsFragmentConfig(config: TlsFragmentConfig) {
        prefs.edit()
            .putBoolean(KEY_FRAG_ENABLED, config.enabled)
            .putString(KEY_FRAG_MODE, config.mode.id)
            .putInt(KEY_FRAG_MIN_CHUNK, config.normalizedMinChunk())
            .putInt(KEY_FRAG_MAX_CHUNK, config.normalizedMaxChunk())
            .putInt(KEY_FRAG_MIN_JITTER, config.normalizedMinJitter())
            .putInt(KEY_FRAG_MAX_JITTER, config.normalizedMaxJitter())
            .putString(KEY_JA3_PROFILE_ID, config.fingerprintProfileId)
            .putBoolean(KEY_ALPN_ENABLED, config.alpnEnabled)
            .apply()
    }

    fun loadActiveProfile(): Pair<TunnelProfile, String> {
        var ip = prefs.getString(KEY_ACTIVE_PROFILE_IP, com.example.advancedtunnel.model.HETZNER_SERVER_IP)
            ?: com.example.advancedtunnel.model.HETZNER_SERVER_IP
        if (ip.isBlank() || ip == "203.0.113.10" || ip == "your-vps-ny.example.com" || ip == "127.0.0.1") {
            ip = com.example.advancedtunnel.model.HETZNER_SERVER_IP
        }
        val port = prefs.getString(KEY_ACTIVE_PROFILE_PORT, "443") ?: "443"
        val presetId = prefs.getString(KEY_ACTIVE_PRESET_ID, "stc") ?: "stc"
        val defaultSni = if (presetId == "mobily") "www.mobily.com.sa" else "www.stc.com.sa"
        val sni = prefs.getString(KEY_ACTIVE_PROFILE_SNI, defaultSni)?.ifBlank { defaultSni } ?: defaultSni
        val protocol = prefs.getString(
            KEY_ACTIVE_PROFILE_PROTOCOL,
            if (presetId == "mobily") "vless-ws-tls" else "vless-reality"
        ) ?: "vless-reality"
        val cdnHost = prefs.getString(KEY_ACTIVE_PROFILE_CDN_HOST, "cdn.mobily.com.sa") ?: "cdn.mobily.com.sa"
        val wsPath = prefs.getString(KEY_ACTIVE_PROFILE_WS_PATH, "/cdn-ws") ?: "/cdn-ws"
        val utlsFp = prefs.getString(KEY_ACTIVE_PROFILE_UTLS, "chrome") ?: "chrome"
        val rawPayload = prefs.getString(KEY_ACTIVE_PROFILE_PAYLOAD, null)
        val payload = if (rawPayload.isNullOrBlank() || !rawPayload.trim().startsWith("{")) {
            com.example.advancedtunnel.vpn.SingBoxConfigBuilder.buildCarrierConfigJson(
                carrierId = presetId,
                serverHost = ip,
                serverPort = port.toIntOrNull() ?: 443,
                customSni = sni,
                customCdnHost = cdnHost,
                customWsPath = wsPath
            )
        } else {
            rawPayload
        }
        return Pair(
            TunnelProfile(
                ip = ip,
                port = port,
                sni = sni,
                payload = payload,
                protocol = protocol,
                cdnHost = cdnHost,
                wsPath = wsPath,
                utlsFingerprint = utlsFp
            ),
            presetId
        )
    }

    fun saveActiveProfile(profile: TunnelProfile, presetId: String) {
        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_IP, profile.ip)
            .putString(KEY_ACTIVE_PROFILE_PORT, profile.port)
            .putString(KEY_ACTIVE_PROFILE_SNI, profile.sni)
            .putString(KEY_ACTIVE_PROFILE_PAYLOAD, profile.payload)
            .putString(KEY_ACTIVE_PROFILE_PROTOCOL, profile.protocol)
            .putString(KEY_ACTIVE_PROFILE_CDN_HOST, profile.cdnHost)
            .putString(KEY_ACTIVE_PROFILE_WS_PATH, profile.wsPath)
            .putString(KEY_ACTIVE_PROFILE_UTLS, profile.utlsFingerprint)
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
                            payload = profObj.optString("payload", ""),
                            protocol = profObj.optString("protocol", "vless-reality"),
                            cdnHost = profObj.optString("cdnHost", "cdn.mobily.com.sa"),
                            wsPath = profObj.optString("wsPath", "/cdn-ws"),
                            utlsFingerprint = profObj.optString("utlsFingerprint", "chrome"),
                            sniSplitMinBytes = profObj.optInt("sniSplitMinBytes", 5),
                            sniSplitMaxBytes = profObj.optInt("sniSplitMaxBytes", 15)
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
                    put("protocol", p.profile.protocol)
                    put("cdnHost", p.profile.cdnHost)
                    put("wsPath", p.profile.wsPath)
                    put("utlsFingerprint", p.profile.utlsFingerprint)
                    put("sniSplitMinBytes", p.profile.sniSplitMinBytes)
                    put("sniSplitMaxBytes", p.profile.sniSplitMaxBytes)
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
