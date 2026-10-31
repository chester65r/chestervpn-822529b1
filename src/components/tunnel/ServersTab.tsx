import { useCallback, useEffect, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { ArrowRight, Loader2, LogOut, Server as ServerIcon, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { supabase } from "@/integrations/supabase/client";
import {
  TRANSPORTS,
  rowToServer,
  validateServerInput,
  type RelayServer,
  type ServerErrors,
} from "@/lib/servers";

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

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setAuthReady(true);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_event, s) => setSession(s));
    return () => sub.subscription.unsubscribe();
  }, []);

  const loadServers = useCallback(async () => {
    setLoading(true);
    const { data, error } = await supabase
      .from("relay_servers")
      .select("*")
      .order("created_at", { ascending: false });
    setLoading(false);
    if (error) {
      toast.error("Couldn't load servers", { description: error.message });
      return;
    }
    setServers((data ?? []).map(rowToServer));
  }, []);

  useEffect(() => {
    if (session) void loadServers();
    else setServers([]);
  }, [session, loadServers]);

  const submitAuth = async () => {
    setAuthBusy(true);
    if (mode === "signin") {
      const { error } = await supabase.auth.signInWithPassword({ email, password });
      if (error) toast.error("Sign-in failed", { description: error.message });
    } else {
      const { data, error } = await supabase.auth.signUp({ email, password });
      if (error) toast.error("Sign-up failed", { description: error.message });
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
      user_id: session.user.id,
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
    const { error } = await supabase.from("relay_servers").delete().eq("id", server.id);
    if (error) {
      toast.error("Couldn't delete server", { description: error.message });
      return;
    }
    toast(`Deleted "${server.name}"`);
    setServers((list) => list.filter((s) => s.id !== server.id));
  };

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
              </div>
              <button
                type="button"
                title="Use in Configure"
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
    </div>
  );
}
