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

/**
 * Production Hetzner Ubuntu VPS infrastructure specification
 * (Verified from Hetzner Cloud Console: Server #166592617 "ahmed-docker-manager").
 */
export const HETZNER_SERVER_SPEC = {
  provider: "Hetzner Cloud",
  serverName: "ahmed-docker-manager",
  serverId: "166592617",
  projectId: "16098183",
  location: "Helsinki, Finland",
  networkZone: "eu-central",
  ip: "2.29.8.194",
  reverseDns: "static.194.8.29.2.clients.your-server.de",
  ipv6Subnet: "2a01:4f9:c015:48b3::/64",
  serverType: "CX23",
  os: "Ubuntu",
  vcpu: 2,
  ramGb: 4,
  diskGb: 40,
  trafficTb: 20,
  wireguardPort: 51820,
  /** Public key of the live wg0 server identity (workspace SERVER-SETUP.md). Public keys are safe to share. */
  wireguardPublicKey: "tq3wgbo6i2A/Fg/nV2II4bk9qjupyksu/IiqLFKZHm8=",
  tlsPort: 443,
  altTlsPort: 8443,
  vpnSubnet: "10.66.66.0/24",
  tunSubnet: "10.8.0.0/24",
} as const;

export const HETZNER_DEFAULT_PROFILE: TunnelProfile = {
  ip: HETZNER_SERVER_SPEC.ip,
  port: String(HETZNER_SERVER_SPEC.tlsPort),
  sni: HETZNER_SERVER_SPEC.reverseDns,
  payload: DEFAULT_PAYLOAD_TEMPLATE,
};

export type NetworkProviderProfile = {
  id: string;
  name: string;
  networkType: string;
  description: string;
  recommendedMtu: number;
  keepAliveIntervalSec: number;
  transportMode: "tls-ws" | "wireguard-udp" | "carrier-cgnat-safe" | "alt-tls-8443";
  profile: TunnelProfile;
};

/**
 * Legitimate network & telecom-provider optimization profiles configured for
 * the Hetzner CX23 (ahmed-docker-manager / 2.29.8.194 / Helsinki eu-central) VPN and relay server.
 */
