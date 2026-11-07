import { useCallback, useEffect, useRef, useState } from "react";
import { useServerFn } from "@tanstack/react-start";
import type { Session } from "@supabase/supabase-js";
import { ArrowRight, Check, Copy, Loader2, LogOut, RefreshCw, Server as ServerIcon, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useWebMCP } from "use-webmcp-tool";
import { runWebMCPTool } from "@/lib/webmcp-telemetry";

import { supabase } from "@/integrations/supabase/client";
import { AwsPanel } from "./AwsPanel";
import {
  TRANSPORTS,
  SAMPLE_VPS_SERVERS,
  rowToServer,
  validateServerInput,
  type RelayServer,
  type ServerErrors,
} from "@/lib/servers";
import { DEPLOYMENT_TARGETS, RELAY_DEPLOYMENT_NOTES, getRelayDeploymentCommand, type RelayDeploymentTarget } from "@/lib/server-deployment";
import { checkRelayServer } from "@/lib/relay-control.functions";

const fieldClass =
  "w-full rounded-xl border border-input bg-muted px-3.5 py-3 text-sm text-foreground outline-none transition-colors placeholder:text-muted-foreground/60 focus:border-ring disabled:opacity-50";

function Field({ label, error, children }: { label: string; error?: string | undefined; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium tracking-wider text-muted-foreground">{label}</span>
      {children}
      {error && <span className="mt-1 block text-xs text-destructive">{error}</span>}
    </label>
  );
}

type Props = {
  onUseServer: (host: string, port: number) => void;
};

