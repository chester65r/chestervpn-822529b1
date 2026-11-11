// @vitest-environment node
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

// Regression guard: alizarin's relay_servers table keys rows by owner_id
// (see src/integrations/supabase/types.ts). The Lovable republish commits
// reintroduced a legacy user_id insert in ServersTab, which PostgREST
// rejects — Add Server silently broke on the live backend. The canary-park
// tables (tunnel_profiles etc.) legitimately use user_id; this guard only
// covers relay_servers writes.
const read = (rel: string) => readFileSync(join(process.cwd(), rel), "utf8");

describe("relay_servers writes use owner_id", () => {
  it("ServersTab inserts with owner_id, never user_id", () => {
    const src = read("src/components/tunnel/ServersTab.tsx");
    expect(src).toContain('from("relay_servers").insert({');
    expect(src).toContain("owner_id: session.user.id");
    expect(src).not.toContain("user_id: session.user.id");
  });

  it("MCP add-relay-server inserts with owner_id", () => {
    const src = read("src/lib/mcp/tools/add-relay-server.ts");
    expect(src).toContain("owner_id: userId");
    expect(src).not.toMatch(/\.insert\(\{[^}]*user_id/);
  });
});
