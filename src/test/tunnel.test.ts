import { describe, expect, it } from "vitest";
import {
  DEFAULT_PAYLOAD_TEMPLATE,
  ROUTE_PRESETS,
  exportProfile,
  importProfile,
  parsePayload,
  parseSavedProfiles,
  serializeSavedProfiles,
  validateProfile,
  type SavedProfile,
} from "@/lib/tunnel";

describe("route presets", () => {
  it("autofill the neutral example hosts and custom stays blank", () => {
    const sni = Object.fromEntries(ROUTE_PRESETS.map((p) => [p.id, p.sni]));
    expect(sni).toEqual({
      alpha: "alpha.example.com",
      beta: "beta.example.com",
      gamma: "gamma.example.com",
      custom: "",
    });
  });
});

describe("payload macros", () => {
  it("replaces [host] and [crlf] and terminates the headers", () => {
    expect(parsePayload("GET / HTTP/1.1[crlf]Host: [host][crlf]", "alpha.example.com")).toBe(
      "GET / HTTP/1.1\r\nHost: alpha.example.com\r\n\r\n",
    );
  });

  it("falls back to the default template when blank", () => {
    expect(parsePayload("  ", "h.example.com")).toBe(
      parsePayload(DEFAULT_PAYLOAD_TEMPLATE, "h.example.com"),
    );
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
    const p = { ip: "vpn.example.com", port: "8443", sni: "beta.example.com", payload: "X: é[crlf]" };
    const code = exportProfile(p);
    expect(code.startsWith("TUNNEL://")).toBe(true);
    expect(importProfile(code)).toEqual(p);
  });

  it("rejects malformed codes", () => {
    expect(importProfile("hello")).toBeNull();
    expect(importProfile("TUNNEL://!!!")).toBeNull();
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
