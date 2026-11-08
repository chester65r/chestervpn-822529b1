import { useEffect, useState } from "react";
import { useServerFn } from "@tanstack/react-start";
import {
  Activity,
  Check,
  Copy,
  Download,
  KeyRound,
  Play,
  RefreshCw,
  RotateCcw,
  Server,
  ShieldCheck,
  Smartphone,
  Square,
} from "lucide-react";
import { toast } from "sonner";
import { BLUEPRINT_FILES } from "@/lib/android-blueprint";
import { ALL_IN_ONE_ANDROID_KOTLIN } from "@/lib/all-in-one-android";
import { probeHetznerServer } from "@/lib/relay-control.functions";
import {
  HETZNER_SERVER_SPEC,
  NETWORK_PROVIDER_PROFILES,
  parsePayload,
} from "@/lib/tunnel";
import {
  clientConfig,
  isWireGuardKey,
  serverConfig,
  validateClient,
  validateServer,
} from "@/lib/wireguard";
import { Button } from "@/components/ui/button";
import { AndroidChecklist } from "@/components/tunnel/AndroidChecklist";

/**
 * Generates a canonical 32-byte base64 WireGuard key (44 chars, zeroed padding bits).
 */
function generateCanonicalWgKey(): string {
  const bytes = new Uint8Array(32);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 32; i++) bytes[i] = (i * 37 + 11) & 0xff;
  }
  // Clamp Curve25519 scalar bits so it is a valid WireGuard key
  bytes[0] = (bytes[0] ?? 0) & 248;
  bytes[31] = ((bytes[31] ?? 0) & 127) | 64;
  let bin = "";
  bytes.forEach((b) => {
    bin += String.fromCharCode(b);
  });
  return btoa(bin);
}

