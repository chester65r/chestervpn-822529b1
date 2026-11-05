# WireGuard health probe (initial implementation)

This is a read-only Linux health endpoint. It reports whether a configured WireGuard interface is administratively up, the number of peers, and how many peers had a handshake within the previous three minutes. It never returns peer keys, config, endpoint addresses, or traffic contents.

## Requirements

- Python 3.10+
- Linux `iproute2` (`ip`) and WireGuard tools (`wg`)
- An already provisioned WireGuard interface (default `wg0`)

It does not provision a gateway, configure forwarding/firewall/DNS, enroll devices, or establish a VPN. It is not evidence by itself of real traffic transfer.

## Run locally

```sh
python3 gateway/health.py --interface wg0 --host 127.0.0.1 --port 9080
curl --fail http://127.0.0.1:9080/healthz
```

The probe checks the interface `UP` flag instead of carrier `operstate`, which may be `UNKNOWN` on a valid point-to-point interface. It intentionally binds only to loopback. Do not expose port 9080 publicly. For remote monitoring, place it behind an authenticated TLS proxy and firewall allowlist. Never blindly alter existing firewall rules.

## Install on an existing owned Debian/Ubuntu gateway

The installer requires a running `wg-quick@wg0.service`, an owner-only `/etc/wireguard/wg0.conf`, and the `ip`, `wg`, `systemctl`, and `curl` tools. It installs a hardened systemd service and makes no WireGuard, routing, DNS, or firewall changes:

```sh
sudo bash gateway/install-health-probe.sh
systemctl status chestervpn-health.service
curl --fail http://127.0.0.1:9080/healthz
```

Rollback the probe only; this leaves WireGuard untouched:

```sh
sudo systemctl disable --now chestervpn-health.service
sudo rm -f /etc/systemd/system/chestervpn-health.service
sudo rm -rf /opt/chestervpn-gateway
sudo systemctl daemon-reload
```

Review the unit/script before production use. Installation has not been performed or tested on an owned VPS.

## Tests

```sh
python3 -m unittest discover -s gateway -p 'test_*.py' -v
```

Tests use mocked command output and static asset checks; they are not live gateway tests.

## Remaining scope

This is a read-only monitoring helper, not a VPN platform or gateway controller. It does not enroll/revoke peers, provision keys, edit firewall/routing, authenticate API clients, persist device state, or configure Android. Those require additional implementation and a controlled deployment environment.
