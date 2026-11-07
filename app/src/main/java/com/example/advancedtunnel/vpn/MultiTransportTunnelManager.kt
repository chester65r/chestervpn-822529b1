package com.example.advancedtunnel.vpn

class MultiTransportTunnelManager {

    enum class TransportType(val displayName: String) {
        HTTP_CUSTOM_PAYLOAD("HTTP Custom Payload"),
        TLS_WEBSOCKET("TLS + WebSocket"),
        TLS_SNI("TLS + SNI"),
        SSH_TUNNEL("SSH Tunnel"),
        SOCKS5("SOCKS5 Bridge"),
        WIREGUARD("WireGuard")
    }

    @Volatile
    private var activeTransport: TransportType = TransportType.HTTP_CUSTOM_PAYLOAD

    fun switchTransport(type: TransportType) {
        activeTransport = type
    }

    fun getActiveTransport(): String {
        return activeTransport.displayName
    }
}
