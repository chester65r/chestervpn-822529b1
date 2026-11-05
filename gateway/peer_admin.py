#!/usr/bin/env python3
"""Root-only WireGuard peer lifecycle tool for an already-owned wg-quick gateway.

Only device public keys enter arguments. Private keys are never accepted or written.
Generated PSKs exist only in memory, the protected enrollment response, and the
WireGuard configuration. This tool does not modify routing or firewall rules.
"""
from __future__ import annotations

import argparse
import base64
import fcntl
import ipaddress
import os
import pathlib
import re
import subprocess
import tempfile
from typing import Callable, Sequence

_KEY = re.compile(r"^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$")
_IFACE = re.compile(r"^[A-Za-z0-9_.=-]{1,15}$")
_HOST = re.compile(r"^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")


class PeerAdminError(Exception):
    """Safe-to-display validation/operation error without subprocess details."""


def _key(value: str) -> str:
    if not _KEY.fullmatch(value):
        raise PeerAdminError("Invalid WireGuard public key")
    try:
        raw = base64.b64decode(value, validate=True)
    except Exception:
        raise PeerAdminError("Invalid WireGuard public key") from None
    if len(raw) != 32 or not any(raw) or base64.b64encode(raw).decode() != value:
        raise PeerAdminError("Invalid WireGuard public key")
    return value


def _run(args: Sequence[str], runner: Callable = subprocess.run) -> str:
    try:
        completed = runner(list(args), check=True, capture_output=True, text=True, timeout=10)
        return completed.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        raise PeerAdminError("WireGuard operation failed; inspect system service logs") from None


def _sections(config: str) -> tuple[list[str], list[list[str]]]:
    interface: list[str] = []
    peers: list[list[str]] = []
    current: list[str] | None = None
    for line in config.splitlines():
        section = line.strip().lower()
        if section == "[interface]":
            if current is not None:
                if current:
                    peers.append(current)
                current = None
            if interface:
                raise PeerAdminError("Config must have exactly one Interface section")
            interface = [line]
        elif section == "[peer]":
            if not interface:
                raise PeerAdminError("Config must begin with an Interface section")
            if current is not None:
                peers.append(current)
            current = [line]
        elif current is not None:
            current.append(line)
        elif interface:
            interface.append(line)
        elif line.strip() and not line.lstrip().startswith("#"):
            raise PeerAdminError("Unexpected config content before Interface")
    if current is not None:
        peers.append(current)
    if not interface:
        raise PeerAdminError("Missing Interface section")
    return interface, peers


def _field(lines: list[str], name: str) -> str | None:
    values = [m.group(1).strip() for line in lines if (m := re.match(rf"^\s*{re.escape(name)}\s*=\s*(.*?)\s*$", line, re.I))]
    if len(values) > 1:
        raise PeerAdminError(f"Duplicate {name} field in config")
    return values[0] if values else None


def _render(interface: list[str], peers: list[list[str]]) -> str:
    lines = interface[:]
    for peer in peers:
        lines.extend([""] + peer)
    return "\n".join(lines).rstrip() + "\n"


def _interface_address(interface: list[str]) -> ipaddress.IPv4Interface:
    address = _field(interface, "Address")
    if not address:
        raise PeerAdminError("Interface must have an IPv4 Address")
    cidrs = [part.strip() for part in address.split(",")]
    ipv4 = [part for part in cidrs if "." in part]
    if len(ipv4) != 1:
        raise PeerAdminError("Interface must have exactly one IPv4 tunnel network")
    try:
        return ipaddress.IPv4Interface(ipv4[0])
    except ValueError:
        raise PeerAdminError("Invalid WireGuard interface address") from None


def _validate_endpoint(value: str) -> str:
    if value.startswith("["):
        match = re.fullmatch(r"\[([0-9a-fA-F:]+)\]:(\d{1,5})", value)
        try:
            if not match or ipaddress.ip_address(match.group(1)).version != 6:
                raise ValueError()
            port = int(match.group(2))
        except ValueError:
            raise PeerAdminError("Invalid endpoint; use host:port or [IPv6]:port") from None
    else:
        match = re.fullmatch(r"([^:\s]+):(\d{1,5})", value)
        if not match:
            raise PeerAdminError("Invalid endpoint; use host:port or [IPv6]:port")
        host, port_text = match.groups()
        try:
            ipaddress.ip_address(host)
        except ValueError:
            if not _HOST.fullmatch(host) or ".." in host:
                raise PeerAdminError("Invalid endpoint host") from None
        port = int(port_text)
    if not 1 <= port <= 65535:
        raise PeerAdminError("Endpoint port must be 1-65535")
    return value


