import { useState } from "react";
import { Activity, ArrowDown, ArrowUp, Clock, Power, RefreshCw, Server, ShieldCheck, Sparkles } from "lucide-react";
import { useServerFn } from "@tanstack/react-start";
import { Button } from "@/components/ui/button";
import { explainConnection, type ConnectionDiagnostics } from "@/lib/connection-help.functions";
import { probeHetznerServer } from "@/lib/relay-control.functions";
import { SpeedGraph } from "./SpeedGraph";
import {
  HETZNER_SERVER_SPEC,
  NETWORK_PROVIDER_PROFILES,
  formatBytes,
  formatDuration,
  formatSpeed,
  type NetworkProviderProfile,
  type TunnelProfile,
} from "@/lib/tunnel";
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
  profile: TunnelProfile;
  networkProfileId: string;
  onSelectNetworkProfile: (profile: NetworkProviderProfile) => void;
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
  profile,
  networkProfileId,
  onSelectNetworkProfile,
  onToggle,
  getDiagnostics,
}: Props) {
  const explain = useServerFn(explainConnection);
  const runHetznerProbe = useServerFn(probeHetznerServer);
  const [help, setHelp] = useState<{ text?: string; error?: string } | null>(null);
  const [asking, setAsking] = useState(false);
  const [probing, setProbing] = useState(false);
  const [probeState, setProbeState] = useState<{
    status: "online" | "degraded" | "offline";
    latencyMs: number | null;
    errorCode: string | null;
    tlsEstablished: boolean;
    tcpReachable: boolean;
    checkedAt: string;
  } | null>(null);

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

  const probeLiveServer = async () => {
    setProbing(true);
    try {
      const portNum = Number(profile.port) || HETZNER_SERVER_SPEC.tlsPort;
      const res = await runHetznerProbe({
        data: {
          port: portNum,
          sni: profile.sni || HETZNER_SERVER_SPEC.ip,
          networkProfileId,
        },
      });
      setProbeState(res);
    } catch {
      setProbeState({
        status: "offline",
        latencyMs: null,
        errorCode: "PROBE_ERROR",
        tlsEstablished: false,
        tcpReachable: false,
        checkedAt: new Date().toISOString(),
      });
    } finally {
      setProbing(false);
    }
  };

  const on = status === "connected";
  const busy = status === "connecting";
  const activeNetProfile =
    NETWORK_PROVIDER_PROFILES.find((item) => item.id === networkProfileId) ??
    NETWORK_PROVIDER_PROFILES[0];

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
        <p className="mt-1 truncate font-mono text-sm text-muted-foreground">{serverLabel}</p>
        {activeNetProfile && (
          <p className="mt-1 text-xs text-primary">
            {activeNetProfile.name} · MTU {activeNetProfile.recommendedMtu}
          </p>
        )}
        <p className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1 font-mono text-xs text-muted-foreground">
          <Clock className="h-3 w-3" /> {formatDuration(seconds)}
        </p>
      </section>

      <section className="rounded-2xl border border-primary/25 bg-card p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="min-w-0">
            <p className="flex items-center gap-1.5 text-xs font-semibold tracking-wider text-primary">
              <Server className="h-3.5 w-3.5" /> HETZNER CX23 INFRASTRUCTURE
            </p>
            <p className="mt-0.5 font-mono text-xs text-muted-foreground">
              {HETZNER_SERVER_SPEC.ip} · {HETZNER_SERVER_SPEC.os} · {HETZNER_SERVER_SPEC.vcpu} vCPU / {HETZNER_SERVER_SPEC.ramGb} GB RAM
            </p>
          </div>
          <Button
            size="sm"
            variant="secondary"
            type="button"
            onClick={() => void probeLiveServer()}
            disabled={probing}
            className="shrink-0 gap-1.5 text-xs"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${probing ? "animate-spin" : ""}`} />
            {probing ? "Probing…" : "Probe 2.29.8.194"}
          </Button>
        </div>

        {probeState && (
          <div className="mt-2.5 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-muted/60 px-3 py-2 text-xs">
            <span className="flex items-center gap-1.5 font-medium">
              <Activity className="h-3.5 w-3.5 text-primary" />
              Status:{" "}
              <span
                className={
                  probeState.status === "online"
                    ? "font-semibold text-primary"
                    : probeState.status === "degraded"
                      ? "font-semibold text-chart-4"
                      : "font-semibold text-destructive"
                }
              >
                {probeState.status.toUpperCase()}
              </span>
            </span>
            <span className="font-mono text-[11px] text-muted-foreground">
              {probeState.latencyMs !== null ? `${probeState.latencyMs} ms` : probeState.errorCode ?? "Unreachable (awaiting deploy)"}
            </span>
          </div>
        )}

        <div className="mt-3">
          <p className="mb-1.5 text-[11px] font-medium tracking-wider text-muted-foreground">
            NETWORK & TELECOM PROVIDER OPTIMIZATION
          </p>
          <div className="grid grid-cols-2 gap-1.5">
            {NETWORK_PROVIDER_PROFILES.map((item) => {
              const selected = item.id === networkProfileId && profile.ip === HETZNER_SERVER_SPEC.ip;
              return (
                <button
                  key={item.id}
                  type="button"
                  disabled={status !== "disconnected"}
                  onClick={() => onSelectNetworkProfile(item)}
                  className={`rounded-xl border px-2.5 py-2 text-left transition-colors disabled:opacity-50 ${
                    selected
                      ? "border-primary bg-primary/10 text-foreground"
                      : "border-border bg-muted/50 text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <p className="truncate text-xs font-semibold">{item.name.replace("Hetzner CX23 ", "")}</p>
                  <p className="truncate font-mono text-[10px] opacity-80">
                    {item.profile.ip}:{item.profile.port} · MTU {item.recommendedMtu}
                  </p>
                </button>
              );
            })}
          </div>
        </div>
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
