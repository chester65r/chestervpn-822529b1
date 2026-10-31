export type TunnelProfile = {
  ip: string;
  port: string;
  sni: string;
  payload: string;
};

export type RoutePreset = {
  id: string;
  label: string;
  sni: string;
};

export const ROUTE_PRESETS: RoutePreset[] = [
  { id: "alpha", label: "Route Preset Alpha (TLS Virtualization)", sni: "alpha.example.com" },
  { id: "beta", label: "Route Preset Beta (Edge CDN Routing)", sni: "beta.example.com" },
  { id: "gamma", label: "Route Preset Gamma (Cloud Delivery)", sni: "gamma.example.com" },
  { id: "custom", label: "Custom Infrastructure Gateway", sni: "" },
];

export const DEFAULT_PAYLOAD_TEMPLATE =
  "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]";

export const EMPTY_PROFILE: TunnelProfile = { ip: "", port: "443", sni: "", payload: "" };

export const PROFILE_PREFIX = "TUNNEL://";

/** Expands [host] / [crlf] macros into the raw handshake text sent after the TLS handshake. */
export function parsePayload(raw: string, host: string): string {
  const template = raw.trim() === "" ? DEFAULT_PAYLOAD_TEMPLATE : raw;
  let out = template
    .replace(/\[host\]/gi, host)
    .replace(/\[crlf\]/gi, "\r\n")
    .replace(/\\r\\n/g, "\r\n");
  if (out.endsWith("\r\n\r\n")) return out;
  out += out.endsWith("\r\n") ? "\r\n" : "\r\n\r\n";
  return out;
}

/** Makes CR/LF visible for the live preview. */
export function visualizePayload(parsed: string): string {
  return parsed.replace(/\r\n/g, "\\r\\n\n");
}

const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;
const DOMAIN = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/i;

export function isHostOrIp(value: string): boolean {
  return IPV4.test(value) || DOMAIN.test(value);
}

export type ProfileErrors = Partial<Record<keyof TunnelProfile, string>>;

export function validateProfile(p: TunnelProfile): ProfileErrors {
  const errors: ProfileErrors = {};
  const ip = p.ip.trim();
  const sni = p.sni.trim();
  const port = p.port.trim();

  if (!ip) errors.ip = "Server IP or domain is required";
  else if (!isHostOrIp(ip)) errors.ip = "Enter a valid IPv4 address or domain";

  if (!port) errors.port = "Port is required";
  else if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535)
    errors.port = "Enter a valid port (1-65535)";

  if (!sni) errors.sni = "Server name (SNI) is required";
  else if (!isHostOrIp(sni)) errors.sni = "Enter a valid host name";

  return errors;
}

function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin);
}

function fromBase64(b64: string): string {
  const bin = atob(b64);
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function exportProfile(p: TunnelProfile): string {
  const json = JSON.stringify({
    ip: p.ip.trim(),
    port: Number(p.port),
    sni: p.sni.trim(),
    payload: p.payload,
  });
  return PROFILE_PREFIX + toBase64(json);
}

export function importProfile(code: string): TunnelProfile | null {
  const trimmed = code.trim();
  if (!trimmed.startsWith(PROFILE_PREFIX)) return null;
  try {
    const data = JSON.parse(fromBase64(trimmed.slice(PROFILE_PREFIX.length)));
    if (
      typeof data?.ip !== "string" ||
      typeof data?.sni !== "string" ||
      typeof data?.payload !== "string" ||
      !Number.isInteger(data?.port)
    )
      return null;
    return { ip: data.ip, port: String(data.port), sni: data.sni, payload: data.payload };
  } catch {
    return null;
  }
}

export type SavedProfile = {
  id: string;
  name: string;
  presetId: string;
  profile: TunnelProfile;
  savedAt: number;
};

export const SAVED_PROFILES_KEY = "tunnel-console-saved-profiles";

export function serializeSavedProfiles(list: SavedProfile[]): string {
  return JSON.stringify(list);
}

/** Parses the stored saved-profiles list, dropping anything malformed. */
export function parseSavedProfiles(json: string | null): SavedProfile[] {
  if (!json) return [];
  try {
    const data: unknown = JSON.parse(json);
    if (!Array.isArray(data)) return [];
    return data.filter(
      (item): item is SavedProfile =>
        typeof item?.id === "string" &&
        typeof item?.name === "string" &&
        typeof item?.presetId === "string" &&
        typeof item?.savedAt === "number" &&
        typeof item?.profile === "object" &&
        item.profile !== null &&
        typeof (item.profile as TunnelProfile).ip === "string" &&
        typeof (item.profile as TunnelProfile).port === "string" &&
        typeof (item.profile as TunnelProfile).sni === "string" &&
        typeof (item.profile as TunnelProfile).payload === "string",
    );
  } catch {
    return [];
  }
}

export function formatSpeed(bytesPerSec: number): string {
  if (bytesPerSec >= 1024 * 1024) return `${(bytesPerSec / 1024 / 1024).toFixed(2)} MB/s`;
  return `${(bytesPerSec / 1024).toFixed(2)} KB/s`;
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export function formatDuration(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  return [h, m, s].map((n) => String(n).padStart(2, "0")).join(":");
}
