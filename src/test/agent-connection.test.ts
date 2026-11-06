import { describe, it, expect } from "vitest";
import { CONNECTION_APP_NAME, CONNECTION_SLUG, connectionSlug, claudeInstallCommand } from "../lib/agent-connection";

describe("assistant connection identity", () => {
  it("displays Tunnel Console publicly", () => {
    expect(CONNECTION_APP_NAME).toBe("Tunnel Console");
  });
  it("keeps the stable install slug so existing connections keep working", () => {
    expect(CONNECTION_SLUG).toBe("tunnel-guardian-compass");
    expect(connectionSlug("Tunnel Console")).not.toBe(CONNECTION_SLUG);
  });
});

describe("assistant connection command", () => {
  it("normalizes and bounds server names", () => {
    expect(connectionSlug("Tünnel Guardian Compass")).toBe("tunnel-guardian-compass");
    expect(connectionSlug("✨")).toBe("lovable-app");
    expect(connectionSlug("a".repeat(100))).toHaveLength(63);
    expect(connectionSlug("workspace")).toBe("workspace-app");
  });
  it("quotes the URL and uses user-scoped HTTP installation under the stable slug", () => {
    expect(claudeInstallCommand(CONNECTION_SLUG, "https://app.invalid/mcp")).toBe("claude mcp add --scope user --transport http tunnel-guardian-compass 'https://app.invalid/mcp'");
    expect(claudeInstallCommand("tunnel", "https://app.invalid/'test")).toBe("claude mcp add --scope user --transport http tunnel 'https://app.invalid/'\\''test'");
  });
});
