import { HETZNER_SERVER_SPEC } from "./tunnel";

export type SingBoxProtocol = "vless-reality" | "vless-ws-tls" | "trojan-tls";
export type UtlsFingerprint = "chrome" | "ios" | "firefox" | "safari" | "randomized";

export type SingBoxClientOptions = {
  protocol?: SingBoxProtocol;
  serverHost?: string;
  serverPort?: number;
  uuid?: string;
  trojanPassword?: string;
  sni?: string;
  realityPublicKey?: string;
  realityShortId?: string;
  wsHost?: string;
  wsPath?: string;
  utlsFingerprint?: UtlsFingerprint;
  fragmentEnabled?: boolean;
  recordFragmentEnabled?: boolean;
  sniSplitMinBytes?: number;
  sniSplitMaxBytes?: number;
  tunAddress?: string;
  mtu?: number;
  dnsServer?: string;
};

export type SingBoxServerOptions = {
  vlessPort?: number;
  wsTlsPort?: number;
  trojanPort?: number;
  uuid?: string;
  trojanPassword?: string;
  realitySni?: string;
  realityHandshakeServer?: string;
  realityPrivateKey?: string;
  realityShortId?: string;
  wsHost?: string;
  wsPath?: string;
  tlsCertPath?: string;
  tlsKeyPath?: string;
};

export function mapFingerprintProfileToUtls(profileId?: string): UtlsFingerprint {
  switch ((profileId || "").trim().toLowerCase()) {
    case "safari_17_ios":
    case "ios":
      return "ios";
    case "firefox_130_android":
    case "firefox":
      return "firefox";
    case "safari":
      return "safari";
    case "randomized_rotation":
    case "randomized":
      return "randomized";
    case "chrome_128_android":
    case "chrome":
    default:
      return "chrome";
  }
}

/**
 * Generates a production sing-box client JSON config (for libbox on Android)
 * supporting:
 * - STC Profile (`vless-reality`): VLESS + `xtls-rprx-vision` + Reality over port 443,
 *   configurable SNI (`www.stc.com.sa`), and TLS ClientHello record fragmentation at SNI boundary (offset 5-15B).
 * - Mobily Profile (`vless-ws-tls`): VLESS over WebSocket + TLS 1.3 (WSS) fronted by CDN
 *   (Cloudflare/Fastly) over port 443, explicit custom `Host` header (`cdn.mobily.com.sa`),
 *   WebSocket path (`/cdn-ws`), and uTLS Chrome JA4 profile (`chrome`).
 * - Trojan + TLS 1.3 fallback (`trojan-tls`) on port 8443.
 */
