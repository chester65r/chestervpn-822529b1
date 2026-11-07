import { createServerFn } from "@tanstack/react-start";
import { requireSupabaseAuth } from "@/integrations/supabase/auth-middleware";

const HEALTH_CHECK_WINDOW_MS = 60_000;
const HEALTH_CHECK_LIMIT = 10;
const DEFAULT_CIRCLECI_DEPLOY_HOOK_URL =
  "https://circleci.com/api/v3/deploy/hooks/13990af0-015d-4b37-957b-22c2650488ae/validate";

export type CheckRelayServerInput = {
  id: string;
  host: string;
  port: number;
  transport: string;
};

function isBlockedHost(host: string): boolean {
  const normalized = host.trim().toLowerCase();
  return (
    normalized === "localhost" ||
    normalized.endsWith(".localhost") ||
    normalized === "::1" ||
    normalized.startsWith("127.") ||
    normalized.startsWith("10.") ||
    normalized.startsWith("192.168.") ||
    /^172\.(1[6-9]|2\d|3[0-1])\./.test(normalized) ||
    normalized.startsWith("169.254.") ||
    normalized === "0.0.0.0"
  );
}

async function notifyCircleCiDeployHook(payload: {
  relayId: string;
  status: "online" | "degraded" | "offline";
  latencyMs: number | null;
  errorCode: string | null;
}): Promise<void> {
  const secret = process.env["CIRCLECI_WEBHOOK_SECRET"]?.trim();
  if (!secret || secret.startsWith("YOUR_")) return;

  const hookUrl =
    process.env["CIRCLECI_DEPLOY_HOOK_URL"]?.trim() || DEFAULT_CIRCLECI_DEPLOY_HOOK_URL;

  try {
    await fetch(hookUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${secret}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        status: payload.status === "online" ? "healthy" : "unhealthy",
        relay_id: payload.relayId,
        relay_status: payload.status,
        latency_ms: payload.latencyMs,
        error_code: payload.errorCode,
        timestamp: new Date().toISOString(),
      }),
      signal: AbortSignal.timeout(4_000),
    });
  } catch (err) {
    console.warn("[relay-control] CircleCI deploy hook notification skipped:", err);
  }
}

export const checkRelayServer = createServerFn({ method: "POST" })
  .middleware([requireSupabaseAuth])
  .inputValidator((d: CheckRelayServerInput) => d)
  .handler(async ({ context, data: input }) => {
    if (!input?.id || !input?.host || !Number.isInteger(input.port) || input.port < 1 || input.port > 65535) {
      throw new Error("Invalid relay server input");
    }
    if (input.transport === "wireguard") {
      throw new Error("WireGuard health checks require a UDP probe and are not available from the web control plane yet.");
    }
    if (isBlockedHost(input.host)) {
      throw new Error("Private and loopback hosts cannot be checked from the control plane.");
    }

    const since = new Date(Date.now() - HEALTH_CHECK_WINDOW_MS).toISOString();
    const { count, error: countError } = await context.supabase
      .from("audit_logs")
      .select("id", { count: "exact", head: true })
      .eq("actor_id", context.userId)
      .eq("action", "relay.health_check")
      .gte("created_at", since);
    if (countError) throw new Error("Health-check rate limit is unavailable");
    if ((count ?? 0) >= HEALTH_CHECK_LIMIT) throw new Error("Health-check limit reached. Try again in a minute.");

    const url = `https://${input.host}:${input.port}/health`;
    const started = performance.now();
    let status: "online" | "degraded" | "offline" = "offline";
    let latencyMs: number | null = null;
    let errorCode: string | null = null;

    try {
      const response = await fetch(url, {
        method: "HEAD",
        redirect: "manual",
        signal: AbortSignal.timeout(4_000),
      });
      latencyMs = Math.round(performance.now() - started);
      status = response.ok ? "online" : "degraded";
      if (!response.ok) errorCode = `HTTP_${response.status}`;
    } catch (error) {
      errorCode = error instanceof Error && error.name === "TimeoutError" ? "TIMEOUT" : "UNREACHABLE";
    }

    await context.supabase
      .from("relay_servers")
      .update({
        status,
        last_checked_at: new Date().toISOString(),
        last_latency_ms: latencyMs,
      })
      .eq("id", input.id)
      .eq("owner_id", context.userId);

    await context.supabase.from("audit_logs").insert({
      actor_id: context.userId,
      action: "relay.health_check",
      entity_type: "relay_server",
      entity_id: input.id,
      metadata: { status, latencyMs, errorCode },
    });

    await notifyCircleCiDeployHook({
      relayId: input.id,
      status,
      latencyMs,
      errorCode,
    });

    return { status, latencyMs, errorCode };
  });
