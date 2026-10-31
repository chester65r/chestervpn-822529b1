import { describe, it, expect } from "vitest";
import { connectionSlug, claudeInstallCommand } from "../lib/agent-connection";
describe("assistant connection command", () => {
  it("normalizes and bounds server names", () => {
    expect(connectionSlug("Tünnel Guardian Compass")).toBe("tunnel-guardian-compass");
    expect(connectionSlug("✨")).toBe("lovable-app");
    expect(connectionSlug("a".repeat(100))).toHaveLength(63);
    expect(connectionSlug("workspace")).toBe("workspace-app");
  });
  it("quotes the URL and uses user-scoped HTTP installation", () => {
    expect(claudeInstallCommand("Tunnel", "https://app.invalid/mcp")).toBe("claude mcp add --scope user --transport http tunnel 'https://app.invalid/mcp'");
    expect(claudeInstallCommand("Tunnel", "https://app.invalid/'test")).toBe("claude mcp add --scope user --transport http tunnel 'https://app.invalid/'\\''test'");
  });
});