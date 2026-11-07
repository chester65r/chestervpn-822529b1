export const TRANSPORT_OPTIONS = [
  {
    id: "wireguard",
    label: "WireGuard (UDP)",
    tls: false,
    fullDeviceVpn: true,
    kind: "vpn",
    description: "Authenticated full-device VPN. This remains the default transport.",
  },
  {
    id: "tls-tcp",
    label: "TLS over TCP",
    tls: true,
    fullDeviceVpn: false,
    kind: "stream",
    description: "A TLS-protected TCP stream to an authorized endpoint.",
  },
  {
    id: "websocket",
    label: "WebSocket (WS)",
    tls: false,
    fullDeviceVpn: false,
    kind: "stream",
    description: "A WebSocket connection without TLS. Traffic is not encrypted by this protocol.",
  },
  {
    id: "websocket-tls",
    label: "Secure WebSocket (WSS)",
    tls: true,
    fullDeviceVpn: false,
    kind: "stream",
    description: "WebSocket over TLS with certificate-chain and hostname verification.",
  },
  {
    id: "http-connect",
    label: "HTTP CONNECT",
    tls: false,
    fullDeviceVpn: false,
    kind: "proxy",
    description: "An application-level HTTP proxy tunnel. The proxy connection itself is not encrypted.",
  },
  {
    id: "https-connect",
    label: "HTTPS CONNECT",
    tls: true,
    fullDeviceVpn: false,
    kind: "proxy",
    description: "HTTP CONNECT carried inside a verified TLS connection to the authorized proxy.",
  },
  {
    id: "socks5",
    label: "SOCKS5",
    tls: false,
    fullDeviceVpn: false,
    kind: "proxy",
    description: "An application-level SOCKS5 proxy connection without TLS.",
  },
  {
    id: "socks5-tls",
    label: "SOCKS5 over TLS",
    tls: true,
    fullDeviceVpn: false,
    kind: "proxy",
    description: "SOCKS5 inside a verified TLS connection. The endpoint must explicitly support this arrangement.",
  },
] as const;

export type TransportProtocol = (typeof TRANSPORT_OPTIONS)[number]["id"];
export type TransportKind = "vpn" | "stream" | "proxy";

export type TransportProfile = {
  protocol: TransportProtocol;
  endpointHost: string;
  endpointPort: string;
  tlsServerName: string;
  websocketPath: string;
  destinationHost: string;
  destinationPort: string;
};

export type TransportProfileErrors = Partial<
  Record<keyof TransportProfile, string>
>;

export const DEFAULT_TRANSPORT_PROFILE: TransportProfile = {
  protocol: "wireguard",
  endpointHost: "",
  endpointPort: "51820",
  tlsServerName: "",
  websocketPath: "/",
  destinationHost: "",
  destinationPort: "443",
};

export const TRANSPORT_PROFILE_STORAGE_KEY = "tunnel-console-transport-profile";

const IPV4 =
  /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;
const DOMAIN =
  /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/i;

export function isTransportHost(value: string): boolean {
  const host = value.trim();
  return IPV4.test(host) || DOMAIN.test(host);
}

export function getTransportOption(protocol: TransportProtocol) {
  return TRANSPORT_OPTIONS.find((option) => option.id === protocol)!;
}

export function isTransportProtocol(value: unknown): value is TransportProtocol {
  return (
    typeof value === "string" &&
    TRANSPORT_OPTIONS.some((option) => option.id === value)
  );
}

export function validateTransportProfile(
  profile: TransportProfile,
): TransportProfileErrors {
  const errors: TransportProfileErrors = {};
  const option = getTransportOption(profile.protocol);
  const host = profile.endpointHost.trim();
  const port = profile.endpointPort.trim();

  if (profile.protocol === "wireguard") return errors;

  if (!host) errors.endpointHost = "Authorized server hostname or IPv4 is required";
  else if (!isTransportHost(host)) {
    errors.endpointHost = "Enter a hostname or IPv4 address, without a URL or path";
  }

  if (!port || !/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
    errors.endpointPort = "Port must be between 1 and 65535";
  }

  if (option.tls) {
    if (!DOMAIN.test(host)) {
      errors.tlsServerName = "TLS requires the authorized endpoint's DNS hostname";
    } else if (profile.tlsServerName.trim().toLowerCase() !== host.toLowerCase()) {
      errors.tlsServerName = "TLS server name must exactly match the endpoint hostname";
    }
  } else if (profile.tlsServerName.trim()) {
    errors.tlsServerName = "A TLS server name is only used by TLS-enabled transports";
  }

  if (profile.protocol === "websocket" || profile.protocol === "websocket-tls") {
    const path = profile.websocketPath.trim();
    if (!path.startsWith("/") || /[\r\n\u0000]/.test(path) || path.length > 256) {
      errors.websocketPath = "WebSocket path must start with / and be at most 256 characters";
    }
  }

  if (option.kind === "proxy") {
    if (!isTransportHost(profile.destinationHost)) {
      errors.destinationHost = "Enter a valid destination hostname or IPv4";
    }
    const destinationPort = profile.destinationPort.trim();
    if (
      !/^\d+$/.test(destinationPort) ||
      Number(destinationPort) < 1 ||
      Number(destinationPort) > 65535
    ) {
      errors.destinationPort = "Destination port must be between 1 and 65535";
    }
  }

  return errors;
}

/** Browser-side profile persistence only. It does not open sockets or start a VPN. */
export function serializeTransportProfile(profile: TransportProfile): string {
  return JSON.stringify({
    version: 1,
    protocol: profile.protocol,
    endpointHost: profile.endpointHost.slice(0, 253),
    endpointPort: profile.endpointPort.slice(0, 5),
    tlsServerName: profile.tlsServerName.slice(0, 253),
    websocketPath: profile.websocketPath.slice(0, 256),
    destinationHost: profile.destinationHost.slice(0, 253),
    destinationPort: profile.destinationPort.slice(0, 5),
  });
}

/** Restore only known protocol IDs and bounded string fields from browser storage. */
export function parseTransportProfile(raw: string | null): TransportProfile {
  if (!raw) return { ...DEFAULT_TRANSPORT_PROFILE };
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) {
      return { ...DEFAULT_TRANSPORT_PROFILE };
    }
    const data = value as Record<string, unknown>;
    if (
      data.version !== 1 ||
      !isTransportProtocol(data.protocol) ||
      typeof data.endpointHost !== "string" ||
      typeof data.endpointPort !== "string" ||
      typeof data.tlsServerName !== "string" ||
      typeof data.websocketPath !== "string" ||
      typeof data.destinationHost !== "string" ||
      typeof data.destinationPort !== "string"
    ) {
      return { ...DEFAULT_TRANSPORT_PROFILE };
    }
    return {
      protocol: data.protocol,
      endpointHost: data.endpointHost.slice(0, 253),
      endpointPort: data.endpointPort.slice(0, 5),
      tlsServerName: data.tlsServerName.slice(0, 253),
      websocketPath: data.websocketPath.slice(0, 256),
      destinationHost: data.destinationHost.slice(0, 253),
      destinationPort: data.destinationPort.slice(0, 5),
    };
  } catch {
    return { ...DEFAULT_TRANSPORT_PROFILE };
  }
}
