export type RelayDeploymentTarget = "multi-transport" | "ubuntu" | "docker";

const WIREGUARD_PORT = 51820;

export type ServerContainerFile = {
  name: string;
  path: string;
  description: string;
  code: string;
};

export type TransportCapability = {
  id: string;
  name: string;
  ports: string;
  protocol: string;
  purpose: string;
};

export const SERVER_TRANSPORT_CAPABILITIES: TransportCapability[] = [
  {
    id: "tls-sni-ws",
    name: "TLS + SNI & WebSocket Gateway",
    ports: "443/tcp, 8443/tcp",
    protocol: "TLS 1.3 + SNI + RFC 6455",
    purpose: "Handles uTLSEvasionEngine fragmented ClientHello, SNI spoofing, WebSocket upgrades, and /health checks",
  },
  {
    id: "http-payload",
    name: "HTTP Custom Payload & Corridor Probe",
    ports: "80/tcp, 8080/tcp, 443/tcp",
    protocol: "HTTP/1.1 Upgrade & CONNECT",
    purpose: "Responds to BugHostProbeEngine corridor scans, custom payload macros, and KeepAliveManager heartbeats",
  },
  {
    id: "wireguard",
    name: "WireGuard Kernel/Userspace VPN",
    ports: "51820/udp",
    protocol: "WireGuard (wg0 10.66.66.1/24)",
    purpose: "Full-tunnel Layer 3 VPN with per-device keys, PSK, and iptables NAT masquerading",
  },
  {
    id: "tun-nat",
    name: "Kernel TUN & NAT Internet Router",
    ports: "tun0 (10.8.0.1/24)",
    protocol: "IPv4/IPv6 Forwarding + TCPMSS",
    purpose: "Routes Android TunnelVpnService (10.8.0.2/32) packets to the open internet with DNS (dnsmasq)",
  },
  {
    id: "socks5",
    name: "SOCKS5 Bridge Proxy",
    ports: "1080/tcp",
    protocol: "RFC 1928 SOCKS5",
    purpose: "Direct outbound TCP stream relay for MultiTransportTunnelManager SOCKS5 mode",
  },
  {
    id: "ssh-tunnel",
    name: "OpenSSH Dynamic Tunnel",
    ports: "2222/tcp",
    protocol: "SSH-2.0 TCP Forwarding",
    purpose: "Encrypted SSH port-forwarding transport for restricted networks",
  },
];

export function getRelayDeploymentCommand(target: RelayDeploymentTarget, publicHost: string): string {
  const host = publicHost.trim();
  if (!host) return "";

  const runCommand = `docker run -d --name tunnel-relay --restart unless-stopped --cap-add=NET_ADMIN --cap-add=SYS_MODULE --sysctl net.ipv4.ip_forward=1 -p ${WIREGUARD_PORT}:${WIREGUARD_PORT}/udp -p 51821:51821/tcp -v wg-easy:/etc/wireguard -e WG_HOST=${host} -e WG_PORT=${WIREGUARD_PORT} -e PORT=51821 ghcr.io/wg-easy/wg-easy:15`;
  if (target === "docker") return runCommand;

  if (target === "multi-transport") {
    return [
      `sudo ufw allow 51820/udp && sudo ufw allow 443/tcp && sudo ufw allow 8443/tcp && sudo ufw allow 80/tcp && sudo ufw allow 8080/tcp && sudo ufw allow 1080/tcp && sudo ufw allow 2222/tcp`,
      `PUBLIC_HOST=${host} docker compose -f relay-server/docker-compose.yml up -d --build`,
    ].join(" && ");
  }

  return `curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker "$USER" && sudo systemctl enable --now docker && sudo ufw allow ${WIREGUARD_PORT}/udp && ${runCommand}`;
}

export function getHetznerSafeReleaseCommands(publicHost = "2.29.8.194"): {
  bootstrapUser: string;
  deployRelease: string;
  rollbackRelease: string;
  statusCheck: string;
} {
  const host = publicHost.trim() || "2.29.8.194";
  return {
    bootstrapUser: `sudo ./relay-server/deploy-hetzner.sh bootstrap-user "<YOUR_DEPLOY_SSH_PUBKEY>"`,
    deployRelease: `PUBLIC_HOST=${host} ./relay-server/deploy-hetzner.sh deploy ${host}`,
    rollbackRelease: `PUBLIC_HOST=${host} ./relay-server/deploy-hetzner.sh rollback`,
    statusCheck: `./relay-server/deploy-hetzner.sh status`,
  };
}

