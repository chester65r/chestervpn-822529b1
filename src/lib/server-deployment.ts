import relayConfigRaw from "../../relay-server/config.json?raw";
import relayServiceRaw from "../../relay-server/sing-box.service?raw";
import relaySetupRaw from "../../relay-server/setup-singbox.sh?raw";
import relayDockerfileRaw from "../../relay-server/Dockerfile?raw";
import relayComposeRaw from "../../relay-server/docker-compose.yml?raw";
import relayEntrypointRaw from "../../relay-server/entrypoint.sh?raw";
import relayDeployRaw from "../../relay-server/deploy-hetzner.sh?raw";

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
    id: "vless-reality",
    name: "sing-box VLESS + XTLS-Vision + Reality",
    ports: "443/tcp",
    protocol: "VLESS + Reality (TLS 1.3)",
    purpose: "Anti-DPI primary inbound stealing TLS 1.3 Reality handshake from www.microsoft.com with uTLS + ClientHello fragmentation",
  },
  {
    id: "trojan-tls",
    name: "sing-box Trojan over TLS (ALPN h2)",
    ports: "8443/tcp",
    protocol: "Trojan + TLS 1.3",
    purpose: "Secondary encrypted TLS inbound on TCP 8443 for middleboxes that throttle port 443",
  },
  {
    id: "wireguard",
    name: "WireGuard Kernel/Userspace VPN",
    ports: "51820/udp",
    protocol: "WireGuard (wg0 10.66.66.1/24)",
    purpose: "Full-tunnel Layer 3 VPN with per-device keys, PSK, and iptables NAT masquerading",
  },
  {
    id: "android-libbox-tun",
    name: "Android libbox gVisor TUN Stack",
    ports: "tun0 (172.19.0.1/30)",
    protocol: "gVisor TCP/IP + DNS Hijack",
    purpose: "Terminates IPv4/IPv6 TCP & UDP/DNS inside compiled Go gVisor without manual Socket/InputStream loops",
  },
  {
    id: "systemd-service",
    name: "sing-box systemd Service Unit",
    ports: "CAP_NET_ADMIN",
    protocol: "systemd + ExecStartPre check",
    purpose: "Runs /usr/bin/sing-box run -c /etc/sing-box/config.json with automatic restart and config validation",
  },
  {
    id: "iptables-nat",
    name: "Egress Routing & Private CIDR Block",
    ports: "ip_is_private -> block",
    protocol: "sing-box Route Rules + NAT",
    purpose: "Routes verified client tunnels to direct internet while blocking SSRF to RFC1918 private ranges",
  },
];

export function getRelayDeploymentCommand(target: RelayDeploymentTarget, publicHost: string): string {
  const host = publicHost.trim();
  if (!host) return "";

  const dockerRun = `docker run -d --name tunnel-relay --restart unless-stopped --cap-add=NET_ADMIN --cap-add=SYS_MODULE --sysctl net.ipv4.ip_forward=1 -p ${WIREGUARD_PORT}:${WIREGUARD_PORT}/udp -p 51821:51821/tcp -v wg-easy:/etc/wireguard -e WG_HOST=${host} -e WG_PORT=${WIREGUARD_PORT} -e PORT=51821 ghcr.io/wg-easy/wg-easy:15`;
  if (target === "docker") return dockerRun;

  if (target === "multi-transport") {
    return [
      `sudo ufw allow 443/tcp && sudo ufw allow 8443/tcp && sudo ufw allow 51820/udp`,
      `sudo PUBLIC_HOST=${host} bash relay-server/setup-singbox.sh`,
    ].join(" && ");
  }

  return `curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker "$USER" && sudo systemctl enable --now docker && sudo ufw allow ${WIREGUARD_PORT}/udp && ${dockerRun}`;
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
    statusCheck: `sudo systemctl status sing-box --no-pager && sing-box check -c /etc/sing-box/config.json`,
  };
}

export const DEPLOYMENT_TARGETS = [
  {
    id: "multi-transport" as const,
    label: "Hetzner CX23 sing-box + systemd (VLESS-Reality / Trojan)",
    description: "Official SagerNet sing-box (443/tcp Reality, 8443/tcp Trojan) + WireGuard (51820/udp)",
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
  "Required firewall ports on 2.29.8.194: TCP 443 (sing-box VLESS-Reality), TCP 8443 (sing-box Trojan-TLS), UDP 51820 (WireGuard), and TCP 22 (SSH).",
  "Run sudo PUBLIC_HOST=2.29.8.194 bash relay-server/setup-singbox.sh to install official sing-box, generate UUID + Reality X25519 keys, and enable sing-box.service.",
  "Use relay-server/deploy-hetzner.sh for container releases under /opt/tunnel-relay/releases/<TIMESTAMP> with instant symlink rollback.",
];

export const SERVER_CONTAINER_FILES: ServerContainerFile[] = [
  {
    name: "config.json",
    path: "relay-server/config.json",
    description: "Production sing-box server config with VLESS+XTLS-Vision+Reality (443/tcp) and Trojan+TLS (8443/tcp).",
    code: relayConfigRaw,
  },
  {
    name: "sing-box.service",
    path: "relay-server/sing-box.service",
    description: "systemd unit validating /etc/sing-box/config.json via ExecStartPre before running sing-box.",
    code: relayServiceRaw,
  },
  {
    name: "setup-singbox.sh",
    path: "relay-server/setup-singbox.sh",
    description: "Installs official SagerNet sing-box APT package, generates Reality keypair + UUID, configures UFW & systemd.",
    code: relaySetupRaw,
  },
  {
    name: "Dockerfile",
    path: "relay-server/Dockerfile",
    description: "Debian bookworm image installing official sing-box package + wireguard-tools + iptables NAT.",
    code: relayDockerfileRaw,
  },
  {
    name: "docker-compose.yml",
    path: "relay-server/docker-compose.yml",
    description: "Compose stack for Hetzner CX23 (2.29.8.194) exposing 443/tcp, 8443/tcp, and 51820/udp with /etc/sing-box volume.",
    code: relayComposeRaw,
  },
  {
    name: "entrypoint.sh",
    path: "relay-server/entrypoint.sh",
    description: "Bootstraps wg0 (10.66.66.1/24), generates sing-box Reality keypair & Trojan TLS cert, checks config, and execs sing-box.",
    code: relayEntrypointRaw,
  },
  {
    name: "deploy-hetzner.sh",
    path: "relay-server/deploy-hetzner.sh",
    description: "Non-destructive release & atomic symlink rollback script for Hetzner CX23 (2.29.8.194).",
    code: relayDeployRaw,
  },
];
