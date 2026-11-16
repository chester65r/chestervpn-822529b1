import { createServerFn } from "@tanstack/react-start";
import { requireSupabaseAuth } from "@/integrations/supabase/auth-middleware";
import { getAuditLogs, recordAuditEvent } from "@/lib/audit";
import { getSecurityHeaders } from "@/lib/security";
import { HETZNER_SERVER_SPEC } from "@/lib/tunnel";

const HEALTH_CHECK_WINDOW_MS = 60_000;
const HEALTH_CHECK_LIMIT = 10;

export type CheckRelayServerInput = {
  id: string;
  host: string;
  port: number;
  transport: string;
};

export type ProbeHetznerInput = {
  host?: string;
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
 * Probes a relay endpoint over standard HTTPS (/health) without opening raw TCP/SSH sockets.
 */
async function probeRelayEndpoint(
  host: string,
  port: number,
  _sniHost?: string,
): Promise<RelayProbeResult> {
  const started = performance.now();

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
  } catch (err) {
    const latencyMs = Math.round(performance.now() - started);
    const msg = err instanceof Error ? err.message : String(err);
    const isCertOrTls = /certificate|self-signed|tls|ssl/i.test(msg);
    if (isCertOrTls) {
      return {
        status: "degraded",
        latencyMs,
        errorCode: "TLS_HANDSHAKE_ONLY",
        tlsEstablished: true,
        tcpReachable: true,
        relayNode: null,
      };
    }
    return {
      status: "offline",
      latencyMs: null,
      errorCode: /timeout|aborted/i.test(msg) ? "TIMEOUT" : "UNREACHABLE",
      tlsEstablished: false,
      tcpReachable: false,
      relayNode: null,
    };
  }
}

async function notifyCircleCiDeployHook(payload: {
  relayId: string;
  status: "online" | "degraded" | "offline";
  latencyMs: number | null;
  errorCode: string | null;
}): Promise<void> {
  const secret = process.env["CIRCLECI_WEBHOOK_SECRET"]?.trim();
  if (!secret || secret.startsWith("YOUR_")) return;

  const hookUrl = process.env["CIRCLECI_DEPLOY_HOOK_URL"]?.trim();
  if (!hookUrl) return;

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
    const targetHost = input?.host?.trim() || HETZNER_SERVER_SPEC.ip;
    if (isBlockedHost(targetHost)) {
      recordAuditEvent({
        actorId: "console-operator",
        action: "relay.hetzner_probe",
        target: targetHost,
        status: "unauthorized",
        details: { reason: "Blocked private or loopback host" },
      });
      throw new Error("Private and loopback hosts cannot be probed from the control plane.");
    }

    const port =
      input?.port && Number.isInteger(input.port) && input.port >= 1 && input.port <= 65535
        ? input.port
        : HETZNER_SERVER_SPEC.tlsPort;
    const sni = input?.sni?.trim() || targetHost;
    const networkProfileId = input?.networkProfileId ?? "stc-ksa-5g-cgnat";

    const probe = await probeRelayEndpoint(targetHost, port, sni);

    recordAuditEvent({
      actorId: "console-operator",
      action: "relay.hetzner_probe",
      target: `${targetHost}:${port}`,
      status: probe.status === "offline" ? "failure" : "success",
      details: {
        sni,
        networkProfileId,
        latencyMs: probe.latencyMs,
        tlsEstablished: probe.tlsEstablished,
        errorCode: probe.errorCode,
      },
    });

    return {
      host: targetHost,
      port,
      sni,
      networkProfileId,
      checkedAt: new Date().toISOString(),
      ...probe,
    };
  });

const RELAY_SERVICE_PORTS = [
  { port: 443, service: "VLESS-Reality / WSS (443)" },
  { port: 8443, service: "Trojan-TLS (8443)" },
] as const;

export const probeHetznerMultiPort = createServerFn({ method: "POST" })
  .inputValidator((d: { host?: string; sni?: string }) => d)
  .handler(async ({ data: input }) => {
    const targetHost = input?.host?.trim() || HETZNER_SERVER_SPEC.ip;
    if (isBlockedHost(targetHost)) {
      throw new Error("Private and loopback hosts cannot be probed from the control plane.");
    }
    const sni = input?.sni?.trim() || targetHost;

    const results = await Promise.all(
      RELAY_SERVICE_PORTS.map(async (item) => {
        const probe = await probeRelayEndpoint(targetHost, item.port, sni);
        return {
          port: item.port,
          service: item.service,
          status: probe.status,
          latencyMs: probe.latencyMs,
          tcpReachable: probe.tcpReachable,
          tlsEstablished: probe.tlsEstablished,
          errorCode: probe.errorCode,
        };
      }),
    );

    recordAuditEvent({
      actorId: "console-operator",
      action: "relay.multi_port_probe",
      target: targetHost,
      status: results.some((r) => r.tcpReachable) ? "success" : "failure",
      details: {
        openPorts: results.filter((r) => r.tcpReachable).map((r) => r.port).join(","),
      },
    });

    return {
      host: targetHost,
      checkedAt: new Date().toISOString(),
      ports: results,
    };
  });

export const getSecurityAndAuditStatus = createServerFn({ method: "GET" }).handler(async () => {
  const auditLogs = getAuditLogs()
    .slice(-25)
    .reverse()
    .map((event) => ({
      id: event.id,
      timestamp: event.timestamp,
      actorId: event.actorId,
      action: event.action,
      target: event.target,
      status: event.status,
      detailsSummary: event.details ? JSON.stringify(event.details) : null,
    }));

  return {
    securityHeaders: getSecurityHeaders(),
    auditLogs,
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
      recordAuditEvent({
        actorId: context.userId,
        action: "relay.health_check",
        target: `${input.host}:${input.port}`,
        status: "unauthorized",
        details: { reason: "Blocked private or loopback host" },
      });
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

    recordAuditEvent({
      actorId: context.userId,
      action: "relay.health_check",
      target: `${input.host}:${input.port}`,
      status: status === "offline" ? "failure" : "success",
      details: { relayId: input.id, latencyMs, errorCode },
    });

    await notifyCircleCiDeployHook({
      relayId: input.id,
      status,
      latencyMs,
      errorCode,
    });

    return { status, latencyMs, errorCode };
  });
