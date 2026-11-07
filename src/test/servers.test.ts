import { describe, expect, it } from "vitest";

import { rowToServer, validateServerInput } from "@/lib/servers";
import {
  SERVER_CONTAINER_FILES,
  SERVER_TRANSPORT_CAPABILITIES,
  getRelayDeploymentCommand,
} from "@/lib/server-deployment";

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

describe("deployment commands", () => {
  it("creates a guarded Ubuntu bootstrap command", () => {
    const command = getRelayDeploymentCommand("ubuntu", "vpn.example.com");
    expect(command).toContain("sudo ufw allow 51820/udp");
    expect(command).toContain("WG_HOST=vpn.example.com");
    expect(command).toContain("--restart unless-stopped");
  });

  it("creates a Docker command without leaking credentials", () => {
    const command = getRelayDeploymentCommand("docker", "203.0.113.10");
    expect(command).toContain("-p 51820:51820/udp");
    expect(command).not.toContain("PRIVATE_KEY");
    expect(getRelayDeploymentCommand("docker", "")).toBe("");
  });

  it("creates an all-in-one multi-transport container command and ships container files", () => {
    const command = getRelayDeploymentCommand("multi-transport", "vpn.example.com");
    expect(command).toContain("PUBLIC_HOST=vpn.example.com docker compose -f relay-server/docker-compose.yml up -d --build");
    expect(command).toContain("443/tcp");
    expect(command).toContain("1080/tcp");
    expect(SERVER_CONTAINER_FILES.map((f) => f.name)).toEqual([
      "Dockerfile",
      "docker-compose.yml",
      "entrypoint.sh",
      "gateway.mjs",
    ]);
    expect(SERVER_TRANSPORT_CAPABILITIES.length).toBeGreaterThanOrEqual(6);
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
