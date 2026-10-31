import { isHostOrIp } from "./tunnel";

export type RelayServer = {
  id: string;
  name: string;
  host: string;
  port: number;
  transport: string;
  notes: string;
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
  { id: "websocket", label: "WebSocket (ws/wss)" },
  { id: "tls", label: "TLS socket" },
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
  notes: string;
  created_at: string;
};

export function rowToServer(row: RelayServerRow): RelayServer {
  return {
    id: row.id,
    name: row.name,
    host: row.host,
    port: row.port,
    transport: row.transport,
    notes: row.notes,
    createdAt: Date.parse(row.created_at),
  };
}
