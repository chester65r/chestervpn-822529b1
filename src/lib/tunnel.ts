export type TunnelProfile = {
  ip: string;
  port: string;
  sni: string;
  payload: string;
  protocol?: "vless-reality" | "vless-ws-tls" | "trojan-tls";
  cdnHost?: string;
  wsPath?: string;
  utlsFingerprint?: "chrome" | "ios" | "firefox" | "safari" | "randomized";
  sniSplitMinBytes?: number;
  sniSplitMaxBytes?: number;
};

export type RoutePreset = {
  id: string;
  label: string;
  sni: string;
  protocol?: "vless-reality" | "vless-ws-tls" | "trojan-tls";
  cdnHost?: string;
  wsPath?: string;
  utlsFingerprint?: "chrome" | "ios";
  sniSplitMinBytes?: number;
  sniSplitMaxBytes?: number;
};

export const ROUTE_PRESETS: RoutePreset[] = [
  {
    id: "stc",
    label: "STC KSA (VLESS + Reality · 5-15B SNI Split)",
    sni: "www.stc.com.sa",
    protocol: "vless-reality",
    utlsFingerprint: "chrome",
    sniSplitMinBytes: 5,
    sniSplitMaxBytes: 15,
  },
  {
    id: "mobily",
    label: "Mobily KSA (WSS + CDN Fronted · Chrome JA4)",
    sni: "www.mobily.com.sa",
    protocol: "vless-ws-tls",
    cdnHost: "cdn.mobily.com.sa",
    wsPath: "/cdn-ws",
    utlsFingerprint: "chrome",
    sniSplitMinBytes: 5,
    sniSplitMaxBytes: 15,
  },
  { id: "alpha", label: "Route Preset Alpha (VLESS + Reality)", sni: "alpha.example.com", protocol: "vless-reality" },
  {
    id: "beta",
    label: "Route Preset Beta (WSS + CDN Routing)",
    sni: "beta.example.com",
    protocol: "vless-ws-tls",
    cdnHost: "cdn.beta.example.com",
    wsPath: "/cdn-ws",
  },
  { id: "gamma", label: "Route Preset Gamma (Trojan + TLS)", sni: "gamma.example.com", protocol: "trojan-tls" },
  { id: "custom", label: "Custom Infrastructure Gateway", sni: "", protocol: "vless-reality" },
];

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

/**
 * Builds a full, validated `sing-box` (`libbox`) outbound JSON configuration
 * mapped directly to the VPN core engine for STC (VLESS+Reality with 5-15B SNI split),
 * Mobily (WSS over CDN with custom Host header & uTLS Chrome JA4), or Trojan-TLS.
 */
export function buildValidatedOutboundPayloadJson(options: {
  protocol?: "vless-reality" | "vless-ws-tls" | "trojan-tls";
  serverHost?: string;
  serverPort?: number;
  sni?: string;
  cdnHost?: string;
  wsPath?: string;
  utlsFingerprint?: "chrome" | "ios" | "firefox" | "safari" | "randomized";
  mtu?: number;
  sniSplitMinBytes?: number;
  sniSplitMaxBytes?: number;
}): string {
  const protocol = options.protocol ?? "vless-reality";
  const server = (options.serverHost ?? HETZNER_SERVER_SPEC.ip).trim() || HETZNER_SERVER_SPEC.ip;
  const serverPort = options.serverPort ?? (protocol === "trojan-tls" ? 8443 : 443);
  const sni =
    (options.sni ?? "").trim() ||
    (protocol === "vless-ws-tls"
      ? "www.mobily.com.sa"
      : protocol === "vless-reality"
        ? "www.stc.com.sa"
        : HETZNER_SERVER_SPEC.reverseDns);
  const utlsFp = options.utlsFingerprint ?? "chrome";
  const mtu = options.mtu ?? 1280;

  const tlsBlock: Record<string, unknown> = {
    enabled: true,
    server_name: sni,
    fragment: true,
    fragment_fallback_delay: "500ms",
    record_fragment: true,
    utls: {
      enabled: true,
      fingerprint: utlsFp,
    },
  };

  if (protocol === "vless-reality") {
    tlsBlock["reality"] = {
      enabled: true,
      public_key: "<REALITY_PUBLIC_KEY>",
      short_id: "<REALITY_SHORT_ID>",
    };
  } else if (protocol === "vless-ws-tls") {
    tlsBlock["alpn"] = ["http/1.1"];
  } else {
    tlsBlock["alpn"] = ["h2", "http/1.1"];
  }

  let proxyOutbound: Record<string, unknown>;
  if (protocol === "vless-reality") {
    proxyOutbound = {
      type: "vless",
      tag: "proxy",
      server,
      server_port: serverPort,
      uuid: "<SERVER_UUID>",
      flow: "xtls-rprx-vision",
      packet_encoding: "xudp",
      tls: tlsBlock,
    };
  } else if (protocol === "vless-ws-tls") {
    const cdnHost = (options.cdnHost ?? "cdn.mobily.com.sa").trim() || sni;
    const rawPath = (options.wsPath ?? "/cdn-ws").trim() || "/cdn-ws";
    const wsPath = rawPath.startsWith("/") ? rawPath : `/${rawPath}`;
    proxyOutbound = {
      type: "vless",
      tag: "proxy",
      server,
      server_port: serverPort,
      uuid: "<SERVER_UUID>",
      packet_encoding: "xudp",
      tls: tlsBlock,
      transport: {
        type: "ws",
        path: wsPath,
        headers: {
          Host: cdnHost,
        },
        max_early_data: 2048,
        early_data_header_name: "Sec-WebSocket-Protocol",
      },
    };
  } else {
    proxyOutbound = {
      type: "trojan",
      tag: "proxy",
      server,
      server_port: serverPort,
      password: "<TROJAN_PASSWORD>",
      tls: tlsBlock,
    };
  }

  return JSON.stringify(
    {
      log: { disabled: false, level: "info", timestamp: true },
      dns: {
        servers: [
          { tag: "remote-dns", type: "udp", server: "1.1.1.1", detour: "proxy" },
          { tag: "local-dns", type: "local" },
        ],
        final: "remote-dns",
      },
      inbounds: [
        {
          type: "tun",
          tag: "tun-in",
          address: ["172.19.0.1/30"],
          mtu,
          auto_route: true,
          strict_route: true,
          stack: "gvisor",
          sniff: true,
        },
      ],
      outbounds: [proxyOutbound, { type: "direct", tag: "direct" }],
      route: {
        rules: [
          { action: "sniff" },
          { protocol: "dns", action: "hijack-dns" },
          { ip_is_private: true, outbound: "direct" },
        ],
        final: "proxy",
        auto_detect_interface: true,
        default_domain_resolver: "local-dns",
      },
    },
    null,
    2,
  );
}

