# Native Xray VPN engine

The generated Android companion has two independent native engines:

- **WireGuard** continues to use the official WireGuard Android tunnel backend.
- **Xray** runs through the pinned `libXray` Android AAR in a dedicated Android process (`:xray`). Android `VpnService` establishes a real TUN interface, Xray receives its file descriptor through `xray.tun.fd`, and upstream sockets are protected with `VpnService.protect()`.

The separate process is intentional: do not load the WireGuard Go runtime and the Xray/libXray Go runtime in one Android process.

## Accepted profile input

The Xray profile editor accepts a complete Xray JSON configuration or one/more share links that the pinned `libXray` parser accepts, including VLESS, VMess AEAD, Trojan, Shadowsocks, SOCKS and Hysteria2. The core supports transports and security layers configured in the Xray outbound JSON, including TCP, WebSocket, gRPC, XHTTP and standard TLS when supported by that outbound and the server. REALITY camouflage is rejected in this client. TLS is a security layer, not a generic interchangeable protocol by itself. TLS certificate validation must stay enabled, and REALITY camouflage is disabled to prevent impersonating an unrelated SNI identity.

Profiles are stored using the existing Android Keystore AES-GCM store under the independent `xray_profile` key/file name. The Activity clears the profile editor when paused. Imported Xray listener inbounds are replaced with the app's private TUN inbound, so a pasted server configuration cannot unintentionally expose a local or LAN proxy listener. Existing outbounds, DNS settings and routing rules are retained; a final fallback rule sends traffic not matched by custom routing to the first outbound.

HTTP CONNECT and SOCKS5 endpoints can be configured using the corresponding Xray HTTP/SOCKS outbounds and their supported `streamSettings`; use a full Xray JSON profile whenever a share-link format cannot represent the exact endpoint options. Server and client settings must match. Unsupported or malformed profiles fail at core startup instead of silently switching to another protocol.

## Build

```sh
bun native/android/generate-project.ts /tmp/tunnel-android
bash native/android/fetch-libxray.sh /tmp/tunnel-android/app/libs
gradle -p /tmp/tunnel-android wrapper --gradle-version 8.9 --distribution-type bin
bash native/android/run-checks.sh /tmp/tunnel-android build
```

The fetch script downloads only the pinned release, verifies its SHA-256, extracts the unique `libXray.aar`, and copies MIT/MPL-2.0 license notices into APK assets. The APK contains native libraries and will be materially larger than the WireGuard-only build.

## Safety and known limits

- The Xray engine uses Android VPN permission and routes device IPv4/IPv6 traffic into a TUN. Xray's TUN stack proxies TCP and UDP and supports ICMP Echo only; other ICMP types are ignored. It is not a general-purpose layer-3 VPN for every IP protocol, a browser-only proxy, or a successful-connectivity guarantee.
- The service has a foreground notification and an explicit Stop action. If the core refuses to stop, the service keeps the TUN descriptor open and reports a stop failure rather than closing the interface underneath a live core.
- Android process death, forced stop, VPN revocation, Always-on VPN policy and OEM background restrictions still require emulator and physical-device verification. Enable Android's system **Block connections without VPN** option separately when a device-wide lockdown is required; the app does not silently claim a kill switch.
- Only one Android VPN is active at a time. The app prevents its Mobily auto-WireGuard rule from starting while the Xray service is active and asks the user to stop WireGuard before starting Xray.
- This supports authorized endpoints and normal TLS validation. It does not implement SNI/Host impersonation, domain fronting, carrier whitelist exploitation, zero-rating or billing bypass, or carrier-control evasion.
- A successful core start only proves that the local profile was accepted and the core started; it does not confirm an authenticated remote handshake. Xray's TUN is a proxy-based TCP/IP stack, so some network-level behaviors differ from WireGuard, and a connect may appear locally accepted even when a remote host is unreachable. Real server handshake, DNS, IPv4/IPv6, TCP/UDP, reconnect, leak and SIM behavior require testing on an owned server and a physical Android device.

## Verification

The native CI lane must build debug/release/test APKs and run lint with the verified AAR present. Emulator and physical-network evidence remain separate; a successful compile is not proof of a working remote server.
