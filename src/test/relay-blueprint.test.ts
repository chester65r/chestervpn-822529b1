// @vitest-environment node
import { afterEach, describe, expect, it } from "vitest";
import { spawn, type ChildProcess } from "node:child_process";
import { createServer, type Server } from "node:net";
import { once } from "node:events";
import { createRequire } from "node:module";
import { BLUEPRINT_FILES } from "../lib/android-blueprint";
const require = createRequire(import.meta.url);
const { WebSocket } = require("ws");
const credential = "local-fixture-only-not-a-real-credential";
const children: ChildProcess[] = [];
const servers: Server[] = [];
afterEach(async () => {
  for (const child of children.splice(0)) { child.kill(); await once(child, "exit").catch(() => undefined); }
  for (const server of servers.splice(0)) await new Promise<void>(resolve => server.close(() => resolve()));
});
async function freePort() {
  const server = createServer(); server.listen(0, "127.0.0.1"); await once(server, "listening");
  const address = server.address(); if (!address || typeof address === "string") throw new Error("No fixture port");
  await new Promise<void>(resolve => server.close(() => resolve())); return address.port;
}
async function launch(extra: Record<string, string> = {}) {
  const code = BLUEPRINT_FILES.find(file => file.name === "relay-server.mjs")?.code;
  if (!code) throw new Error("Relay missing");
  const port = await freePort();
  const child = spawn(process.execPath, ["--input-type=module", "-e", code], { cwd: process.cwd(), env: { ...process.env, PORT: String(port), RELAY_TOKEN: credential, ALLOWED_TARGETS: "127.0.0.1:443", ...extra }, stdio: ["ignore", "pipe", "pipe"] });
  children.push(child);
  await once(child.stdout, "data"); return port;
}
async function refused(input: unknown, extra: Record<string,string> = {}) {
  const port = await launch(extra);
  const ws = new WebSocket(`ws://127.0.0.1:${port}`);
  await once(ws, "open"); const closed = once(ws, "close"); ws.send(JSON.stringify(input));
  return (await closed)[0];
}
describe("executable educational relay safeguards", () => {
  it("rejects a missing or incorrect credential before opening upstream", async () => {
    expect(await refused({ host: "127.0.0.1", port: 443 })).toBe(1008);
    expect(await refused({ token: "wrong", host: "127.0.0.1", port: 443 })).toBe(1008);
  });
  it("rejects fractional ports and destinations outside the exact host/port allowlist", async () => {
    expect(await refused({ token: credential, host: "127.0.0.1", port: 443.5 })).toBe(1008);
    expect(await refused({ token: credential, host: "127.0.0.1", port: 444 })).toBe(1008);
  });
  it("exchanges a binary stream with an explicitly authorized local echo fixture", async () => {
    const echo = createServer(socket => socket.pipe(socket)); servers.push(echo);
    echo.listen(0, "127.0.0.1"); await once(echo, "listening");
    const address = echo.address(); if (!address || typeof address === "string") throw new Error("No echo port");
    const port = await launch({ ALLOWED_TARGETS: `127.0.0.1:${address.port}` });
    const ws = new WebSocket(`ws://127.0.0.1:${port}`); await once(ws, "open");
    const acknowledgement = once(ws, "message"); ws.send(JSON.stringify({ token: credential, host: "127.0.0.1", port: address.port }));
    expect(JSON.parse((await acknowledgement)[0].toString())).toEqual({ ok: true });
    const reply = once(ws, "message"); ws.send(Buffer.from("authorized echo"));
    expect((await reply)[0].toString()).toBe("authorized echo");
    ws.close(); await once(ws, "close");
  });
});