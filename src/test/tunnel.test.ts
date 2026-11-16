import { describe, expect, it } from "vitest";
import {
  DEFAULT_PAYLOAD_TEMPLATE,
  HETZNER_DEFAULT_PROFILE,
  HETZNER_SERVER_SPEC,
  NETWORK_PROVIDER_PROFILES,
  ROUTE_PRESETS,
  exportProfile,
  getNetworkProviderProfile,
  importProfile,
  parsePayload,
  parseActiveProfile,
  parseSavedProfiles,
  resolveInitialActiveProfile,
  serializeSavedProfiles,
  validateProfile,
  type SavedProfile,
} from "@/lib/tunnel";
import { validateSingBoxClientConfig } from "@/lib/singbox";

describe("route presets", () => {
  it("provides STC (VLESS+Reality) and Mobily (VLESS+WSS CDN) carrier presets and keeps custom blank", () => {
    const sni = Object.fromEntries(ROUTE_PRESETS.map((p) => [p.id, p.sni]));
    expect(sni).toEqual({
      stc: "www.stc.com.sa",
      mobily: "www.mobily.com.sa",
      alpha: "alpha.example.com",
      beta: "beta.example.com",
      gamma: "gamma.example.com",
      custom: "",
    });
  });
});

describe("Hetzner CX23 (2.29.8.194 / ahmed-docker-manager #166592617) infrastructure & telecom profiles", () => {
  it("defaults initial active profile to Hetzner CX23 2.29.8.194:443 with STC VLESS+Reality", () => {
    const resolved = resolveInitialActiveProfile(null);
    expect(resolved.profile).toEqual(HETZNER_DEFAULT_PROFILE);
    expect(resolved.profile.ip).toBe("2.29.8.194");
    expect(resolved.profile.port).toBe("443");
    expect(resolved.profile.sni).toBe("www.stc.com.sa");
    expect(resolved.profile.protocol).toBe("vless-reality");
    expect(validateProfile(resolved.profile)).toEqual({});
  });

  it("provides validated sing-box outbound JSON configurations for STC and Mobily carrier profiles targeting 2.29.8.194", () => {
    expect(HETZNER_SERVER_SPEC.serverName).toBe("ahmed-docker-manager");
    expect(HETZNER_SERVER_SPEC.serverId).toBe("166592617");
    expect(HETZNER_SERVER_SPEC.projectId).toBe("16098183");
    expect(HETZNER_SERVER_SPEC.ip).toBe("2.29.8.194");
    expect(HETZNER_SERVER_SPEC.ipv6Subnet).toBe("2a01:4f9:c015:48b3::/64");
    expect(HETZNER_SERVER_SPEC.location).toBe("Helsinki, Finland");
    expect(HETZNER_SERVER_SPEC.networkZone).toBe("eu-central");
    expect(NETWORK_PROVIDER_PROFILES.length).toBeGreaterThanOrEqual(4);
    for (const netProfile of NETWORK_PROVIDER_PROFILES) {
      expect(netProfile.profile.ip).toBe("2.29.8.194");
      expect(validateProfile(netProfile.profile)).toEqual({});
      const parsed = parsePayload(netProfile.profile.payload, netProfile.profile.sni);
      const parsedJson = JSON.parse(parsed) as Record<string, unknown>;
      expect(validateSingBoxClientConfig(parsedJson)).toEqual([]);
      expect(parsed).toContain(`"server_name": "${netProfile.profile.sni}"`);
    }
    const stc = getNetworkProviderProfile("stc-ksa-5g-cgnat");
    expect(stc?.recommendedMtu).toBe(1280);
    expect(stc?.profile.protocol).toBe("vless-reality");
    expect(stc?.profile.sni).toBe("www.stc.com.sa");
    expect(stc?.profile.payload).toContain('"xtls-rprx-vision"');
    expect(stc?.profile.payload).toContain('"record_fragment": true');

    const mobily = getNetworkProviderProfile("mobily-ksa-lte-bypass");
    expect(mobily?.recommendedMtu).toBe(1280);
    expect(mobily?.profile.protocol).toBe("vless-ws-tls");
    expect(mobily?.profile.sni).toBe("www.mobily.com.sa");
    expect(mobily?.profile.cdnHost).toBe("cdn.mobily.com.sa");
    expect(mobily?.profile.wsPath).toBe("/cdn-ws");
    expect(mobily?.profile.payload).toContain('"type": "ws"');
    expect(mobily?.profile.payload).toContain('"Host": "cdn.mobily.com.sa"');
    expect(mobily?.profile.payload).toContain('"fingerprint": "chrome"');
  });
});

