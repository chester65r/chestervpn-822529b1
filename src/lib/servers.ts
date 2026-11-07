import { isHostOrIp } from "./tunnel";

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

/** Sample VPS server presets for quick setup. Replace with your actual server details. */
export const SAMPLE_VPS_SERVERS: RelayServer[] = [
  {
    id: "sample-vps-1",
    name: "US East VPS",
    host: "your-vps-ny.example.com",
    port: 443,
    transport: "websocket",
    notes: "Replace with your actual VPS hostname or IP",
    status: "unknown",
    lastCheckedAt: null,
    lastLatencyMs: null,
    createdAt: 0,
  },
  {
    id: "sample-vps-2",
    name: "Europe VPS",
    host: "your-vps-fra.example.com",
    port: 8443,
    transport: "tls",
    notes: "Replace with your actual VPS hostname or IP",
    status: "unknown",
    lastCheckedAt: null,
    lastLatencyMs: null,
    createdAt: 0,
  },
  {
    id: "sample-vps-3",
    name: "Asia VPS",
    host: "your-vps-sgp.example.com",
    port: 443,
    transport: "websocket",
    notes: "Replace with your actual VPS hostname or IP",
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
