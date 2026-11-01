import { useState } from "react";
import { ArrowDown, ArrowUp, Clock, Power, ShieldCheck, Sparkles } from "lucide-react";
import { useServerFn } from "@tanstack/react-start";
import { Button } from "@/components/ui/button";
import { explainConnection, type ConnectionDiagnostics } from "@/lib/connection-help.functions";
import { SpeedGraph } from "./SpeedGraph";
import { formatBytes, formatDuration, formatSpeed } from "@/lib/tunnel";
import type { TunnelStatus } from "@/lib/use-tunnel-sim";

type Props = {
  status: TunnelStatus;
  down: number[];
  up: number[];
  downSpeed: number;
  upSpeed: number;
  seconds: number;
  totals: { rx: number; tx: number };
  serverLabel: string;
  onToggle: () => void;
  getDiagnostics: () => ConnectionDiagnostics;
};

const STATUS_COPY: Record<TunnelStatus, string> = {
  disconnected: "DISCONNECTED",
  connecting: "CONNECTING…",
  connected: "CONNECTED",
};

export function HomeTab({
  status,
  down,
  up,
  downSpeed,
  upSpeed,
  seconds,
  totals,
  serverLabel,
  onToggle,
  getDiagnostics,
}: Props) {
  const explain = useServerFn(explainConnection);
  const [help, setHelp] = useState<{ text?: string; error?: string } | null>(null);
  const [asking, setAsking] = useState(false);
  const ask = async () => {
    setAsking(true);
    setHelp(null);
    try {
      setHelp(await explain({ data: getDiagnostics() }));
    } catch {
      setHelp({ error: "The AI helper couldn't answer right now." });
    } finally {
      setAsking(false);
    }
  };
  const on = status === "connected";
  const busy = status === "connecting";

  return (
    <div className="space-y-4">
      <section
        className={`relative overflow-hidden rounded-3xl border p-6 text-center shadow-card transition-all duration-700 ${
          on ? "border-primary/40 bg-card shadow-glow" : "border-border bg-card"
        }`}
      >
        <div className="relative mx-auto mb-4 grid h-24 w-24 place-items-center">
          {on && <span className="absolute inset-0 animate-ring rounded-full bg-primary/30" />}
          <span
            className={`relative grid h-20 w-20 place-items-center rounded-full transition-colors duration-700 ${
              on
                ? "bg-primary text-primary-foreground"
                : busy
                  ? "bg-accent text-accent-foreground"
                  : "bg-muted text-destructive"
            }`}
          >
            <ShieldCheck className={`h-9 w-9 ${busy ? "animate-pulse" : ""}`} />
          </span>
        </div>
        <p
          className={`font-mono text-xl font-bold tracking-widest transition-colors duration-700 ${
            on ? "text-primary" : busy ? "text-foreground" : "text-destructive"
          }`}
        >
          {STATUS_COPY[status]}
        </p>
        <p className="mt-1 truncate text-sm text-muted-foreground">{serverLabel}</p>
        <p className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1 font-mono text-xs text-muted-foreground">
          <Clock className="h-3 w-3" /> {formatDuration(seconds)}
        </p>
      </section>

      <section className="grid grid-cols-2 gap-3">
        <div className="rounded-2xl border border-border bg-card p-4">
          <p className="flex items-center gap-1.5 text-xs font-medium tracking-wider text-muted-foreground">
            <ArrowDown className="h-3.5 w-3.5 text-primary" /> DOWNLOAD
          </p>
          <p className="mt-1 font-mono text-lg font-bold text-primary">{formatSpeed(downSpeed)}</p>
          <p className="font-mono text-xs text-muted-foreground">{formatBytes(totals.rx)} total</p>
        </div>
        <div className="rounded-2xl border border-border bg-card p-4">
          <p className="flex items-center gap-1.5 text-xs font-medium tracking-wider text-muted-foreground">
            <ArrowUp className="h-3.5 w-3.5 text-chart-4" /> UPLOAD
          </p>
          <p className="mt-1 font-mono text-lg font-bold text-chart-4">{formatSpeed(upSpeed)}</p>
          <p className="font-mono text-xs text-muted-foreground">{formatBytes(totals.tx)} total</p>
        </div>
      </section>

      <section className="rounded-2xl border border-border bg-card p-4">
        <p className="mb-2 text-xs font-medium tracking-wider text-muted-foreground">
          LIVE BANDWIDTH <span className="font-normal">(simulated)</span>
        </p>
        <SpeedGraph down={down} up={up} active={on} />
      </section>

      <button
        type="button"
        onClick={onToggle}
        disabled={busy}
        className={`flex w-full items-center justify-center gap-3 rounded-2xl py-5 text-base font-bold tracking-widest transition-all duration-300 active:scale-[0.98] disabled:opacity-70 ${
          on
            ? "bg-destructive text-destructive-foreground"
            : "bg-primary text-primary-foreground shadow-glow"
        }`}
      >
        <Power className="h-5 w-5" />
        {busy ? "CONNECTING…" : on ? "DISCONNECT" : "CONNECT"}
      </button>

      <section className="rounded-2xl border border-border bg-card p-4" aria-live="polite">
        <div className="flex items-center justify-between gap-3">
          <p className="flex items-center gap-1.5 text-xs font-medium tracking-wider text-muted-foreground">
            <Sparkles className="h-3.5 w-3.5 text-primary" /> AI CONNECTION HELPER
          </p>
          <Button size="sm" variant="secondary" onClick={ask} disabled={asking}>
            {asking ? "Checking…" : "Explain my connection"}
          </Button>
        </div>
        {help?.text && <p className="mt-3 whitespace-pre-line text-sm text-foreground">{help.text}</p>}
        {help?.error && <p className="mt-3 text-sm text-destructive">{help.error}</p>}
        {!help && !asking && (
          <p className="mt-2 text-xs text-muted-foreground">Reads this screen's status and your settings to explain any problem.</p>
        )}
      </section>
    </div>
  );
}
