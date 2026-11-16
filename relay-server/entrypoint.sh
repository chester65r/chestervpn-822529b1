#!/usr/bin/env bash
set -euo pipefail

PUBLIC_HOST="${PUBLIC_HOST:-2.29.8.194}"
REALITY_SNI="${REALITY_SNI:-www.microsoft.com}"
WG_DEV="${WG_DEV:-wg0}"
WG_PORT="${WG_PORT:-51820}"
WG_SERVER_IP="${WG_SERVER_IP:-10.66.66.1/24}"
WG_SUBNET="${WG_SUBNET:-10.66.66.0/24}"
CONFIG_PATH="/etc/sing-box/config.json"
CRED_SUMMARY_PATH="/etc/sing-box/client-credentials.env"

echo "[tunnel-relay] Initializing sing-box (VLESS-Reality/Trojan) + WireGuard on host=${PUBLIC_HOST}"

# 1. Bootstrap WireGuard server identity and wg0 interface if not present
mkdir -p /etc/wireguard
chmod 700 /etc/wireguard
if [ ! -f /etc/wireguard/server.key ]; then
  wg genkey | tee /etc/wireguard/server.key | wg pubkey > /etc/wireguard/server.pub
  chmod 600 /etc/wireguard/server.key
fi

if [ ! -f "/etc/wireguard/${WG_DEV}.conf" ]; then
  SERVER_PRIV_KEY="$(cat /etc/wireguard/server.key)"
  cat > "/etc/wireguard/${WG_DEV}.conf" <<EOF
[Interface]
PrivateKey = ${SERVER_PRIV_KEY}
Address = ${WG_SERVER_IP}
ListenPort = ${WG_PORT}
MTU = 1280
EOF
  chmod 600 "/etc/wireguard/${WG_DEV}.conf"
fi

wg-quick up "${WG_DEV}" >/dev/null 2>&1 || echo "[tunnel-relay] WireGuard interface ${WG_DEV} skipped or already active"

DEFAULT_IF="$(ip route show default 2>/dev/null | awk '/default/ {print $5; exit}')"
DEFAULT_IF="${DEFAULT_IF:-eth0}"
iptables -t nat -C POSTROUTING -s "${WG_SUBNET}" -o "${DEFAULT_IF}" -j MASQUERADE 2>/dev/null || \
  iptables -t nat -A POSTROUTING -s "${WG_SUBNET}" -o "${DEFAULT_IF}" -j MASQUERADE
iptables -C FORWARD -i "${WG_DEV}" -j ACCEPT 2>/dev/null || iptables -A FORWARD -i "${WG_DEV}" -j ACCEPT
iptables -C FORWARD -o "${WG_DEV}" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
  iptables -A FORWARD -o "${WG_DEV}" -m state --state RELATED,ESTABLISHED -j ACCEPT

# 2. Provision TLS certificate for Trojan-TLS inbound on 8443
mkdir -p /etc/sing-box/tls
TLS_CERT="/etc/sing-box/tls/fullchain.pem"
TLS_KEY="/etc/sing-box/tls/privkey.pem"
if [ ! -f "${TLS_CERT}" ] || [ ! -f "${TLS_KEY}" ]; then
  SAN_ENTRY="DNS:static.194.8.29.2.clients.your-server.de,IP:127.0.0.1,IP:${PUBLIC_HOST}"
  openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
    -keyout "${TLS_KEY}" \
    -out "${TLS_CERT}" \
    -subj "/CN=static.194.8.29.2.clients.your-server.de" \
    -addext "subjectAltName=${SAN_ENTRY}" >/dev/null 2>&1
fi

# 3. Generate sing-box VLESS-Reality + Trojan credentials if config does not exist
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
        "certificate_path": "${TLS_CERT}",
        "key_path": "${TLS_KEY}"
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

sing-box check -c "${CONFIG_PATH}"

echo "[tunnel-relay] WireGuard Public Key: $(cat /etc/wireguard/server.pub 2>/dev/null || echo 'N/A')"
echo "[tunnel-relay] Launching sing-box VLESS-Reality (443) + Trojan-TLS (8443)..."
exec sing-box run -c "${CONFIG_PATH}"
