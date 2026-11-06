import { describe, expect, it } from "vitest";
import { oauthReturnPath } from "../lib/oauth-return-path";
describe("OAuth return paths", () => {
  it("preserves the full local consent return", () => {
    expect(oauthReturnPath("/.lovable/oauth/consent?authorization_id=test#account")).toBe("/.lovable/oauth/consent?authorization_id=test#account");
  });
  it("rejects external, network and backslash redirects", () => {
    for (const value of ["https://outside.invalid", "//outside.invalid", "/\\outside.invalid", "/\ninvalid", null]) expect(oauthReturnPath(value)).toBe("/");
  });
});