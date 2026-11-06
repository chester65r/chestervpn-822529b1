package com.example.advancedtunnel.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.advancedtunnel.data.TunnelRepository
import com.example.advancedtunnel.firebase.FirebaseAuthManager
import com.example.advancedtunnel.firebase.FirestoreManager
import com.example.advancedtunnel.firebase.GeminiAiService
import com.example.advancedtunnel.model.ROUTE_PRESETS
import com.example.advancedtunnel.model.RelayServer
import com.example.advancedtunnel.model.SAMPLE_VPS_SERVERS
import com.example.advancedtunnel.model.SavedProfile
import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.model.TunnelUtils
import com.example.advancedtunnel.vpn.RealVpnState
import com.example.advancedtunnel.vpn.VpnManager
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TunnelViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TunnelRepository(application)
    val authManager = FirebaseAuthManager(application)
    private val firestoreManager = FirestoreManager(application)
    private val geminiService = GeminiAiService()

    val vpnState: StateFlow<RealVpnState> = VpnManager.vpnState
    val realRxBytes: StateFlow<Long> = VpnManager.rxBytes
    val realTxBytes: StateFlow<Long> = VpnManager.txBytes
    val realDownSpeed: StateFlow<Double> = VpnManager.rxSpeed
    val realUpSpeed: StateFlow<Double> = VpnManager.txSpeed
    val uptimeSeconds: StateFlow<Long> = VpnManager.uptimeSeconds
    val serverLatencyMs: StateFlow<Long?> = VpnManager.serverLatencyMs

    // Unified TunnelStatus derived directly from RealVpnState (NEVER fake or simulated)
    private val _status = MutableStateFlow(TunnelStatus.DISCONNECTED)
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    private val _profile = MutableStateFlow(TunnelProfile())
    val profile: StateFlow<TunnelProfile> = _profile.asStateFlow()

    private val _presetId = MutableStateFlow("alpha")
    val presetId: StateFlow<String> = _presetId.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val pointsCount = 40
    private val _downHistory = MutableStateFlow(List(pointsCount) { 0.0 })
    val downHistory: StateFlow<List<Double>> = _downHistory.asStateFlow()

    private val _upHistory = MutableStateFlow(List(pointsCount) { 0.0 })
    val upHistory: StateFlow<List<Double>> = _upHistory.asStateFlow()

    private val _savedProfiles = MutableStateFlow<List<SavedProfile>>(emptyList())
    val savedProfiles: StateFlow<List<SavedProfile>> = _savedProfiles.asStateFlow()

    private val _servers = MutableStateFlow<List<RelayServer>>(SAMPLE_VPS_SERVERS)
    val servers: StateFlow<List<RelayServer>> = _servers.asStateFlow()

    private val _exportedCode = MutableStateFlow<String?>(null)
    val exportedCode: StateFlow<String?> = _exportedCode.asStateFlow()

    private val _aiHelperText = MutableStateFlow<String?>(null)
    val aiHelperText: StateFlow<String?> = _aiHelperText.asStateFlow()

    private val _isAnalyzingAi = MutableStateFlow(false)
    val isAnalyzingAi: StateFlow<Boolean> = _isAnalyzingAi.asStateFlow()

    val currentUser: StateFlow<FirebaseUser?> = authManager.currentUser
    val isAuthLoading: StateFlow<Boolean> = authManager.isLoading

    private var firestoreSyncJob: Job? = null

    init {
        val (loadedProfile, loadedPreset) = repository.loadActiveProfile()
        _profile.value = loadedProfile
        _presetId.value = loadedPreset
        _savedProfiles.value = repository.loadSavedProfiles()
        val localServers = repository.loadServers()
        if (localServers.isNotEmpty()) {
            _servers.value = localServers
        }

        // Observe real VPN state directly from VpnManager
        viewModelScope.launch {
            VpnManager.vpnState.collect { state ->
                _status.value = when (state) {
                    is RealVpnState.Connected -> TunnelStatus.CONNECTED
                    is RealVpnState.Connecting, is RealVpnState.Reconnecting -> TunnelStatus.CONNECTING
                    is RealVpnState.Disconnected, is RealVpnState.Failed -> TunnelStatus.DISCONNECTED
                }
            }
        }

        // Record real telemetry history for canvas chart
        viewModelScope.launch {
            VpnManager.rxSpeed.collect { downBps ->
                val list = _downHistory.value.toMutableList()
                if (list.size >= pointsCount) list.removeAt(0)
                list.add(downBps)
                _downHistory.value = list
            }
        }

        viewModelScope.launch {
            VpnManager.txSpeed.collect { upBps ->
                val list = _upHistory.value.toMutableList()
                if (list.size >= pointsCount) list.removeAt(0)
                list.add(upBps)
                _upHistory.value = list
            }
        }

        // Sync with Cloud Firestore
        viewModelScope.launch {
            authManager.currentUser.collect { user ->
                firestoreSyncJob?.cancel()
                if (user != null) {
                    firestoreSyncJob = launch {
                        firestoreManager.observeRelayServers(user.uid).collect { cloudList ->
                            if (cloudList.isNotEmpty()) {
                                _servers.value = cloudList
                                repository.saveServers(cloudList)
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateProfile(
        ip: String? = null,
        port: String? = null,
        sni: String? = null,
        payload: String? = null
    ) {
        val cur = _profile.value
        val updated = cur.copy(
            ip = ip ?: cur.ip,
            port = port ?: cur.port,
            sni = sni ?: cur.sni,
            payload = payload ?: cur.payload
        )
        _profile.value = updated
        if (sni != null && sni != cur.sni) {
            _presetId.value = "custom"
        }
        _errors.value = emptyMap()
        repository.saveActiveProfile(updated, _presetId.value)
    }

    fun selectPreset(id: String) {
        _presetId.value = id
        val preset = ROUTE_PRESETS.find { it.id == id }
        if (preset != null && preset.sni.isNotEmpty()) {
            val updated = _profile.value.copy(sni = preset.sni)
            _profile.value = updated
            _errors.value = emptyMap()
            repository.saveActiveProfile(updated, id)
        }
    }

    fun connectHetznerServer(
        ip: String,
        port: String = "443",
        sni: String = "hetzner.cloud",
        context: Context,
        onRequestPermission: () -> Unit
    ) {
        val trimmedIp = ip.trim()
        val trimmedPort = port.trim().ifEmpty { "443" }
        val trimmedSni = sni.trim().ifEmpty { trimmedIp }
        
        val updated = _profile.value.copy(
            ip = trimmedIp,
            port = trimmedPort,
            sni = trimmedSni
        )
        _profile.value = updated
        _presetId.value = "hetzner"
        _errors.value = emptyMap()
        repository.saveActiveProfile(updated, "hetzner")
        
        requestConnectVpn(context, onRequestPermission)
    }

    fun requestConnectVpn(context: Context, onRequestPermission: () -> Unit) {
        val errs = TunnelUtils.validateProfile(_profile.value)
        if (errs.isNotEmpty()) {
            _errors.value = errs
            return
        }

        if (VpnManager.isVpnPrepared(context)) {
            VpnManager.startVpn(context, _profile.value)
        } else {
            onRequestPermission()
        }
    }

    fun disconnectVpn(context: Context) {
        VpnManager.stopVpn(context)
    }

    fun pingServer() {
        val p = _profile.value
        val port = p.port.toIntOrNull() ?: 443
        if (p.ip.isNotBlank()) {
            viewModelScope.launch {
                VpnManager.checkServerReachability(p.ip, port)
            }
        }
    }

    fun saveCurrentProfile(name: String): Boolean {
        val errs = TunnelUtils.validateProfile(_profile.value)
        if (errs.isNotEmpty()) {
            _errors.value = errs
            return false
        }
        val label = name.trim().ifEmpty { "${_profile.value.ip}:${_profile.value.port}" }
        val entry = SavedProfile(
            name = label,
            presetId = _presetId.value,
            profile = _profile.value
        )
        val list = listOf(entry) + _savedProfiles.value
        _savedProfiles.value = list
        repository.saveSavedProfiles(list)
        return true
    }

    fun loadSavedProfile(id: String) {
        val entry = _savedProfiles.value.find { it.id == id } ?: return
        _profile.value = entry.profile
        _presetId.value = entry.presetId
        _errors.value = emptyMap()
        repository.saveActiveProfile(entry.profile, entry.presetId)
    }

    fun deleteSavedProfile(id: String) {
        val list = _savedProfiles.value.filter { it.id != id }
        _savedProfiles.value = list
        repository.saveSavedProfiles(list)
    }

    fun exportCurrentProfile(): String? {
        val errs = TunnelUtils.validateProfile(_profile.value)
        if (errs.isNotEmpty()) {
            _errors.value = errs
            return null
        }
        val code = TunnelUtils.exportProfile(_profile.value)
        _exportedCode.value = code
        return code
    }

    fun importProfileCode(code: String): Boolean {
        val imported = TunnelUtils.importProfile(code) ?: return false
        _profile.value = imported
        val match = ROUTE_PRESETS.find { it.sni.isNotEmpty() && it.sni == imported.sni }
        _presetId.value = match?.id ?: "custom"
        _errors.value = emptyMap()
        repository.saveActiveProfile(imported, _presetId.value)
        return true
    }

    fun applyServer(server: RelayServer) {
        val updated = _profile.value.copy(
            ip = server.host,
            port = server.port.toString(),
            sni = server.host
        )
        _profile.value = updated
        _presetId.value = "custom"
        _errors.value = emptyMap()
        repository.saveActiveProfile(updated, "custom")
    }

    fun addServer(name: String, host: String, portStr: String, transport: String, notes: String): Boolean {
        if (name.isBlank() || host.isBlank()) return false
        val p = portStr.trim().toIntOrNull() ?: return false
        if (p !in 1..65535) return false

        val newServer = RelayServer(
            name = name.trim(),
            host = host.trim(),
            port = p,
            transport = transport,
            notes = notes.trim()
        )
        val list = listOf(newServer) + _servers.value
        _servers.value = list
        repository.saveServers(list)

        val user = currentUser.value
        if (user != null) {
            viewModelScope.launch {
                firestoreManager.saveRelayServer(user.uid, newServer)
            }
        }
        return true
    }

    fun deleteServer(id: String) {
        val list = _servers.value.filter { it.id != id }
        _servers.value = list
        repository.saveServers(list)

        val user = currentUser.value
        if (user != null) {
            viewModelScope.launch {
                firestoreManager.deleteRelayServer(user.uid, id)
            }
        }
    }

    fun signInWithGoogle(onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = authManager.signInWithGoogle()
            if (result.isSuccess) {
                onResult(true, null)
            } else {
                onResult(false, result.exceptionOrNull()?.localizedMessage)
            }
        }
    }

    fun signOut() {
        authManager.signOut()
    }

    fun runAiDiagnostics() {
        _isAnalyzingAi.value = true
        viewModelScope.launch {
            val p = _profile.value
            val s = _status.value
            val sec = VpnManager.uptimeSeconds.value
            val down = VpnManager.rxSpeed.value
            val up = VpnManager.txSpeed.value

            val result = geminiService.analyzeConnection(p, s, sec, down, up)
            if (result.isSuccess) {
                _aiHelperText.value = result.getOrNull()
            } else {
                val errs = TunnelUtils.validateProfile(p)
                val fallback = if (errs.isNotEmpty()) {
                    "Configuration Advisory:\n• Found issues: ${errs.values.joinToString(", ")}.\n• Please correct in Configure screen."
                } else {
                    "Diagnostic Status:\n• Active Endpoint: ${p.ip}:${p.port}\n• SNI: ${p.sni}\n• State: ${s.name}\n• Gemini analysis: ${result.exceptionOrNull()?.localizedMessage ?: "Analysis completed."}"
                }
                _aiHelperText.value = fallback
            }
            _isAnalyzingAi.value = false
        }
    }
}
