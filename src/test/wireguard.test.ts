// @vitest-environment node
import { describe, expect, it } from "vitest";
import { generateKeyPairSync, randomBytes } from "node:crypto";
import { clientConfig, deriveWireGuardPublicKey, generateWireGuardKeyPair, isIpv6Cidr, isWireGuardKey, serverConfig, validateClient, validateServer, type WireGuardClient } from "../lib/wireguard";
import { BLUEPRINT_FILES } from "../lib/android-blueprint";

const key = () => generateKeyPairSync("x25519").publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
const psk = () => randomBytes(32).toString("base64");
const base = (): WireGuardClient => ({
  privateKey: key(), address: "10.66.66.2/32", dns: "10.66.66.1", mtu: 1280, serverPublicKey: key(), presharedKey: psk(),
  endpointHost: "vpn.example.com", endpointPort: 51820, allowedIps: ["0.0.0.0/0", "::/0"], keepalive: 25,
});

describe("WireGuard profile rules", () => {
  it("accepts only canonical 32-byte base64 keys", () => {
    expect(isWireGuardKey(key())).toBe(true);
    expect(isWireGuardKey(randomBytes(31).toString("base64"))).toBe(false);
    expect(isWireGuardKey("<SERVER_PUBLIC_KEY>")).toBe(false);
    // Same length but non-zero trailing bits is not a canonical encoding.
    expect(isWireGuardKey(key().slice(0, 42) + "B=")).toBe(false);
  });

  it("accepts a complete full-tunnel profile", () => {
    expect(validateClient(base())).toEqual([]);
  });

  it("requires a preshared key, a single /32 client address and MTU 1280-1420", () => {
    expect(validateClient({ ...base(), presharedKey: "" })).toContain("Preshared key must be a 32-byte base64 key");
    expect(validateClient({ ...base(), address: "10.66.66.0/24" })).toContain("Client address must be a single IPv4 /32");
    expect(validateClient({ ...base(), mtu: 1279 })).toContain("MTU must be between 1280 and 1420");
    expect(validateClient({ ...base(), mtu: 1421 })).toContain("MTU must be between 1280 and 1420");
    expect(validateClient({ ...base(), endpointPort: 0 })).toContain("Endpoint port must be 1-65535");
  });

  it("validates IPv6 routes used to block IPv6 leaks", () => {
    expect(isIpv6Cidr("::/0")).toBe(true);
    expect(isIpv6Cidr("fd00::/8")).toBe(true);
    expect(isIpv6Cidr("::/129")).toBe(false);
    expect(isIpv6Cidr("1::2::3/64")).toBe(false);
  });

  it("rejects servers whose peers share keys or tunnel addresses", () => {
    const shared = key();
    const errors = validateServer({
      privateKey: key(), address: "10.66.66.1/24", listenPort: 51820, mtu: 1280,
      peers: [
        { name: "a", publicKey: shared, presharedKey: psk(), address: "10.66.66.2/32" },
        { name: "b", publicKey: shared, presharedKey: psk(), address: "10.66.66.2/32" },
      ],
    });
    expect(errors).toContain("Peer b reuses another peer's key");
    expect(errors).toContain("Peer b reuses another peer's address");
  });

  it("writes the client keys and server address into matching wg-quick files", () => {
    const client = base();
    const text = clientConfig(client);
    expect(text).toContain(`PublicKey = ${client.serverPublicKey}`);
    expect(text).toContain("Endpoint = vpn.example.com:51820");
    expect(text).toContain("AllowedIPs = 0.0.0.0/0, ::/0");
    const server = serverConfig({ privateKey: key(), address: "10.66.66.1/24", listenPort: 51820, mtu: 1280, peers: [{ name: "x\nPostUp = rm", publicKey: key(), presharedKey: client.presharedKey, address: client.address }] });
    expect(server).toContain(`AllowedIPs = ${client.address}`);
    expect(server).not.toMatch(/^PostUp/m);
  });
});

