# WireGuard health probe (initial implementation)

This is a read-only Linux health endpoint. It reports whether a configured WireGuard interface is up, the number of peers, and how many peers had a handshake within the previous three minutes. It never exposes peer public keys or configuration, private keys, endpoint addresses, or traffic contents.

## Requirements

- Python 3.10+
- Linux `iproute2` (`ip`) and WireGuard tools (`wg`)
- A separately provisioned WireGuard interface, normally `wg0`

It does not provision a gateway, configure forwarding/firewall/DNS, enroll devices, or establish a VPN. The endpoint is not a substitute for an actual tunnel handshake or traffic test.

## Run locally

```sh
python3 gateway/health.py --interface wg0 --host 127.0.0.1 --port 9080
curl --fail http://127.0.0.1:9080/healthz
```

It intentionally binds only to loopback. Do not expose port 9080 publicly. If remote monitoring is needed, put it behind an authenticated TLS reverse proxy and firewall allowlist. Do not blindly alter existing firewall rules.

## Tests

```sh
python3 -m unittest discover -s gateway -p 'test_*.py' -v
```

The unit tests mock command output; they are not live gateway tests. Live verification needs an authorized Linux VPS with WireGuard configured.
