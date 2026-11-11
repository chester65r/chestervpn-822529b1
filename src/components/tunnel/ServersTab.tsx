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
  PRIMARY_HETZNER_SERVER,
  TRANSPORTS,
  SAMPLE_VPS_SERVERS,
  rowToServer,
  validateServerInput,
  type RelayServer,
  type ServerErrors,
} from "@/lib/servers";
import {
  DEPLOYMENT_TARGETS,
  RELAY_DEPLOYMENT_NOTES,
  SERVER_CONTAINER_FILES,
  SERVER_TRANSPORT_CAPABILITIES,
  getHetznerSafeReleaseCommands,
  getRelayDeploymentCommand,
  type RelayDeploymentTarget,
} from "@/lib/server-deployment";
import { checkRelayServer, probeHetznerServer } from "@/lib/relay-control.functions";
import { HETZNER_SERVER_SPEC } from "@/lib/tunnel";

const fieldClass = "tc-field py-3";

function Field({ label, error, children }: { label: string; error?: string | undefined; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="tc-label">{label}</span>
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
  const [name, setName] = useState<string>(PRIMARY_HETZNER_SERVER.name);
  const [host, setHost] = useState<string>(HETZNER_SERVER_SPEC.ip);
  const [port, setPort] = useState("443");
  const [transport, setTransport] = useState("websocket");
  const [notes, setNotes] = useState<string>(PRIMARY_HETZNER_SERVER.notes);
  const [errors, setErrors] = useState<ServerErrors>({});
  const [saving, setSaving] = useState(false);
  const [deploymentTarget, setDeploymentTarget] = useState<RelayDeploymentTarget>("multi-transport");
  const [deploymentHost, setDeploymentHost] = useState<string>(HETZNER_SERVER_SPEC.ip);
  const [copiedDeployment, setCopiedDeployment] = useState(false);
  const [activeContainerFileIdx, setActiveContainerFileIdx] = useState(0);
  const [copiedContainerFile, setCopiedContainerFile] = useState(false);
  const [checkingServerId, setCheckingServerId] = useState<string | null>(null);
  const [probingPrimary, setProbingPrimary] = useState(false);
  const [primaryProbe, setPrimaryProbe] = useState<{
    status: "online" | "degraded" | "offline";
    latencyMs: number | null;
    errorCode: string | null;
  } | null>(null);
  const runHealthCheck = useServerFn(checkRelayServer);
  const runPrimaryProbe = useServerFn(probeHetznerServer);

  const checkPrimaryHetzner = async () => {
    setProbingPrimary(true);
    try {
      const res = await runPrimaryProbe({ data: { port: 443, sni: HETZNER_SERVER_SPEC.ip } });
      setPrimaryProbe({ status: res.status, latencyMs: res.latencyMs, errorCode: res.errorCode });
      toast[res.status === "online" ? "success" : "message"](
        `Hetzner CX23 (${HETZNER_SERVER_SPEC.ip}): ${res.status}`,
        {
          description:
            res.latencyMs !== null
              ? `${res.latencyMs} ms response time`
              : `${res.errorCode ?? "Unreachable"} — deploy relay-server/ to 2.29.8.194 to bring online.`,
        },
      );
    } catch (err) {
      toast.error("Primary server probe failed", {
        description: err instanceof Error ? err.message : "Try again later.",
      });
    } finally {
      setProbingPrimary(false);
    }
  };

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

  const primaryHetznerCard = (
    <div className="tc-card-accent space-y-2.5 p-4">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="tc-section-title">
            PRIMARY HETZNER CX23 INFRASTRUCTURE
          </p>
          <p className="truncate font-mono text-xs text-foreground">
            {HETZNER_SERVER_SPEC.ip}:443 (TLS/WS) · {HETZNER_SERVER_SPEC.ip}:51820 (WireGuard)
          </p>
          <p className="text-[11px] text-muted-foreground">
            {HETZNER_SERVER_SPEC.os} · {HETZNER_SERVER_SPEC.vcpu} vCPU · {HETZNER_SERVER_SPEC.ramGb} GB RAM ·{" "}
            {HETZNER_SERVER_SPEC.diskGb} GB NVMe
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          <button
            type="button"
            title="Probe Hetzner 2.29.8.194"
            aria-label="Probe Hetzner 2.29.8.194"
            onClick={() => void checkPrimaryHetzner()}
            disabled={probingPrimary}
            className="tc-icon-btn"
          >
            <RefreshCw className={`h-4 w-4 ${probingPrimary ? "animate-spin" : ""}`} />
          </button>
          <button
            type="button"
            title="Use 2.29.8.194 in Configure"
            aria-label="Use Hetzner 2.29.8.194 in Configure"
            onClick={() => onUseServer(HETZNER_SERVER_SPEC.ip, HETZNER_SERVER_SPEC.tlsPort)}
            className="tc-btn-primary px-2.5 py-2 text-xs"
          >
            Use 2.29.8.194
          </button>
        </div>
      </div>
      {primaryProbe && (
        <p className="font-mono text-[11px] text-muted-foreground">
          Live probe: <span className="font-semibold text-foreground">{primaryProbe.status}</span>
          {primaryProbe.latencyMs !== null ? ` · ${primaryProbe.latencyMs} ms` : ` · ${primaryProbe.errorCode ?? "offline"}`}
        </p>
      )}
    </div>
  );

  if (!session) {
    return (
      <div className="space-y-4">
        {primaryHetznerCard}
        <p className="rounded-xl border border-border bg-muted/60 px-3.5 py-2.5 text-xs leading-relaxed text-muted-foreground">
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
          className="tc-btn-primary w-full py-3"
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

  const safeReleaseCommands = getHetznerSafeReleaseCommands(deploymentHost);

  return (
    <div className="space-y-4">
      {primaryHetznerCard}
      <div className="flex items-center justify-between gap-2 rounded-xl border border-border bg-muted/60 px-3.5 py-2.5">
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

      {/* Hetzner CX23 Endpoints */}
      <div className="space-y-2">
        <p className="tc-label">HETZNER CX23 (2.29.8.194) ENDPOINTS</p>
        {SAMPLE_VPS_SERVERS.map((sample) => (
          <div key={sample.id} className="flex items-center gap-2 rounded-xl border border-dashed border-border bg-muted/40 px-3 py-2.5 transition-colors hover:border-primary/30">
            <ServerIcon className="h-4 w-4 shrink-0 text-primary" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{sample.name}</p>
              <p className="truncate font-mono text-[11px] text-muted-foreground">
                {sample.host}:{sample.port} - {sample.transport}
              </p>
            </div>
            <button
              type="button"
              title="Use this endpoint"
              aria-label={`Use ${sample.name}`}
              onClick={() => {
                setName(sample.name);
                setHost(sample.host);
                setPort(sample.port.toString());
                setTransport(sample.transport);
                setNotes(sample.notes);
              }}
              className="tc-icon-btn"
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
        <p className="tc-empty py-6">
          No servers yet — add your Hetzner 2.29.8.194 relay below.
        </p>
      ) : (
        <div className="space-y-2">
          {servers.map((s) => (
            <div key={s.id} className="tc-card flex items-center gap-2 px-3.5 py-3 shadow-none">
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
                className="tc-icon-btn"
              >
                <RefreshCw className={`h-4 w-4 ${checkingServerId === s.id ? "animate-spin" : ""}`} />
              </button>
              <button
                type="button"
                title="Use in Configure"
                aria-label={`Use ${s.name} in Configure`}
                onClick={() => onUseServer(s.host, s.port)}
                className="tc-icon-btn"
              >
                <ArrowRight className="h-4 w-4" />
              </button>
              <button
                type="button"
                title="Delete server"
                onClick={() => removeServer(s)}
                className="tc-icon-btn tc-icon-btn-danger"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="tc-card space-y-3 p-4">
        <p className="tc-section-title">ADD RELAY SERVER</p>
        <Field label="NAME" error={errors.name}>
          <input
            className={fieldClass}
            placeholder="Hetzner CX23 Primary (Ubuntu)"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <div className="grid grid-cols-[1fr_6.5rem] gap-3">
          <Field label="HOST" error={errors.host}>
            <input
              className={fieldClass}
              placeholder="2.29.8.194"
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
          className="tc-btn-primary w-full py-3"
        >
          {saving && <Loader2 className="h-4 w-4 animate-spin" />}
          Add server
        </button>
      </div>

      <div className="tc-card-accent space-y-3 p-4">
        <div>
          <p className="tc-section-title">DEPLOY TO HETZNER CX23 (2.29.8.194)</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Includes every internet transport and anti-DPI tool built into Tunnel Console (WireGuard, TLS+SNI, WebSocket, HTTP Custom Payload, Corridor Probe, SOCKS5, SSH, and NAT Masquerading).
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            <strong className="font-semibold text-foreground">Not deployed:</strong> this relay stack is
            not running on 2.29.8.194 today — the live server runs WireGuard (wg0, UDP 51820) only, so
            the transports below are a deployment blueprint, not live endpoints.
          </p>
        </div>

        <div className="grid gap-1.5 sm:grid-cols-2">
          {SERVER_TRANSPORT_CAPABILITIES.map((cap) => (
            <div key={cap.id} className="rounded-xl border border-border bg-background/70 p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold text-foreground">{cap.name}</span>
                <span className="rounded bg-secondary px-1.5 py-0.5 font-mono text-[10px] text-primary">
                  {cap.ports}
                </span>
              </div>
              <p className="mt-1 text-[11px] leading-snug text-muted-foreground">{cap.purpose}</p>
            </div>
          ))}
        </div>

        <Field label="PUBLIC HOSTNAME OR IP">
          <input
            className={fieldClass}
            placeholder="2.29.8.194"
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
        <div className="tc-code p-3">
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
          className="tc-btn-primary w-full py-3"
        >
          {copiedDeployment ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
          {copiedDeployment ? "Copied" : "Copy deployment command"}
        </button>

        <div className="tc-code space-y-1.5 p-3">
          <p className="font-sans text-[11px] font-semibold tracking-wider text-primary">
            SAFE RELEASE & ATOMIC ROLLBACK (deploy-hetzner.sh)
          </p>
          <p className="font-mono text-[11px] text-muted-foreground">
            1. Bootstrap limited user: <code className="text-foreground">{safeReleaseCommands.bootstrapUser}</code>
          </p>
          <p className="font-mono text-[11px] text-muted-foreground">
            2. Deploy immutable release: <code className="text-foreground">{safeReleaseCommands.deployRelease}</code>
          </p>
          <p className="font-mono text-[11px] text-muted-foreground">
            3. Instant symlink rollback: <code className="text-foreground">{safeReleaseCommands.rollbackRelease}</code>
          </p>
        </div>

        {(() => {
          const activeContainerFile =
            SERVER_CONTAINER_FILES[activeContainerFileIdx] ?? SERVER_CONTAINER_FILES[0];
          if (!activeContainerFile) return null;
          return (
            <div className="space-y-2 pt-2">
              <p className="tc-label">
                SERVER CONTAINER FILES (relay-server/)
              </p>
              <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1">
                {SERVER_CONTAINER_FILES.map((file, idx) => (
                  <button
                    key={file.name}
                    type="button"
                    onClick={() => {
                      setActiveContainerFileIdx(idx);
                      setCopiedContainerFile(false);
                    }}
                    className={`shrink-0 rounded-lg px-2.5 py-1 font-mono text-[11px] transition-colors ${
                      idx === activeContainerFileIdx
                        ? "bg-primary text-primary-foreground"
                        : "bg-secondary text-secondary-foreground"
                    }`}
                  >
                    {file.name}
                  </button>
                ))}
              </div>
              <div className="overflow-hidden rounded-2xl border border-border bg-background">
                <div className="flex items-center justify-between gap-2 border-b border-border px-3 py-2">
                  <div className="min-w-0">
                    <p className="truncate font-mono text-[11px] font-medium text-foreground">
                      {activeContainerFile.path}
                    </p>
                    <p className="truncate text-[10px] text-muted-foreground">
                      {activeContainerFile.description}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={async () => {
                      try {
                        await navigator.clipboard.writeText(activeContainerFile.code);
                        setCopiedContainerFile(true);
                        toast.success(`${activeContainerFile.name} copied`);
                      } catch {
                        toast.error("Couldn't access the clipboard");
                      }
                    }}
                    className="flex shrink-0 items-center gap-1.5 rounded-md bg-secondary px-2.5 py-1 text-xs font-medium text-secondary-foreground"
                  >
                    {copiedContainerFile ? <Check className="h-3.5 w-3.5 text-primary" /> : <Copy className="h-3.5 w-3.5" />}
                    {copiedContainerFile ? "Copied" : "Copy"}
                  </button>
                </div>
                <pre className="max-h-64 overflow-auto p-3 font-mono text-[11px] leading-relaxed text-foreground">
                  <code>{activeContainerFile.code}</code>
                </pre>
              </div>
            </div>
          );
        })()}

        <ul className="space-y-1 text-[11px] text-muted-foreground">
          {RELAY_DEPLOYMENT_NOTES.map((note) => <li key={note}>• {note}</li>)}
        </ul>
      </div>
      <AwsPanel />
    </div>
  );
}
