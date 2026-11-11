import { useEffect, useState } from "react";
import {
  Activity,
  ArrowDown,
  ArrowUp,
  Check,
  Clock,
  Code2,
  Copy,
  Download,
  KeyRound,
  Power,
  RefreshCw,
  Save,
  Server,
  Share2,
  ShieldCheck,
  SlidersHorizontal,
  Smartphone,
  Sparkles,
  Trash2,
  Upload,
} from "lucide-react";
import { useServerFn } from "@tanstack/react-start";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ALL_IN_ONE_ANDROID_KOTLIN } from "@/lib/all-in-one-android";
import { explainConnection, type ConnectionDiagnostics } from "@/lib/connection-help.functions";
import { probeHetznerServer } from "@/lib/relay-control.functions";
import { clientConfig, generateWireGuardKeyPair } from "@/lib/wireguard";
import { SpeedGraph } from "./SpeedGraph";
import {
  DEFAULT_PAYLOAD_TEMPLATE,
  HETZNER_SERVER_SPEC,
  NETWORK_PROVIDER_PROFILES,
  ROUTE_PRESETS,
  formatBytes,
  formatDuration,
  formatSpeed,
  parsePayload,
  visualizePayload,
  type NetworkProviderProfile,
  type ProfileErrors,
  type SavedProfile,
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
  presetId?: string;
  networkProfileId: string;
  errors?: ProfileErrors;
  exportedCode?: string | null;
  saved?: SavedProfile[];
  onPreset?: (id: string) => void;
  onSelectNetworkProfile: (profile: NetworkProviderProfile) => void;
  onChange?: (patch: Partial<TunnelProfile>) => void;
  onToggle: () => void;
  onExport?: () => void;
  onImport?: (code: string) => boolean;
  onSave?: (name: string) => boolean;
  onLoad?: (id: string) => void;
  onDelete?: (id: string) => void;
  getDiagnostics: () => ConnectionDiagnostics;
};

const STATUS_COPY: Record<TunnelStatus, string> = {
  disconnected: "DISCONNECTED",
  connecting: "CONNECTING…",
  connected: "CONNECTED",
};

const fieldClass = "tc-field";

function generateWgKey(): string {
  const bytes = new Uint8Array(32);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 32; i++) bytes[i] = (i * 37 + 11) & 0xff;
  }
  bytes[0] = (bytes[0] ?? 0) & 248;
  bytes[31] = ((bytes[31] ?? 0) & 127) | 64;
  let bin = "";
  bytes.forEach((b) => {
    bin += String.fromCharCode(b);
  });
  return btoa(bin);
}