export const NETWORK_PROVIDER_PROFILES: NetworkProviderProfile[] = [
  {
    id: "hetzner-tls-direct",
    name: "Hetzner CX23 Direct (TLS 1.3 + WebSocket)",
    networkType: "Fiber / Broadband / Wi-Fi (Helsinki eu-central)",
    description:
      "Direct TLS 1.3 handshake to ahmed-docker-manager (2.29.8.194:443) with RFC 6455 WebSocket upgrade and standard 1420 MTU.",
    recommendedMtu: 1420,
    keepAliveIntervalSec: 25,
    transportMode: "tls-ws",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.ip,
      payload:
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]X-Tunnel-Profile: hetzner-tls-direct[crlf]",
    },
  },
  {
    id: "telecom-mobile-cgnat",
    name: "Mobile Carrier / 4G & 5G CGNAT Safe",
    networkType: "Telecom Mobile Data (5G VoLTE / Carrier NAT)",
    description:
      "Clamped 1280 MTU, 15s NAT keepalive, and chunked TLS handshake for carrier-grade NAT and strict mobile packet inspection.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 15,
    transportMode: "carrier-cgnat-safe",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.ip,
      payload:
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade, Keep-Alive[crlf]Upgrade: websocket[crlf]X-Tunnel-MTU: 1280[crlf]X-Tunnel-Profile: telecom-mobile-cgnat[crlf]",
    },
  },
  {
    id: "enterprise-firewall-443",
    name: "Reverse-DNS SNI Relay (clients.your-server.de)",
    networkType: "Restricted Firewall / Corporate Wi-Fi (UDP Blocked)",
    description:
      "Uses Hetzner's official reverse DNS hostname (static.194.8.29.2.clients.your-server.de) as TLS SNI over TCP 443.",
    recommendedMtu: 1360,
    keepAliveIntervalSec: 20,
    transportMode: "tls-ws",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.reverseDns,
      payload:
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]Cache-Control: no-cache[crlf]X-Tunnel-Profile: enterprise-firewall-443[crlf]",
    },
  },
  {
    id: "hetzner-alt-8443",
    name: "Hetzner CX23 Alternate TLS Port (8443)",
    networkType: "Alternate Port / Middlebox Fallback",
    description:
      "Connects to the secondary TLS listener on 2.29.8.194:8443 when middleboxes throttle standard port 443.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 20,
    transportMode: "alt-tls-8443",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "8443",
      sni: HETZNER_SERVER_SPEC.ip,
      payload:
        "GET / HTTP/1.1[crlf]Host: [host]:8443[crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]X-Tunnel-Profile: hetzner-alt-8443[crlf]",
    },
  },
  {
    id: "strict-dpi-bypass",
    name: "Strict Deep Packet Inspection (DPI) Bypass & Obfuscated SNI",
    networkType: "Restricted ISP / Censored Firewall / DPI Bypass",
    description:
      "Advanced anti-DPI obfuscation profile utilizing fragmented TLS SNI headers, custom User-Agent spoofing, and randomized keepalive padding targeting the Hetzner CX23 gateway.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 10,
    transportMode: "tls-ws",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.reverseDns,
      payload:
        "GET /cdn/websocket HTTP/1.1[crlf]Host: [host][crlf]User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36[crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]Sec-WebSocket-Protocol: tunnel-v2[crlf]X-Bypass-DPI: true[crlf]",
    },
  },
  {
    id: "stc-ksa-5g-cgnat",
    name: "STC KSA 4G/5G (Saudi Telecom Carrier NAT Optimized)",
    networkType: "Saudi Telecom (STC KSA Mobile Data & Fiber)",
    description:
      "Specialized profile optimized for STC KSA network gateways (AS25019 / AS39386) with 1280 MTU, aggressive 12s keepalive, and reverse-DNS SNI over port 443.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 12,
    transportMode: "carrier-cgnat-safe",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.reverseDns,
      payload:
        "GET /stc/v2/stream HTTP/1.1[crlf]Host: [host][crlf]X-Carrier: STC-KSA[crlf]Connection: Upgrade, Keep-Alive[crlf]Upgrade: websocket[crlf]Cache-Control: no-store[crlf]",
    },
  },
  {
    id: "mobily-ksa-lte-bypass",
    name: "Mobily KSA (Etihad Etisalat Mobile Data & CGNAT)",
    networkType: "Mobily KSA (Etihad Etisalat Mobile & Broadband)",
    description:
      "Tuned specifically for Mobily KSA infrastructure (AS2914 / AS39386) featuring chunked HTTP header padding, 1280 MTU, and 15s persistent keepalive to defeat middlebox throttling.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 15,
    transportMode: "carrier-cgnat-safe",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.reverseDns,
      payload:
        "GET /mobily/ws/connect HTTP/1.1[crlf]Host: [host][crlf]X-Operator: Mobily-KSA[crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]Sec-WebSocket-Version: 13[crlf]",
    },
  },
];

export function getNetworkProviderProfile(id: string): NetworkProviderProfile | undefined {
  return NETWORK_PROVIDER_PROFILES.find((item) => item.id === id);
}

function isTunnelProfile(value: unknown): value is TunnelProfile {
  if (typeof value !== "object" || value === null) return false;
  const p = value as Partial<TunnelProfile>;
  return typeof p.ip === "string" && typeof p.port === "string" &&
    typeof p.sni === "string" && typeof p.payload === "string";
}

/** Restore only typed fields; a corrupted draft must never crash the dashboard. */
export function parseActiveProfile(json: string | null): { profile: TunnelProfile; presetId: string } {
  const fallback = { profile: EMPTY_PROFILE, presetId: "custom" };
  if (!json) return fallback;
  try {
    const data = JSON.parse(json);
    if (!isTunnelProfile(data?.profile)) return fallback;
    const preset = ROUTE_PRESETS.find((p) => p.id === data.presetId && p.sni === data.profile.sni);
    return { profile: data.profile, presetId: preset?.id ?? "custom" };
  } catch {
    return fallback;
  }
}

/**
 * Resolves the active profile on workspace hydration, defaulting to the
 * production Hetzner CX23 (2.29.8.194) profile when no saved draft is present.
 */
export function resolveInitialActiveProfile(json: string | null): { profile: TunnelProfile; presetId: string } {
  if (!json) {
    return { profile: HETZNER_DEFAULT_PROFILE, presetId: "custom" };
  }
  const parsed = parseActiveProfile(json);
  if (!parsed.profile.ip && !parsed.profile.sni && !parsed.profile.payload) {
    return { profile: HETZNER_DEFAULT_PROFILE, presetId: "custom" };
  }
  return parsed;
}

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
    const profile = { ip: data.ip, port: String(data.port), sni: data.sni, payload: data.payload };
    return Object.keys(validateProfile(profile)).length === 0 ? profile : null;
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

/** Converts a bytes/second sample to megabits/second for plain-text status reports. */
export function bytesPerSecToMbps(bytesPerSec: number): number {
  return (bytesPerSec * 8) / 1_000_000;
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