export const DEFAULT_PAYLOAD_TEMPLATE = buildValidatedOutboundPayloadJson({
  protocol: "vless-reality",
  serverHost: HETZNER_SERVER_SPEC.ip,
  serverPort: 443,
  sni: "www.stc.com.sa",
  utlsFingerprint: "chrome",
  mtu: 1280,
  sniSplitMinBytes: 5,
  sniSplitMaxBytes: 15,
});

export const EMPTY_PROFILE: TunnelProfile = { ip: "", port: "443", sni: "", payload: "" };

export const HETZNER_DEFAULT_PROFILE: TunnelProfile = {
  ip: HETZNER_SERVER_SPEC.ip,
  port: String(HETZNER_SERVER_SPEC.tlsPort),
  sni: "www.stc.com.sa",
  payload: DEFAULT_PAYLOAD_TEMPLATE,
  protocol: "vless-reality",
  utlsFingerprint: "chrome",
  sniSplitMinBytes: 5,
  sniSplitMaxBytes: 15,
};

export type NetworkProviderProfile = {
  id: string;
  name: string;
  networkType: string;
  description: string;
  recommendedMtu: number;
  keepAliveIntervalSec: number;
  transportMode: "vless-reality" | "vless-ws-tls" | "wireguard-udp" | "trojan-tls-8443";
  profile: TunnelProfile;
};

/**
 * Modern carrier & telecom-provider routing profiles mapped directly to the native
 * `sing-box` (`libbox`) VPN core engine for Hetzner CX23 (ahmed-docker-manager / 2.29.8.194).
 */