export const DEPLOYMENT_TARGETS = [
  {
    id: "multi-transport" as const,
    label: "Hetzner CX23 Multi-Transport Container",
    description: "WireGuard (51820/udp) + TLS/SNI + WebSocket (443/tcp) + NAT Masquerading",
  },
  {
    id: "ubuntu" as const,
    label: "Ubuntu VPS",
    description: "Docker + firewall setup",
  },
  {
    id: "docker" as const,
    label: "Existing Docker host",
    description: "Run the relay container",
  },
];

export const RELAY_DEPLOYMENT_NOTES = [
  "Target Infrastructure: Hetzner CX23 Ubuntu VPS (2.29.8.194 — 2 vCPU, 4 GB RAM, 40 GB disk).",
  "Minimum required firewall ports on 2.29.8.194: UDP 51820 (WireGuard), TCP 443 (TLS/WebSocket Gateway), and TCP 22 (SSH).",
  "Use relay-server/deploy-hetzner.sh for non-destructive timestamped releases under /opt/tunnel-relay/releases/<TIMESTAMP> and instant symlink rollback.",
  "Generate client keys on the device; persistent Docker volumes (tunnel-wireguard, tunnel-tls) preserve enrolled peers across upgrades and rollbacks.",
];

export const SERVER_CONTAINER_FILES: ServerContainerFile[] = [
  {
    name: "Dockerfile",
    path: "relay-server/Dockerfile",
    description: "Debian bookworm image with wireguard-tools, iproute2, iptables, nftables, dnsmasq, openssh-server, openssl, and Node.js gateway.",
    code: `FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \\
    ca-certificates \\
    curl \\
    dnsmasq \\
    iproute2 \\
    iptables \\
    kmod \\
    nftables \\
    nodejs \\
    openssh-server \\
    openssl \\
    procps \\
    socat \\
    wireguard-tools \\
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/tunnel-relay

COPY gateway.mjs /opt/tunnel-relay/gateway.mjs
COPY entrypoint.sh /opt/tunnel-relay/entrypoint.sh

RUN chmod +x /opt/tunnel-relay/entrypoint.sh \\
  && mkdir -p /etc/wireguard /etc/tunnel-relay/tls /var/run/sshd

EXPOSE 51820/udp 443/tcp 8443/tcp 80/tcp 8080/tcp 1080/tcp 2222/tcp

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \\
  CMD curl -kfsS https://127.0.0.1:443/health || exit 1

ENTRYPOINT ["/opt/tunnel-relay/entrypoint.sh"]`,
  },
  {
    name: "docker-compose.yml",
    path: "relay-server/docker-compose.yml",
    description: "Compose stack for Hetzner CX23 (2.29.8.194) granting NET_ADMIN, /dev/net/tun, IP forwarding sysctls, and persistent volumes.",
    code: `services:
  tunnel-relay:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: tunnel-relay-all-in-one
    restart: unless-stopped
    cap_add:
      - NET_ADMIN
      - SYS_MODULE
    devices:
      - /dev/net/tun:/dev/net/tun
    sysctls:
      - net.ipv4.ip_forward=1
      - net.ipv4.conf.all.src_valid_mark=1
      - net.ipv6.conf.all.forwarding=1
    environment:
      - PUBLIC_HOST=\${PUBLIC_HOST:-2.29.8.194}
      - TUN_SUBNET=10.8.0.0/24
      - TUN_SERVER_IP=10.8.0.1/24
      - WG_SUBNET=10.66.66.0/24
      - WG_SERVER_IP=10.66.66.1/24
      - WG_PORT=51820
      - TLS_PORTS=443,8443
      - HTTP_PORTS=80,8080
      - SOCKS5_PORT=1080
      - SSH_PORT=2222
    ports:
      - "51820:51820/udp"
      - "443:443/tcp"
      - "8443:8443/tcp"
      - "80:80/tcp"
      - "8080:8080/tcp"
      - "1080:1080/tcp"
      - "2222:2222/tcp"
    volumes:
      - tunnel-wireguard:/etc/wireguard
      - tunnel-tls:/etc/tunnel-relay/tls

volumes:
  tunnel-wireguard:
  tunnel-tls:`,
  },
  {
    name: "entrypoint.sh",
    path: "relay-server/entrypoint.sh",
    description: "Initializes tun0 (10.8.0.1/24) and wg0 (10.66.66.1/24), configures iptables NAT masquerading, DNS, SSH, and TLS certs for 2.29.8.194.",
    code: `#!/usr/bin/env bash
set -euo pipefail

PUBLIC_HOST="\${PUBLIC_HOST:-2.29.8.194}"
TUN_DEV="\${TUN_DEV:-tun0}"
TUN_SERVER_IP="\${TUN_SERVER_IP:-10.8.0.1/24}"
TUN_SUBNET="\${TUN_SUBNET:-10.8.0.0/24}"
WG_DEV="\${WG_DEV:-wg0}"
WG_PORT="\${WG_PORT:-51820}"
WG_SERVER_IP="\${WG_SERVER_IP:-10.66.66.1/24}"
WG_SUBNET="\${WG_SUBNET:-10.66.66.0/24}"
SSH_PORT="\${SSH_PORT:-2222}"

mkdir -p /dev/net
[ -c /dev/net/tun ] || mknod /dev/net/tun c 10 200 || true
ip tuntap add dev "\${TUN_DEV}" mode tun 2>/dev/null || true
ip addr add "\${TUN_SERVER_IP}" dev "\${TUN_DEV}" 2>/dev/null || true
ip link set dev "\${TUN_DEV}" mtu 1500 up 2>/dev/null || true

mkdir -p /etc/wireguard && chmod 700 /etc/wireguard
if [ ! -f /etc/wireguard/server.key ]; then
  wg genkey | tee /etc/wireguard/server.key | wg pubkey > /etc/wireguard/server.pub
fi
if [ ! -f "/etc/wireguard/\${WG_DEV}.conf" ]; then
  cat > "/etc/wireguard/\${WG_DEV}.conf" <<EOF
[Interface]
PrivateKey = $(cat /etc/wireguard/server.key)
Address = \${WG_SERVER_IP}
ListenPort = \${WG_PORT}
MTU = 1280
EOF
fi
wg-quick up "\${WG_DEV}" 2>/dev/null || true

DEFAULT_IF="$(ip route show default 2>/dev/null | awk '/default/ {print $5; exit}')"
DEFAULT_IF="\${DEFAULT_IF:-eth0}"
iptables -t nat -A POSTROUTING -s "\${TUN_SUBNET}" -o "\${DEFAULT_IF}" -j MASQUERADE
iptables -t nat -A POSTROUTING -s "\${WG_SUBNET}" -o "\${DEFAULT_IF}" -j MASQUERADE
iptables -A FORWARD -i "\${TUN_DEV}" -j ACCEPT
iptables -A FORWARD -i "\${WG_DEV}" -j ACCEPT
iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

TLS_CERT="/etc/tunnel-relay/tls/fullchain.pem"
TLS_KEY="/etc/tunnel-relay/tls/privkey.pem"
if [ ! -f "\${TLS_CERT}" ] || [ ! -f "\${TLS_KEY}" ]; then
  openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \\
    -keyout "\${TLS_KEY}" -out "\${TLS_CERT}" -subj "/CN=\${PUBLIC_HOST}" \\
    -addext "subjectAltName=DNS:\${PUBLIC_HOST},IP:127.0.0.1,IP:2.29.8.194"
fi

dnsmasq --no-resolv --server=8.8.8.8 --server=1.1.1.1 --listen-address=127.0.0.1,10.8.0.1,10.66.66.1 || true
/usr/sbin/sshd || true
exec node /opt/tunnel-relay/gateway.mjs`,
  },
  {
    name: "gateway.mjs",
    path: "relay-server/gateway.mjs",
    description: "Multi-transport Node.js daemon for TLS+SNI, WebSocket Upgrade, HTTP Custom Payload, CONNECT proxy, SOCKS5, and /health.",
    code: `import fs from "node:fs";
import net from "node:net";
import tls from "node:tls";
import crypto from "node:crypto";

// Listens on TLS_PORTS (443, 8443), HTTP_PORTS (80, 8080), and SOCKS5_PORT (1080)
// - Accepts any SNI hostname via SNICallback for uTLSEvasionEngine & CarrierTunnelOptimizer
// - Responds 200 OK on HEAD/GET /health with Hetzner CX23 node telemetry
// - Upgrades HTTP Custom Payloads (101 Switching Protocols) & echoes X-Tunnel-Profile-Accepted
// - Filters KeepAliveManager "\\r\\n" heartbeats and forwards traffic to the internet`,
  },
  {
    name: "deploy-hetzner.sh",
    path: "relay-server/deploy-hetzner.sh",
    description: "Non-destructive release & atomic symlink rollback script for Hetzner CX23 (2.29.8.194) using dedicated 'tunneldeploy' account.",
    code: `#!/usr/bin/env bash
# Usage:
#   sudo ./relay-server/deploy-hetzner.sh bootstrap-user "<SSH_PUBKEY>"
#   PUBLIC_HOST=2.29.8.194 ./relay-server/deploy-hetzner.sh deploy 2.29.8.194
#   PUBLIC_HOST=2.29.8.194 ./relay-server/deploy-hetzner.sh rollback
#   ./relay-server/deploy-hetzner.sh status
# Stages immutable releases under /opt/tunnel-relay/releases/<TIMESTAMP> and switches /opt/tunnel-relay/current atomically.`,
  },
];
