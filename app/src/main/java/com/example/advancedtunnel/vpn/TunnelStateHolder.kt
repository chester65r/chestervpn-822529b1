package com.example.advancedtunnel.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TunnelConnectionState {
    object Disconnected : TunnelConnectionState()
    object Connecting : TunnelConnectionState()
    object Authenticating : TunnelConnectionState()
    object TunnelEstablished : TunnelConnectionState()
    object Reconnecting : TunnelConnectionState()
    data class Error(val message: String) : TunnelConnectionState()
}

object TunnelStateHolder {

    private val _connectionState =
        MutableStateFlow<TunnelConnectionState>(TunnelConnectionState.Disconnected)

    val connectionState: StateFlow<TunnelConnectionState> =
        _connectionState.asStateFlow()

    private val _reconnectPhase = MutableStateFlow<String?>(null)

    val reconnectPhase: StateFlow<String?> =
        _reconnectPhase.asStateFlow()

    private val _dataTransferred = MutableStateFlow(0L)

    val dataTransferred: StateFlow<Long> =
        _dataTransferred.asStateFlow()

    private val _connectedAtMillis = MutableStateFlow<Long?>(null)

    val connectedAtMillis: StateFlow<Long?> =
        _connectedAtMillis.asStateFlow()

    private val _downloadBytesPerSec = MutableStateFlow(0L)

    val downloadBytesPerSec: StateFlow<Long> =
        _downloadBytesPerSec.asStateFlow()

    private val _uploadBytesPerSec = MutableStateFlow(0L)

    val uploadBytesPerSec: StateFlow<Long> =
        _uploadBytesPerSec.asStateFlow()

    fun setConnectionState(state: TunnelConnectionState) {
        _connectionState.value = state
        when (state) {
            is TunnelConnectionState.TunnelEstablished -> {
                if (_connectedAtMillis.value == null) {
                    _connectedAtMillis.value = System.currentTimeMillis()
                }
                _reconnectPhase.value = null
            }
            is TunnelConnectionState.Disconnected,
            is TunnelConnectionState.Error -> {
                _connectedAtMillis.value = null
                _downloadBytesPerSec.value = 0L
                _uploadBytesPerSec.value = 0L
                _reconnectPhase.value = null
            }
            is TunnelConnectionState.Reconnecting -> Unit
            else -> {
                _reconnectPhase.value = null
            }
        }
    }

    fun setReconnectPhase(phase: String?) {
        _reconnectPhase.value = phase
        if (phase != null && _connectionState.value !is TunnelConnectionState.Reconnecting) {
            _connectionState.value = TunnelConnectionState.Reconnecting
        }
    }

    fun updateSpeeds(downloadBps: Long, uploadBps: Long) {
        _downloadBytesPerSec.value = downloadBps.coerceAtLeast(0L)
        _uploadBytesPerSec.value = uploadBps.coerceAtLeast(0L)
    }

    fun addData(bytes: Long) {
        _dataTransferred.value += bytes
    }

    fun resetData() {
        _dataTransferred.value = 0L
        _downloadBytesPerSec.value = 0L
        _uploadBytesPerSec.value = 0L
    }
}
