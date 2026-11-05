# Android VPN Application

This directory contains the **real, functional Android VPN implementation** for Advanced Tunnel.

## Project Structure

```
android/
├── src/main/java/com/example/advancedtunnel/
│   ├── AdvancedVpnService.kt    # Real VPNService implementation
│   ├── MainActivity.kt           # Main activity with UI
│   ├── TunnelConfig.kt           # Configuration data classes
│   └── ...
├── build.gradle                  # Project build configuration
├── app/build.gradle              # App module configuration
└── AndroidManifest.xml           # App manifest
```

## Features Implemented

### ✅ Core VPN Functionality
- **Real VPNService** - System-level VPN integration
- **TLS Support** - Secure TLS connections with SNI
- **WebSocket Support** - WebSocket and WSS tunneling
- **HTTP CONNECT Proxy** - Proxy support
- **SOCKS5 Proxy** - SOCKS5 proxy support
- **IPv4 and IPv6** - Dual stack support

### ✅ Configuration Management
- **Profile Storage** - Save and load VPN configurations
- **Validation** - Input validation for all settings
- **Payload Builder** - Custom HTTP payload support

### ✅ User Interface
- **Start/Stop VPN** - Toggle VPN connection
- **Configuration Form** - Server IP, port, SNI, payload
- **Error Handling** - Clear error messages

## How to Build

### Prerequisites
- Android Studio (latest version)
- Android SDK (API 34)
- Java JDK 17+
- Kotlin plugin

### Steps
1. Open Android Studio
2. Select "Open Project" and choose the `android` directory
3. Wait for Gradle sync to complete
4. Build and run on an Android device (API 26+)

## How to Use

1. **Enter your server details:**
   - Server IP or hostname
   - Port (default: 443)
   - SNI host (for TLS)
   - Custom payload (optional)

2. **Click "START TUNNEL"**
   - Android will request VPN permission
   - Grant permission to establish the VPN

3. **Monitor the connection**
   - Notification shows VPN is active
   - All traffic is routed through your server

4. **Click "STOP TUNNEL"** to disconnect

## Configuration Options

### TunnelConfig Parameters
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| serverIp | String | "" | Server IP or hostname |
| serverPort | Int | 443 | Server port |
| sniHost | String | "" | SNI for TLS |
| customPayload | String | "" | Custom HTTP payload |
| protocol | String | "tls" | Protocol: tls, websocket, wss, http-connect, socks5 |
| proxyType | String? | null | Proxy type: http, socks5 |
| proxyHost | String? | null | Proxy hostname |
| proxyPort | Int? | null | Proxy port |
| allowInsecure | Boolean | false | Allow insecure connections |
| ipv6 | Boolean | false | Enable IPv6 |
| mtu | Int | 1500 | MTU size |
| keepalive | Int | 25 | Keepalive interval (seconds) |

## Security Notes

⚠️ **Important:** This VPN implementation is for **legitimate use only** with servers you own or have permission to use.

- All traffic is routed through your configured server
- TLS certificates are validated by default
- SNI is properly set for TLS connections
- Proxy configurations are supported

## Testing

### Test with a Local Server
1. Set up a local server on your computer (e.g., Python HTTP server)
2. Use `localhost` or your local IP as the server
3. Use port 443 for HTTPS or 80 for HTTP
4. Connect and verify traffic flows

### Test with a VPS
1. Deploy a server to a VPS (e.g., Nginx, Apache, custom proxy)
2. Configure the server to accept connections
3. Enter your VPS IP/hostname and port
4. Connect and test the tunnel

## Troubleshooting

### Common Issues
- **VPN permission denied** - Make sure you grant VPN permission when prompted
- **Connection failed** - Verify server is running and accessible
- **TLS handshake failed** - Check SNI and certificate validity
- **No internet access** - Verify DNS configuration and routing

### Debugging
- Check Android logcat for errors: `adb logcat | grep AdvancedVpnService`
- Verify server is reachable: `ping your-server.com`
- Test port connectivity: `nc -zv your-server.com 443`

## License

This code is provided for educational and legitimate use only. Do not use it to bypass carrier restrictions or violate terms of service.
