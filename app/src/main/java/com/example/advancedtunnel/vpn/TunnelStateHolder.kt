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

    fun setConnectionState(state: TunnelConnectionState) {
        _connectionState.value = state
        if (state !is TunnelConnectionState.Reconnecting) {
            _reconnectPhase.value = null
        }
    }

    fun setReconnectPhase(phase: String?) {
        _reconnectPhase.value = phase
        if (phase != null && _connectionState.value !is TunnelConnectionState.Reconnecting) {
            _connectionState.value = TunnelConnectionState.Reconnecting
        }
    }

    fun addData(bytes: Long) {
        _dataTransferred.value += bytes
    }

    fun resetData() {
        _dataTransferred.value = 0L
    }
}
