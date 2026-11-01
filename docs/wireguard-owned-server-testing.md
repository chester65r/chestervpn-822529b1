# Owned WireGuard server: safe setup and test record

Status: guidance prepared; no remote server has been accessed or configured. Lovable Cloud and the additional database service are unchanged. Database hosting is not an operating-system host for WireGuard.

## Required non-secret details

- VPS provider, Ubuntu/Debian version and public IPv4 or DNS hostname.
- Whether SSH administrator access and the provider's recovery console are available. Never send SSH passwords or private keys in chat.
- Public UDP endpoint, normally port 51820; a hostname must resolve to the owned server. A private-only endpoint needs explicit routing or port forwarding.
- Internet-facing network interface, available disk/memory and existing firewall/VPN/container services.
- Confirm that 10.66.66.0/24 does not overlap local networks and the test device is disposable.
- Owned TCP/UDP test destinations and approved traffic scope; do not scan or test third-party services.

## Setup using the existing app blueprint

1. Generate the Android source with `bun native/android/generate-project.ts /tmp/tunnel-android`. The `server/` directory contains `server-setup.sh` and `peer-admin.py` displayed in the Android tab.
2. On the VPS, take a snapshot and retain a recovery console. Review existing firewall rules first. The setup adds a forwarding policy that may affect containers or other routed services; prefer a disposable dedicated test VPS. Do not flush existing firewall rules or blindly run setup on a shared production host.
3. Allow inbound UDP 51820 in both the provider firewall and host firewall. Keep SSH restricted to your administrator address; do not expose tunnel DNS publicly. UDP scans alone do not prove WireGuard reachability.
4. Copy the reviewed scripts over authenticated SSH. Determine the real outbound interface with `ip -4 route get 1.1.1.1`; set `EGRESS_IF` to its `dev` value rather than assuming eth0. Run `sudo env EGRESS_IF=YOUR_INTERFACE bash server-setup.sh` only after replacing that value and reviewing the script. Setup refuses to overwrite an initialized server identity.
5. Check `sudo systemctl is-active wg-quick@wg0`, `sudo systemctl is-active unbound`, `sudo wg show wg0 listen-port` and `sudo nft list table inet wg_filter`. Verify forwarding/NAT and existing firewall compatibility. Do not share complete configurations or `wg showconf` output: they contain secrets.
6. Generate identity inside Android; give only its public key to the server administrator. Run the supplied `peer-admin.py enroll` command using that public key, a unique address such as `10.66.66.2/32`, your actual public endpoint, and a new root-only response-file path. Never generate the Android private key on the server.
7. Obtain the server public key with `sudo wg show wg0 public-key`, verify it independently over a trusted channel, and pin it in Android. Transfer the enrollment response confidentially: it contains a per-device preshared key. Never put it in chat, GitHub, browser storage or TUNNEL:// share codes. Delete transferred copies after secure native enrollment.

## Connection settings

| Detail | Expected setting |
| --- | --- |
| Endpoint | Owned public hostname/IPv4 plus UDP 51820, or your explicitly configured UDP port |
| Server address | 10.66.66.1/24 |
| Device address | Unique usable /32 in 10.66.66.0/24, excluding .0, .1 and .255 |
| DNS | 10.66.66.1, reachable only inside the tunnel |
| MTU | 1280 |
| Keepalive | 25 seconds |
| Android routes | 0.0.0.0/0 and ::/0 as required by the current enrollment validator |
| Public identities | Server public key pinned independently; device public key enrolled |
| Secrets | Device private key stays on Android; per-device PSK delivered confidentially |

The supplied server setup forwards/NATs IPv4 only. The Android full-tunnel IPv6 route is not proof of working IPv6 internet access. Verify IPv6 is blocked without leakage, or separately design and test server IPv6 forwarding before claiming dual-stack support.

## Real test acceptance

- Record full emulator boot, app/test APK installations and credential instrumentation separately.
- Grant native VPN consent and connect with the real enrollment. Browser Connect is simulation, not this test.
- Confirm a fresh authenticated handshake on both native client and server, then transfer known data to owned TCP/UDP endpoints and make a DNS query through 10.66.66.1. Record timestamps, byte counts and expected answers, not keys.
- Check IPv4 egress, IPv6 behavior and DNS leakage; deny consent and test disconnect/error states.
- Revoke the peer, confirm new tunnel traffic fails, restart the server and confirm revocation persists. Re-enroll and test PSK rotation, ensuring the old PSK no longer works.
- Repeat supported Android APIs; hardware Keystore, mobile/Wi-Fi roaming, OEM background restrictions and signed releases still require physical-device evidence.

Reachability remains unverified until a genuine authenticated handshake and authorized traffic succeed. General permission to proceed does not supply a server endpoint or a remote administrative connection.