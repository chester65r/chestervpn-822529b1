export type RelayDeploymentTarget = "ubuntu" | "docker";

const WIREGUARD_PORT = 51820;

export function getRelayDeploymentCommand(target: RelayDeploymentTarget, publicHost: string): string {
  const host = publicHost.trim();
  if (!host) return "";

  const runCommand = `docker run -d --name tunnel-relay --restart unless-stopped --cap-add=NET_ADMIN --cap-add=SYS_MODULE --sysctl net.ipv4.ip_forward=1 -p ${WIREGUARD_PORT}:${WIREGUARD_PORT}/udp -p 51821:51821/tcp -v wg-easy:/etc/wireguard -e WG_HOST=${host} -e WG_PORT=${WIREGUARD_PORT} -e PORT=51821 ghcr.io/wg-easy/wg-easy:15`;
  if (target === "docker") return runCommand;

  return `curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker "$USER" && sudo systemctl enable --now docker && sudo ufw allow ${WIREGUARD_PORT}/udp && ${runCommand}`;
}

export const DEPLOYMENT_TARGETS = [
  { id: "ubuntu" as const, label: "Ubuntu VPS", description: "Docker + firewall setup" },
  { id: "docker" as const, label: "Existing Docker host", description: "Run the relay container" },
];

export const RELAY_DEPLOYMENT_NOTES = [
  "Use a dedicated VPS and a DNS name that points to its public IP.",
  "Open UDP 51820 in the cloud firewall and host firewall.",
  "Generate client keys on the device; never paste private keys into this dashboard.",
  "Replace the example image with a pinned, reviewed image digest before production.",
];
