#!/usr/bin/env bash
# ==============================================================================
# Hetzner CX23 (2.29.8.194) — sing-box VLESS-Reality + Trojan-TLS Provisioner
# Installs official sing-box package, generates UUID + Reality X25519 keypair,
# writes /etc/sing-box/config.json, enables systemd unit, and opens UFW ports.
# ==============================================================================
set -euo pipefail
umask 077

REALITY_SNI="${REALITY_SNI:-www.microsoft.com}"
PUBLIC_HOST="${PUBLIC_HOST:-2.29.8.194}"
CONFIG_PATH="/etc/sing-box/config.json"
CRED_SUMMARY_PATH="/etc/sing-box/client-credentials.env"

if [ "$(id -u)" -ne 0 ]; then
  echo "[error] Run setup-singbox.sh as root (sudo)." >&2
  exit 1
fi

echo "[sing-box] Installing official SagerNet sing-box APT repository..."
mkdir -p /etc/apt/keyrings
curl -fsSL https://sing-box.app/gpg.key -o /etc/apt/keyrings/sagernet.asc
chmod a+r /etc/apt/keyrings/sagernet.asc

cat > /etc/apt/sources.list.d/sagernet.sources <<EOF
Types: deb
URIs: https://deb.sagernet.org/
Suites: *
Components: *
Enabled: yes
Signed-By: /etc/apt/keyrings/sagernet.asc
EOF

apt-get update
apt-get install -y --no-install-recommends sing-box openssl curl ca-certificates

mkdir -p /etc/sing-box/tls

# Generate TLS cert for Trojan port 8443 fallback if not already provisioned
if [ ! -f /etc/sing-box/tls/fullchain.pem ] || [ ! -f /etc/sing-box/tls/privkey.pem ]; then
  openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
    -keyout /etc/sing-box/tls/privkey.pem \
    -out /etc/sing-box/tls/fullchain.pem \
    -subj "/CN=static.194.8.29.2.clients.your-server.de" \
    -addext "subjectAltName=DNS:static.194.8.29.2.clients.your-server.de,IP:${PUBLIC_HOST}"
fi

# Generate fresh credentials only if config.json does not already exist
if [ ! -f "${CONFIG_PATH}" ] || grep -q "<REALITY_PRIVATE_KEY>" "${CONFIG_PATH}" 2>/dev/null; then
  SERVER_UUID="$(sing-box generate uuid)"
  TROJAN_PASSWORD="$(openssl rand -hex 24)"
  REALITY_KEYS="$(sing-box generate reality-keypair)"
  REALITY_PRIV="$(printf '%s\n' "${REALITY_KEYS}" | awk '/PrivateKey:/ {print $2}')"
  REALITY_PUB="$(printf '%s\n' "${REALITY_KEYS}" | awk '/PublicKey:/ {print $2}')"
  SHORT_ID="$(openssl rand -hex 8)"

  cat > "${CONFIG_PATH}" <<EOF
{
  "log": {
    "level": "info",
    "timestamp": true
  },
  "inbounds": [
    {
      "type": "vless",
      "tag": "vless-reality-in",
      "listen": "::",
      "listen_port": 443,
      "users": [
        {
          "name": "android-client",
          "uuid": "${SERVER_UUID}",
          "flow": "xtls-rprx-vision"
        }
      ],
      "tls": {
        "enabled": true,
        "server_name": "${REALITY_SNI}",
        "reality": {
          "enabled": true,
          "handshake": {
            "server": "${REALITY_SNI}",
            "server_port": 443
          },
          "private_key": "${REALITY_PRIV}",
          "short_id": [
            "${SHORT_ID}"
          ]
        }
      }
    },
    {
      "type": "trojan",
      "tag": "trojan-tls-in",
      "listen": "::",
      "listen_port": 8443,
      "users": [
        {
          "name": "android-trojan-client",
          "password": "${TROJAN_PASSWORD}"
        }
      ],
      "tls": {
        "enabled": true,
        "server_name": "static.194.8.29.2.clients.your-server.de",
        "alpn": ["h2", "http/1.1"],
        "certificate_path": "/etc/sing-box/tls/fullchain.pem",
        "key_path": "/etc/sing-box/tls/privkey.pem"
      }
    }
  ],
  "outbounds": [
    {
      "type": "direct",
      "tag": "direct"
    },
    {
      "type": "block",
      "tag": "block"
    }
  ],
  "route": {
    "rules": [
      {
        "ip_is_private": true,
        "outbound": "block"
      }
    ],
    "final": "direct"
  }
}
EOF
  chmod 600 "${CONFIG_PATH}"

  cat > "${CRED_SUMMARY_PATH}" <<EOF
PUBLIC_HOST=${PUBLIC_HOST}
VLESS_PORT=443
TROJAN_PORT=8443
SERVER_UUID=${SERVER_UUID}
REALITY_PUBLIC_KEY=${REALITY_PUB}
REALITY_SHORT_ID=${SHORT_ID}
REALITY_SNI=${REALITY_SNI}
TROJAN_PASSWORD=${TROJAN_PASSWORD}
EOF
  chmod 600 "${CRED_SUMMARY_PATH}"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "${SCRIPT_DIR}/sing-box.service" ]; then
  cp "${SCRIPT_DIR}/sing-box.service" /etc/systemd/system/sing-box.service
fi

sing-box check -c "${CONFIG_PATH}"

if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp comment "SSH Management" || true
  ufw allow 443/tcp comment "sing-box VLESS-Reality" || true
  ufw allow 8443/tcp comment "sing-box Trojan-TLS" || true
  ufw allow 51820/udp comment "WireGuard VPN" || true
fi

systemctl daemon-reload
systemctl enable sing-box
systemctl restart sing-box
systemctl status sing-box --no-pager || true

echo ""
echo "[sing-box] Client import parameters saved at ${CRED_SUMMARY_PATH}:"
cat "${CRED_SUMMARY_PATH}"
