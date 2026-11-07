import { describe, expect, it } from "vitest";
import {
  DEFAULT_TRANSPORT_PROFILE,
  TRANSPORT_OPTIONS,
  getTransportOption,
  parseTransportProfile,
  serializeTransportProfile,
  validateTransportProfile,
  type TransportProfile,
} from "@/lib/transport-adapters";

const tlsTcp: TransportProfile = {
  ...DEFAULT_TRANSPORT_PROFILE,
  protocol: "tls-tcp",
  endpointHost: "vpn.example.com",
  endpointPort: "443",
  tlsServerName: "vpn.example.com",
};

describe("authorized transport adapter profiles", () => {
  it("keeps WireGuard as the only full-device VPN transport", () => {
    const fullDevice = TRANSPORT_OPTIONS.filter((option) => option.fullDeviceVpn);
    expect(fullDevice.map((option) => option.id)).toEqual(["wireguard"]);
    expect(getTransportOption("wireguard").kind).toBe("vpn");
  });

  it("offers TLS/TCP, WSS, HTTPS CONNECT and SOCKS5 variants", () => {
    const ids = TRANSPORT_OPTIONS.map((option) => option.id);
    expect(ids).toEqual([
      "wireguard",
      "tls-tcp",
      "websocket",
      "websocket-tls",
      "http-connect",
      "https-connect",
      "socks5",
      "socks5-tls",
    ]);
    expect(getTransportOption("websocket-tls").tls).toBe(true);
    expect(getTransportOption("https-connect").tls).toBe(true);
  });

  it("accepts a TLS profile only when the server name matches the endpoint", () => {
    expect(validateTransportProfile(tlsTcp)).toEqual({});
    expect(validateTransportProfile({ ...tlsTcp, tlsServerName: "other.example.com" }).tlsServerName)
      .toMatch(/match the endpoint/);
  });

  it("requires TLS profiles to use a DNS hostname", () => {
    const errors = validateTransportProfile({
      ...tlsTcp,
      endpointHost: "203.0.113.10",
      tlsServerName: "203.0.113.10",
    });
    expect(errors.tlsServerName).toMatch(/DNS hostname/);
  });

  it("rejects malformed hosts, ports and WebSocket paths", () => {
    expect(validateTransportProfile({ ...tlsTcp, endpointHost: "https://vpn.example.com" }).endpointHost)
      .toBeDefined();
    expect(validateTransportProfile({ ...tlsTcp, endpointPort: "65536" }).endpointPort)
      .toBeDefined();
    expect(validateTransportProfile({
      ...tlsTcp,
      protocol: "websocket-tls",
      websocketPath: "/bad\r\nHost: evil.example",
    }).websocketPath).toBeDefined();
  });

  it("requires proxy destination and valid target port", () => {
    const profile: TransportProfile = {
      ...DEFAULT_TRANSPORT_PROFILE,
      protocol: "https-connect",
      endpointHost: "proxy.example.com",
      endpointPort: "443",
      tlsServerName: "proxy.example.com",
      destinationHost: "service.example.com",
      destinationPort: "8443",
    };
    expect(validateTransportProfile(profile)).toEqual({});
    expect(validateTransportProfile({ ...profile, destinationPort: "0" }).destinationPort)
      .toBeDefined();
  });

  it("keeps WebSocket paths bounded and origin-form", () => {
    const profile: TransportProfile = {
      ...tlsTcp,
      protocol: "websocket-tls",
      websocketPath: "/tunnel",
    };
    expect(validateTransportProfile(profile)).toEqual({});
    expect(validateTransportProfile({ ...profile, websocketPath: "https://evil.example/" }).websocketPath)
      .toBeDefined();
  });

  it("round-trips an adapter without credentials", () => {
    expect(parseTransportProfile(serializeTransportProfile(tlsTcp))).toEqual(tlsTcp);
  });

  it("returns safe defaults for corrupt, unknown or oversized storage values", () => {
    expect(parseTransportProfile("not json")).toEqual(DEFAULT_TRANSPORT_PROFILE);
    expect(parseTransportProfile(JSON.stringify({ version: 2 })).protocol).toBe("wireguard");
    const oversized = {
      version: 1,
      protocol: "tls-tcp",
      endpointHost: "x".repeat(500),
      endpointPort: "999999",
      tlsServerName: "",
      websocketPath: "/",
      destinationHost: "",
      destinationPort: "443",
    };
    const restored = parseTransportProfile(JSON.stringify(oversized));
    expect(restored.endpointHost).toHaveLength(253);
    expect(restored.endpointPort).toHaveLength(5);
  });
});
