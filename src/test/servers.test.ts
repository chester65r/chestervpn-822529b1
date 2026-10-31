import { describe, expect, it } from "vitest";

import { rowToServer, validateServerInput } from "@/lib/servers";

const valid = {
  name: "Frankfurt relay",
  host: "relay.example.com",
  port: "8443",
  transport: "websocket",
  notes: "",
};

describe("validateServerInput", () => {
  it("accepts a valid server", () => {
    expect(validateServerInput(valid)).toEqual({});
  });

  it("requires a name", () => {
    expect(validateServerInput({ ...valid, name: "   " }).name).toBeTruthy();
  });

  it("requires a host", () => {
    expect(validateServerInput({ ...valid, host: "" }).host).toBeTruthy();
  });

  it("rejects an invalid host", () => {
    expect(validateServerInput({ ...valid, host: "not a host!!" }).host).toBeTruthy();
  });

  it("accepts an IPv4 host", () => {
    expect(validateServerInput({ ...valid, host: "203.0.113.10" })).toEqual({});
  });

  it("rejects out-of-range and non-numeric ports", () => {
    expect(validateServerInput({ ...valid, port: "0" }).port).toBeTruthy();
    expect(validateServerInput({ ...valid, port: "70000" }).port).toBeTruthy();
    expect(validateServerInput({ ...valid, port: "abc" }).port).toBeTruthy();
  });
});

describe("rowToServer", () => {
  it("maps a database row to a RelayServer", () => {
    const server = rowToServer({
      id: "abc",
      name: "n",
      host: "relay.example.com",
      port: 443,
      transport: "websocket",
      notes: "",
      created_at: "2026-10-31T00:00:00Z",
    });
    expect(server).toMatchObject({ id: "abc", host: "relay.example.com", port: 443 });
    expect(server.createdAt).toBe(Date.parse("2026-10-31T00:00:00Z"));
  });
});
