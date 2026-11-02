import { createServerFn } from "@tanstack/react-start";
import { requireSupabaseAuth } from "@/integrations/supabase/auth-middleware";
import { allowsUdpPort, parseInstances, parseSecurityGroups, safetyWarnings } from "./aws-status";

const GATEWAY_URL = "https://connector-gateway.lovable.dev/aws";

// Read-only: only Describe* actions are ever sent.
async function ec2Describe(body: string) {
  const lovable = process.env["LOVABLE_API_KEY"];
  const aws = process.env["AWS_API_KEY"];
  if (!lovable || !aws) throw new Error("AWS connection is not configured");
  const res = await fetch(`${GATEWAY_URL}/ec2/`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${lovable}`,
      "X-Connection-Api-Key": aws,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: `${body}&Version=2016-11-15`,
  });
  const text = await res.text();
  if (!res.ok) {
    console.error(`AWS request failed [${res.status}]: ${text}`);
    const code = text.match(/<Code>([^<]*)<\/Code>/)?.[1] ?? `HTTP ${res.status}`;
    throw new Error(`AWS request failed: ${code}`);
  }
  return text;
}

export const getAwsStatus = createServerFn({ method: "GET" })
  .middleware([requireSupabaseAuth])
  .handler(async ({ context }) => {
    const { data: isAdmin } = await context.supabase.rpc("has_role", { _user_id: context.userId, _role: "admin" });
    if (!isAdmin) return { allowed: false as const };
    try {
      const [ix, sx] = await Promise.all([
        ec2Describe("Action=DescribeInstances"),
        ec2Describe("Action=DescribeSecurityGroups"),
      ]);
      const instances = parseInstances(ix);
      const groups = parseSecurityGroups(sx);
      return {
        allowed: true as const,
        error: null,
        servers: instances.map((i) => ({
          ...i,
          wireguardOpen: groups
            .filter((g) => i.securityGroupIds.includes(g.id))
            .some((g) => g.inbound.some((r) => allowsUdpPort(r, 51820))),
        })),
        warnings: safetyWarnings(groups),
      };
    } catch (e) {
      return { allowed: true as const, error: e instanceof Error ? e.message : "AWS unavailable", servers: [], warnings: [] };
    }
  });
