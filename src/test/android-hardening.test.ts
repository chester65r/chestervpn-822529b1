import { describe, expect, it } from "vitest";
import { BLUEPRINT_FILES } from "../lib/android-blueprint";
import { bytesPerSecToMbps } from "../lib/tunnel";

const file = (name: string) => {
  const found = BLUEPRINT_FILES.find((f) => f.name === name);
  if (!found) throw new Error(`missing ${name}`);
  return found.code;
};

describe("Android client fails closed (source assertions, not device evidence)", () => {
  it("never tears down the VPN automatically when the handshake is missing", () => {
    const activity = file("MainActivity.kt");
    expect(activity).not.toMatch(/waitingSince|20_000/);
    expect(activity).toContain("traffic blocked");
  });

  it("gates credential changes on the controller's confirmed-down engine state", () => {
    const activity = file("MainActivity.kt");
    expect(activity).not.toMatch(/private var started/);
    expect(activity).toContain("TunnelController.whileIdle({ enrollment.clear() })");
    const controller = file("TunnelController.kt");
    expect(controller).toContain("val stopped = tunnel.state == Tunnel.State.DOWN");
    expect(controller).not.toMatch(/catch \(_: Exception\) \{\}\s*\n\s*serverKey = null/);
  });

  it("rejects enrollment profiles that are not full-tunnel", () => {
    const controller = file("TunnelController.kt");
    expect(controller).toContain("it.address is Inet4Address");
    expect(controller).toContain("it.address is Inet6Address");
    expect(controller).toContain("mask == 32");
  });
});

describe("authorized native transport diagnostics", () => {
  it("supports bounded TLS, WSS, CONNECT and SOCKS5 protocol probes", () => {
    const probe = file("AuthorizedTransportProbe.kt");
    for (const protocol of ["TLS_TCP", "WS", "WSS", "HTTP_CONNECT", "HTTPS_CONNECT", "SOCKS5", "SOCKS5_TLS"]) {
      expect(probe).toContain(protocol);
    }
    expect(probe).toContain("MAX_HTTP_HEADER_BYTES = 16 * 1024");
    expect(probe).toContain("timeoutMs in 1_000..60_000");
    expect(probe).toContain("MessageDigest.getInstance(\"SHA-1\")");
  });

  it("retains platform TLS trust and hostname verification without a trust-all fallback", () => {
    const probe = file("AuthorizedTransportProbe.kt");
    expect(probe).toContain("endpointIdentificationAlgorithm = \"HTTPS\"");
    expect(probe).toContain("SNIHostName(request.tlsServerName)");
    expect(probe).toContain("TLS server name must match the authorized endpoint");
    expect(probe).not.toContain("TrustAll");
  });

  it("labels non-WireGuard protocols as diagnostics, not full-device VPN transports", () => {
    const activity = file("MainActivity.kt");
    const layout = file("activity_main.xml");
    expect(activity).toContain("AuthorizedTransportProbe.execute(request)");
    expect(activity).toContain("transportProbeInFlight");
    expect(layout).toContain("Authorized protocol diagnostics (not a full-device VPN)");
    expect(layout).toContain("@+id/btnProbeTransport");
  });
});

describe("server setup isolates and persists its firewall", () => {
  it("blocks tunnel clients from server-local services except DNS and loads rules on boot", () => {
    const setup = file("server-setup.sh");
    expect(setup).toMatch(/chain input[\s\S]*dport 53 accept[\s\S]*iifname "wg0" drop/);
    expect(setup).toContain("systemctl enable nftables.service");
    expect(setup).toContain("Requires=nftables.service");
  });
});

describe("speed units", () => {
  it("converts 600,000 bytes/second to 4.8 Mbps", () => {
    expect(bytesPerSecToMbps(600_000)).toBeCloseTo(4.8);
  });
});
