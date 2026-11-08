#!/usr/bin/env bash
set -euo pipefail

PUBLIC_HOST="${PUBLIC_HOST:-2.29.8.194}"
TUN_DEV="${TUN_DEV:-tun0}"
TUN_SERVER_IP="${TUN_SERVER_IP:-10.8.0.1/24}"
TUN_SUBNET="${TUN_SUBNET:-10.8.0.0/24}"
WG_DEV="${WG_DEV:-wg0}"
WG_PORT="${WG_PORT:-51820}"
WG_SERVER_IP="${WG_SERVER_IP:-10.66.66.1/24}"
WG_SUBNET="${WG_SUBNET:-10.66.66.0/24}"
SSH_PORT="${SSH_PORT:-2222}"

echo "[tunnel-relay] Initializing multi-transport internet relay for host=${PUBLIC_HOST}"

# 1. Ensure /dev/net/tun exists for TunnelVpnService IPv4 packet forwarding
mkdir -p /dev/net
if [ ! -c /dev/net/tun ]; then
  mknod /dev/net/tun c 10 200 || true
  chmod 600 /dev/net/tun || true
fi

# 2. Create and configure tun0 (matches Android TunnelVpnService 10.8.0.2/32)
if ! ip link show "${TUN_DEV}" >/dev/null 2>&1; then
  ip tuntap add dev "${TUN_DEV}" mode tun || true
fi
ip addr flush dev "${TUN_DEV}" >/dev/null 2>&1 || true
ip addr add "${TUN_SERVER_IP}" dev "${TUN_DEV}" >/dev/null 2>&1 || true
ip link set dev "${TUN_DEV}" mtu 1500 up >/dev/null 2>&1 || true

# 3. Bootstrap WireGuard server identity and wg0 interface if not present
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

# 4. Configure NAT Masquerading and IP forwarding so all tunnel transports reach the internet
DEFAULT_IF="$(ip route show default 2>/dev/null | awk '/default/ {print $5; exit}')"
DEFAULT_IF="${DEFAULT_IF:-eth0}"

iptables -t nat -C POSTROUTING -s "${TUN_SUBNET}" -o "${DEFAULT_IF}" -j MASQUERADE 2>/dev/null || \
  iptables -t nat -A POSTROUTING -s "${TUN_SUBNET}" -o "${DEFAULT_IF}" -j MASQUERADE
iptables -t nat -C POSTROUTING -s "${WG_SUBNET}" -o "${DEFAULT_IF}" -j MASQUERADE 2>/dev/null || \
  iptables -t nat -A POSTROUTING -s "${WG_SUBNET}" -o "${DEFAULT_IF}" -j MASQUERADE

iptables -C FORWARD -i "${TUN_DEV}" -j ACCEPT 2>/dev/null || iptables -A FORWARD -i "${TUN_DEV}" -j ACCEPT
iptables -C FORWARD -o "${TUN_DEV}" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
  iptables -A FORWARD -o "${TUN_DEV}" -m state --state RELATED,ESTABLISHED -j ACCEPT
iptables -C FORWARD -i "${WG_DEV}" -j ACCEPT 2>/dev/null || iptables -A FORWARD -i "${WG_DEV}" -j ACCEPT
iptables -C FORWARD -o "${WG_DEV}" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
  iptables -A FORWARD -o "${WG_DEV}" -m state --state RELATED,ESTABLISHED -j ACCEPT
iptables -t mangle -C FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || \
  iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

# 5. Generate self-signed TLS certificate for SNI / uTLSEvasionEngine handshakes if none mounted
TLS_CERT="/etc/tunnel-relay/tls/fullchain.pem"
TLS_KEY="/etc/tunnel-relay/tls/privkey.pem"
if [ ! -f "${TLS_CERT}" ] || [ ! -f "${TLS_KEY}" ]; then
  SAN_ENTRY="DNS:${PUBLIC_HOST},DNS:static.194.8.29.2.clients.your-server.de,IP:127.0.0.1,IP:2.29.8.194"
  openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
    -keyout "${TLS_KEY}" \
    -out "${TLS_CERT}" \
    -subj "/CN=${PUBLIC_HOST}" \
    -addext "subjectAltName=${SAN_ENTRY}" >/dev/null 2>&1
fi

# 6. Start local DNS forwarder (dnsmasq) for tunnel clients (10.8.0.1 / 10.66.66.1)
dnsmasq --no-resolv --server=8.8.8.8 --server=1.1.1.1 --listen-address=127.0.0.1,10.8.0.1,10.66.66.1 >/dev/null 2>&1 || true

# 7. Start OpenSSH server on SSH_PORT for SSH tunnel transport
sed -i "s/^#\?Port .*/Port ${SSH_PORT}/" /etc/ssh/sshd_config || true
sed -i "s/^#\?AllowTcpForwarding .*/AllowTcpForwarding yes/" /etc/ssh/sshd_config || true
/usr/sbin/sshd >/dev/null 2>&1 || true

echo "[tunnel-relay] WireGuard Public Key: $(cat /etc/wireguard/server.pub 2>/dev/null || echo 'N/A')"
echo "[tunnel-relay] Launching multi-transport gateway (TLS/SNI, WebSocket, HTTP Payload, SOCKS5, TUN Bridge)..."
exec node /opt/tunnel-relay/gateway.mjs
