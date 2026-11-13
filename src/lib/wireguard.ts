// Shared WireGuard configuration rules for the Android client and Linux server blueprints.
// Pure functions only: the browser never opens a tunnel and never needs real keys.

export type WireGuardClient = {
  privateKey: string;
  address: string; // e.g. 10.66.66.2/32
  dns: string; // resolver reachable inside the tunnel, e.g. 10.66.66.1
  mtu: number;
  serverPublicKey: string;
  presharedKey: string;
  endpointHost: string;
  endpointPort: number;
  allowedIps: string[];
  keepalive: number;
};

export type WireGuardServerPeer = { name: string; publicKey: string; presharedKey: string; address: string };

export type WireGuardServer = {
  privateKey: string;
  address: string; // e.g. 10.66.66.1/24
  listenPort: number;
  mtu: number;
  peers: WireGuardServerPeer[];
};

const IPV4_CIDR = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{1,2})$/;
const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;
const HOST = /^(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;

function base64ToBytes(value: string): Uint8Array {
  const bin = atob(value);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

function bytesToBase64(bytes: Uint8Array): string {
  let bin = "";
  bytes.forEach((b) => {
    bin += String.fromCharCode(b);
  });
  return btoa(bin);
}

// --- X25519 (RFC 7748) -------------------------------------------------------
// WireGuard public keys are X25519(privateKey, base point 9). Browsers cannot
// derive them natively, and a "public key" that is just another random value
// produces configs that can never handshake — so derivation is implemented
// here as a pure function over BigInt and tested against RFC vectors.

const X25519_P = (1n << 255n) - 19n;
const X25519_A24 = 121665n;

function x25519Mod(value: bigint): bigint {
  return ((value % X25519_P) + X25519_P) % X25519_P;
}

function x25519Pow(base: bigint, exponent: bigint): bigint {
  let result = 1n;
  let b = x25519Mod(base);
  let e = exponent;
  while (e > 0n) {
    if (e & 1n) result = x25519Mod(result * b);
    b = x25519Mod(b * b);
    e >>= 1n;
  }
  return result;
}

function bytesToBigIntLE(bytes: Uint8Array): bigint {
  let out = 0n;
  for (let i = bytes.length - 1; i >= 0; i--) out = (out << 8n) | BigInt(bytes[i] ?? 0);
  return out;
}

function bigIntToBytesLE(value: bigint, length = 32): Uint8Array {
  const out = new Uint8Array(length);
  let v = value;
  for (let i = 0; i < length; i++) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}

/** Derives the WireGuard public key for a 32-byte base64 private key (RFC 7748 §5). */
export function deriveWireGuardPublicKey(privateKey: string): string {
  if (!isWireGuardKey(privateKey)) throw new Error("Private key must be a 32-byte base64 WireGuard key");
  const scalarBytes = base64ToBytes(privateKey);
  // Clamp per RFC 7748 decodeScalar25519.
  scalarBytes[0] = (scalarBytes[0] ?? 0) & 248;
  scalarBytes[31] = ((scalarBytes[31] ?? 0) & 127) | 64;
  const scalar = bytesToBigIntLE(scalarBytes);

  const x1 = 9n;
  let x2 = 1n;
  let z2 = 0n;
  let x3 = x1;
  let z3 = 1n;
  let swap = 0n;
  const cswap = (a: bigint, b: bigint, bit: bigint): [bigint, bigint] => (bit === 1n ? [b, a] : [a, b]);

  for (let t = 254; t >= 0; t--) {
    const bit = (scalar >> BigInt(t)) & 1n;
    swap ^= bit;
    [x2, x3] = cswap(x2, x3, swap);
    [z2, z3] = cswap(z2, z3, swap);
    swap = bit;

    const a = x25519Mod(x2 + z2);
    const aa = x25519Mod(a * a);
    const b = x25519Mod(x2 - z2);
    const bb = x25519Mod(b * b);
    const e = x25519Mod(aa - bb);
    const c = x25519Mod(x3 + z3);
    const d = x25519Mod(x3 - z3);
    const da = x25519Mod(d * a);
    const cb = x25519Mod(c * b);
    const daPlusCb = x25519Mod(da + cb);
    const daMinusCb = x25519Mod(da - cb);
    x3 = x25519Mod(daPlusCb * daPlusCb);
    z3 = x25519Mod(x1 * x25519Mod(daMinusCb * daMinusCb));
    x2 = x25519Mod(aa * bb);
    z2 = x25519Mod(e * x25519Mod(aa + X25519_A24 * e));
  }
  [x2, x3] = cswap(x2, x3, swap);
  [z2, z3] = cswap(z2, z3, swap);

  const result = x25519Mod(x2 * x25519Pow(z2, X25519_P - 2n));
  return bytesToBase64(bigIntToBytesLE(result));
}

/**
 * Generates a real WireGuard keypair in the browser: a clamped random private
 * key from WebCrypto and its X25519-derived public key. The pair actually
 * matches — unlike picking two independent random strings.
 */
export function generateWireGuardKeyPair(): { privateKey: string; publicKey: string } {
  const bytes = new Uint8Array(32);
  const cryptoObj = globalThis.crypto;
  if (!cryptoObj || typeof cryptoObj.getRandomValues !== "function") {
    throw new Error("Secure random generation is unavailable in this environment");
  }
  cryptoObj.getRandomValues(bytes);
  bytes[0] = (bytes[0] ?? 0) & 248;
  bytes[31] = ((bytes[31] ?? 0) & 127) | 64;
  const privateKey = bytesToBase64(bytes);
  return { privateKey, publicKey: deriveWireGuardPublicKey(privateKey) };
}

/** A WireGuard key is exactly 32 bytes encoded as canonical padded base64 (44 characters). */
export function isWireGuardKey(value: string): boolean {
  if (!/^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$/.test(value)) return false;
  try {
    return atob(value).length === 32;
  } catch {
    return false;
  }
}

function octetsOk(match: RegExpMatchArray | null) {
  return !!match && match.slice(1, 5).every((part) => Number(part) <= 255 && String(Number(part)) === part);
}
export function isIpv4Cidr(value: string): boolean {
  const match = value.match(IPV4_CIDR);
  return octetsOk(match) && Number(match?.[5]) <= 32;
}
export function isIpv6Cidr(value: string): boolean {
  const match = value.match(/^([0-9a-fA-F:]+)\/(\d{1,3})$/);
  if (!match || Number(match[2]) > 128) return false;
  const address = match[1] ?? "";
  const halves = address.split("::");
  if (halves.length > 2) return false;
  const groups = halves.flatMap((half) => (half ? half.split(":") : []));
  if (!groups.every((group) => /^[0-9a-fA-F]{1,4}$/.test(group))) return false;
  return halves.length === 2 ? groups.length < 8 : groups.length === 8;
}
export function isIpv4(value: string): boolean {
  return octetsOk(value.match(IPV4));
}
export function isEndpointHost(value: string): boolean {
  return isIpv4(value) || (HOST.test(value) && !/^\d+(\.\d+)*$/.test(value));
}

/** Checks whether a peer or client keypair exceeds the recommended rotation window (default 90 days). */
export function needsKeyRotation(createdAtEpochMs: number, maxAgeDays = 90): boolean {
  const ageMs = Date.now() - createdAtEpochMs;
  const maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000;
  return ageMs > maxAgeMs;
}
const portOk = (port: number) => Number.isInteger(port) && port >= 1 && port <= 65535;
// 1280 is the IPv6 minimum; 1420 is the WireGuard default for a 1500-byte underlay.
const mtuOk = (mtu: number) => Number.isInteger(mtu) && mtu >= 1280 && mtu <= 1420;

export function validateClient(client: WireGuardClient): string[] {
  const errors: string[] = [];
  if (!isWireGuardKey(client.privateKey)) errors.push("Client private key must be a 32-byte base64 key");
  if (!isWireGuardKey(client.serverPublicKey)) errors.push("Server public key must be a 32-byte base64 key");
  if (!isWireGuardKey(client.presharedKey)) errors.push("Preshared key must be a 32-byte base64 key");
  if (client.privateKey === client.serverPublicKey) errors.push("Client and server keys must differ");
  if (!isIpv4Cidr(client.address) || !client.address.endsWith("/32")) errors.push("Client address must be a single IPv4 /32");
  if (!isIpv4(client.dns)) errors.push("DNS must be an IPv4 resolver reachable through the tunnel");
  if (!mtuOk(client.mtu)) errors.push("MTU must be between 1280 and 1420");
  if (!isEndpointHost(client.endpointHost)) errors.push("Endpoint must be a hostname or IPv4 address");
  if (!portOk(client.endpointPort)) errors.push("Endpoint port must be 1-65535");
  if (!client.allowedIps.length || !client.allowedIps.every((cidr) => isIpv4Cidr(cidr) || isIpv6Cidr(cidr))) errors.push("Allowed IPs must be CIDR ranges");
  if (!Number.isInteger(client.keepalive) || client.keepalive < 0 || client.keepalive > 65535) errors.push("Keepalive must be 0-65535 seconds");
  return errors;
}

export function validateServer(server: WireGuardServer): string[] {
  const errors: string[] = [];
  if (!isWireGuardKey(server.privateKey)) errors.push("Server private key must be a 32-byte base64 key");
  if (!isIpv4Cidr(server.address)) errors.push("Server address must be an IPv4 CIDR");
  if (!portOk(server.listenPort)) errors.push("Listen port must be 1-65535");
  if (!mtuOk(server.mtu)) errors.push("MTU must be between 1280 and 1420");
  if (!server.peers.length) errors.push("At least one client peer is required");
  const keys = new Set<string>();
  const addresses = new Set<string>();
  for (const peer of server.peers) {
    if (!isWireGuardKey(peer.publicKey) || !isWireGuardKey(peer.presharedKey)) errors.push(`Peer ${peer.name} needs valid public and preshared keys`);
    if (!isIpv4Cidr(peer.address) || !peer.address.endsWith("/32")) errors.push(`Peer ${peer.name} must own exactly one /32 address`);
    if (keys.has(peer.publicKey)) errors.push(`Peer ${peer.name} reuses another peer's key`);
    if (addresses.has(peer.address)) errors.push(`Peer ${peer.name} reuses another peer's address`);
    keys.add(peer.publicKey);
    addresses.add(peer.address);
  }
  return errors;
}

/** wg-quick client file, parsed on Android by com.wireguard.config.Config.parse. */
export function clientConfig(client: WireGuardClient): string {
  return [
    "[Interface]",
    `PrivateKey = ${client.privateKey}`,
    `Address = ${client.address}`,
    `DNS = ${client.dns}`,
    `MTU = ${client.mtu}`,
    "",
    "[Peer]",
    `PublicKey = ${client.serverPublicKey}`,
    `PresharedKey = ${client.presharedKey}`,
    `Endpoint = ${client.endpointHost}:${client.endpointPort}`,
    `AllowedIPs = ${client.allowedIps.join(", ")}`,
    `PersistentKeepalive = ${client.keepalive}`,
    "",
  ].join("\n");
}

/** wg-quick server file. Each peer is authorized for exactly one tunnel address. */
export function serverConfig(server: WireGuardServer): string {
  return [
    "[Interface]",
    `PrivateKey = ${server.privateKey}`,
    `Address = ${server.address}`,
    `ListenPort = ${server.listenPort}`,
    `MTU = ${server.mtu}`,
    "",
    ...server.peers.flatMap((peer) => [
      `# ${peer.name.replace(/[\r\n]/g, " ")}`,
      "[Peer]",
      `PublicKey = ${peer.publicKey}`,
      `PresharedKey = ${peer.presharedKey}`,
      `AllowedIPs = ${peer.address}`,
      "",
    ]),
  ].join("\n");
}

/**
 * Sample WireGuard server configuration for VPS setup.
 * REPLACE ALL PLACEHOLDER VALUES with your actual keys and settings.
 * Generate keys with: wg genkey | wg pubkey
 */
export const SAMPLE_SERVER_CONFIG: WireGuardServer = {
  privateKey: "REPLACE_WITH_YOUR_SERVER_PRIVATE_KEY_BASE64_44CHARS",
  address: "10.66.66.1/24",
  listenPort: 51820,
  mtu: 1420,
  peers: [
    {
      name: "Android Client",
      publicKey: "REPLACE_WITH_CLIENT_PUBLIC_KEY_BASE64_44CHARS",
      presharedKey: "REPLACE_WITH_PRESHARED_KEY_BASE64_44CHARS",
      address: "10.66.66.2/32",
    },
  ],
};

/**
 * Sample WireGuard client configuration for Android.
 * REPLACE ALL PLACEHOLDER VALUES with your actual keys and server details.
 */
export const SAMPLE_CLIENT_CONFIG: WireGuardClient = {
  privateKey: "REPLACE_WITH_YOUR_CLIENT_PRIVATE_KEY_BASE64_44CHARS",
  address: "10.66.66.2/32",
  dns: "10.66.66.1",
  mtu: 1420,
  serverPublicKey: "REPLACE_WITH_SERVER_PUBLIC_KEY_BASE64_44CHARS",
  presharedKey: "REPLACE_WITH_PRESHARED_KEY_BASE64_44CHARS",
  endpointHost: "your-vps.example.com",
  endpointPort: 51820,
  allowedIps: ["0.0.0.0/0", "::/0"],
  keepalive: 25,
};
