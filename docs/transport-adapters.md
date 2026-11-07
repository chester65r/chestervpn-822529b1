# Authorized transport adapters

WireGuard is the default and only transport currently designated as a full-device VPN.
The optional adapters are protocol profiles and endpoint checks for servers the user owns
or is explicitly authorized to use. The browser UI does not open sockets or route device
traffic; an Android integration must be separately implemented and reviewed before any
adapter can be described as a VPN.

## Protocol matrix

| Adapter | Encryption | Scope in this release |
| --- | --- | --- |
| WireGuard / UDP | WireGuard cryptography | Full-device VPN through the native WireGuard engine |
| TLS over TCP | TLS | Authorized endpoint profile / diagnostic |
| WebSocket (WS) | None by WS itself | Authorized endpoint profile / diagnostic |
| Secure WebSocket (WSS) | TLS | Authorized endpoint profile / diagnostic |
| HTTP CONNECT | None by HTTP itself | Authorized proxy profile / diagnostic |
| HTTPS CONNECT | TLS to proxy | Authorized proxy profile / diagnostic |
| SOCKS5 | None by SOCKS5 itself | Authorized proxy profile / diagnostic |
| SOCKS5 over TLS | TLS wrapper, if the endpoint explicitly supports it | Authorized proxy profile / diagnostic |

## TLS requirements

TLS-enabled profiles must use the exact DNS hostname of the authorized endpoint as the
TLS server name. Native implementations must keep Android/platform certificate-chain
validation and HTTPS hostname verification enabled, use bounded connect/read timeouts,
cap request and response sizes, and report explicit failures. No trust-all certificate
manager, hostname-verification override, silent fallback to plaintext, or arbitrary
remote command channel is allowed.

The TLS server name and HTTP Host value are derived from the endpoint identity; this
profile UI does not accept an unrelated Host/SNI value. Proxy credentials are not stored
in browser local storage. If authentication is later added, it must be handled by a
separately reviewed native secure store (Android Keystore) and never exported in profile
share codes.

## Boundaries

- Only connect to endpoints you own or have explicit authorization to use.
- Do not use these options for domain fronting, unrelated-host impersonation, carrier
  whitelist exploitation, zero-rating/billing bypass, or evasion of carrier access controls.
- Carrier selection is metadata/testing only. A Mobily or STC SIM label does not create
  mobile-data service or change the carrier's charging decision.
- A no-balance SIM may trigger the same WireGuard auto-start rule, but the tunnel still
  requires a usable underlying network path to complete its handshake and carry traffic.
- SOCKS5, HTTP CONNECT, TLS streams and WebSockets are application-level protocols here.
  They do not route every Android app through the device TUN. WireGuard remains the
  full-device VPN until a separate, reviewed VpnService/TUN adapter is implemented.

## Manual verification required

For a real endpoint, verify its owner and supported protocol, server certificate, DNS
hostname, listening port and authentication policy. Test TLS success and invalid/expired
certificate rejection on a physical Android device. Do not mark carrier compatibility or
native adapter traffic as verified based on browser validation or generated source alone.
