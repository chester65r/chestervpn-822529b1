// Generates disposable keys and wg-quick fixtures with the app's shared WireGuard generator.
// Keys are created per run in a temporary directory and are never committed or reused.
import { generateKeyPairSync, randomBytes } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { clientConfig, serverConfig, validateClient, validateServer, type WireGuardClient } from "../../src/lib/wireguard";

const out = process.argv[2];
if (!out) throw new Error("Usage: generate-fixtures.ts <directory>");
mkdirSync(out, { recursive: true, mode: 0o700 });

function keyPair() {
  const { publicKey, privateKey } = generateKeyPairSync("x25519");
  const raw = (key: typeof publicKey, type: "spki" | "pkcs8") => key.export({ format: "der", type }).subarray(-32).toString("base64");
  return { privateKey: raw(privateKey, "pkcs8"), publicKey: raw(publicKey, "spki") };
}
const psk = () => randomBytes(32).toString("base64");

const server = keyPair();
const client = keyPair();
const intruder = keyPair();
const clientPsk = psk();
const rotatedPsk = psk();

const serverFile = { privateKey: server.privateKey, address: "10.66.66.1/24", listenPort: 51820, mtu: 1280, peers: [{ name: "android-device-1", publicKey: client.publicKey, presharedKey: clientPsk, address: "10.66.66.2/32" }] };
const authorized: WireGuardClient = {
  privateKey: client.privateKey, address: "10.66.66.2/32", dns: "10.66.66.1", mtu: 1280,
  serverPublicKey: server.publicKey, presharedKey: clientPsk, endpointHost: "127.0.0.1", endpointPort: 51820,
  allowedIps: ["0.0.0.0/0", "::/0"], keepalive: 25,
};
const variants: Record<string, WireGuardClient> = {
  "client.conf": authorized,
  "intruder.conf": { ...authorized, privateKey: intruder.privateKey },
  "wrong-psk.conf": { ...authorized, presharedKey: psk() },
  "wrong-server.conf": { ...authorized, serverPublicKey: intruder.publicKey },
  "rotated.conf": { ...authorized, presharedKey: rotatedPsk },
  "spoof.conf": { ...authorized, address: "10.66.66.3/32" },
};

const serverErrors = validateServer(serverFile);
if (serverErrors.length) throw new Error(serverErrors.join("; "));
writeFileSync(join(out, "server.conf"), serverConfig(serverFile), { mode: 0o600 });
writeFileSync(join(out, "rotated-server.conf"), serverConfig({ ...serverFile, peers: serverFile.peers.map(peer => ({ ...peer, presharedKey: rotatedPsk })) }), { mode: 0o600 });
for (const [name, config] of Object.entries(variants)) {
  const errors = validateClient(config);
  if (errors.length) throw new Error(`${name}: ${errors.join("; ")}`);
  writeFileSync(join(out, name), clientConfig(config), { mode: 0o600 });
}
console.log(`Wrote ${Object.keys(variants).length + 1} disposable fixtures`);
