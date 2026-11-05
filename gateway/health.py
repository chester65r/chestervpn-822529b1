#!/usr/bin/env python3
"""Loopback-only, read-only health endpoint for a Linux WireGuard gateway."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Sequence

_INTERFACE_RE = re.compile(r"^[A-Za-z0-9_.=-]{1,15}$")
_ALLOWED_HOSTS = {"127.0.0.1", "::1", "localhost"}


def _run(args: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(args), check=True, capture_output=True, text=True, timeout=3
    )


def probe(interface: str, runner: Callable = _run) -> dict:
    """Inspect link and WireGuard peer timestamps without returning identifiers."""
    if not _INTERFACE_RE.fullmatch(interface):
        raise ValueError("invalid WireGuard interface name")

    checked_at = datetime.now(timezone.utc).isoformat()
    try:
        link_result = runner(["ip", "-j", "link", "show", "dev", interface])
        links = json.loads(link_result.stdout)
        # Kernel reports operstate=UNKNOWN for some administratively-up WireGuard
        # interfaces. Use the authoritative IFF_UP flag, not carrier operstate.
        if len(links) != 1 or links[0].get("ifname") != interface:
            raise RuntimeError("interface not found")
        if "UP" not in links[0].get("flags", []):
            raise RuntimeError("interface is administratively down")

        peer_result = runner(["wg", "show", interface, "latest-handshakes"])
        rows = [line.split() for line in peer_result.stdout.splitlines() if line.strip()]
        if any(len(row) != 2 or not row[1].isdigit() for row in rows):
            raise RuntimeError("unexpected WireGuard output")
        now = int(datetime.now(timezone.utc).timestamp())
        active_peers = sum(
            1 for _public_key, timestamp in rows
            if int(timestamp) > 0 and now - int(timestamp) <= 180
        )
        return {
            "status": "ok",
            "interface": interface,
            "peer_count": len(rows),
            "recent_handshakes_3m": active_peers,
            "checked_at": checked_at,
        }
    except (OSError, subprocess.SubprocessError, ValueError, RuntimeError, TypeError, json.JSONDecodeError):
        return {
            "status": "unhealthy",
            "interface": interface,
            "checked_at": checked_at,
        }


def make_handler(interface: str):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path != "/healthz":
                self.send_error(404)
                return
            result = probe(interface)
            body = json.dumps(result, separators=(",", ":")).encode("utf-8")
            self.send_response(200 if result["status"] == "ok" else 503)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            return

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interface", default="wg0")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9080)
    options = parser.parse_args()
    if options.host not in _ALLOWED_HOSTS:
        parser.error("health endpoint must bind to loopback; put an authenticated TLS proxy in front if remote access is required")
    if not 1 <= options.port <= 65535:
        parser.error("port must be between 1 and 65535")
    if not _INTERFACE_RE.fullmatch(options.interface):
        parser.error("invalid WireGuard interface name")

    server = ThreadingHTTPServer((options.host, options.port), make_handler(options.interface))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
