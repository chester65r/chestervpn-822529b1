import { describe, expect, it } from "vitest";

import { PRIMARY_HETZNER_SERVER, SAMPLE_VPS_SERVERS, TRANSPORTS, TRANSPORT_IDS, rowToServer, validateServerInput } from "@/lib/servers";
import {
  SERVER_CONTAINER_FILES,
  SERVER_TRANSPORT_CAPABILITIES,
  getHetznerSafeReleaseCommands,
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
    const command = getRelayDeploymentCommand("multi-transport", "2.29.8.194");
    expect(command).toContain("PUBLIC_HOST=2.29.8.194 docker compose -f relay-server/docker-compose.yml up -d --build");
    expect(command).toContain("443/tcp");
    expect(command).toContain("1080/tcp");
    expect(SERVER_CONTAINER_FILES.map((f) => f.name)).toEqual([
      "Dockerfile",
      "docker-compose.yml",
      "entrypoint.sh",
      "gateway.mjs",
      "deploy-hetzner.sh",
    ]);
    expect(SERVER_TRANSPORT_CAPABILITIES.length).toBeGreaterThanOrEqual(6);
  });

  it("provides Hetzner CX23 (2.29.8.194) primary endpoints and safe release/rollback commands", () => {
    expect(PRIMARY_HETZNER_SERVER.host).toBe("2.29.8.194");
    expect(PRIMARY_HETZNER_SERVER.port).toBe(51820);
    expect(PRIMARY_HETZNER_SERVER.transport).toBe("wireguard");
    // Only the primary entry may claim the real server, and only as WireGuard
    // UDP 51820 — no sample may present an invented TLS/WebSocket listener on it.
    const realHostSamples = SAMPLE_VPS_SERVERS.filter((s) => s.host === "2.29.8.194");
    expect(realHostSamples).toEqual([PRIMARY_HETZNER_SERVER]);
    expect(SAMPLE_VPS_SERVERS.filter((s) => s.host !== "2.29.8.194").every((s) => s.host.endsWith(".example.com"))).toBe(true);
    const cmds = getHetznerSafeReleaseCommands("2.29.8.194");
    expect(cmds.bootstrapUser).toContain("deploy-hetzner.sh bootstrap-user");
    expect(cmds.deployRelease).toContain("PUBLIC_HOST=2.29.8.194 ./relay-server/deploy-hetzner.sh deploy 2.29.8.194");
    expect(cmds.rollbackRelease).toContain("./relay-server/deploy-hetzner.sh rollback");
  });
});

describe("transports", () => {
  it("offers WireGuard with its UDP port so the real server can be registered truthfully", () => {
    const wireguard = TRANSPORTS.find((t) => t.id === "wireguard");
    expect(wireguard?.label).toContain("WireGuard");
    expect(wireguard?.label).toContain("51820");
    expect(validateServerInput({ ...valid, transport: "wireguard", port: "51820" })).toEqual({});
  });

  it("keeps the canonical transport ids in sync with the form options", () => {
    expect(TRANSPORT_IDS).toEqual(TRANSPORTS.map((t) => t.id));
    expect(TRANSPORT_IDS).toContain("websocket");
    expect(TRANSPORT_IDS).toContain("tls");
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