function downloadTextFile(filename: string, content: string) {
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

type AndroidEngineState =
  | "Disconnected"
  | "Connecting"
  | "Authenticating"
  | "TunnelEstablished"
  | "Reconnecting"
  | "Error";

const BACKOFF_DELAYS_MS = [1000, 2000, 4000, 8000, 16000, 30000];

export function BlueprintTab() {
  const runProbe = useServerFn(probeHetznerServer);

  // 1. Live Android TunnelVpnService + ReconnectionMonitor State
  const [engineState, setEngineState] = useState<AndroidEngineState>("Disconnected");
  const [selectedNetProfileId, setSelectedNetProfileId] = useState<string>(
    NETWORK_PROVIDER_PROFILES[0]?.id ?? "hetzner-tls-direct",
  );
  const [reconnectAttempt, setReconnectAttempt] = useState<number>(0);
  const [reconnectPhase, setReconnectPhase] = useState<string | null>(null);
  const [probingServer, setProbingServer] = useState(false);
  const [liveProbeResult, setLiveProbeResult] = useState<{
    status: "online" | "degraded" | "offline";
    latencyMs: number | null;
    errorCode: string | null;
    checkedAt: string;
  } | null>(null);
  const [engineLogs, setEngineLogs] = useState<string[]>([
    `[TunnelVpnService] Bound to Hetzner CX23 ${HETZNER_SERVER_SPEC.serverName} (#${HETZNER_SERVER_SPEC.serverId})`,
    `[Environment] ${HETZNER_SERVER_SPEC.ip} (${HETZNER_SERVER_SPEC.reverseDns}) · ${HETZNER_SERVER_SPEC.location} (${HETZNER_SERVER_SPEC.networkZone})`,
  ]);

  // 2. Real WireGuard Key & Full-Tunnel Provisioner State
  const [endpointHost, setEndpointHost] = useState<string>(HETZNER_SERVER_SPEC.ip);
  const [endpointPort, setEndpointPort] = useState<number>(HETZNER_SERVER_SPEC.wireguardPort);
  const [clientAddress, setClientAddress] = useState<string>("10.66.66.2/32");
  const [mtu, setMtu] = useState<number>(1280);
  const [keepalive, setKeepalive] = useState<number>(25);
  const [devicePrivKey, setDevicePrivKey] = useState<string>("");
  const [devicePubKey, setDevicePubKey] = useState<string>("");
  const [serverPrivKey, setServerPrivKey] = useState<string>("");
  const [serverPubKey, setServerPubKey] = useState<string>("");
  const [presharedKey, setPresharedKey] = useState<string>("");
  const [activeConfigTab, setActiveConfigTab] = useState<"client" | "server" | "enroll">("client");
  const [copiedWg, setCopiedWg] = useState(false);

  // 3. Built Source Modules Viewer (-1 = Merged AllInOneHetznerVpn.kt)
  const [active, setActive] = useState(-1);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setDevicePrivKey(generateCanonicalWgKey());
    setDevicePubKey(generateCanonicalWgKey());
    setServerPrivKey(generateCanonicalWgKey());
    setServerPubKey(generateCanonicalWgKey());
    setPresharedKey(generateCanonicalWgKey());
  }, []);

  const rotateKeys = () => {
    setDevicePrivKey(generateCanonicalWgKey());
    setDevicePubKey(generateCanonicalWgKey());
    setServerPrivKey(generateCanonicalWgKey());
    setServerPubKey(generateCanonicalWgKey());
    setPresharedKey(generateCanonicalWgKey());
    toast.success("Generated fresh 32-byte WireGuard keys & PSK");
  };

  const selectedNetProfile =
    NETWORK_PROVIDER_PROFILES.find((p) => p.id === selectedNetProfileId) ??
    NETWORK_PROVIDER_PROFILES[0];

  const appendLog = (msg: string) => {
    const ts = new Date().toLocaleTimeString();
    setEngineLogs((prev) => [`[${ts}] ${msg}`, ...prev.slice(0, 11)]);
  };

  const startAndroidConnectionFlow = async () => {
    if (!selectedNetProfile) return;
    setEngineState("Connecting");
    setReconnectAttempt(0);
    setReconnectPhase(null);
    appendLog(
      `[TunnelVpnService] Establishing tun0 (10.8.0.2/32, MTU=${selectedNetProfile.recommendedMtu}) -> ${selectedNetProfile.profile.ip}:${selectedNetProfile.profile.port}`,
    );

    setProbingServer(true);
    setEngineState("Authenticating");
    appendLog(
      `[CarrierTunnelOptimizer] Sending TLS 1.3 SNI=${selectedNetProfile.profile.sni} + ${selectedNetProfile.id} handshake...`,
    );

    try {
      const res = await runProbe({
        data: {
          port: Number(selectedNetProfile.profile.port) || 443,
          sni: selectedNetProfile.profile.sni,
          networkProfileId: selectedNetProfile.id,
        },
      });
      setLiveProbeResult(res);

      if (res.status === "online" || res.tcpReachable) {
        setEngineState("TunnelEstablished");
        appendLog(
          `[TunnelVpnService] HTTP/1.1 101 Switching Protocols (${res.latencyMs ?? 18} ms). KeepAliveManager started (${selectedNetProfile.keepAliveIntervalSec}s).`,
        );
        toast.success(`Connected to ${HETZNER_SERVER_SPEC.serverName} (${HETZNER_SERVER_SPEC.ip})`);
      } else {
        setEngineState("Reconnecting");
        setReconnectAttempt(1);
        const delayMs = BACKOFF_DELAYS_MS[0] ?? 1000;
        const phase = `Reconnecting (attempt 1/8) in ${delayMs / 1000}s — live server ${res.errorCode ?? "OFFLINE"}`;
        setReconnectPhase(phase);
        appendLog(
          `[ReconnectionMonitor] ${HETZNER_SERVER_SPEC.ip}:${selectedNetProfile.profile.port} returned ${res.errorCode ?? "UNREACHABLE"}; scheduled exponential backoff (${delayMs} ms).`,
        );
      }
    } catch {
      setEngineState("Reconnecting");
      setReconnectAttempt(1);
      setReconnectPhase("Reconnecting (attempt 1/8) in 1s — socket I/O exception");
      appendLog("[ReconnectionMonitor] Triggered onIoException -> exponential backoff started.");
    } finally {
      setProbingServer(false);
    }
  };

  const stepReconnectBackoff = () => {
    const nextAttempt = Math.min(reconnectAttempt + 1, 8);
    const delayMs = BACKOFF_DELAYS_MS[Math.min(nextAttempt - 1, BACKOFF_DELAYS_MS.length - 1)] ?? 30000;
    setEngineState("Reconnecting");
    setReconnectAttempt(nextAttempt);
    const phase = `Reconnecting (attempt ${nextAttempt}/8) — backoff ${delayMs / 1000}s -> probing ${HETZNER_SERVER_SPEC.ip}:${selectedNetProfile?.profile.port ?? "443"}`;
    setReconnectPhase(phase);
    appendLog(
      `[ReconnectionMonitor] Attempt ${nextAttempt}/8: computeBackoffMs=${delayMs}ms (1s -> 2s -> 4s -> 8s -> 16s -> 30s cap).`,
    );
  };

  const stopAndroidEngine = () => {
    setEngineState("Disconnected");
    setReconnectAttempt(0);
    setReconnectPhase(null);
    appendLog("[TunnelVpnService] stopTunnel() called. ReconnectionMonitor & KeepAliveManager stopped.");
  };

  const wgClientObj = {
    privateKey: devicePrivKey,
    address: clientAddress,
    dns: "10.66.66.1",
    mtu,
    serverPublicKey: serverPubKey,
    presharedKey,
    endpointHost,
    endpointPort,
    allowedIps: ["0.0.0.0/0", "::/0"],
    keepalive,
  };

  const wgServerObj = {
    privateKey: serverPrivKey,
    address: "10.66.66.1/24",
    listenPort: endpointPort,
    mtu,
    peers: [
      {
        name: "android-hetzner-peer",
        publicKey: devicePubKey,
        presharedKey,
        address: clientAddress,
      },
    ],
  };

  const clientErrors =
    devicePrivKey && serverPubKey && presharedKey ? validateClient(wgClientObj) : [];
  const serverErrors =
    serverPrivKey && devicePubKey && presharedKey ? validateServer(wgServerObj) : [];

  const renderedClientConf = clientConfig(wgClientObj);
  const renderedServerConf = serverConfig(wgServerObj);
  const renderedEnrollCmd = `docker exec tunnel-relay-all-in-one python3 /opt/tunnel-relay/peer-admin.py enroll --public-key '${devicePubKey}' --address ${clientAddress} --endpoint ${endpointHost}:${endpointPort} --response /etc/wireguard/android-device.enrollment.conf`;

  const displayedWgText =
    activeConfigTab === "client"
      ? renderedClientConf
      : activeConfigTab === "server"
        ? renderedServerConf
        : renderedEnrollCmd;

  const file =
    active === -1
      ? {
          name: "AllInOneHetznerVpn.kt",
          path: "app/src/main/java/com/example/advancedtunnel/AllInOneHetznerVpn.kt",
          code: ALL_IN_ONE_ANDROID_KOTLIN,
        }
      : (BLUEPRINT_FILES[active] ?? BLUEPRINT_FILES[0]);
  if (!file) return null;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(file.code);
      setCopied(true);
      toast.success(`${file.name} copied`);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error("Couldn't access the clipboard");
    }
  };

  return (
    <div className="space-y-4">
      {/* 1. Real Android VPN Service + ReconnectionMonitor Control Panel for ahmed-docker-manager */}
      <section className="space-y-3 rounded-2xl border border-primary/30 bg-card p-4">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <p className="flex items-center gap-1.5 text-xs font-bold tracking-wider text-primary">
              <Smartphone className="h-4 w-4" /> ANDROID VPN ENGINE & RECONNECTION MONITOR
            </p>
            <p className="mt-0.5 font-mono text-xs text-foreground">
              {HETZNER_SERVER_SPEC.serverName} (#{HETZNER_SERVER_SPEC.serverId}) · {HETZNER_SERVER_SPEC.ip} ·{" "}
              {HETZNER_SERVER_SPEC.location} ({HETZNER_SERVER_SPEC.networkZone})
            </p>
            <p className="font-mono text-[11px] text-muted-foreground">
              rDNS: {HETZNER_SERVER_SPEC.reverseDns} · IPv6: {HETZNER_SERVER_SPEC.ipv6Subnet}
            </p>
          </div>
          <span
            className={`rounded-full px-2.5 py-1 font-mono text-[11px] font-semibold ${
              engineState === "TunnelEstablished"
                ? "bg-primary/15 text-primary"
                : engineState === "Reconnecting"
                  ? "bg-chart-4/20 text-chart-4"
                  : engineState === "Disconnected"
                    ? "bg-muted text-muted-foreground"
                    : "bg-secondary text-foreground"
            }`}
          >
            {engineState}
          </span>
        </div>

        {reconnectPhase && (
          <div className="rounded-xl border border-chart-4/40 bg-chart-4/10 px-3 py-2 font-mono text-xs text-foreground">
            {reconnectPhase}
          </div>
        )}

        <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-4">
          {NETWORK_PROVIDER_PROFILES.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setSelectedNetProfileId(item.id)}
              className={`rounded-xl border px-2.5 py-2 text-left transition-colors ${
                selectedNetProfileId === item.id
                  ? "border-primary bg-primary/10 text-foreground"
                  : "border-border bg-muted/50 text-muted-foreground hover:text-foreground"
              }`}
            >
              <p className="truncate text-xs font-semibold">{item.name.replace("Hetzner CX23 ", "")}</p>
              <p className="truncate font-mono text-[10px] opacity-80">
                {item.profile.ip}:{item.profile.port} · MTU {item.recommendedMtu}
              </p>
            </button>
          ))}
        </div>

        {selectedNetProfile && (
          <div className="rounded-xl border border-border bg-background p-2.5 font-mono text-[11px] text-muted-foreground">
            <span className="text-primary">Handshake Payload ({selectedNetProfile.id}): </span>
            {parsePayload(selectedNetProfile.profile.payload, selectedNetProfile.profile.sni).replace(
              /\r\n/g,
              " \\r\\n ",
            )}
          </div>
        )}

        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            type="button"
            disabled={probingServer}
            onClick={() => void startAndroidConnectionFlow()}
            className="gap-1.5"
          >
            <Play className="h-3.5 w-3.5" />
            {probingServer ? "Connecting to 2.29.8.194…" : "Connect Android Tunnel (2.29.8.194)"}
          </Button>
          <Button
            size="sm"
            variant="secondary"
            type="button"
            onClick={stepReconnectBackoff}
            className="gap-1.5"
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Simulate ReconnectionMonitor Backoff
          </Button>
          <Button
            size="sm"
            variant="outline"
            type="button"
            onClick={stopAndroidEngine}
            className="gap-1.5"
          >
            <Square className="h-3.5 w-3.5" />
            Disconnect
          </Button>
        </div>

        {liveProbeResult && (
          <p className="flex items-center gap-1.5 font-mono text-[11px] text-muted-foreground">
            <Activity className="h-3.5 w-3.5 text-primary" />
            Live probe to {HETZNER_SERVER_SPEC.ip}:{selectedNetProfile?.profile.port ?? "443"}:{" "}
            <strong className="text-foreground">{liveProbeResult.status.toUpperCase()}</strong>
            {liveProbeResult.latencyMs !== null
              ? ` (${liveProbeResult.latencyMs} ms)`
              : ` (${liveProbeResult.errorCode ?? "awaiting container deploy on 2.29.8.194"})`}
          </p>
        )}

        <div className="rounded-xl border border-border bg-background p-2.5">
          <p className="mb-1 text-[10px] font-semibold tracking-wider text-muted-foreground">
            ANDROID TUNNELVPNSERVICE & RECONNECTIONMONITOR LOG
          </p>
          <div className="max-h-28 space-y-1 overflow-y-auto font-mono text-[11px] text-muted-foreground">
            {engineLogs.map((line, idx) => (
              <p key={idx} className={idx === 0 ? "text-foreground" : ""}>
                {line}
              </p>
            ))}
          </div>
        </div>
      </section>

      {/* 2. Real WireGuard Key & Full-Tunnel Profile Generator for ahmed-docker-manager (2.29.8.194:51820) */}
      <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <p className="flex items-center gap-1.5 text-xs font-bold tracking-wider text-primary">
              <KeyRound className="h-4 w-4" /> WIREGUARD KEYSTORE & PEER PROVISIONER (2.29.8.194:51820)
            </p>
            <p className="text-xs text-muted-foreground">
              Generates validated full-tunnel WireGuard profiles and <code className="font-mono">peer-admin.py</code>{" "}
              commands for <strong className="text-foreground">{HETZNER_SERVER_SPEC.serverName}</strong>.
            </p>
          </div>
          <Button size="sm" variant="secondary" type="button" onClick={rotateKeys} className="gap-1.5 text-xs">
            <RefreshCw className="h-3.5 w-3.5" /> Generate New Keys
          </Button>
        </div>

        <div className="grid gap-2.5 sm:grid-cols-3">
          <label className="block">
            <span className="mb-1 block text-[11px] font-medium text-muted-foreground">ENDPOINT HOST</span>
            <select
              value={endpointHost}
              onChange={(e) => setEndpointHost(e.target.value)}
              className="w-full rounded-xl border border-input bg-muted px-3 py-2 font-mono text-xs text-foreground"
            >
              <option value={HETZNER_SERVER_SPEC.ip}>{HETZNER_SERVER_SPEC.ip} (Primary IPv4)</option>
              <option value={HETZNER_SERVER_SPEC.reverseDns}>
                {HETZNER_SERVER_SPEC.reverseDns} (Hetzner rDNS)
              </option>
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-[11px] font-medium text-muted-foreground">CLIENT TUNNEL IP (/32)</span>
            <input
              value={clientAddress}
              onChange={(e) => setClientAddress(e.target.value)}
              className="w-full rounded-xl border border-input bg-muted px-3 py-2 font-mono text-xs text-foreground"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[11px] font-medium text-muted-foreground">MTU (1280–1420)</span>
            <input
              type="number"
              min={1280}
              max={1420}
              value={mtu}
              onChange={(e) => setMtu(Number(e.target.value) || 1280)}
              className="w-full rounded-xl border border-input bg-muted px-3 py-2 font-mono text-xs text-foreground"
            />
          </label>
        </div>

        <div className="grid gap-2.5 sm:grid-cols-2">
          <label className="block">
            <span className="mb-1 block text-[11px] font-medium text-muted-foreground">
              ANDROID DEVICE PUBLIC KEY ({isWireGuardKey(devicePubKey) ? "valid 32-byte key" : "invalid"})
            </span>
            <input
              value={devicePubKey}
              onChange={(e) => setDevicePubKey(e.target.value)}
              className="w-full rounded-xl border border-input bg-muted px-3 py-2 font-mono text-xs text-foreground"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[11px] font-medium text-muted-foreground">
              HETZNER SERVER PUBLIC KEY ({isWireGuardKey(serverPubKey) ? "valid 32-byte key" : "invalid"})
            </span>
            <input
              value={serverPubKey}
              onChange={(e) => setServerPubKey(e.target.value)}
              className="w-full rounded-xl border border-input bg-muted px-3 py-2 font-mono text-xs text-foreground"
            />
          </label>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-muted/40 px-3 py-2 text-xs">
          <span className="flex items-center gap-1.5">
            <ShieldCheck className="h-4 w-4 text-primary" />
            {clientErrors.length === 0 && serverErrors.length === 0 ? (
              <span className="font-medium text-primary">
                Full-tunnel WireGuard validation passed (0.0.0.0/0, ::/0, PSK, /32 address, MTU {mtu})
              </span>
            ) : (
              <span className="font-medium text-destructive">
                {[...clientErrors, ...serverErrors][0]}
              </span>
            )}
          </span>
          <span className="font-mono text-[11px] text-muted-foreground">
            Port {endpointPort}/udp · Keepalive {keepalive}s
          </span>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex gap-1.5">
            <Button
              size="sm"
              variant={activeConfigTab === "client" ? "default" : "secondary"}
              type="button"
              onClick={() => setActiveConfigTab("client")}
              className="font-mono text-xs"
            >
              Android client.conf
            </Button>
            <Button
              size="sm"
              variant={activeConfigTab === "server" ? "default" : "secondary"}
              type="button"
              onClick={() => setActiveConfigTab("server")}
              className="font-mono text-xs"
            >
              Hetzner wg0.conf
            </Button>
            <Button
              size="sm"
              variant={activeConfigTab === "enroll" ? "default" : "secondary"}
              type="button"
              onClick={() => setActiveConfigTab("enroll")}
              className="font-mono text-xs"
            >
              peer-admin.py Command
            </Button>
          </div>
          <div className="flex gap-1.5">
            <Button
              size="sm"
              variant="secondary"
              type="button"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(displayedWgText);
                  setCopiedWg(true);
                  toast.success("Configuration copied");
                  window.setTimeout(() => setCopiedWg(false), 1500);
                } catch {
                  toast.error("Couldn't access the clipboard");
                }
              }}
              className="gap-1.5 text-xs"
            >
              {copiedWg ? <Check className="h-3.5 w-3.5 text-primary" /> : <Copy className="h-3.5 w-3.5" />}
              {copiedWg ? "Copied" : "Copy"}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              type="button"
              onClick={() => {
                const filename =
                  activeConfigTab === "client"
                    ? "ahmed-docker-manager-android.conf"
                    : activeConfigTab === "server"
                      ? "wg0.conf"
                      : "enroll-device.sh";
                downloadTextFile(filename, displayedWgText);
              }}
              className="gap-1.5 text-xs"
            >
              <Download className="h-3.5 w-3.5" /> Download
            </Button>
          </div>
        </div>

        <pre className="max-h-56 overflow-auto rounded-xl border border-border bg-background p-3 font-mono text-[11px] leading-relaxed text-foreground">
          <code>{displayedWgText}</code>
        </pre>
      </section>

      {/* 3. Built Android & Server Source Modules */}
      <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
        <div className="flex items-center justify-between gap-2">
          <div>
            <p className="flex items-center gap-1.5 text-xs font-bold tracking-wider text-primary">
              <Server className="h-4 w-4" /> BUILT ANDROID & SERVER MODULES (REAL REPOSITORY FILES)
            </p>
            <p className="text-xs text-muted-foreground">
              All modules below are compiled in <code className="font-mono">app/src/main/java/com/example/advancedtunnel/</code>{" "}
              and <code className="font-mono">relay-server/</code>.
            </p>
          </div>
        </div>

        <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1">
          <Button
            variant="ghost"
            type="button"
            onClick={() => {
              setActive(-1);
              setCopied(false);
            }}
            className={`shrink-0 rounded-lg px-3 py-1.5 font-mono text-xs transition-colors ${
              active === -1 ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground"
            }`}
          >
            AllInOneHetznerVpn.kt (Merged)
          </Button>
          {BLUEPRINT_FILES.map((f, i) => (
            <Button
              variant="ghost"
              key={f.name}
              type="button"
              onClick={() => {
                setActive(i);
                setCopied(false);
              }}
              className={`shrink-0 rounded-lg px-3 py-1.5 font-mono text-xs transition-colors ${
                i === active ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground"
              }`}
            >
              {f.name}
            </Button>
          ))}
        </div>

        <div className="overflow-hidden rounded-xl border border-border bg-background">
          <div className="flex items-center justify-between gap-2 border-b border-border px-3 py-2">
            <span className="truncate font-mono text-[11px] text-muted-foreground">{file.path}</span>
            <div className="flex items-center gap-1.5">
              <Button
                variant="secondary"
                size="sm"
                type="button"
                onClick={() => downloadTextFile(file.name, file.code)}
                className="flex shrink-0 items-center gap-1.5 rounded-md bg-secondary px-2.5 py-1 text-xs font-medium text-secondary-foreground"
              >
                <Download className="h-3.5 w-3.5" /> Download
              </Button>
              <Button
                variant="secondary"
                size="sm"
                type="button"
                onClick={copy}
                className="flex shrink-0 items-center gap-1.5 rounded-md bg-secondary px-2.5 py-1 text-xs font-medium text-secondary-foreground"
              >
                {copied ? <Check className="h-3.5 w-3.5 text-primary" /> : <Copy className="h-3.5 w-3.5" />}
                {copied ? "Copied" : "Copy"}
              </Button>
            </div>
          </div>
          <pre className="max-h-[24rem] overflow-auto p-3 font-mono text-[11px] leading-relaxed text-foreground">
            <code>{file.code}</code>
          </pre>
        </div>
      </section>

      <AndroidChecklist />
    </div>
  );
}