function downloadFile(filename: string, content: string) {
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

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
  presetId = "custom",
  networkProfileId,
  errors = {},
  exportedCode = null,
  saved = [],
  onPreset,
  onSelectNetworkProfile,
  onChange,
  onToggle,
  onExport,
  onImport,
  onSave,
  onLoad,
  onDelete,
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

  // Inline Share / Save state
  const [saveName, setSaveName] = useState("");
  const [importCode, setImportCode] = useState("");
  const [importErr, setImportErr] = useState<string | null>(null);

  // Inline Quick WireGuard & All-in-One Android state
  const [wgPriv, setWgPriv] = useState("");
  const [wgPub, setWgPub] = useState("");
  const [wgServerPub, setWgServerPub] = useState("");
  const [wgPsk, setWgPsk] = useState("");
  const [wgAddress, setWgAddress] = useState("10.66.66.4/32");
  const [previewTab, setPreviewTab] = useState<"none" | "android-kt" | "wg-conf" | "deploy-sh">("none");
  const [copiedTag, setCopiedTag] = useState<string | null>(null);

  useEffect(() => {
    // A real keypair: the public key is X25519-derived from the private key,
    // so the downloaded config can actually handshake. The server key defaults
    // to the live server's real public key (still editable for other servers).
    const pair = generateWireGuardKeyPair();
    setWgPriv(pair.privateKey);
    setWgPub(pair.publicKey);
    setWgServerPub(HETZNER_SERVER_SPEC.wireguardPublicKey);
    setWgPsk(generateWgKey());
  }, []);

  const copyText = async (label: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedTag(label);
      toast.success(`${label} copied to clipboard`);
      window.setTimeout(() => setCopiedTag(null), 1500);
    } catch {
      toast.error("Couldn't access clipboard");
    }
  };

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
  const disabledEdit = status !== "disconnected";
  const activeNetProfile =
    NETWORK_PROVIDER_PROFILES.find((item) => item.id === networkProfileId) ??
    NETWORK_PROVIDER_PROFILES[0];

  const payloadPreview = visualizePayload(parsePayload(profile.payload, profile.sni || "[host]"));

  const quickWgConf =
    wgPriv && wgServerPub && wgPsk
      ? clientConfig({
          privateKey: wgPriv,
          address: wgAddress.trim() || "10.66.66.4/32",
          dns: "1.1.1.1",
          mtu: activeNetProfile?.recommendedMtu ?? 1280,
          serverPublicKey: wgServerPub,
          presharedKey: wgPsk,
          endpointHost: HETZNER_SERVER_SPEC.ip,
          endpointPort: HETZNER_SERVER_SPEC.wireguardPort,
          allowedIps: ["0.0.0.0/0", "::/0"],
          keepalive: 25,
        })
      : "";

  // Enrolls this hub's peer on the LIVE wg-quick server (wg0), with the same
  // preshared key the downloaded config carries, then persists the config.
  const wgAddressForCmd = wgAddress.trim() || "10.66.66.4/32";
  const hetznerDeployCmd = `ssh root@${HETZNER_SERVER_SPEC.ip} "umask 077; printf '%s' '${wgPsk}' > /root/.tc-psk; wg set wg0 peer '${wgPub}' preshared-key /root/.tc-psk allowed-ips ${wgAddressForCmd}; wg-quick save wg0; rm -f /root/.tc-psk"`;

  return (
    <div className="space-y-5">
      {/* 1. Unified Hero Status + Connect Action + Speed Telemetry */}
      <div className="grid gap-5 lg:grid-cols-[1.15fr_1fr]">
        <section
          className={`tc-hero relative flex flex-col justify-between overflow-hidden p-6 text-center transition-all duration-700 ${
            on ? "border-primary/40 shadow-glow" : ""
          }`}
        >
          <div>
            <div className="relative mx-auto mb-4 grid h-24 w-24 place-items-center">
              {on && <span className="absolute inset-0 animate-ring rounded-full bg-primary/30" />}
              <span
                className={`relative grid h-20 w-20 place-items-center rounded-full shadow-pop transition-colors duration-700 ${
                  on
                    ? "bg-primary text-primary-foreground"
                    : busy
                      ? "bg-accent text-accent-foreground"
                      : "bg-muted text-destructive"
                }`}
              >
                <ShieldCheck className={`h-10 w-10 ${busy ? "animate-pulse" : ""}`} />
              </span>
            </div>
            <p
              className={`font-mono text-xl font-bold tracking-[0.2em] transition-colors duration-700 ${
                on ? "text-primary" : busy ? "text-foreground" : "text-destructive"
              }`}
            >
              {STATUS_COPY[status]}
            </p>
            <p className="mt-0.5 truncate font-mono text-xs text-muted-foreground">{serverLabel}</p>
            <p className="mt-1 text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
              Simulated — no real tunnel in a browser
            </p>
            {activeNetProfile && (
              <p className="mt-1 text-xs font-medium text-primary">
                {activeNetProfile.name} · MTU {activeNetProfile.recommendedMtu}
              </p>
            )}
            <p className="tc-chip tnum mt-3 font-mono">
              <Clock className="h-3 w-3" /> {formatDuration(seconds)}
            </p>
          </div>

          <button
            type="button"
            onClick={onToggle}
            disabled={busy}
            className={`mt-5 w-full py-4 text-base font-bold tracking-widest ${
              on ? "tc-btn-danger" : "tc-btn-primary"
            }`}
          >
            <Power className="h-5 w-5" />
            {busy ? "CONNECTING…" : on ? "DISCONNECT" : "CONNECT"}
          </button>
        </section>

        <div className="flex flex-col justify-between gap-4">
          <section className="grid grid-cols-2 gap-4">
            <div className="tc-card p-4">
              <p className="flex items-center gap-1.5 text-[11px] font-medium tracking-wider text-muted-foreground">
                <ArrowDown className="h-3.5 w-3.5 text-primary" /> DOWNLOAD{" "}
                <span className="font-normal">(simulated)</span>
              </p>
              <p className="tnum mt-1.5 font-mono text-xl font-bold text-primary">{formatSpeed(downSpeed)}</p>
              <p className="tnum font-mono text-[11px] text-muted-foreground">{formatBytes(totals.rx)} total</p>
            </div>
            <div className="tc-card p-4">
              <p className="flex items-center gap-1.5 text-[11px] font-medium tracking-wider text-muted-foreground">
                <ArrowUp className="h-3.5 w-3.5 text-chart-4" /> UPLOAD{" "}
                <span className="font-normal">(simulated)</span>
              </p>
              <p className="tnum mt-1.5 font-mono text-xl font-bold text-chart-4">{formatSpeed(upSpeed)}</p>
              <p className="tnum font-mono text-[11px] text-muted-foreground">{formatBytes(totals.tx)} total</p>
            </div>
          </section>

          <section className="tc-card flex-1 p-4">
            <p className="mb-1.5 text-[11px] font-medium tracking-wider text-muted-foreground">
              LIVE BANDWIDTH <span className="font-normal">(simulated)</span>
            </p>
            <SpeedGraph down={down} up={up} active={on} />
          </section>
        </div>
      </div>

      {/* 2. Hetzner CX23 (ahmed-docker-manager #166592617) Server + 1-Tap Carrier Presets */}
      <section className="tc-card-accent p-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="min-w-0">
            <p className="tc-section-title">
              <Server className="h-3.5 w-3.5" /> HETZNER CX23 — {HETZNER_SERVER_SPEC.serverName} (#{HETZNER_SERVER_SPEC.serverId})
            </p>
            <p className="mt-0.5 font-mono text-xs text-foreground">
              IPv4: {HETZNER_SERVER_SPEC.ip} · rDNS: {HETZNER_SERVER_SPEC.reverseDns}
            </p>
            <p className="font-mono text-[11px] text-muted-foreground">
              IPv6: {HETZNER_SERVER_SPEC.ipv6Subnet} · {HETZNER_SERVER_SPEC.location} ({HETZNER_SERVER_SPEC.networkZone}) ·{" "}
              {HETZNER_SERVER_SPEC.vcpu} vCPU / {HETZNER_SERVER_SPEC.ramGb} GB RAM / {HETZNER_SERVER_SPEC.diskGb} GB Disk
            </p>
          </div>
          <div className="flex flex-wrap gap-1.5">
            <Button
              size="sm"
              variant="secondary"
              type="button"
              onClick={() => void probeLiveServer()}
              disabled={probing}
              className="gap-1.5 text-xs"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${probing ? "animate-spin" : ""}`} />
              {probing ? "Probing…" : "Probe 2.29.8.194"}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              type="button"
              onClick={() => void copyText("Hetzner peer enroll command", hetznerDeployCmd)}
              className="gap-1.5 text-xs"
            >
              {copiedTag === "Hetzner peer enroll command" ? (
                <Check className="h-3.5 w-3.5 text-primary" />
              ) : (
                <Copy className="h-3.5 w-3.5" />
              )}
              Copy Peer Enroll Cmd
            </Button>
          </div>
        </div>

        {probeState && (
          <div className="tc-code mt-3 flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-xs">
            <span className="flex items-center gap-1.5 font-medium">
              <Activity className="h-3.5 w-3.5 text-primary" />
              Live Server Status:{" "}
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
              {probeState.latencyMs !== null
                ? `${probeState.latencyMs} ms`
                : probeState.errorCode ?? "Unreachable (awaiting deploy)"}
            </span>
          </div>
        )}

        <div className="mt-3">
          <p className="mb-1.5 text-[11px] font-medium tracking-wider text-muted-foreground">
            1-TAP CARRIER & TELECOM PROVIDER MODE
          </p>
          <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-4">
            {NETWORK_PROVIDER_PROFILES.map((item) => {
              const selected = item.id === networkProfileId && profile.ip === HETZNER_SERVER_SPEC.ip;
              return (
                <button
                  key={item.id}
                  type="button"
                  disabled={disabledEdit}
                  onClick={() => onSelectNetworkProfile(item)}
                  className={`rounded-xl border px-3 py-2.5 text-left transition-all hover:border-primary/40 active:scale-[0.98] disabled:opacity-50 ${
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

      {/* 3. Merged Inline Connection Configuration + Saved Profiles / Share */}
      <div className="grid gap-5 lg:grid-cols-2">
        <section className="tc-card space-y-4 p-5">
          <div className="flex items-center justify-between gap-2">
            <p className="tc-section-title">
              <SlidersHorizontal className="h-3.5 w-3.5" /> INLINE ENDPOINT, SNI & PAYLOAD EDITOR
            </p>
            {disabledEdit && (
              <span className="font-mono text-[10px] text-muted-foreground">Disconnect to edit</span>
            )}
          </div>

          <div className="grid grid-cols-[1fr_5.5rem_1fr] gap-2">
            <label className="block">
              <span className="tc-label">SERVER IP / HOST</span>
              <input
                aria-label="Server IP or domain"
                className={fieldClass}
                value={profile.ip}
                disabled={disabledEdit}
                onChange={(e) => onChange?.({ ip: e.target.value })}
              />
              {errors.ip && <span className="mt-0.5 block text-[10px] text-destructive">{errors.ip}</span>}
            </label>
            <label className="block">
              <span className="tc-label">PORT</span>
              <input
                aria-label="Server Port"
                className={fieldClass}
                inputMode="numeric"
                value={profile.port}
                disabled={disabledEdit}
                onChange={(e) => onChange?.({ port: e.target.value })}
              />
              {errors.port && <span className="mt-0.5 block text-[10px] text-destructive">{errors.port}</span>}
            </label>
            <label className="block">
              <span className="tc-label">ROUTE PRESET</span>
              <select
                aria-label="Route preset"
                className={fieldClass}
                value={presetId}
                disabled={disabledEdit}
                onChange={(e) => onPreset?.(e.target.value)}
              >
                {ROUTE_PRESETS.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <label className="block">
            <span className="tc-label">SERVER NAME (TLS SNI)</span>
            <input
              aria-label="Server Name SNI"
              className={fieldClass}
              value={profile.sni}
              disabled={disabledEdit}
              onChange={(e) => onChange?.({ sni: e.target.value })}
            />
            {errors.sni && <span className="mt-0.5 block text-[10px] text-destructive">{errors.sni}</span>}
          </label>

          <label className="block">
            <span className="tc-label">
              HANDSHAKE PAYLOAD (<code className="font-mono text-primary">[host]</code>{" "}
              <code className="font-mono text-primary">[crlf]</code>)
            </span>
            <textarea
              aria-label="Handshake Payload"
              className={`${fieldClass} min-h-16 font-mono text-[11px]`}
              placeholder={DEFAULT_PAYLOAD_TEMPLATE}
              value={profile.payload}
              disabled={disabledEdit}
              onChange={(e) => onChange?.({ payload: e.target.value })}
            />
          </label>

          <div className="tc-code p-3">
            <p className="mb-1 text-[10px] font-medium tracking-wider text-muted-foreground">LIVE PARSED PAYLOAD</p>
            <pre className="max-h-20 overflow-x-auto whitespace-pre-wrap break-all text-[11px] text-primary">
              {payloadPreview}
            </pre>
          </div>
        </section>

        {/* 4. Merged All-in-One Android + WireGuard + Saved Profiles / Share */}
        <div className="space-y-4">
          <section className="tc-card-accent space-y-3 p-5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <p className="tc-section-title">
                  <Smartphone className="h-3.5 w-3.5" /> ALL-IN-ONE ANDROID & WIREGUARD HUB
                </p>
                <p className="text-[11px] text-muted-foreground">
                  Merged single-file Android VPN engine (<code className="font-mono">AllInOneHetznerVpn.kt</code>) &{" "}
                  <code className="font-mono">wg0.conf</code> for 2.29.8.194.
                </p>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-3">
              <Button
                size="sm"
                type="button"
                onClick={() => void copyText("AllInOneHetznerVpn.kt", ALL_IN_ONE_ANDROID_KOTLIN)}
                className="gap-1.5 text-xs"
              >
                <Code2 className="h-3.5 w-3.5" />
                Copy All-in-One .kt
              </Button>
              <Button
                size="sm"
                variant="secondary"
                type="button"
                onClick={() => downloadFile("AllInOneHetznerVpn.kt", ALL_IN_ONE_ANDROID_KOTLIN)}
                className="gap-1.5 text-xs"
              >
                <Download className="h-3.5 w-3.5" />
                Download .kt
              </Button>
              <Button
                size="sm"
                variant="secondary"
                type="button"
                onClick={() => downloadFile("ahmed-docker-manager-wg.conf", quickWgConf)}
                className="col-span-2 gap-1.5 text-xs sm:col-span-1"
              >
                <KeyRound className="h-3.5 w-3.5" />
                Download wg.conf
              </Button>
            </div>

            <div className="grid gap-2 sm:grid-cols-2">
              <label className="block">
                <span className="tc-label">
                  Client tunnel address
                </span>
                <input
                  className={fieldClass}
                  value={wgAddress}
                  onChange={(e) => setWgAddress(e.target.value)}
                />
                <span className="mt-1 block text-[10px] text-muted-foreground">
                  10.66.66.2 and .3 are already enrolled on the server — .4 is the next free address.
                </span>
              </label>
              <label className="block">
                <span className="tc-label">
                  Server public key
                </span>
                <input
                  className={fieldClass}
                  value={wgServerPub}
                  onChange={(e) => setWgServerPub(e.target.value)}
                />
                <span className="mt-1 block text-[10px] text-muted-foreground">
                  Defaults to your live server&apos;s real public key. Your device public key below is
                  derived from the generated private key — enroll it with the Peer Enroll Cmd.
                </span>
              </label>
            </div>
            <p className="tc-code break-all p-3 text-[10px] text-muted-foreground">
              Device public key: {wgPub}
            </p>

            <div className="flex flex-wrap gap-1.5">
              <Button
                size="sm"
                variant={previewTab === "android-kt" ? "default" : "outline"}
                type="button"
                onClick={() => setPreviewTab(previewTab === "android-kt" ? "none" : "android-kt")}
                className="h-7 px-2.5 font-mono text-[11px]"
              >
                {previewTab === "android-kt" ? "Hide Merged Android Code" : "Preview AllInOneHetznerVpn.kt"}
              </Button>
              <Button
                size="sm"
                variant={previewTab === "wg-conf" ? "default" : "outline"}
                type="button"
                onClick={() => setPreviewTab(previewTab === "wg-conf" ? "none" : "wg-conf")}
                className="h-7 px-2.5 font-mono text-[11px]"
              >
                {previewTab === "wg-conf" ? "Hide WireGuard Config" : "Preview WireGuard client.conf"}
              </Button>
            </div>

            {previewTab !== "none" && (
              <pre className="tc-code max-h-48 overflow-auto p-3 text-[10px] leading-relaxed text-foreground">
                <code>
                  {previewTab === "android-kt"
                    ? ALL_IN_ONE_ANDROID_KOTLIN
                    : previewTab === "wg-conf"
                      ? quickWgConf
                      : hetznerDeployCmd}
                </code>
              </pre>
            )}
          </section>

          <section className="tc-card space-y-3 p-5">
            <div className="flex items-center justify-between gap-2">
              <p className="tc-section-title">
                <Share2 className="h-3.5 w-3.5" /> QUICK SAVE & TUNNEL:// SHARE / IMPORT
              </p>
              {onExport && (
                <Button size="sm" variant="secondary" type="button" onClick={onExport} className="h-7 gap-1 text-xs">
                  <Copy className="h-3 w-3" /> Export TUNNEL://
                </Button>
              )}
            </div>

            {exportedCode && (
              <pre className="tc-code max-h-20 overflow-auto whitespace-pre-wrap break-all p-2.5 text-[10px] text-muted-foreground">
                {exportedCode}
              </pre>
            )}

            <div className="flex gap-1.5">
              <input
                aria-label="Profile save name"
                value={saveName}
                onChange={(e) => setSaveName(e.target.value)}
                placeholder="Save current profile as (e.g. Hetzner Helsinki)"
                className={fieldClass}
              />
              <Button
                size="sm"
                type="button"
                onClick={() => {
                  if (onSave?.(saveName)) setSaveName("");
                }}
                className="shrink-0 gap-1"
              >
                <Save className="h-3.5 w-3.5" /> Save
              </Button>
            </div>

            <div className="flex gap-1.5">
              <input
                aria-label="Import TUNNEL code"
                value={importCode}
                onChange={(e) => {
                  setImportCode(e.target.value);
                  setImportErr(null);
                }}
                placeholder="Paste TUNNEL://eyJpcCI6… to import"
                className={`${fieldClass} font-mono`}
              />
              <Button
                size="sm"
                variant="secondary"
                type="button"
                onClick={() => {
                  if (onImport?.(importCode)) {
                    setImportCode("");
                    setImportErr(null);
                  } else {
                    setImportErr("Invalid TUNNEL:// profile code.");
                  }
                }}
                className="shrink-0 gap-1"
              >
                <Download className="h-3.5 w-3.5" /> Import
              </Button>
            </div>
            {importErr && <p className="text-[11px] text-destructive">{importErr}</p>}

            {saved.length > 0 && (
              <ul className="max-h-28 space-y-1.5 overflow-y-auto pt-1">
                {saved.map((s) => (
                  <li
                    key={s.id}
                    className="flex items-center gap-2 rounded-xl border border-border bg-background/60 px-3 py-2 transition-colors hover:border-primary/30"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-xs font-medium">{s.name}</p>
                      <p className="truncate font-mono text-[10px] text-muted-foreground">
                        {s.profile.ip}:{s.profile.port} · {s.profile.sni}
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => onLoad?.(s.id)}
                      aria-label={`Load ${s.name}`}
                      className="rounded-lg p-1.5 text-primary transition-colors hover:bg-muted"
                    >
                      <Upload className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => onDelete?.(s.id)}
                      aria-label={`Delete ${s.name}`}
                      className="rounded-lg p-1.5 text-destructive transition-colors hover:bg-muted"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>

      {/* 5. AI Connection Diagnostics Helper */}
      <section className="tc-card p-5" aria-live="polite">
        <div className="flex items-center justify-between gap-3">
          <p className="tc-section-title">
            <Sparkles className="h-3.5 w-3.5 text-primary" /> AI CONNECTION HELPER
          </p>
          <Button size="sm" variant="secondary" onClick={ask} disabled={asking}>
            {asking ? "Checking…" : "Explain my connection"}
          </Button>
        </div>
        {help?.text && <p className="mt-3 whitespace-pre-line text-sm text-foreground">{help.text}</p>}
        {help?.error && <p className="mt-3 text-sm text-destructive">{help.error}</p>}
        {!help && !asking && (
          <p className="mt-2 text-xs text-muted-foreground">
            Reads this screen's status and your settings to explain any problem.
          </p>
        )}
      </section>
    </div>
  );
}