export function buildSingBoxClientConfig(options: SingBoxClientOptions = {}): Record<string, unknown> {
  const protocol: SingBoxProtocol = options.protocol ?? "vless-reality";
  const server = (options.serverHost ?? HETZNER_SERVER_SPEC.ip).trim() || HETZNER_SERVER_SPEC.ip;
  const serverPort =
    options.serverPort && Number.isInteger(options.serverPort) && options.serverPort >= 1 && options.serverPort <= 65535
      ? options.serverPort
      : protocol === "trojan-tls"
        ? 8443
        : 443;
  const defaultSni =
    protocol === "vless-ws-tls"
      ? "www.mobily.com.sa"
      : protocol === "vless-reality"
        ? "www.stc.com.sa"
        : HETZNER_SERVER_SPEC.reverseDns;
  const sni = (options.sni ?? defaultSni).trim() || defaultSni;
  const utlsFingerprint = options.utlsFingerprint ?? "chrome";
  const fragmentEnabled = options.fragmentEnabled ?? true;
  const recordFragmentEnabled = options.recordFragmentEnabled ?? true;
  const tunAddress = options.tunAddress ?? "172.19.0.1/30";
  const mtu = Math.max(1280, Math.min(1500, options.mtu ?? 1500));
  const dnsServer = options.dnsServer ?? "1.1.1.1";

  interface SingBoxTlsBlock {
    enabled: boolean;
    server_name: string;
    fragment: boolean;
    fragment_fallback_delay: string;
    record_fragment: boolean;
    utls: {
      enabled: boolean;
      fingerprint: UtlsFingerprint;
    };
    reality?: {
      enabled: boolean;
      public_key: string;
      short_id: string;
    };
    alpn?: string[];
  }

  const tlsBlock: SingBoxTlsBlock = {
    enabled: true,
    server_name: sni,
    fragment: fragmentEnabled,
    fragment_fallback_delay: "500ms",
    record_fragment: recordFragmentEnabled,
    utls: {
      enabled: true,
      fingerprint: utlsFingerprint,
    },
  };

  if (protocol === "vless-reality") {
    tlsBlock.reality = {
      enabled: true,
      public_key: options.realityPublicKey ?? "<REALITY_PUBLIC_KEY>",
      short_id: options.realityShortId ?? "<REALITY_SHORT_ID>",
    };
  } else if (protocol === "vless-ws-tls") {
    tlsBlock.alpn = ["http/1.1"];
  } else {
    tlsBlock.alpn = ["h2", "http/1.1"];
  }

  let proxyOutbound: Record<string, unknown>;
  if (protocol === "vless-reality") {
    proxyOutbound = {
      type: "vless",
      tag: "proxy",
      server,
      server_port: serverPort,
      uuid: options.uuid ?? "<SERVER_UUID>",
      flow: "xtls-rprx-vision",
      packet_encoding: "xudp",
      tls: tlsBlock,
    };
  } else if (protocol === "vless-ws-tls") {
    const wsHost = (options.wsHost ?? "cdn.mobily.com.sa").trim() || sni;
    const rawWsPath = (options.wsPath ?? "/cdn-ws").trim() || "/cdn-ws";
    const wsPath = rawWsPath.startsWith("/") ? rawWsPath : `/${rawWsPath}`;
    proxyOutbound = {
      type: "vless",
      tag: "proxy",
      server,
      server_port: serverPort,
      uuid: options.uuid ?? "<SERVER_UUID>",
      packet_encoding: "xudp",
      tls: tlsBlock,
      transport: {
        type: "ws",
        path: wsPath,
        headers: {
          Host: wsHost,
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
      password: options.trojanPassword ?? "<TROJAN_PASSWORD>",
      tls: tlsBlock,
    };
  }

  return {
    log: {
      disabled: false,
      level: "info",
      timestamp: true,
    },
    dns: {
      servers: [
        {
          tag: "remote-dns",
          type: "udp",
          server: dnsServer,
          detour: "proxy",
        },
        {
          tag: "local-dns",
          type: "local",
        },
      ],
      final: "remote-dns",
    },
    inbounds: [
      {
        type: "tun",
        tag: "tun-in",
        address: [tunAddress],
        mtu,
        auto_route: true,
        strict_route: true,
        stack: "gvisor",
        sniff: true,
      },
    ],
    outbounds: [
      proxyOutbound,
      {
        type: "direct",
        tag: "direct",
      },
    ],
    route: {
      rules: [
        {
          action: "sniff",
        },
        {
          protocol: "dns",
          action: "hijack-dns",
        },
        {
          ip_is_private: true,
          outbound: "direct",
        },
      ],
      final: "proxy",
      auto_detect_interface: true,
      default_domain_resolver: "local-dns",
    },
  };
}

/**
 * Builds a carrier-specific validated sing-box client configuration for STC or Mobily.
 */
export function buildCarrierSingBoxClientConfig(
  carrierId: string,
  overrides: SingBoxClientOptions = {},
): Record<string, unknown> {
  const normalized = carrierId.trim().toLowerCase();
  if (normalized.includes("mobily")) {
    return buildSingBoxClientConfig({
      protocol: "vless-ws-tls",
      serverHost: overrides.serverHost ?? HETZNER_SERVER_SPEC.ip,
      serverPort: overrides.serverPort ?? 443,
      sni: overrides.sni ?? "www.mobily.com.sa",
      wsHost: overrides.wsHost ?? "cdn.mobily.com.sa",
      wsPath: overrides.wsPath ?? "/cdn-ws",
      utlsFingerprint: overrides.utlsFingerprint ?? "chrome",
      fragmentEnabled: true,
      recordFragmentEnabled: true,
      sniSplitMinBytes: 5,
      sniSplitMaxBytes: 15,
      mtu: overrides.mtu ?? 1280,
      ...overrides,
    });
  }
  if (normalized.includes("8443") || normalized.includes("trojan")) {
    return buildSingBoxClientConfig({
      protocol: "trojan-tls",
      serverHost: overrides.serverHost ?? HETZNER_SERVER_SPEC.ip,
      serverPort: overrides.serverPort ?? 8443,
      sni: overrides.sni ?? HETZNER_SERVER_SPEC.reverseDns,
      utlsFingerprint: overrides.utlsFingerprint ?? "chrome",
      fragmentEnabled: true,
      recordFragmentEnabled: true,
      sniSplitMinBytes: 5,
      sniSplitMaxBytes: 15,
      mtu: overrides.mtu ?? 1280,
      ...overrides,
    });
  }
  return buildSingBoxClientConfig({
    protocol: "vless-reality",
    serverHost: overrides.serverHost ?? HETZNER_SERVER_SPEC.ip,
    serverPort: overrides.serverPort ?? 443,
    sni: overrides.sni ?? "www.stc.com.sa",
    utlsFingerprint: overrides.utlsFingerprint ?? "chrome",
    fragmentEnabled: true,
    recordFragmentEnabled: true,
    sniSplitMinBytes: 5,
    sniSplitMaxBytes: 15,
    mtu: overrides.mtu ?? 1280,
    ...overrides,
  });
}

/**
 * Generates the standard sing-box server JSON config deployed on Hetzner CX23 (2.29.8.194).
 */
export function buildSingBoxServerConfig(options: SingBoxServerOptions = {}): Record<string, unknown> {
  const vlessPort = options.vlessPort ?? 443;
  const wsTlsPort = options.wsTlsPort ?? 2053;
  const trojanPort = options.trojanPort ?? 8443;
  const realitySni = options.realitySni ?? "www.stc.com.sa";
  const realityHandshakeServer = options.realityHandshakeServer ?? realitySni;
  const wsHost = options.wsHost ?? "cdn.mobily.com.sa";
  const wsPath = options.wsPath ?? "/cdn-ws";

  return {
    log: {
      level: "info",
      timestamp: true,
    },
    inbounds: [
      {
        type: "vless",
        tag: "vless-reality-in",
        listen: "::",
        listen_port: vlessPort,
        users: [
          {
            name: "android-client",
            uuid: options.uuid ?? "<SERVER_UUID>",
            flow: "xtls-rprx-vision",
          },
        ],
        tls: {
          enabled: true,
          server_name: realitySni,
          reality: {
            enabled: true,
            handshake: {
              server: realityHandshakeServer,
              server_port: 443,
            },
            private_key: options.realityPrivateKey ?? "<REALITY_PRIVATE_KEY>",
            short_id: [options.realityShortId ?? "<REALITY_SHORT_ID>"],
          },
        },
      },
      {
        type: "vless",
        tag: "vless-ws-in",
        listen: "::",
        listen_port: wsTlsPort,
        users: [
          {
            name: "android-mobily-wss-client",
            uuid: options.uuid ?? "<SERVER_UUID>",
          },
        ],
        tls: {
          enabled: true,
          server_name: "www.mobily.com.sa",
          alpn: ["http/1.1"],
          certificate_path: options.tlsCertPath ?? "/etc/sing-box/tls/fullchain.pem",
          key_path: options.tlsKeyPath ?? "/etc/sing-box/tls/privkey.pem",
        },
        transport: {
          type: "ws",
          path: wsPath,
          headers: {
            Host: wsHost,
          },
          max_early_data: 2048,
          early_data_header_name: "Sec-WebSocket-Protocol",
        },
      },
      {
        type: "trojan",
        tag: "trojan-tls-in",
        listen: "::",
        listen_port: trojanPort,
        users: [
          {
            name: "android-trojan-client",
            password: options.trojanPassword ?? "<TROJAN_PASSWORD>",
          },
        ],
        tls: {
          enabled: true,
          server_name: HETZNER_SERVER_SPEC.reverseDns,
          alpn: ["h2", "http/1.1"],
          certificate_path: options.tlsCertPath ?? "/etc/sing-box/tls/fullchain.pem",
          key_path: options.tlsKeyPath ?? "/etc/sing-box/tls/privkey.pem",
        },
      },
    ],
    outbounds: [
      {
        type: "direct",
        tag: "direct",
      },
      {
        type: "block",
        tag: "block",
      },
    ],
    route: {
      rules: [
        {
          ip_is_private: true,
          outbound: "block",
        },
      ],
      final: "direct",
    },
  };
}

export function validateSingBoxClientConfig(config: Record<string, unknown>): string[] {
  const errors: string[] = [];
  const inbounds = Array.isArray(config["inbounds"]) ? config["inbounds"] : [];
  const tun = inbounds.find((item) => (item as Record<string, unknown>)?.["type"] === "tun") as
    | Record<string, unknown>
    | undefined;
  if (!tun) {
    errors.push("Missing TUN inbound.");
  } else if (tun["stack"] !== "gvisor") {
    errors.push("TUN inbound must use stack=gvisor.");
  }

  const outbounds = Array.isArray(config["outbounds"]) ? config["inbounds"] && config["outbounds"] : [];
  const proxy = (Array.isArray(outbounds) ? outbounds : []).find(
    (item) => (item as Record<string, unknown>)?.["tag"] === "proxy",
  ) as Record<string, unknown> | undefined;
  if (!proxy) {
    errors.push("Missing proxy outbound.");
    return errors;
  }

  if (proxy["type"] !== "vless" && proxy["type"] !== "trojan") {
    errors.push(`Unsupported proxy protocol: ${String(proxy["type"])}. Expected vless or trojan.`);
  }

  const tls = proxy["tls"] as Record<string, unknown> | undefined;
  if (!tls?.["enabled"]) errors.push("TLS must be enabled on outbound.");
  if (!tls?.["record_fragment"] || !tls?.["fragment"]) {
    errors.push("ClientHello fragment & record_fragment must be enabled.");
  }

  const utls = tls?.["utls"] as Record<string, unknown> | undefined;
  const fp = utls?.["fingerprint"];
  if (!utls?.["enabled"] || (fp !== "chrome" && fp !== "ios" && fp !== "firefox" && fp !== "safari" && fp !== "randomized")) {
    errors.push("uTLS fingerprinting (chrome/ios/etc.) must be enabled.");
  }

  if (proxy["type"] === "vless") {
    const transport = proxy["transport"] as Record<string, unknown> | undefined;
    if (transport?.["type"] === "ws") {
      const headers = transport["headers"] as Record<string, unknown> | undefined;
      if (typeof transport["path"] !== "string" || !String(transport["path"]).startsWith("/")) {
        errors.push("VLESS WebSocket transport requires a valid path starting with '/'.");
      }
      if (typeof headers?.["Host"] !== "string" || !String(headers["Host"]).trim()) {
        errors.push("VLESS WebSocket transport requires an explicit CDN Host header.");
      }
    } else {
      if (proxy["flow"] !== "xtls-rprx-vision") errors.push("VLESS Reality requires xtls-rprx-vision flow.");
      const reality = tls?.["reality"] as Record<string, unknown> | undefined;
      if (!reality?.["enabled"]) errors.push("VLESS Reality outbound requires Reality TLS enabled.");
    }
  }

  return errors;
}
