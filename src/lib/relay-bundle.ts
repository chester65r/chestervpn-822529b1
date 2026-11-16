import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { HETZNER_SERVER_SPEC } from "./tunnel";
import { buildZipArchive, type ZipEntryInput } from "./zip";

const RELAY_DIR = resolve(process.cwd(), "relay-server");

const RELAY_FILENAMES = [
  "config.json",
  "sing-box.service",
  "setup-singbox.sh",
  "Dockerfile",
  "docker-compose.yml",
  "entrypoint.sh",
  "peer-admin.py",
  "deploy-hetzner.sh",
] as const;

export function loadRelayServerFiles(): { name: string; content: string }[] {
  return RELAY_FILENAMES.map((name) => ({
    name,
    content: readFileSync(join(RELAY_DIR, name), "utf8"),
  }));
}

export function buildRelayBundleZip(): Uint8Array {
  const entries: ZipEntryInput[] = loadRelayServerFiles().map((file) => ({
    path: `tunnel-relay/${file.name}`,
    content: file.content,
    unixMode: file.name.endsWith(".sh") || file.name.endsWith(".py") ? 0o755 : 0o644,
  }));
  return buildZipArchive(entries);
}

/**
 * Generates a self-extracting bash installer script containing all relay-server files
 * (sing-box config.json, systemd unit, setup-singbox.sh, Docker & WireGuard admin)
 * so operators can provision Hetzner CX23 (2.29.8.194) atomically.
 */
export function buildRelayInstallerScript(publicHost: string = HETZNER_SERVER_SPEC.ip): string {
  const safeHost = publicHost.trim().replace(/[^a-zA-Z0-9.-]/g, "") || HETZNER_SERVER_SPEC.ip;
  const files = loadRelayServerFiles();
  const payloadSections = files
    .map((file) => {
      const b64 = Buffer.from(file.content, "utf8").toString("base64");
      const chmodLine =
        file.name.endsWith(".sh") || file.name.endsWith(".py")
          ? `chmod +x "\${STAGING_DIR}/${file.name}"`
          : "";
      return [
        `cat <<'__B64__' | base64 -d > "\${STAGING_DIR}/${file.name}"`,
        b64,
        "__B64__",
        chmodLine,
      ]
        .filter(Boolean)
        .join("\n");
    })
    .join("\n\n");

  return `#!/usr/bin/env bash
# Tunnel Console — Self-Extracting Hetzner sing-box (VLESS-Reality + Trojan-TLS) & WireGuard Installer
# Target Host: ${safeHost}
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "[error] Run this installer as root (e.g. sudo bash install-hetzner.sh)." >&2
  exit 1
fi

STAGING_DIR="$(mktemp -d /tmp/tunnel-relay-src.XXXXXX)"
trap 'rm -rf "\${STAGING_DIR}"' EXIT

${payloadSections}

if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp comment "SSH Management" || true
  ufw allow 443/tcp comment "sing-box VLESS-Reality" || true
  ufw allow 8443/tcp comment "sing-box Trojan-TLS" || true
  ufw allow 51820/udp comment "Tunnel Relay WireGuard VPN" || true
fi

if [ "\${USE_DOCKER:-0}" = "1" ]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "[tunnel-relay] Installing Docker & Compose plugin..."
    curl -fsSL https://get.docker.com | sh
  fi
  echo "[tunnel-relay] Running atomic Docker release deploy for ${safeHost}..."
  PUBLIC_HOST="${safeHost}" bash "\${STAGING_DIR}/deploy-hetzner.sh" deploy "${safeHost}"
else
  echo "[tunnel-relay] Provisioning native systemd sing-box service on ${safeHost}..."
  PUBLIC_HOST="${safeHost}" bash "\${STAGING_DIR}/setup-singbox.sh"
  sing-box check -c /etc/sing-box/config.json
fi
`;
}