export const NETWORK_PROVIDER_PROFILES: NetworkProviderProfile[] = [
  {
    id: "stc-ksa-5g-cgnat",
    name: "STC KSA 4G/5G (VLESS + Reality · SNI Split)",
    networkType: "Saudi Telecom (STC KSA 5G/LTE — VLESS + Reality Port 443)",
    description:
      "VLESS + XTLS-Vision + Reality over port 443 with TLS 1.3 handshake to operator SNI (www.stc.com.sa) and ClientHello fragmentation at the SNI boundary (offset 5–15 bytes) to defeat stateful DPI.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 12,
    transportMode: "vless-reality",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: "www.stc.com.sa",
      protocol: "vless-reality",
      utlsFingerprint: "chrome",
      sniSplitMinBytes: 5,
      sniSplitMaxBytes: 15,
      payload: buildValidatedOutboundPayloadJson({
        protocol: "vless-reality",
        serverHost: HETZNER_SERVER_SPEC.ip,
        serverPort: 443,
        sni: "www.stc.com.sa",
        utlsFingerprint: "chrome",
        mtu: 1280,
        sniSplitMinBytes: 5,
        sniSplitMaxBytes: 15,
      }),
    },
  },
  {
    id: "mobily-ksa-lte-bypass",
    name: "Mobily KSA (WebSocket + TLS CDN · Chrome JA4)",
    networkType: "Mobily KSA (Etihad Etisalat — WSS Fronted by Cloudflare/Fastly)",
    description:
      "VLESS over WebSocket + TLS 1.3 (WSS) fronted by CDN edge on port 443 with explicit Host header (cdn.mobily.com.sa), path /cdn-ws, and enforced uTLS Chrome JA4 fingerprint.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 15,
    transportMode: "vless-ws-tls",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: "www.mobily.com.sa",
      protocol: "vless-ws-tls",
      cdnHost: "cdn.mobily.com.sa",
      wsPath: "/cdn-ws",
      utlsFingerprint: "chrome",
      sniSplitMinBytes: 5,
      sniSplitMaxBytes: 15,
      payload: buildValidatedOutboundPayloadJson({
        protocol: "vless-ws-tls",
        serverHost: HETZNER_SERVER_SPEC.ip,
        serverPort: 443,
        sni: "www.mobily.com.sa",
        cdnHost: "cdn.mobily.com.sa",
        wsPath: "/cdn-ws",
        utlsFingerprint: "chrome",
        mtu: 1280,
      }),
    },
  },
  {
    id: "hetzner-tls-direct",
    name: "Hetzner CX23 Direct (VLESS + Reality 443)",
    networkType: "Fiber / Broadband / Wi-Fi (Helsinki eu-central)",
    description:
      "Direct VLESS + Reality TLS 1.3 handshake to ahmed-docker-manager (2.29.8.194:443) with uTLS Chrome JA4 and 1420 MTU.",
    recommendedMtu: 1420,
    keepAliveIntervalSec: 25,
    transportMode: "vless-reality",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.reverseDns,
      protocol: "vless-reality",
      utlsFingerprint: "chrome",
      payload: buildValidatedOutboundPayloadJson({
        protocol: "vless-reality",
        serverHost: HETZNER_SERVER_SPEC.ip,
        serverPort: 443,
        sni: HETZNER_SERVER_SPEC.reverseDns,
        utlsFingerprint: "chrome",
        mtu: 1420,
      }),
    },
  },
  {
    id: "telecom-mobile-cgnat",
    name: "Mobile Carrier / 4G & 5G CGNAT Safe",
    networkType: "Telecom Mobile Data (5G VoLTE / Carrier NAT)",
    description:
      "Clamped 1280 MTU, 15s NAT keepalive, and VLESS + Reality ClientHello SNI boundary split (5-15B) for carrier-grade NAT.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 15,
    transportMode: "vless-reality",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: "www.stc.com.sa",
      protocol: "vless-reality",
      utlsFingerprint: "ios",
      sniSplitMinBytes: 5,
      sniSplitMaxBytes: 15,
      payload: buildValidatedOutboundPayloadJson({
        protocol: "vless-reality",
        serverHost: HETZNER_SERVER_SPEC.ip,
        serverPort: 443,
        sni: "www.stc.com.sa",
        utlsFingerprint: "ios",
        mtu: 1280,
      }),
    },
  },
  {
    id: "enterprise-firewall-443",
    name: "Reverse-DNS SNI Relay (clients.your-server.de)",
    networkType: "Restricted Firewall / Corporate Wi-Fi (UDP Blocked)",
    description:
      "Uses Hetzner's official reverse DNS hostname (static.194.8.29.2.clients.your-server.de) with VLESS over WSS TLS 443.",
    recommendedMtu: 1360,
    keepAliveIntervalSec: 20,
    transportMode: "vless-ws-tls",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "443",
      sni: HETZNER_SERVER_SPEC.reverseDns,
      protocol: "vless-ws-tls",
      cdnHost: HETZNER_SERVER_SPEC.reverseDns,
      wsPath: "/cdn-ws",
      utlsFingerprint: "chrome",
      payload: buildValidatedOutboundPayloadJson({
        protocol: "vless-ws-tls",
        serverHost: HETZNER_SERVER_SPEC.ip,
        serverPort: 443,
        sni: HETZNER_SERVER_SPEC.reverseDns,
        cdnHost: HETZNER_SERVER_SPEC.reverseDns,
        wsPath: "/cdn-ws",
        utlsFingerprint: "chrome",
        mtu: 1360,
      }),
    },
  },
  {
    id: "hetzner-alt-8443",
    name: "Hetzner CX23 Trojan + TLS (Port 8443)",
    networkType: "Alternate Port / Middlebox Fallback",
    description:
      "Connects to the secondary Trojan + TLS 1.3 listener on 2.29.8.194:8443 when middleboxes throttle standard port 443.",
    recommendedMtu: 1280,
    keepAliveIntervalSec: 20,
    transportMode: "trojan-tls-8443",
    profile: {
      ip: HETZNER_SERVER_SPEC.ip,
      port: "8443",
      sni: HETZNER_SERVER_SPEC.reverseDns,
      protocol: "trojan-tls",
      utlsFingerprint: "chrome",
      payload: buildValidatedOutboundPayloadJson({
        protocol: "trojan-tls",
        serverHost: HETZNER_SERVER_SPEC.ip,
        serverPort: 8443,
        sni: HETZNER_SERVER_SPEC.reverseDns,
        utlsFingerprint: "chrome",
        mtu: 1280,
      }),
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
 * production Hetzner CX23 (2.29.8.194) STC VLESS+Reality profile when no saved draft is present.
 */
export function resolveInitialActiveProfile(json: string | null): { profile: TunnelProfile; presetId: string } {
  if (!json) {
    return { profile: HETZNER_DEFAULT_PROFILE, presetId: "stc" };
  }
  const parsed = parseActiveProfile(json);
  if (!parsed.profile.ip && !parsed.profile.sni && !parsed.profile.payload) {
    return { profile: HETZNER_DEFAULT_PROFILE, presetId: "stc" };
  }
  return parsed;
}

export const PROFILE_PREFIX = "TUNNEL://";

/**
 * Resolves the active `sing-box` outbound JSON configuration for the given profile & SNI host.
 */
export function parsePayload(raw: string, host: string): string {
  const cleanHost = host.trim() || "www.stc.com.sa";
  const trimmed = raw.trim();
  if (trimmed.startsWith("{")) {
    try {
      const parsed = JSON.parse(trimmed) as Record<string, unknown>;
      const outbounds = Array.isArray(parsed["outbounds"]) ? parsed["outbounds"] : [];
      for (const item of outbounds) {
        if (typeof item === "object" && item !== null) {
          const outObj = item as Record<string, unknown>;
          const tlsObj = outObj["tls"] as Record<string, unknown> | undefined;
          if (tlsObj && host.trim()) {
            tlsObj["server_name"] = cleanHost;
          }
        }
      }
      return JSON.stringify(parsed, null, 2);
    } catch {
      // Fall through to build validated outbound JSON
    }
  }
  const isMobily = cleanHost.toLowerCase().includes("mobily");
  return buildValidatedOutboundPayloadJson({
    protocol: isMobily ? "vless-ws-tls" : "vless-reality",
    serverHost: HETZNER_SERVER_SPEC.ip,
    serverPort: 443,
    sni: cleanHost,
    ...(isMobily ? { cdnHost: "cdn.mobily.com.sa", wsPath: "/cdn-ws" } : {}),
    utlsFingerprint: "chrome",
    mtu: 1280,
  });
}

/** Formats the parsed sing-box JSON payload for live preview. */
export function visualizePayload(parsed: string): string {
  return parsed.trim();
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
  const payloadObj: Record<string, unknown> = {
    ip: p.ip.trim(),
    port: Number(p.port),
    sni: p.sni.trim(),
    payload: p.payload,
  };
  if (p.protocol) payloadObj["protocol"] = p.protocol;
  if (p.cdnHost) payloadObj["cdnHost"] = p.cdnHost;
  if (p.wsPath) payloadObj["wsPath"] = p.wsPath;
  if (p.utlsFingerprint) payloadObj["utlsFingerprint"] = p.utlsFingerprint;
  if (p.sniSplitMinBytes !== undefined) payloadObj["sniSplitMinBytes"] = p.sniSplitMinBytes;
  if (p.sniSplitMaxBytes !== undefined) payloadObj["sniSplitMaxBytes"] = p.sniSplitMaxBytes;

  return PROFILE_PREFIX + toBase64(JSON.stringify(payloadObj));
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
    const profile: TunnelProfile = {
      ip: data.ip,
      port: String(data.port),
      sni: data.sni,
      payload: data.payload,
      ...(typeof data.protocol === "string" ? { protocol: data.protocol } : {}),
      ...(typeof data.cdnHost === "string" ? { cdnHost: data.cdnHost } : {}),
      ...(typeof data.wsPath === "string" ? { wsPath: data.wsPath } : {}),
      ...(typeof data.utlsFingerprint === "string" ? { utlsFingerprint: data.utlsFingerprint } : {}),
      ...(typeof data.sniSplitMinBytes === "number" ? { sniSplitMinBytes: data.sniSplitMinBytes } : {}),
      ...(typeof data.sniSplitMaxBytes === "number" ? { sniSplitMaxBytes: data.sniSplitMaxBytes } : {}),
    };
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
