#!/usr/bin/env bash
# Installs only the read-only health probe. Does not provision peers, touch firewall,
# enable forwarding, change DNS, or alter the WireGuard server configuration.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$repo_root/gateway"
install_dir="/opt/chestervpn-gateway"
unit="/etc/systemd/system/chestervpn-health.service"

if [[ "${EUID}" -ne 0 ]]; then echo "Run with sudo on the owned gateway." >&2; exit 1; fi
for command in ip wg systemctl curl install; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
[[ -f /etc/wireguard/wg0.conf ]] || { echo "Expected existing /etc/wireguard/wg0.conf; this installer will not create server keys or config." >&2; exit 1; }
systemctl is-active --quiet wg-quick@wg0.service || { echo "wg-quick@wg0.service is not active; refusing to install until the existing gateway is healthy." >&2; exit 1; }
ip link show dev wg0 >/dev/null || { echo "WireGuard interface wg0 not found." >&2; exit 1; }
[[ -f "$source_dir/health.py" && -f "$source_dir/chestervpn-health.service" ]] || { echo "Run from the complete repository checkout." >&2; exit 1; }

# Do not overwrite an unrelated service or installation.
if [[ -e "$unit" && ! -f "$install_dir/health.py" ]]; then
  echo "$unit already exists without this installer's managed files; refusing to overwrite." >&2
  exit 1
fi
install -d -o root -g root -m 0755 "$install_dir"
install -o root -g root -m 0644 "$source_dir/health.py" "$install_dir/health.py.new"
mv "$install_dir/health.py.new" "$install_dir/health.py"
install -o root -g root -m 0644 "$source_dir/README.md" "$install_dir/README.md.new"
mv "$install_dir/README.md.new" "$install_dir/README.md"
install -o root -g root -m 0644 "$source_dir/chestervpn-health.service" "$unit.new"
mv "$unit.new" "$unit"
systemctl daemon-reload
systemctl enable --now chestervpn-health.service

for _ in $(seq 1 10); do
  if curl --fail --silent --show-error http://127.0.0.1:9080/healthz; then
    echo
    echo "Probe installed. It is loopback-only; no firewall or forwarding rules were changed."
    exit 0
  fi
  sleep 1
done

echo "Health endpoint failed. Disable with: systemctl disable --now chestervpn-health.service" >&2
exit 1
