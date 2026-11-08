import { HETZNER_SERVER_SPEC, isHostOrIp } from "./tunnel";

export type RelayServer = {
  id: string;
  name: string;
  host: string;
  port: number;
  transport: string;
  notes: string;
  status: "unknown" | "online" | "offline" | "degraded";
  lastCheckedAt: number | null;
  lastLatencyMs: number | null;
  createdAt: number;
};

export type ServerInput = {
  name: string;
  host: string;
  port: string;
  transport: string;
  notes: string;
};

export type ServerErrors = Partial<Record<"name" | "host" | "port", string>>;

export const TRANSPORTS = [
  { id: "websocket", label: "WebSocket + TLS (ws/wss)" },
  { id: "tls", label: "TLS + SNI Socket" },
  { id: "http-payload", label: "HTTP Custom Payload (Anti-DPI)" },
  { id: "socks5", label: "SOCKS5 Bridge (TCP 1080)" },
  { id: "ssh", label: "SSH Tunnel (TCP 2222)" },
  { id: "wireguard", label: "WireGuard VPN (UDP 51820)" },
];

export const PRIMARY_HETZNER_SERVER: RelayServer = {
  id: "hetzner-cx23-primary",
  name: "ahmed-docker-manager (Hetzner CX23 · Helsinki)",
  host: HETZNER_SERVER_SPEC.ip,
  port: HETZNER_SERVER_SPEC.tlsPort,
  transport: "websocket",
  notes: "Hetzner #166592617 (eu-central Helsinki) · 2.29.8.194 / static.194.8.29.2.clients.your-server.de · IPv6 2a01:4f9:c015:48b3::/64",
  status: "unknown",
  lastCheckedAt: null,
  lastLatencyMs: null,
  createdAt: 0,
};

/** Pre-configured Hetzner 2.29.8.194 (ahmed-docker-manager) infrastructure endpoints. */
export const SAMPLE_VPS_SERVERS: RelayServer[] = [
  PRIMARY_HETZNER_SERVER,
  {
    id: "hetzner-cx23-wireguard",
    name: "ahmed-docker-manager WireGuard (wg0)",
    host: HETZNER_SERVER_SPEC.ip,
    port: HETZNER_SERVER_SPEC.wireguardPort,
    transport: "wireguard",
    notes: "Hetzner #166592617 UDP 51820 WireGuard VPN (10.66.66.1/24, MTU 1280, Helsinki eu-central)",
    status: "unknown",
    lastCheckedAt: null,
    lastLatencyMs: null,
    createdAt: 0,
  },
  {
    id: "hetzner-cx23-alt-tls",
    name: "ahmed-docker-manager Alternate TLS (8443)",
    host: HETZNER_SERVER_SPEC.ip,
    port: HETZNER_SERVER_SPEC.altTlsPort,
    transport: "tls",
    notes: "Hetzner #166592617 secondary TLS + SNI listener on TCP 8443 (static.194.8.29.2.clients.your-server.de)",
    status: "unknown",
    lastCheckedAt: null,
    lastLatencyMs: null,
    createdAt: 0,
  },
];

export function validateServerInput(input: ServerInput): ServerErrors {
  const errors: ServerErrors = {};
  if (!input.name.trim()) errors.name = "Name is required";

  const host = input.host.trim();
  if (!host) errors.host = "Host is required";
  else if (!isHostOrIp(host)) errors.host = "Enter a valid IPv4 address or domain";

  const port = input.port.trim();
  if (!port) errors.port = "Port is required";
  else if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535)
    errors.port = "Enter a valid port (1-65535)";

  return errors;
}

/** Shape of a row in the public.relay_servers table. */
export type RelayServerRow = {
  id: string;
  name: string;
  host: string;
  port: number;
  transport: string;
  notes: string | null;
  status?: string;
  last_checked_at?: string | null;
  last_latency_ms?: number | null;
  created_at: string;
};

export function rowToServer(row: RelayServerRow): RelayServer {
  const status: RelayServer["status"] =
    row.status === "online" || row.status === "offline" || row.status === "degraded"
      ? row.status
      : "unknown";

  return {
    id: row.id,
    name: row.name,
    host: row.host,
    port: row.port,
    transport: row.transport,
    notes: row.notes ?? "",
    status,
    lastCheckedAt: row.last_checked_at ? Date.parse(row.last_checked_at) : null,
    lastLatencyMs: row.last_latency_ms ?? null,
    createdAt: Date.parse(row.created_at),
  };
}
