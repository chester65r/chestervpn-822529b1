// @vitest-environment node
import { describe, expect, it } from "vitest";
import { getSecurityHeaders } from "../lib/security";

describe("Security Headers", () => {
  it("includes all essential hardening headers", () => {
    const headers = getSecurityHeaders();
    expect(headers["X-Content-Type-Options"]).toBe("nosniff");
    expect(headers["X-Frame-Options"]).toBe("DENY");
    expect(headers["Strict-Transport-Security"]).toContain("max-age=");
    expect(headers["Content-Security-Policy"]).toContain("default-src 'self'");
  });
});
