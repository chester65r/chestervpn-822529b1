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
