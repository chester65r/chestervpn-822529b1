package com.example.advancedtunnel.viewmodel

import android.app.Activity
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
import com.example.advancedtunnel.security.ConfigVault
import com.example.advancedtunnel.vpn.FragmentDispatchStats
import com.example.advancedtunnel.vpn.NodeBenchmarkMetric
import com.example.advancedtunnel.vpn.NodeHealthTier
import com.example.advancedtunnel.vpn.RealVpnState
import com.example.advancedtunnel.vpn.ServerHealthMonitor
import com.example.advancedtunnel.vpn.TelemetryLogEntry
import com.example.advancedtunnel.vpn.TlsFingerprintEngine
import com.example.advancedtunnel.vpn.TlsFragmentConfig
import com.example.advancedtunnel.vpn.TlsFragmentMode
import com.example.advancedtunnel.vpn.TlsFragmentationEngine
import com.example.advancedtunnel.vpn.VpnManager
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class TunnelViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TunnelRepository(application)
    private val configVault = ConfigVault(application)
    val authManager = FirebaseAuthManager(application)
    private val firestoreManager = FirestoreManager(application)
    private val geminiService = GeminiAiService()

    val vpnState: StateFlow<RealVpnState> = VpnManager.vpnState
    val realRxBytes: StateFlow<Long> = VpnManager.rxBytes
    val realTxBytes: StateFlow<Long> = VpnManager.txBytes
    val realDownSpeed: StateFlow<Double> = VpnManager.rxSpeed
    val realUpSpeed: StateFlow<Double> = VpnManager.txSpeed
    val peakDownSpeed: StateFlow<Double> = VpnManager.peakRxSpeed
    val peakUpSpeed: StateFlow<Double> = VpnManager.peakTxSpeed
    val uptimeSeconds: StateFlow<Long> = VpnManager.uptimeSeconds
    val serverLatencyMs: StateFlow<Long?> = VpnManager.serverLatencyMs
    val telemetryLogs: StateFlow<List<TelemetryLogEntry>> = VpnManager.telemetryLogs

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

    private val _exportedVaultCode = MutableStateFlow<String?>(null)
    val exportedVaultCode: StateFlow<String?> = _exportedVaultCode.asStateFlow()

    private val _hasKeystoreVaultBackup = MutableStateFlow(false)
    val hasKeystoreVaultBackup: StateFlow<Boolean> = _hasKeystoreVaultBackup.asStateFlow()

    private val _aiHelperText = MutableStateFlow<String?>(null)
    val aiHelperText: StateFlow<String?> = _aiHelperText.asStateFlow()

    private val _isAnalyzingAi = MutableStateFlow(false)
    val isAnalyzingAi: StateFlow<Boolean> = _isAnalyzingAi.asStateFlow()

    val currentUser: StateFlow<FirebaseUser?> = authManager.currentUser
    val isAuthLoading: StateFlow<Boolean> = authManager.isLoading

    private val _killswitchEnabled = MutableStateFlow(repository.loadKillswitchEnabled())
    val killswitchEnabled: StateFlow<Boolean> = _killswitchEnabled.asStateFlow()

    private val _splitTunnelingEnabled = MutableStateFlow(repository.loadSplitTunnelingEnabled())
    val splitTunnelingEnabled: StateFlow<Boolean> = _splitTunnelingEnabled.asStateFlow()

    private val _disallowedApps = MutableStateFlow(repository.loadDisallowedApps())
    val disallowedApps: StateFlow<Set<String>> = _disallowedApps.asStateFlow()

    private val _selectedServer = MutableStateFlow<RelayServer?>(null)
    val selectedServer: StateFlow<RelayServer?> = _selectedServer.asStateFlow()

    private val _serverLatencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    val serverLatencies: StateFlow<Map<String, Long>> = _serverLatencies.asStateFlow()

    private val _nodeBenchmarks = MutableStateFlow<Map<String, NodeBenchmarkMetric>>(emptyMap())
    val nodeBenchmarks: StateFlow<Map<String, NodeBenchmarkMetric>> = _nodeBenchmarks.asStateFlow()

    private val _isBenchmarkingNodes = MutableStateFlow(false)
    val isBenchmarkingNodes: StateFlow<Boolean> = _isBenchmarkingNodes.asStateFlow()

    private val _autoFailoverEnabled = MutableStateFlow(true)
    val autoFailoverEnabled: StateFlow<Boolean> = _autoFailoverEnabled.asStateFlow()

    private val _tlsFragmentConfig = MutableStateFlow(repository.loadTlsFragmentConfig())
    val tlsFragmentConfig: StateFlow<TlsFragmentConfig> = _tlsFragmentConfig.asStateFlow()

    private val _lastDesyncSimStats = MutableStateFlow<FragmentDispatchStats?>(null)
    val lastDesyncSimStats: StateFlow<FragmentDispatchStats?> = _lastDesyncSimStats.asStateFlow()

    private var firestoreSyncJob: Job? = null

    init {
        val (loadedProfile, loadedPreset) = repository.loadActiveProfile()
        _profile.value = loadedProfile
        _presetId.value = loadedPreset
        _savedProfiles.value = repository.loadSavedProfiles()
        _hasKeystoreVaultBackup.value = configVault.hasKeystoreBackup()
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
                if (state is RealVpnState.Failed && _autoFailoverEnabled.value) {
                    triggerAutoFailoverEvaluation()
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

    fun connectGcpServer(
        ip: String,
        port: String = "443",
        sni: String = "cloud.google.com",
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
        _presetId.value = "gcp"
        _errors.value = emptyMap()
        repository.saveActiveProfile(updated, "gcp")
        
        requestConnectVpn(context, onRequestPermission)
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
            onVpnPermissionGranted(context)
        } else {
            onRequestPermission()
        }
    }

    fun onVpnPermissionGranted(context: Context) {
        val isSplit = _splitTunnelingEnabled.value
        val disallowed = if (isSplit) _disallowedApps.value.toList() else emptyList()
        VpnManager.startVpn(
            context = context,
            profile = _profile.value,
            killswitchEnabled = _killswitchEnabled.value,
            disallowedApps = disallowed,
            tlsFragmentConfig = _tlsFragmentConfig.value
        )
    }

    fun updateTlsFragmentConfig(
        enabled: Boolean? = null,
        mode: TlsFragmentMode? = null,
        minChunkBytes: Int? = null,
        maxChunkBytes: Int? = null,
        minJitterMs: Int? = null,
        maxJitterMs: Int? = null,
        fingerprintProfileId: String? = null,
        alpnEnabled: Boolean? = null
    ) {
        val cur = _tlsFragmentConfig.value
        val updated = cur.copy(
            enabled = enabled ?: cur.enabled,
            mode = mode ?: cur.mode,
            minChunkBytes = (minChunkBytes ?: cur.minChunkBytes).coerceIn(1, 256),
            maxChunkBytes = (maxChunkBytes ?: cur.maxChunkBytes).coerceIn(1, 512),
            minJitterMs = (minJitterMs ?: cur.minJitterMs).coerceIn(0, 100),
            maxJitterMs = (maxJitterMs ?: cur.maxJitterMs).coerceIn(0, 250),
            fingerprintProfileId = fingerprintProfileId ?: cur.fingerprintProfileId,
            alpnEnabled = alpnEnabled ?: cur.alpnEnabled
        )
        _tlsFragmentConfig.value = updated
        repository.saveTlsFragmentConfig(updated)
        val fp = TlsFingerprintEngine.computeFingerprint(
            profileId = updated.fingerprintProfileId,
            sniHost = _profile.value.sni.ifBlank { "cloud.google.com" },
            alpnEnabled = updated.alpnEnabled
        )
        VpnManager.appendTelemetryLog(
            "JA3/JA4",
            "Updated Anti-DPI config: Mode=${if (updated.enabled) updated.mode.id else "OFF"} (${updated.normalizedMinChunk()}-${updated.normalizedMaxChunk()}B, ${updated.normalizedMinJitter()}-${updated.normalizedMaxJitter()}ms) • JA4=${fp.ja4String}"
        )
    }

    fun runClientHelloDesyncSimulation() {
        val sni = _profile.value.sni.ifBlank { _profile.value.ip.ifBlank { "cloud.google.com" } }
        val cfg = _tlsFragmentConfig.value
        val stats = TlsFragmentationEngine.simulateClientHelloDesync(sni, cfg)
        _lastDesyncSimStats.value = stats
        VpnManager.appendTelemetryLog(
            "DESYNC",
            "Dry-run ClientHello desync ($sni): ${stats.originalBytes}B -> ${stats.tlsRecordCount} records / ${stats.tcpWriteChunks} TCP chunks (SNI split @ +${stats.sniSplitOffset}B, total jitter ${stats.totalJitterDelayMs}ms)"
        )
    }

    fun toggleKillswitch(enabled: Boolean) {
        _killswitchEnabled.value = enabled
        repository.saveKillswitchEnabled(enabled)
        VpnManager.appendTelemetryLog("INFO", "Always-On Killswitch set to $enabled")
    }

    fun toggleSplitTunneling(enabled: Boolean) {
        _splitTunnelingEnabled.value = enabled
        repository.saveSplitTunnelingEnabled(enabled)
        VpnManager.appendTelemetryLog("INFO", "Per-App Split Tunneling set to $enabled (${_disallowedApps.value.size} excluded)")
    }

    fun toggleDisallowedApp(pkgName: String) {
        val current = _disallowedApps.value.toMutableSet()
        if (current.contains(pkgName)) {
            current.remove(pkgName)
        } else {
            current.add(pkgName)
        }
        _disallowedApps.value = current
        repository.saveDisallowedApps(current)
    }

    fun setDisallowedAppsBulk(packages: Set<String>) {
        val updated = _disallowedApps.value.toMutableSet().apply { addAll(packages) }
        _disallowedApps.value = updated
        repository.saveDisallowedApps(updated)
    }

    fun clearDisallowedApps() {
        _disallowedApps.value = emptySet()
        repository.saveDisallowedApps(emptySet())
    }

    fun toggleAutoFailover(enabled: Boolean) {
        _autoFailoverEnabled.value = enabled
        VpnManager.appendTelemetryLog("INFO", "Multi-Endpoint Auto-Failover set to $enabled")
    }

    fun selectServerNode(server: RelayServer) {
        _selectedServer.value = server
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

    fun probeAllServerLatencies() {
        benchmarkAndRankAllServers(applyBestIfAutoFailover = _autoFailoverEnabled.value)
    }

    fun benchmarkAndRankAllServers(applyBestIfAutoFailover: Boolean = false) {
        if (_isBenchmarkingNodes.value) return
        _isBenchmarkingNodes.value = true
        viewModelScope.launch {
            try {
                val results = ServerHealthMonitor.benchmarkAllNodes(_servers.value)
                val benchMap = results.associateBy { it.serverId }
                _nodeBenchmarks.value = benchMap

                val latMap = _serverLatencies.value.toMutableMap()
                results.forEach { metric ->
                    if (metric.avgRttMs >= 0) {
                        latMap[metric.serverId] = metric.avgRttMs
                    }
                }
                _serverLatencies.value = latMap

                // Reorder servers list so healthy lowest-latency nodes appear first
                val rankedIds = results.map { it.serverId }
                val sortedServers = _servers.value.sortedBy { s ->
                    val idx = rankedIds.indexOf(s.id)
                    if (idx >= 0) idx else Int.MAX_VALUE
                }
                _servers.value = sortedServers

                val bestHealthy = results.firstOrNull { it.tier != NodeHealthTier.UNREACHABLE }
                if (bestHealthy != null) {
                    VpnManager.appendTelemetryLog(
                        "PROBE",
                        "Benchmarked ${results.size} nodes • Fastest: ${bestHealthy.serverName} (${bestHealthy.avgRttMs}ms, jitter ${bestHealthy.jitterMs}ms)"
                    )
                    if (applyBestIfAutoFailover && _status.value != TunnelStatus.CONNECTED) {
                        val bestServer = sortedServers.find { it.id == bestHealthy.serverId }
                        if (bestServer != null) {
                            selectServerNode(bestServer)
                        }
                    }
                } else {
                    VpnManager.appendTelemetryLog("WARN", "Benchmark completed: all ${results.size} relay endpoints timed out.")
                }
            } finally {
                _isBenchmarkingNodes.value = false
            }
        }
    }

    private fun triggerAutoFailoverEvaluation() {
        viewModelScope.launch {
            val failedHost = _profile.value.ip
            val candidate = ServerHealthMonitor.selectBestFailoverServer(_servers.value, failedHost)
            if (candidate != null) {
                val (bestServer, metric) = candidate
                selectServerNode(bestServer)
                VpnManager.appendTelemetryLog(
                    "INFO",
                    "Auto-Failover selected healthy relay '${bestServer.name}' (${metric.avgRttMs}ms RTT)"
                )
            }
        }
    }

    fun clearTelemetryLogs() {
        VpnManager.clearTelemetryLogs()
    }

    fun exportSanitizedDiagnostics(): String {
        return VpnManager.exportSanitizedDiagnosticsReport(_profile.value, _tlsFragmentConfig.value)
    }

    fun exportEncryptedVault(passphrase: String): String? {
        if (passphrase.length < 4) return null
        val p = _profile.value
        val nodesArray = JSONArray()
        _servers.value.forEach { server ->
            nodesArray.put(
                JSONObject().apply {
                    put("id", server.id)
                    put("name", server.name)
                    put("host", server.host)
                    put("port", server.port)
                    put("sni", server.host)
                    put("transport", server.transport)
                    put("websocketPath", "/ws-tunnel")
                }
            )
        }
        val code = configVault.exportEncryptedTcfgCode(
            activeIp = p.ip.ifBlank { "127.0.0.1" },
            activePort = p.port.toIntOrNull() ?: 443,
            activeSni = p.sni,
            activePayload = p.payload,
            serversJsonArray = nodesArray,
            passphrase = passphrase
        )
        _exportedVaultCode.value = code
        _hasKeystoreVaultBackup.value = configVault.hasKeystoreBackup()
        VpnManager.appendTelemetryLog("INFO", "Encrypted AES-256-GCM vault bundle exported & backed up to AndroidKeyStore.")
        return code
    }

    fun importEncryptedVault(rawCode: String, passphrase: String): Result<String> {
        val result = configVault.importEncryptedTcfgCode(rawCode, passphrase)
        return result.map { bundle ->
            applyDecryptedVaultBundle(bundle)
            _hasKeystoreVaultBackup.value = configVault.hasKeystoreBackup()
            VpnManager.appendTelemetryLog(
                "SUCCESS",
                "Decrypted AES-256-GCM vault (v${bundle.version}): loaded ${bundle.nodes.size} relay nodes."
            )
            "Decrypted ${bundle.nodes.size} relay node(s) and active profile."
        }
    }

    fun restoreFromKeystoreVault(): Result<String> {
        val result = configVault.loadFromKeystoreVault()
        return result.map { bundle ->
            applyDecryptedVaultBundle(bundle)
            VpnManager.appendTelemetryLog(
                "SUCCESS",
                "Restored configuration bundle from hardware-backed Android Keystore."
            )
            "Restored ${bundle.nodes.size} node(s) from Android Keystore."
        }
    }

    private fun applyDecryptedVaultBundle(bundle: com.example.advancedtunnel.security.DecryptedVaultBundle) {
        if (bundle.nodes.isNotEmpty()) {
            val mappedServers = bundle.nodes.map { node ->
                RelayServer(
                    id = node.id,
                    name = node.name,
                    host = node.host,
                    port = node.port,
                    transport = node.transport,
                    notes = "SNI: ${node.sni}"
                )
            }
            _servers.value = mappedServers
            repository.saveServers(mappedServers)
        }
        val activePreset = bundle.presets.firstOrNull()
        val firstNode = bundle.nodes.firstOrNull()
        if (activePreset != null || firstNode != null) {
            val hostParts = activePreset?.name?.split(":")
            val resolvedIp = firstNode?.host ?: hostParts?.getOrNull(0) ?: _profile.value.ip
            val resolvedPort = firstNode?.port?.toString() ?: hostParts?.getOrNull(1) ?: _profile.value.port
            val resolvedSni = activePreset?.sni ?: firstNode?.sni ?: _profile.value.sni
            val resolvedPayload = activePreset?.template ?: _profile.value.payload
            val updated = _profile.value.copy(
                ip = resolvedIp,
                port = resolvedPort,
                sni = resolvedSni,
                payload = resolvedPayload
            )
            _profile.value = updated
            repository.saveActiveProfile(updated, "custom")
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

    fun signInWithGoogle(activityContext: Context? = null, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = if (activityContext != null) {
                authManager.signInWithGoogle(activityContext)
            } else {
                authManager.signInWithGoogle()
            }
            if (result.isSuccess) {
                onResult(true, null)
            } else {
                onResult(false, result.exceptionOrNull()?.localizedMessage)
            }
        }
    }

    fun signInWithMicrosoft(activity: Activity, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = authManager.signInWithMicrosoft(activity)
            if (result.isSuccess) {
                onResult(true, null)
            } else {
                onResult(false, result.exceptionOrNull()?.localizedMessage)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
        }
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

    private val _diagnosticResult = MutableStateFlow<DiagnosticResult?>(null)
    val diagnosticResult: StateFlow<DiagnosticResult?> = _diagnosticResult.asStateFlow()

    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()

    fun runNetworkDiagnostics(targetHost: String, targetPort: Int = 443) {
        if (targetHost.isBlank()) return
        _isDiagnosing.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var reachable = false
            var latency = 0L
            var detail = ""
            var mtu = 1420

            try {
                val socket = java.net.Socket()
                val address = java.net.InetSocketAddress(targetHost, targetPort)
                socket.connect(address, 3000)
                latency = System.currentTimeMillis() - startTime
                reachable = true
                socket.close()

                mtu = when {
                    latency > 250 -> 1280
                    latency > 120 -> 1360
                    else -> 1420
                }
                detail = "TCP handshake successful on port $targetPort in ${latency}ms. Recommended MTU: $mtu."
            } catch (e: Exception) {
                reachable = false
                latency = -1L
                detail = "Target reachability check failed ($targetHost:$targetPort): ${e.localizedMessage ?: "Connection timed out"}"
            }

            _diagnosticResult.value = DiagnosticResult(
                host = targetHost,
                isReachable = reachable,
                latencyMs = latency,
                recommendedMtu = mtu,
                details = detail
            )
            _isDiagnosing.value = false
        }
    }
}

data class DiagnosticResult(
    val host: String,
    val isReachable: Boolean,
    val latencyMs: Long,
    val recommendedMtu: Int,
    val details: String
)
