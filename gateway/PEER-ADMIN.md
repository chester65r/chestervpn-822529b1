# WireGuard peer administration (owned Linux gateway only)

`peer_admin.py` is an initial root-only peer lifecycle utility for an already configured `wg-quick` interface. It supports enrollment, PSK rotation, and revocation. It does **not** provision a server, manage a database or API, change routes/firewall/DNS, create Android private keys, or prove traffic passes.

## Preconditions

- Use only on a Linux server you own/administer and have permission to manage.
- `wg0` is already running under `wg-quick@wg0.service`.
- `/etc/wireguard/wg0.conf` exists, is root-owned, and has mode `0600` or stricter.
- `wg`, `wg-quick`, Python 3.10+ are installed.
- The enrollment response directory must be root-owned and not group/world writable; response file is created mode `0600` and contains a preshared key. Transfer securely to the device owner, then remove it.
- Back up config securely before a change. The tool uses a lock file and atomic disk writes, updates only the WireGuard config/live interface, and never invokes firewall commands.

## Commands

Run from a trusted checkout; substitute the actual authorized endpoint and an unused /32 inside the gateway's configured tunnel network. Never pass private keys or PSKs as command arguments.

```sh
sudo install -d -o root -g root -m 0700 /root/chestervpn-enrollment
sudo python3 gateway/peer_admin.py enroll \
  --interface wg0 --config /etc/wireguard/wg0.conf \
  --public-key 'DEVICE_PUBLIC_KEY' \
  --address 10.66.66.2/32 \
  --endpoint vpn.example.net:51820 \
  --response /root/chestervpn-enrollment/device-001.conf
```

The Android device creates and retains its own private key. Only its public key is sent to the server administrator. The client profile response intentionally contains the server public key and per-device PSK but **not** a client private key. Deliver the response over an authenticated confidential channel, import it into a client that keeps credentials native/encrypted, and remove the response file after successful enrollment.

Revoke an enrolled device:

```sh
sudo python3 gateway/peer_admin.py revoke --interface wg0 --config /etc/wireguard/wg0.conf --public-key 'DEVICE_PUBLIC_KEY'
```

Rotate a device PSK (its old profile becomes invalid; issue the new response securely):

```sh
sudo python3 gateway/peer_admin.py rotate \
  --interface wg0 --config /etc/wireguard/wg0.conf \
  --public-key 'DEVICE_PUBLIC_KEY' --address 10.66.66.2/32 \
  --endpoint vpn.example.net:51820 \
  --response /root/chestervpn-enrollment/device-001-rotated.conf
```

For device-key rotation: revoke the old public key, remove the old identity from the device only after stopping its tunnel, create a new device identity, and enroll the new public key. WireGuard peer keys do not auto-expire.

## Recovery and verification

- Enrollment/rotation writes the persistent config before `wg syncconf`. If sync fails, it deletes the response and restores the prior file, then tries to restore the live config; if that fails, it reports that `wg-quick@wg0` needs operator recovery. **Do not distribute a response on failed operation.**
- Revocation writes disk first; if `syncconf` fails, it separately attempts immediate live `wg set ... remove`. If that also fails, stop/restart the WireGuard service and confirm the key is absent from both live interface and file before claiming revocation complete.
- After any change, inspect `wg show wg0` locally, verify handshake from the intended device, verify the expected peer alone has its assigned IP, then test authorized TCP/UDP/DNS and restart persistence. Do not paste key-bearing output or config into tickets/chat.
- Unit tests use a fake runner and temporary files. They are not evidence of successful production WireGuard commands or traffic.