describe("sing-box outbound payload generation", () => {
  it("normalizes and updates server_name in a sing-box JSON configuration", () => {
    const updated = parsePayload(DEFAULT_PAYLOAD_TEMPLATE, "portal.stc.com.sa");
    const parsed = JSON.parse(updated) as Record<string, unknown>;
    expect(validateSingBoxClientConfig(parsed)).toEqual([]);
    expect(updated).toContain('"server_name": "portal.stc.com.sa"');
  });

  it("falls back to a validated sing-box JSON config when blank", () => {
    const out = parsePayload("  ", "www.stc.com.sa");
    expect(validateSingBoxClientConfig(JSON.parse(out) as Record<string, unknown>)).toEqual([]);
  });
});

describe("validation", () => {
  const ok = { ip: "203.0.113.10", port: "443", sni: "alpha.example.com", payload: "" };

  it("accepts a valid profile", () => {
    expect(validateProfile(ok)).toEqual({});
  });

  it("rejects ports outside 1-65535", () => {
    expect(validateProfile({ ...ok, port: "0" }).port).toBeDefined();
    expect(validateProfile({ ...ok, port: "65536" }).port).toBeDefined();
    expect(validateProfile({ ...ok, port: "65535" }).port).toBeUndefined();
  });

  it("requires server and SNI", () => {
    const e = validateProfile({ ...ok, ip: "", sni: "" });
    expect(e.ip).toBeDefined();
    expect(e.sni).toBeDefined();
  });
});

describe("TUNNEL:// codes", () => {
  it("round-trips a profile including non-ASCII payload text", () => {
    const p = { ip: "vpn.example.com", port: "8443", sni: "beta.example.com", payload: '{"note":"é"}' };
    const code = exportProfile(p);
    expect(code.startsWith("TUNNEL://")).toBe(true);
    expect(importProfile(code)).toEqual(p);
  });

  it("rejects malformed codes", () => {
    expect(importProfile("hello")).toBeNull();
    expect(importProfile("TUNNEL://!!!")).toBeNull();
  });

  it("rejects imported ports outside 1-65535", () => {
    const p = { ip: "vpn.example.com", port: "0", sni: "alpha.example.com", payload: "" };
    expect(importProfile(exportProfile(p))).toBeNull();
    expect(importProfile(exportProfile({ ...p, port: "65536" }))).toBeNull();
  });

  it("rejects imported profiles without valid server and SNI hosts", () => {
    const p = { ip: "", port: "443", sni: "alpha.example.com", payload: "" };
    expect(importProfile(exportProfile(p))).toBeNull();
    expect(importProfile(exportProfile({ ...p, ip: "vpn.example.com", sni: "https://alpha.example.com" }))).toBeNull();
  });
});

describe("saved profiles storage", () => {
  const entry: SavedProfile = {
    id: "p1",
    name: "Office relay",
    presetId: "alpha",
    profile: { ip: "203.0.113.10", port: "443", sni: "alpha.example.com", payload: "" },
    savedAt: 1761897600000,
  };

  it("round-trips a list of saved profiles", () => {
    expect(parseSavedProfiles(serializeSavedProfiles([entry]))).toEqual([entry]);
  });

  it("returns an empty list for missing or corrupt storage", () => {
    expect(parseSavedProfiles(null)).toEqual([]);
    expect(parseSavedProfiles("not json")).toEqual([]);
    expect(parseSavedProfiles("{}")).toEqual([]);
  });

  it("drops malformed entries but keeps valid ones", () => {
    const raw = JSON.stringify([entry, { id: 42, name: "broken" }, null]);
    expect(parseSavedProfiles(raw)).toEqual([entry]);
  });
});

describe("active profile recovery", () => {
  it("restores a draft and its matching neutral preset", () => {
    const profile = { ip: "vpn.example.com", port: "443", sni: "alpha.example.com", payload: "" };
    expect(parseActiveProfile(JSON.stringify({ profile, presetId: "alpha" }))).toEqual({ profile, presetId: "alpha" });
  });

  it("replaces malformed drafts instead of passing non-string fields to the UI", () => {
    expect(parseActiveProfile(JSON.stringify({ profile: { ip: 42, port: "443", sni: null, payload: [] } }))).toEqual({
      profile: { ip: "", port: "443", sni: "", payload: "" }, presetId: "custom",
    });
    expect(parseActiveProfile("bad json").presetId).toBe("custom");
  });
});
