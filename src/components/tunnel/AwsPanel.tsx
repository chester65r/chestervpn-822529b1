import { useEffect, useState } from "react";
import { useServerFn } from "@tanstack/react-start";
import { Loader2, RefreshCw, ShieldAlert } from "lucide-react";
import { supabase } from "@/integrations/supabase/client";
import { getAwsStatus } from "@/lib/aws-status.functions";

type Status = Awaited<ReturnType<typeof getAwsStatus>>;

export function AwsPanel() {
  const fetchStatus = useServerFn(getAwsStatus);
  const [signedIn, setSignedIn] = useState(false);
  const [status, setStatus] = useState<Status | null>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setBusy(true);
    try {
      setStatus(await fetchStatus());
    } catch {
      setStatus(null);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => setSignedIn(!!data.session));
    const { data: sub } = supabase.auth.onAuthStateChange((_e, s) => setSignedIn(!!s));
    return () => sub.subscription.unsubscribe();
  }, []);

  useEffect(() => {
    if (signedIn) void load();
    else setStatus(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signedIn]);

  if (!signedIn || (status && !status.allowed)) return null;

  return (
    <section className="mt-6 rounded-2xl border border-border bg-card p-4">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <h2 className="text-sm font-semibold text-foreground">AWS servers</h2>
          <p className="text-xs text-muted-foreground">View-only · nothing is changed in your account</p>
        </div>
        <button
          onClick={load}
          disabled={busy}
          aria-label="Refresh AWS status"
          className="rounded-lg border border-border p-2 text-muted-foreground hover:text-foreground"
        >
          {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
        </button>
      </div>
      {status?.allowed && status.error && <p className="text-xs text-destructive">{status.error}</p>}
      {status?.allowed && !status.error && status.servers.length === 0 && (
        <p className="text-xs text-muted-foreground">No servers in this AWS region yet.</p>
      )}
      <ul className="space-y-2">
        {status?.allowed &&
          status.servers.map((s) => (
            <li key={s.id} className="rounded-xl bg-muted p-3 font-mono text-xs">
              <div className="flex justify-between">
                <span className="text-foreground">{s.name || s.id}</span>
                <span className={s.state === "running" ? "text-primary" : "text-muted-foreground"}>{s.state}</span>
              </div>
              <div className="mt-1 text-muted-foreground">
                {s.type} · {s.publicIp ?? "no public address"} · VPN port 51820:{" "}
                <span className={s.wireguardOpen ? "text-primary" : "text-destructive"}>
                  {s.wireguardOpen ? "open" : "closed"}
                </span>
              </div>
            </li>
          ))}
      </ul>
      {status?.allowed && status.warnings.length > 0 && (
        <div className="mt-3 space-y-1">
          {status.warnings.map((w, i) => (
            <p key={i} className="flex items-center gap-1.5 text-xs text-destructive">
              <ShieldAlert className="h-3.5 w-3.5" /> {w.message}
            </p>
          ))}
        </div>
      )}
    </section>
  );
}