describe("WireGuard public key derivation (X25519, RFC 7748)", () => {
  const fromHex = (hex: string) => Buffer.from(hex, "hex").toString("base64");

  it("matches the RFC 7748 §6.1 base-point vector", () => {
    // Alice's private key and public key from the RFC's Diffie-Hellman example.
    const alicePrivate = fromHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    expect(deriveWireGuardPublicKey(alicePrivate)).toBe(
      fromHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"),
    );
  });

  it("agrees with Node's native X25519 keypairs", () => {
    for (let i = 0; i < 5; i++) {
      const pair = generateKeyPairSync("x25519");
      const privateRaw = pair.privateKey.export({ format: "der", type: "pkcs8" }).subarray(-32);
      const publicRaw = pair.publicKey.export({ format: "der", type: "spki" }).subarray(-32);
      expect(deriveWireGuardPublicKey(Buffer.from(privateRaw).toString("base64"))).toBe(
        Buffer.from(publicRaw).toString("base64"),
      );
    }
  });

  it("rejects values that are not canonical WireGuard keys", () => {
    expect(() => deriveWireGuardPublicKey("not-a-key")).toThrow();
    expect(() => deriveWireGuardPublicKey(randomBytes(31).toString("base64"))).toThrow();
  });

  it("generates browser keypairs whose public key is really derived from the private key", () => {
    const pair = generateWireGuardKeyPair();
    expect(isWireGuardKey(pair.privateKey)).toBe(true);
    expect(isWireGuardKey(pair.publicKey)).toBe(true);
    expect(deriveWireGuardPublicKey(pair.privateKey)).toBe(pair.publicKey);
    expect(generateWireGuardKeyPair().privateKey).not.toBe(pair.privateKey);
  });

  it("builds a client/server pair that validates with cross-matched public keys", () => {
    const device = generateWireGuardKeyPair();
    const server = generateWireGuardKeyPair();
    const psk = randomBytes(32).toString("base64");
    expect(
      validateClient({
        privateKey: device.privateKey, address: "10.66.66.4/32", dns: "1.1.1.1", mtu: 1380,
        serverPublicKey: server.publicKey, presharedKey: psk,
        endpointHost: "2.29.8.194", endpointPort: 51820, allowedIps: ["0.0.0.0/0", "::/0"], keepalive: 25,
      }),
    ).toEqual([]);
    expect(
      validateServer({
        privateKey: server.privateKey, address: "10.66.66.1/24", listenPort: 51820, mtu: 1380,
        peers: [{ name: "device", publicKey: device.publicKey, presharedKey: psk, address: "10.66.66.4/32" }],
      }),
    ).toEqual([]);
  });
});

describe("Android and server blueprint use one packet protocol", () => {
  const file = (name: string) => BLUEPRINT_FILES.find((f) => f.name === name)?.code ?? "";

  it("embeds the WireGuard packet engine instead of the retired TLS/WebSocket path", () => {
    expect(file("build.gradle.kts")).toContain('implementation("com.wireguard.android:tunnel:1.0.20260102")');
    expect(BLUEPRINT_FILES.map((f) => f.name)).not.toContain("relay-server.mjs");
    expect(BLUEPRINT_FILES.map((f) => f.name)).not.toContain("AdvancedVpnService.kt");
  });

  it("reports CONNECTED only after a handshake with the configured server key", () => {
    expect(file("TunnelController.kt")).toContain("peer(key)");
    expect(file("TunnelController.kt")).toContain("latestHandshakeEpochMillis() == 0L");
  });

  it("ships client and server templates whose addresses and port agree", () => {
    expect(file("client.conf")).toContain("Address = 10.66.66.2/32");
    expect(file("wg0.conf")).toContain("AllowedIPs = 10.66.66.2/32");
    expect(file("client.conf")).toContain(":51820");
    expect(file("wg0.conf")).toContain("ListenPort = 51820");
  });
});
