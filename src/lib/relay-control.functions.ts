import net from "node:net";
import tls from "node:tls";
import { createServerFn } from "@tanstack/react-start";
import { requireSupabaseAuth } from "@/integrations/supabase/auth-middleware";
import { HETZNER_SERVER_SPEC } from "@/lib/tunnel";

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

export type ProbeHetznerInput = {
  port?: number;
  sni?: string;
  networkProfileId?: string;
};

export type RelayProbeResult = {
  status: "online" | "degraded" | "offline";
  latencyMs: number | null;
  errorCode: string | null;
  tlsEstablished: boolean;
  tcpReachable: boolean;
  relayNode: string | null;
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

/**
 * Probes a relay endpoint over HTTPS (/health), falling back to a TLS 1.3 socket
 * handshake (supporting self-signed IP certificates on 2.29.8.194) and raw TCP check.
 */
async function probeRelayEndpoint(
  host: string,
  port: number,
  sniHost?: string,
): Promise<RelayProbeResult> {
  const started = performance.now();

  // 1. Try standard HTTPS /health fetch first
  try {
    const response = await fetch(`https://${host}:${port}/health`, {
      method: "HEAD",
      redirect: "manual",
      signal: AbortSignal.timeout(3_500),
    });
    const latencyMs = Math.round(performance.now() - started);
    const relayNode = response.headers.get("x-relay-node");
    return {
      status: response.ok ? "online" : "degraded",
      latencyMs,
      errorCode: response.ok ? null : `HTTP_${response.status}`,
      tlsEstablished: true,
      tcpReachable: true,
      relayNode,
    };
  } catch {
    // Fall through to TLS socket probe (handles self-signed IP certificates on 2.29.8.194)
  }

  // 2. Try TLS socket handshake + GET /health (allows self-signed certificate on IP endpoints)
  const tlsResult = await new Promise<RelayProbeResult | null>((resolve) => {
    const tlsStarted = performance.now();
    let settled = false;
    const finish = (val: RelayProbeResult | null) => {
      if (!settled) {
        settled = true;
        resolve(val);
      }
    };

    const socket = tls.connect(
      {
        host,
        port,
        servername: sniHost && !/^\d+\.\d+\.\d+\.\d+$/.test(sniHost) ? sniHost : undefined,
        rejectUnauthorized: false,
        timeout: 3_500,
      },
      () => {
        const req =
          `GET /health HTTP/1.1\r\n` +
          `Host: ${sniHost || host}\r\n` +
          `User-Agent: TunnelConsole-HealthProbe/1.0\r\n` +
          `Connection: close\r\n\r\n`;
        socket.write(req);
      },
    );

    let buf = "";
    socket.on("data", (chunk) => {
      buf += chunk.toString("utf8");
      if (buf.includes("\r\n\r\n") || buf.length > 2048) {
        const latencyMs = Math.round(performance.now() - tlsStarted);
        socket.destroy();
        const is200 = /^HTTP\/1\.[01]\s+200\b/i.test(buf);
        const isHttp = /^HTTP\/1\.[01]\s+\d{3}\b/i.test(buf);
        const nodeMatch = buf.match(/x-relay-node:\s*([^\r\n]+)/i);
        finish({
          status: is200 ? "online" : "degraded",
          latencyMs,
          errorCode: is200 ? null : isHttp ? "HTTP_NON_200" : "TLS_HANDSHAKE_ONLY",
          tlsEstablished: true,
          tcpReachable: true,
          relayNode: nodeMatch?.[1]?.trim() ?? null,
        });
      }
    });

    socket.on("timeout", () => {
      socket.destroy();
      finish(null);
    });
    socket.on("error", () => {
      socket.destroy();
      finish(null);
    });
    socket.on("close", () => {
      finish(null);
    });
  });

  if (tlsResult) return tlsResult;

  // 3. Fallback raw TCP reachability check
  return new Promise<RelayProbeResult>((resolve) => {
    const tcpStarted = performance.now();
    let settled = false;
    const finish = (val: RelayProbeResult) => {
      if (!settled) {
        settled = true;
        resolve(val);
      }
    };

    const rawSocket = net.connect({ host, port, timeout: 3_000 }, () => {
      const latencyMs = Math.round(performance.now() - tcpStarted);
      rawSocket.destroy();
      finish({
        status: "degraded",
        latencyMs,
        errorCode: "TCP_OPEN_TLS_PENDING",
        tlsEstablished: false,
        tcpReachable: true,
        relayNode: null,
      });
    });

    rawSocket.on("timeout", () => {
      rawSocket.destroy();
      finish({
        status: "offline",
        latencyMs: null,
        errorCode: "TIMEOUT",
        tlsEstablished: false,
        tcpReachable: false,
        relayNode: null,
      });
    });

    rawSocket.on("error", (err: NodeJS.ErrnoException) => {
      rawSocket.destroy();
      finish({
        status: "offline",
        latencyMs: null,
        errorCode: err.code === "ECONNREFUSED" ? "CONN_REFUSED" : "UNREACHABLE",
        tlsEstablished: false,
        tcpReachable: false,
        relayNode: null,
      });
    });
  });
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

export const probeHetznerServer = createServerFn({ method: "POST" })
  .inputValidator((d: ProbeHetznerInput) => d)
  .handler(async ({ data: input }) => {
    const port =
      input?.port && Number.isInteger(input.port) && input.port >= 1 && input.port <= 65535
        ? input.port
        : HETZNER_SERVER_SPEC.tlsPort;
    const sni = input?.sni?.trim() || HETZNER_SERVER_SPEC.ip;

    const probe = await probeRelayEndpoint(HETZNER_SERVER_SPEC.ip, port, sni);
    return {
      host: HETZNER_SERVER_SPEC.ip,
      port,
      sni,
      networkProfileId: input?.networkProfileId ?? "hetzner-tls-direct",
      checkedAt: new Date().toISOString(),
      ...probe,
    };
  });

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

    const { status, latencyMs, errorCode } = await probeRelayEndpoint(input.host, input.port);

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
