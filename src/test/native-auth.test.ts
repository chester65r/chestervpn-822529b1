// @vitest-environment node
import { describe, expect, it } from "vitest";
import { mkdtempSync, writeFileSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { peerAdminSource } from "../lib/native-auth-blueprint";

describe("server native peer enrollment tool (mocked wg commands, not deployment evidence)", () => {
  it("enrolls a public identity without a device private key, rotates PSK, then revokes persistently", () => {
    const dir = mkdtempSync(join(tmpdir(), "native-enroll-"));
    try {
      const key = () => randomBytes(32).toString("base64");
      const publicKey = key(), serverKey = key();
      const script = join(dir, "peer-admin.py"), config = join(dir, "wg0.conf"), log = join(dir, "calls");
      writeFileSync(script, peerAdminSource);
      const original = `[Interface]\nPrivateKey = ${key()}\nAddress = 10.66.66.1/24\nListenPort = 51820\n`;
      writeFileSync(config, original, { mode: 0o600 });
      writeFileSync(join(dir, "wg"), `#!/usr/bin/env python3\nimport sys,base64,secrets\nfrom pathlib import Path\nargs=sys.argv[1:]\nif args==['genpsk']: print(base64.b64encode(secrets.token_bytes(32)).decode())\nelif args==['show','wg0','public-key']: print(${JSON.stringify(serverKey)})\nelif args[0]=='syncconf':\n Path(${JSON.stringify(log)}).open('a').write('syncconf\\n')\n assert '[Interface]' in Path(args[2]).read_text()\nelif args[0]=='set': Path(${JSON.stringify(log)}).open('a').write('remove\\n')\nelse: sys.exit(1)\n`, { mode: 0o700 });
      writeFileSync(join(dir, "wg-quick"), "#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\nprint(Path(sys.argv[2]).read_text())\n", { mode: 0o700 });
      const run = (action: string, extra: string[] = []) => spawnSync("python3", [script, action, "--config", config, "--public-key", publicKey, ...extra], { encoding: "utf8", env: { ...process.env, PATH: `${dir}:${process.env['PATH']}` } });
      const response = join(dir, "response.conf");
      const fields = (file: string) => ["--address", "10.66.66.2/32", "--endpoint", "vpn.example.com:51820", "--response", file];
      expect(run("enroll", fields(response)).status).toBe(0);
      const enrolled = readFileSync(response, "utf8");
      expect(enrolled).not.toMatch(/^PrivateKey\s*=/m);
      expect(enrolled).toContain(`PublicKey = ${serverKey}`);
      expect(statSync(response).mode & 0o777).toBe(0o600);
      expect(readFileSync(config, "utf8")).toContain(`PublicKey = ${publicKey}`);
      expect(run("enroll", fields(join(dir, "duplicate.conf"))).status).toBe(1);
      const rotated = join(dir, "rotated.conf");
      expect(run("rotate", fields(rotated)).status).toBe(0);
      expect(readFileSync(rotated, "utf8").match(/^PresharedKey = (.+)$/m)?.[1]).not.toBe(enrolled.match(/^PresharedKey = (.+)$/m)?.[1]);
      expect(readFileSync(log, "utf8")).toContain("remove\nsyncconf");
      expect(run("revoke").status).toBe(0);
      expect(readFileSync(config, "utf8")).not.toContain(publicKey);
      expect(run("revoke").status).toBe(1);
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
});