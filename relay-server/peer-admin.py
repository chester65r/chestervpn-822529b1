#!/usr/bin/env python3
"""Root-only, offline WireGuard peer enrollment, PSK rotation, and revocation
for Hetzner CX23 (ahmed-docker-manager / 2.29.8.194).
No device private keys. No secrets in argv, output, or environment.
"""
import argparse, base64, fcntl, ipaddress, os, pathlib, re, subprocess, tempfile

def key(value):
    try:
        raw = base64.b64decode(value, validate=True)
        if len(raw) != 32 or not any(raw) or base64.b64encode(raw).decode() != value:
            raise ValueError()
    except Exception:
        raise ValueError("Invalid public key") from None
    return value

def run(args, data=None):
    result = subprocess.run(args, input=data, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("WireGuard operation failed")
    return result.stdout.strip()

def atomic(path, text):
    fd, tmp = tempfile.mkstemp(dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write(text); stream.flush(); os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def sync(interface, directory, text):
    candidate = pathlib.Path(directory) / "candidate.conf"
    atomic(candidate, text)
    stripped = run(["wg-quick", "strip", str(candidate)])
    runtime = pathlib.Path(directory) / "runtime.conf"
    atomic(runtime, stripped + "\n")
    run(["wg", "syncconf", interface, str(runtime)])

def apply(config, interface, text, previous, before_sync=None, revoke=False):
    with tempfile.TemporaryDirectory(dir=config.parent) as directory:
        atomic(config, text)
        try:
            if before_sync: before_sync()
            sync(interface, directory, text)
        except Exception:
            if revoke:
                raise RuntimeError("Revocation saved to disk but live interface not updated; restart wg-quick@" + interface)
            atomic(config, previous)
            try: sync(interface, directory, previous)
            except Exception: raise RuntimeError("Change rolled back on disk; live interface needs restart of wg-quick@" + interface)
            raise RuntimeError("WireGuard operation failed; previous configuration restored")

def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["enroll", "rotate", "revoke"])
    parser.add_argument("--config", default="/etc/wireguard/wg0.conf")
    parser.add_argument("--interface", default="wg0")
    parser.add_argument("--public-key", required=True)
    parser.add_argument("--address", default="10.66.66.2/32")
    parser.add_argument("--endpoint", default="2.29.8.194:51820")
    parser.add_argument("--response")
    args = parser.parse_args()
    if os.geteuid() != 0: parser.error("Run as root on your WireGuard server")
    try:
        public = key(args.public_key)
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,15}", args.interface): raise ValueError("Invalid interface")
        config = pathlib.Path(args.config).resolve(strict=True)
        if not config.is_file() or config.stat().st_mode & 0o077: raise ValueError("Config must be owner-only")
        with open(str(config) + ".lock", "a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            text = config.read_text()
            parts = re.split(r"(?m)^\[Peer\]\s*$", text)
            matches = [i for i in range(1, len(parts)) if re.search(r"(?m)^PublicKey\s*=\s*" + re.escape(public) + r"\s*$", parts[i])]
            if args.action == "revoke":
                if len(matches) != 1: raise ValueError("Peer not registered")
                del parts[matches[0]]
                apply(config, args.interface, "[Peer]\n".join(parts), text, revoke=True)
                return
            if not args.response or not args.address or not args.endpoint: raise ValueError("Response, address and endpoint required")
            address = ipaddress.IPv4Interface(args.address)
            if address.network.prefixlen != 32 or address.ip not in ipaddress.IPv4Network("10.66.66.0/24") or address.ip in [ipaddress.IPv4Address("10.66.66.0"), ipaddress.IPv4Address("10.66.66.1"), ipaddress.IPv4Address("10.66.66.255")]:
                raise ValueError("Device needs a unique usable /32 in 10.66.66.0/24")
            if not re.fullmatch(r"[a-zA-Z0-9.-]+:[0-9]{1,5}", args.endpoint) or not 1 <= int(args.endpoint.rsplit(":", 1)[1]) <= 65535:
                raise ValueError("Invalid endpoint")
            if args.action == "enroll" and matches: raise ValueError("Already enrolled; use rotate")
            if args.action == "rotate" and len(matches) != 1: raise ValueError("Peer not registered")
            if matches: del parts[matches[0]]
            for section in parts[1:]:
                ips = re.search(r"(?m)^AllowedIPs\s*=\s*(.+)$", section)
                if ips and any(address.ip in ipaddress.ip_network(c.strip()) for c in ips[1].split(",")):
                    raise ValueError("Address already assigned")
            response = pathlib.Path(args.response)
            if response.exists() or response.is_symlink(): raise ValueError("Response already exists")
            psk = key(run(["wg", "genpsk"]))
            server = key(run(["wg", "show", args.interface, "public-key"]))
            section = "\nPublicKey = " + public + "\nPresharedKey = " + psk + "\nAllowedIPs = " + str(address) + "\n"
            updated = "[Peer]\n".join(parts).rstrip() + "\n\n[Peer]" + section
            enrollment = "[Interface]\nAddress = " + str(address) + "\nDNS = 10.66.66.1\nMTU = 1280\n\n[Peer]\nPublicKey = " + server + "\nPresharedKey = " + psk + "\nEndpoint = " + args.endpoint + "\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25\n"
            fd = os.open(response, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(fd, "w") as stream: stream.write(enrollment); stream.flush(); os.fsync(stream.fileno())
                drop = (lambda: run(["wg", "set", args.interface, "peer", public, "remove"])) if args.action == "rotate" else None
                apply(config, args.interface, updated, text, before_sync=drop)
            except Exception:
                response.unlink(missing_ok=True); raise
    except Exception as error:
        parser.exit(1, (str(error) if isinstance(error, (ValueError, RuntimeError)) else "Enrollment operation failed") + "\n")

if __name__ == "__main__": main()
