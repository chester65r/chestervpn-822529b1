// @vitest-environment node
import { describe, it, expect } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

describe("Android workflow evidence gates (mock tools, not Android evidence)", () => {
  const check = (mode: string, qemu: string, result: string, boot = "1", installOk = true, packageReady = true, packageInstalled = true) => {
    const dir = mkdtempSync(join(tmpdir(), "android-workflow-"));
    try {
      mkdirSync(join(dir, "app")); mkdirSync(join(dir, "jdk/bin"), { recursive: true }); mkdirSync(join(dir, "sdk/platform-tools"), { recursive: true });
      writeFileSync(join(dir, "app/build.gradle.kts"), "");
      writeFileSync(join(dir, "gradlew"), "#!/bin/sh\nexit 0\n", { mode: 0o700 });
      writeFileSync(join(dir, "jdk/bin/java"), '#!/bin/sh\necho \'openjdk version "17.0.1"\'\n', { mode: 0o700 });
      writeFileSync(join(dir, "sdk/platform-tools/adb"), `#!/usr/bin/env python3\nimport sys\na=sys.argv[3:]\nassert sys.argv[1:3] == ['-s', 'explicit-test-serial']\nif a==['get-state']: print('device')\nelif 'ro.kernel.qemu' in a: print(${JSON.stringify(qemu)})\nelif 'sys.boot_completed' in a: print(${JSON.stringify(boot)})\nelif a==['shell','service','check','package']: print(${JSON.stringify(packageReady ? "Service package: found" : "Service package: not found")})\nelif 'ro.build.version.sdk' in a: print('34')\nelif 'ro.product.cpu.abi' in a: print('x86_64')\nelif a and a[0]=='install':\n assert a[1:3]==['--no-streaming','-r']\n print('Success' if ${installOk ? "True" : "False"} else 'Failure')\n sys.exit(${installOk ? 0 : 124})\nelif a[:3]==['shell','pm','path']: print(${JSON.stringify(packageInstalled ? "package:/data/app/test/base.apk" : "")})\nelif 'instrument' in a: print(${JSON.stringify(result)})\n`, { mode: 0o700 });
      return spawnSync("bash", [resolve("native/android/run-checks.sh"), dir, mode, "explicit-test-serial"], { encoding: "utf8", env: { ...process.env, JAVA_HOME: join(dir, "jdk"), ANDROID_HOME: join(dir, "sdk") } });
    } finally { rmSync(dir, { recursive: true, force: true }); }
  };
  it("rejects an emulator as physical-device evidence", () => {
    const result = check("physical", "1", "OK (6 tests)");
    expect(result.status).toBe(1);
    expect(result.stdout).toContain("Device kind does not match");
  });
  it("rejects failed instrumentation even when the command exits zero", () => {
    const result = check("emulator", "1", "FAILURES!!!");
    expect(result.status).toBe(1);
    expect(result.stdout).toContain("Instrumentation failed");
  });
  it("does not run runtime tests before full device boot", () => {
    const result = check("emulator", "1", "OK (6 tests)", "");
    expect(result.status).toBe(1);
    expect(result.stdout).toContain("has not completed boot");
  });
  it("installs both APKs without streaming on the explicitly selected device", () => {
    expect(check("emulator", "1", "OK (6 tests)").status).toBe(0);
  });
  it("stops before installation when the package service is unavailable", () => {
    const result = check("emulator", "1", "OK (6 tests)", "1", true, false);
    expect(result.status).toBe(1);
    expect(result.stdout).toContain("Package service unavailable");
  });
  it("does not accept an install timeout as passing runtime evidence", () => {
    const result = check("emulator", "1", "OK (6 tests)", "1", false);
    expect(result.status).toBe(1);
    expect(result.stdout).toContain("APK installation failed or timed out");
  });
  it("requires installed package read-back before instrumentation", () => {
    const result = check("emulator", "1", "OK (6 tests)", "1", true, true, false);
    expect(result.status).toBe(1);
    expect(result.stdout).toContain("Installed package verification failed");
  });
});