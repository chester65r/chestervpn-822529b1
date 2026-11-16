import { useCallback, useEffect, useState } from "react";
import { useServerFn } from "@tanstack/react-start";
import { RefreshCw, ShieldAlert, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getSecurityAndAuditStatus } from "@/lib/relay-control.functions";
import { getSecurityHeaders } from "@/lib/security";

type Props = {
  refreshToken?: number;
};

type AuditLogItem = Awaited<ReturnType<typeof getSecurityAndAuditStatus>>["auditLogs"][number];

export function SecurityAuditPanel({ refreshToken = 0 }: Props) {
  const fetchStatus = useServerFn(getSecurityAndAuditStatus);
  const [headers, setHeaders] = useState<Record<string, string>>(() => getSecurityHeaders());
  const [logs, setLogs] = useState<AuditLogItem[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchStatus();
      if (data && typeof data === "object") {
        if (data.securityHeaders && typeof data.securityHeaders === "object") {
          setHeaders(data.securityHeaders);
        }
        if (Array.isArray(data.auditLogs)) {
          setLogs(data.auditLogs);
        }
      }
    } catch {
      /* Ignore transient load errors */
    } finally {
      setLoading(false);
    }
  }, [fetchStatus]);

  useEffect(() => {
    void load();
  }, [load, refreshToken]);

  const safeHeaders =
    headers && typeof headers === "object" && !Array.isArray(headers)
      ? headers
      : (getSecurityHeaders() ?? {});
  const headerEntries = Object.entries(safeHeaders);
  const safeLogs = Array.isArray(logs) ? logs : [];

  return (
    <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="flex items-center gap-1.5 text-xs font-bold tracking-wider text-primary">
            <ShieldCheck className="h-4 w-4" /> SECURITY HARDENING & STRUCTURED AUDIT LOG
          </p>
          <p className="text-[11px] text-muted-foreground">
            Active HTTP response headers (<code className="font-mono">src/lib/security.ts</code>) & append-only probe/MCP audit events (<code className="font-mono">src/lib/audit.ts</code>).
          </p>
        </div>
        <Button
          size="sm"
          variant="secondary"
          type="button"
          onClick={() => void load()}
          disabled={loading}
          className="h-7 gap-1 text-xs"
        >
          <RefreshCw className={`h-3 w-3 ${loading ? "animate-spin" : ""}`} />
          Refresh
        </Button>
      </div>

      {headerEntries.length > 0 && (
        <div className="grid gap-1.5 sm:grid-cols-2">
          {headerEntries.map(([key, value]) => (
            <div
              key={key}
              className="rounded-xl border border-border bg-background px-2.5 py-2"
            >
              <p className="font-mono text-[10px] font-semibold text-primary">{key}</p>
              <p className="truncate font-mono text-[10px] text-muted-foreground" title={value}>
                {value}
              </p>
            </div>
          ))}
        </div>
      )}

      <div className="rounded-xl border border-border bg-background p-2.5">
        <p className="mb-1.5 text-[10px] font-semibold tracking-wider text-muted-foreground">
          RECENT AUDIT EVENTS ({safeLogs.length})
        </p>
        {safeLogs.length === 0 ? (
          <p className="flex items-center gap-1.5 py-1 text-xs text-muted-foreground">
            <ShieldAlert className="h-3.5 w-3.5 text-muted-foreground" />
            No runtime events yet — click "Probe 2.29.8.194" or invoke an MCP relay action to record an audit entry.
          </p>
        ) : (
          <ul className="max-h-36 space-y-1.5 overflow-y-auto font-mono text-[11px]">
            {safeLogs.map((entry) => (
              <li
                key={entry.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border/60 bg-muted/40 px-2.5 py-1.5"
              >
                <span className="text-foreground">
                  <strong className="text-primary">{entry.action}</strong> → {entry.target}
                </span>
                <span
                  className={
                    entry.status === "success"
                      ? "text-primary"
                      : entry.status === "unauthorized"
                        ? "text-chart-4"
                        : "text-destructive"
                  }
                >
                  {entry.status.toUpperCase()} · {new Date(entry.timestamp).toLocaleTimeString()}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