def _validate_config_path(path: pathlib.Path, interface: str, owner_uid: int = 0) -> pathlib.Path:
    if not _IFACE.fullmatch(interface):
        raise PeerAdminError("Invalid interface name")
    try:
        resolved = path.resolve(strict=True)
        info = resolved.stat()
    except OSError:
        raise PeerAdminError("WireGuard config file not found") from None
    if resolved.name != f"{interface}.conf":
        raise PeerAdminError("Config filename must match the WireGuard interface")
    parent = resolved.parent.stat()
    if (not resolved.is_file() or info.st_uid != owner_uid or info.st_mode & 0o077
            or parent.st_uid != owner_uid or parent.st_mode & 0o022):
        raise PeerAdminError("WireGuard config and parent must be owner-controlled and not group/world writable; config mode 0600 or stricter")
    return resolved


def _atomic_write(path: pathlib.Path, text: str) -> None:
    fd, tmp = tempfile.mkstemp(prefix=".chestervpn-", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
        dirfd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(dirfd)
        finally:
            os.close(dirfd)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def _live_remove(interface: str, public_key: str, runner: Callable) -> None:
    _run(["wg", "set", interface, "peer", public_key, "remove"], runner)


def _sync(interface: str, config_text: str, runner: Callable, temp_root: str = "/run") -> None:
    try:
        with tempfile.TemporaryDirectory(prefix="chestervpn-", dir=temp_root) as directory:
            quick = pathlib.Path(directory) / f"{interface}.conf"
            runtime = pathlib.Path(directory) / "runtime.conf"
            quick.write_text(config_text, encoding="utf-8")
            quick.chmod(0o600)
            stripped = _run(["wg-quick", "strip", str(quick)], runner)
            runtime.write_text(stripped + "\n", encoding="utf-8")
            runtime.chmod(0o600)
            _run(["wg", "syncconf", interface, str(runtime)], runner)
    except OSError:
        raise PeerAdminError("Cannot create protected temporary WireGuard config") from None


def _peer_keys(peers: list[list[str]]) -> list[str]:
    values: list[str] = []
    for peer in peers:
        value = _field(peer, "PublicKey")
        if not value:
            raise PeerAdminError("Every configured peer must have one public key")
        values.append(_key(value))
    if len(set(values)) != len(values):
        raise PeerAdminError("Config contains duplicate peer keys")
    return values


def apply(action: str, config_path: pathlib.Path, interface: str, public_key: str,
          address: str | None = None, endpoint: str | None = None,
          response_path: pathlib.Path | None = None, runner: Callable = subprocess.run,
          temp_root: str = "/run", euid: int | None = None, owner_uid: int = 0) -> None:
    """Apply an operation. Requires root; injected runner/temp path exist for tests."""
    if action not in {"enroll", "rotate", "revoke"}:
        raise PeerAdminError("Unsupported operation")
    if (os.geteuid() if euid is None else euid) != 0:
        raise PeerAdminError("Run as root on your owned WireGuard server")
    public_key = _key(public_key)
    path = _validate_config_path(config_path, interface, owner_uid=owner_uid)
    lock_path = path.with_suffix(path.suffix + ".chestervpn.lock")
    lock_fd = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        os.fchmod(lock_fd, 0o600)
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        original = path.read_text(encoding="utf-8")
        interface_lines, peers = _sections(original)
        keys = _peer_keys(peers)
        matches = [i for i, key_value in enumerate(keys) if key_value == public_key]

        if action == "revoke":
            if len(matches) != 1:
                raise PeerAdminError("Peer is not enrolled")
            updated_peers = [peer for i, peer in enumerate(peers) if i != matches[0]]
            updated = _render(interface_lines, updated_peers)
            _atomic_write(path, updated)
            try:
                _sync(interface, updated, runner, temp_root)
            except Exception:
                try:
                    _live_remove(interface, public_key, runner)
                except Exception:
                    raise PeerAdminError("Revocation is saved on disk, but live peer removal failed; stop/restart the WireGuard service immediately") from None
                raise PeerAdminError("Peer was removed from live interface; revocation is saved on disk") from None
            return

        if action == "enroll" and matches:
            raise PeerAdminError("Peer is already enrolled; use rotate")
        if action == "rotate" and len(matches) != 1:
            raise PeerAdminError("Peer is not enrolled")
        if not address or not endpoint or not response_path:
            raise PeerAdminError("Enrollment requires address, endpoint and protected response path")
        _validate_endpoint(endpoint)
        try:
            client_address = ipaddress.IPv4Interface(address)
        except ValueError:
            raise PeerAdminError("Device address must be an IPv4 /32") from None
        server_address = _interface_address(interface_lines)
        network = server_address.network
        if client_address.network.prefixlen != 32 or client_address.ip not in network or client_address.ip == network.network_address or client_address.ip == network.broadcast_address or client_address.ip == server_address.ip:
            raise PeerAdminError("Device needs an unused host /32 inside the WireGuard interface network")
        for peer in peers:
            if _field(peer, "PublicKey") == public_key:
                continue
            allowed = _field(peer, "AllowedIPs") or ""
            try:
                if any(client_address.ip in ipaddress.ip_network(item.strip(), strict=False) for item in allowed.split(",") if item.strip()):
                    raise PeerAdminError("Device address is already assigned")
            except ValueError:
                raise PeerAdminError("Existing peer has invalid AllowedIPs") from None
        if response_path.exists() or response_path.is_symlink():
            raise PeerAdminError("Enrollment response path already exists; choose a new private path")
        if not response_path.parent.is_dir() or response_path.parent.stat().st_uid != owner_uid or response_path.parent.stat().st_mode & 0o022:
            raise PeerAdminError("Enrollment response directory must be root-owned and not group/world writable")

        psk = _key(_run(["wg", "genpsk"], runner))
        server_public = _key(_run(["wg", "show", interface, "public-key"], runner))
        if public_key == server_public:
            raise PeerAdminError("Device and server public keys must differ")
        replacement = [
            "[Peer]", f"PublicKey = {public_key}", f"PresharedKey = {psk}", f"AllowedIPs = {client_address.ip}/32"
        ]
        updated_peers = [peer for i, peer in enumerate(peers) if i not in matches] + [replacement]
        updated = _render(interface_lines, updated_peers)
        enrollment = (
            "[Interface]\n"
            f"Address = {client_address.ip}/32\n"
            f"DNS = {server_address.ip}\n"
            "MTU = 1280\n\n"
            "[Peer]\n"
            f"PublicKey = {server_public}\n"
            f"PresharedKey = {psk}\n"
            f"Endpoint = {endpoint}\n"
            "AllowedIPs = 0.0.0.0/0, ::/0\n"
            "PersistentKeepalive = 25\n"
        )
        fd = os.open(response_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(enrollment)
                stream.flush()
                os.fsync(stream.fileno())
        except Exception:
            response_path.unlink(missing_ok=True)
            raise PeerAdminError("Could not safely write enrollment response") from None

        try:
            _atomic_write(path, updated)
            _sync(interface, updated, runner, temp_root)
        except Exception:
            response_path.unlink(missing_ok=True)
            try:
                if path.read_text(encoding="utf-8") != original:
                    _atomic_write(path, original)
                _sync(interface, original, runner, temp_root)
            except Exception:
                raise PeerAdminError("Change rolled back on disk where possible, but the live WireGuard interface could not be restored; stop/restart wg-quick@" + interface) from None
            raise PeerAdminError("WireGuard synchronization failed; previous peer configuration restored and enrollment response deleted") from None
    finally:
        os.close(lock_fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["enroll", "rotate", "revoke"])
    parser.add_argument("--interface", default="wg0")
    parser.add_argument("--config", default="/etc/wireguard/wg0.conf")
    parser.add_argument("--public-key", required=True)
    parser.add_argument("--address")
    parser.add_argument("--endpoint")
    parser.add_argument("--response", help="root-only output file for PSK-bearing client profile")
    args = parser.parse_args()
    try:
        apply(args.action, pathlib.Path(args.config), args.interface, args.public_key,
              args.address, args.endpoint, pathlib.Path(args.response) if args.response else None)
    except PeerAdminError as error:
        parser.exit(1, f"{error}\n")
    print("Operation completed. Never display or transmit the enrollment response over an untrusted channel.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