export function ServersTab({ onUseServer }: Props) {
  const [session, setSession] = useState<Session | null>(null);
  const [authReady, setAuthReady] = useState(false);
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [authBusy, setAuthBusy] = useState(false);

  const [servers, setServers] = useState<RelayServer[]>([]);
  const [loading, setLoading] = useState(false);
  const [name, setName] = useState("");
  const [host, setHost] = useState("");
  const [port, setPort] = useState("443");
  const [transport, setTransport] = useState("websocket");
  const [notes, setNotes] = useState("");
  const [errors, setErrors] = useState<ServerErrors>({});
  const [saving, setSaving] = useState(false);
  const [deploymentTarget, setDeploymentTarget] = useState<RelayDeploymentTarget>("ubuntu");
  const [deploymentHost, setDeploymentHost] = useState("");
  const [copiedDeployment, setCopiedDeployment] = useState(false);
  const [checkingServerId, setCheckingServerId] = useState<string | null>(null);
  const runHealthCheck = useServerFn(checkRelayServer);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setAuthReady(true);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_event, s) => setSession(s));
    return () => sub.subscription.unsubscribe();
  }, []);

  // Each load is tagged with the requesting account and a generation number so a slow
  // response from a previous account (or an older request) can never replace current data.
  const loadGeneration = useRef(0);
  const activeUserId = session?.user.id ?? null;
  const activeUser = useRef<string | null>(null);
  activeUser.current = activeUserId;

  const loadServers = useCallback(async () => {
    const userId = activeUser.current;
    if (!userId) return;
    const generation = ++loadGeneration.current;
    setLoading(true);
    const { data, error } = await supabase
      .from("relay_servers")
      .select("id,name,host,port,transport,notes,status,last_checked_at,last_latency_ms,created_at")
      .order("created_at", { ascending: false });
    if (generation !== loadGeneration.current || activeUser.current !== userId) return;
    setLoading(false);
    if (error) {
      setServers([]);
      toast.error("Couldn't load servers", { description: error.message });
      return;
    }
    setServers((data ?? []).map(rowToServer));
  }, []);

  useEffect(() => {
    // Clear account-owned data immediately on any account change, then load for the new one.
    const invalidateGeneration = () => { loadGeneration.current += 1 };
    invalidateGeneration();
    setServers([]);
    setLoading(false);
    if (activeUserId) void loadServers();
    return () => {
      invalidateGeneration();
    };
  }, [activeUserId, loadServers]);

  const submitAuth = async () => {
    setAuthBusy(true);
    if (mode === "signin") {
      const { error } = await supabase.auth.signInWithPassword({ email, password });
      if (error) {
        const description = /email not confirmed/i.test(error.message)
          ? "Confirm your email before signing in."
          : /rate limit|too many/i.test(error.message)
            ? "Too many attempts. Try again later."
            : "Invalid email or password.";
        toast.error("Sign-in failed", { description });
      }
    } else {
      const { data, error } = await supabase.auth.signUp({ email, password });
      if (error) {
        const description = /password/i.test(error.message)
          ? error.message
          : /rate limit|too many/i.test(error.message)
            ? "Too many attempts. Try again later."
            : "We couldn't create that account. Check your details and try again.";
        toast.error("Sign-up failed", { description });
      }
      else if (!data.session)
        toast.success("Account created", {
          description: "Check your email to confirm your account, then sign in.",
        });
    }
    setAuthBusy(false);
  };

  const addServer = async () => {
    const found = validateServerInput({ name, host, port, transport, notes });
    setErrors(found);
    if (Object.keys(found).length > 0 || !session) return;
    setSaving(true);
    const { error } = await supabase.from("relay_servers").insert({
      owner_id: session.user.id,
      name: name.trim(),
      host: host.trim(),
      port: Number(port),
      transport,
      notes: notes.trim(),
    });
    setSaving(false);
    if (error) {
      toast.error("Couldn't save server", { description: error.message });
      return;
    }
    toast.success(`Added "${name.trim()}"`);
    setName("");
    setHost("");
    setPort("443");
    setNotes("");
    void loadServers();
  };

  const removeServer = async (server: RelayServer) => {
    if (!window.confirm(`Delete ${server.name}? This only removes it from your saved relay list.`)) return;
    const { error } = await supabase.from("relay_servers").delete().eq("id", server.id);
    if (error) {
      toast.error("Couldn't delete server", { description: error.message });
      return;
    }
    toast(`Deleted "${server.name}"`);
    setServers((list) => list.filter((s) => s.id !== server.id));
  };

  const checkHealth = async (server: RelayServer) => {
    setCheckingServerId(server.id);
    try {
      const result = await runHealthCheck({
        data: { id: server.id, host: server.host, port: server.port, transport: server.transport },
      });
      setServers((list) => list.map((item) => item.id === server.id
        ? { ...item, status: result.status, lastLatencyMs: result.latencyMs, lastCheckedAt: Date.now() }
        : item));
      toast[result.status === "online" ? "success" : "error"](
        result.status === "online" ? `${server.name} is online` : `${server.name} is ${result.status}`,
        { description: result.latencyMs ? `${result.latencyMs} ms response time` : result.errorCode ?? "No response" },
      );
    } catch (error) {
      toast.error("Health check failed", { description: error instanceof Error ? error.message : "Try again later." });
    } finally {
      setCheckingServerId(null);
    }
  };

  useWebMCP({
    name: "read_visible_relay_servers",
    description: "Read relay servers currently shown on the Servers screen; requires a signed-in account.",
    annotations: { readOnlyHint: true, untrustedContentHint: true },
    enabled: authReady && Boolean(session),
    execute: () => runWebMCPTool("read_visible_relay_servers", () => {
      if (!authReady || !session) throw new Error("Sign in to read your relay servers.");
      if (loading) return "Relay servers are loading.";
      return servers.length ? servers.map(s => `${s.id}: ${s.name} — ${s.host}:${s.port} (${s.transport})`).join("\n") : "No servers shown.";
    }),
  });

  if (!authReady) {
    return (
      <div className="flex justify-center py-16">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!session) {
    return (
      <div className="space-y-4">
        <p className="rounded-xl border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
          Sign in to register the relay servers you operate and sync them across your devices.
        </p>
        <Field label="EMAIL">
          <input
            className={fieldClass}
            type="email"
            placeholder="you@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>
        <Field label="PASSWORD">
          <input
            className={fieldClass}
            type="password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <button
          type="button"
          onClick={submitAuth}
          disabled={authBusy || !email || !password}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary py-3 text-sm font-semibold text-primary-foreground transition-opacity disabled:opacity-50"
        >
          {authBusy && <Loader2 className="h-4 w-4 animate-spin" />}
          {mode === "signin" ? "Sign in" : "Create account"}
        </button>
        <button
          type="button"
          onClick={() => setMode(mode === "signin" ? "signup" : "signin")}
          className="w-full text-center text-xs text-muted-foreground underline-offset-2 hover:underline"
        >
          {mode === "signin" ? "No account yet? Create one" : "Already have an account? Sign in"}
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2 rounded-xl border border-border bg-muted px-3 py-2">
        <span className="truncate text-xs text-muted-foreground">{session.user.email}</span>
        <button
          type="button"
          onClick={() => supabase.auth.signOut()}
          className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          <LogOut className="h-3.5 w-3.5" /> Sign out
        </button>
      </div>

      <p className="text-xs text-muted-foreground">
        Your relay servers, stored in your account. Use the arrow to send one to the Configure screen.
      </p>

      {/* Quick Start - Sample VPS Servers */}
      <div className="space-y-2">
        <p className="text-xs font-medium tracking-wider text-muted-foreground">QUICK START SAMPLES</p>
        {SAMPLE_VPS_SERVERS.map((sample) => (
          <div key={sample.id} className="flex items-center gap-2 rounded-xl border border-dashed border-border bg-muted/50 px-3 py-2.5">
            <ServerIcon className="h-4 w-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{sample.name}</p>
              <p className="truncate font-mono text-[11px] text-muted-foreground">
                {sample.host}:{sample.port} - {sample.transport}
              </p>
            </div>
            <button
              type="button"
              title="Use this sample"
              aria-label={`Use ${sample.name}`}
              onClick={() => {
                setName(sample.name);
                setHost(sample.host);
                setPort(sample.port.toString());
                setTransport(sample.transport);
                setNotes(sample.notes);
              }}
              className="rounded-lg bg-secondary p-2 text-secondary-foreground transition-colors hover:text-primary"
            >
              <ArrowRight className="h-4 w-4" />
            </button>
          </div>
        ))}
      </div>

      {loading ? (
        <div className="flex justify-center py-8">
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
        </div>
      ) : servers.length === 0 ? (
        <p className="rounded-xl border border-dashed border-border px-3 py-6 text-center text-xs text-muted-foreground">
          No servers yet — add your first relay below.
        </p>
      ) : (
        <div className="space-y-2">
          {servers.map((s) => (
            <div key={s.id} className="flex items-center gap-2 rounded-xl border border-border bg-card px-3 py-2.5">
              <ServerIcon className="h-4 w-4 shrink-0 text-primary" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">{s.name}</p>
                <p className="truncate font-mono text-[11px] text-muted-foreground">
                  {s.host}:{s.port} · {s.transport}
                </p>
                <p className={`text-[11px] ${s.status === "online" ? "text-primary" : s.status === "offline" ? "text-destructive" : "text-muted-foreground"}`}>
                  {s.status}{s.lastLatencyMs ? ` · ${s.lastLatencyMs} ms` : ""}
                </p>
              </div>
              <button
                type="button"
                title="Check server health"
                aria-label={`Check health of ${s.name}`}
                onClick={() => void checkHealth(s)}
                disabled={checkingServerId === s.id}
                className="rounded-lg bg-secondary p-2 text-secondary-foreground transition-colors hover:text-primary disabled:opacity-50"
              >
                <RefreshCw className={`h-4 w-4 ${checkingServerId === s.id ? "animate-spin" : ""}`} />
              </button>
              <button
                type="button"
                title="Use in Configure"
                aria-label={`Use ${s.name} in Configure`}
                onClick={() => onUseServer(s.host, s.port)}
                className="rounded-lg bg-secondary p-2 text-secondary-foreground transition-colors hover:text-primary"
              >
                <ArrowRight className="h-4 w-4" />
              </button>
              <button
                type="button"
                title="Delete server"
                onClick={() => removeServer(s)}
                className="rounded-lg bg-secondary p-2 text-secondary-foreground transition-colors hover:text-destructive"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="space-y-3 rounded-xl border border-border bg-card p-3">
        <p className="text-xs font-medium tracking-wider text-muted-foreground">ADD RELAY SERVER</p>
        <Field label="NAME" error={errors.name}>
          <input
            className={fieldClass}
            placeholder="My Frankfurt relay"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <div className="grid grid-cols-[1fr_6.5rem] gap-3">
          <Field label="HOST" error={errors.host}>
            <input
              className={fieldClass}
              placeholder="relay.example.com"
              value={host}
              onChange={(e) => setHost(e.target.value)}
            />
          </Field>
          <Field label="PORT" error={errors.port}>
            <input
              className={fieldClass}
              inputMode="numeric"
              placeholder="443"
              value={port}
              onChange={(e) => setPort(e.target.value)}
            />
          </Field>
        </div>
        <Field label="TRANSPORT">
          <select className={fieldClass} value={transport} onChange={(e) => setTransport(e.target.value)}>
            {TRANSPORTS.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="NOTES">
          <input
            className={fieldClass}
            placeholder="Optional"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </Field>
        <button
          type="button"
          onClick={addServer}
          disabled={saving}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary py-3 text-sm font-semibold text-primary-foreground transition-opacity disabled:opacity-50"
        >
          {saving && <Loader2 className="h-4 w-4 animate-spin" />}
          Add server
        </button>
      </div>

      <div className="space-y-3 rounded-xl border border-primary/20 bg-primary/5 p-3">
        <div>
          <p className="text-xs font-medium tracking-wider text-primary">DEPLOY YOUR OWN RELAY</p>
          <p className="mt-1 text-xs text-muted-foreground">Generate a reproducible setup command for a server you control.</p>
        </div>
        <Field label="PUBLIC HOSTNAME OR IP">
          <input
            className={fieldClass}
            placeholder="vpn.example.com"
            value={deploymentHost}
            onChange={(e) => {
              setDeploymentHost(e.target.value);
              setCopiedDeployment(false);
            }}
          />
        </Field>
        <Field label="DEPLOYMENT TARGET">
          <select className={fieldClass} value={deploymentTarget} onChange={(e) => setDeploymentTarget(e.target.value as RelayDeploymentTarget)}>
            {DEPLOYMENT_TARGETS.map((target) => (
              <option key={target.id} value={target.id}>{target.label} — {target.description}</option>
            ))}
          </select>
        </Field>
        <div className="rounded-lg border border-border bg-background p-2.5">
          <code className="block break-words font-mono text-[11px] leading-relaxed text-muted-foreground">
            {deploymentHost.trim() ? getRelayDeploymentCommand(deploymentTarget, deploymentHost) : "Enter the server host to generate a setup command."}
          </code>
        </div>
        <button
          type="button"
          disabled={!deploymentHost.trim()}
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(getRelayDeploymentCommand(deploymentTarget, deploymentHost));
              setCopiedDeployment(true);
              toast.success("Deployment command copied");
            } catch {
              toast.error("Couldn't access the clipboard", { description: "Select and copy the command manually." });
            }
          }}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary py-3 text-sm font-semibold text-primary-foreground transition-opacity disabled:opacity-50"
        >
          {copiedDeployment ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
          {copiedDeployment ? "Copied" : "Copy deployment command"}
        </button>
        <ul className="space-y-1 text-[11px] text-muted-foreground">
          {RELAY_DEPLOYMENT_NOTES.map((note) => <li key={note}>• {note}</li>)}
        </ul>
      </div>
      <AwsPanel />
    </div>
  );
}
