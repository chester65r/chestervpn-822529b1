import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Home, Server, Share2, ShieldCheck, SlidersHorizontal, Plug, Code2 } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { useWebMCP } from "use-webmcp-tool";
import { runWebMCPTool } from "@/lib/webmcp-telemetry";

import { HomeTab } from "@/components/tunnel/HomeTab";
import { ConfigTab } from "@/components/tunnel/ConfigTab";
import { ShareTab } from "@/components/tunnel/ShareTab";
import { BlueprintTab } from "@/components/tunnel/BlueprintTab";
import { ServersTab } from "@/components/tunnel/ServersTab";
import { AdditionalAccount } from "@/components/tunnel/AdditionalAccount";
import { ServiceOverview } from "@/components/tunnel/ServiceOverview";
import { useTunnelSim } from "@/lib/use-tunnel-sim";
import { useAdditionalSession } from "@/lib/use-additional-session";
import {
  HETZNER_DEFAULT_PROFILE,
  NETWORK_PROVIDER_PROFILES,
  ROUTE_PRESETS,
  SAVED_PROFILES_KEY,
  buildValidatedOutboundPayloadJson,
  exportProfile,
  bytesPerSecToMbps,
  importProfile,
  parseSavedProfiles,
  resolveInitialActiveProfile,
  serializeSavedProfiles,
  validateProfile,
  type NetworkProviderProfile,
  type ProfileErrors,
  type SavedProfile,
  type TunnelProfile,
} from "@/lib/tunnel";

export const Route = createFileRoute("/")({
  staticData: { sitemap: true },
  head: () => ({
    meta: [
      { title: "Tunnel Console — VPN dashboard & Android blueprint" },
      {
        name: "description",
        content:
          "Mobile-first tunnel dashboard with simulated live bandwidth, TUNNEL:// profile sharing and an Android Studio VPN blueprint.",
      },
      { property: "og:title", content: "Tunnel Console — VPN dashboard & Android blueprint" },
      {
        property: "og:description",
        content:
          "Simulated live bandwidth, profile import/export and copyable Android VPN service code.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: Index,
});

type Tab = "home" | "config" | "servers" | "share" | "android";

const TABS: { id: Tab; label: string; icon: typeof Home }[] = [
  { id: "home", label: "Home", icon: Home },
  { id: "config", label: "Configure", icon: SlidersHorizontal },
  { id: "servers", label: "Servers", icon: Server },
  { id: "share", label: "Share", icon: Share2 },
  { id: "android", label: "Android", icon: Code2 },
];

const STORAGE_KEY = "tunnel-console-profile";

function Index() {
  const [tab, setTab] = useState<Tab>("home");
  const [profile, setProfile] = useState<TunnelProfile>(HETZNER_DEFAULT_PROFILE);
  const [presetId, setPresetId] = useState("stc");
  const [networkProfileId, setNetworkProfileId] = useState<string>(
    NETWORK_PROVIDER_PROFILES[0]?.id ?? "stc-ksa-5g-cgnat",
  );
  const [errors, setErrors] = useState<ProfileErrors>({});
  const [exported, setExported] = useState<string | null>(null);
  const [saved, setSaved] = useState<SavedProfile[]>([]);
  const [loaded, setLoaded] = useState(false);
  const sim = useTunnelSim();
  // Route-level: the additional-account session (and its sim-transition
  // history recorder) must survive tab switches, so it is owned here and
  // not by the Servers-tab section that merely displays it.
  const additional = useAdditionalSession(sim.status);

  useEffect(() => {
    try {
      const restored = resolveInitialActiveProfile(localStorage.getItem(STORAGE_KEY));
      setProfile(restored.profile);
      setPresetId(restored.presetId);
    } catch {
      /* Storage may be blocked; the dashboard still works in memory. */
    }
    try {
      setSaved(parseSavedProfiles(localStorage.getItem(SAVED_PROFILES_KEY)));
    } catch {
      /* ignore corrupt storage */
    }
    setLoaded(true);
  }, []);

  useEffect(() => {
    if (!loaded) return;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ profile, presetId }));
    } catch {
      toast.error("Changes can't be stored on this device", {
        description: "Export your profile to keep a copy.",
      });
    }
  }, [loaded, profile, presetId]);

  useEffect(() => {
    if (!loaded) return;
    try {
      localStorage.setItem(SAVED_PROFILES_KEY, serializeSavedProfiles(saved));
    } catch {
      toast.error("Saved profiles can't be stored on this device", {
        description: "Export your profile to keep a copy.",
      });
    }
  }, [loaded, saved]);

  useEffect(() => setExported(null), [profile]);

  const canEdit = () => {
    if (sim.status === "disconnected") return true;
    toast.error("Disconnect before changing the configuration");
    return false;
  };

  const change = (patch: Partial<TunnelProfile>) => {
    setProfile((p) => {
      const merged: TunnelProfile = { ...p, ...patch };
      if (
        !("payload" in patch) &&
        ("ip" in patch ||
          "port" in patch ||
          "sni" in patch ||
          "protocol" in patch ||
          "cdnHost" in patch ||
          "wsPath" in patch)
      ) {
        const proto =
          merged.protocol ?? (Number(merged.port) === 8443 ? "trojan-tls" : "vless-reality");
        merged.payload = buildValidatedOutboundPayloadJson({
          protocol: proto,
          serverHost: merged.ip.trim() || "2.29.8.194",
          serverPort: Number(merged.port) || 443,
          sni: merged.sni.trim() || "www.stc.com.sa",
          cdnHost: merged.cdnHost || merged.sni || "cdn.mobily.com.sa",
          wsPath: merged.wsPath || "/cdn-ws",
          utlsFingerprint: merged.utlsFingerprint ?? "chrome",
          sniSplitMinBytes: merged.sniSplitMinBytes ?? 5,
          sniSplitMaxBytes: merged.sniSplitMaxBytes ?? 15,
        });
      }
      return merged;
    });
    setErrors((e) => {
      const next = { ...e };
      (Object.keys(patch) as (keyof TunnelProfile)[]).forEach((k) => delete next[k]);
      return next;
    });
    if ("sni" in patch) setPresetId("custom");
  };

  const choosePreset = (id: string) => {
    setPresetId(id);
    const preset = ROUTE_PRESETS.find((p) => p.id === id);
    if (preset && preset.sni) {
      if (id === "stc") setNetworkProfileId("stc-ksa-5g-cgnat");
      if (id === "mobily") setNetworkProfileId("mobily-ksa-lte-bypass");
      setProfile((p) => {
        const nextProto = preset.protocol ?? "vless-reality";
        const nextSni = preset.sni;
        const nextCdnHost = preset.cdnHost || nextSni;
        const nextWsPath = preset.wsPath || "/cdn-ws";
        const nextUtls = preset.utlsFingerprint ?? "chrome";
        const nextSplitMin = preset.sniSplitMinBytes ?? 5;
        const nextSplitMax = preset.sniSplitMaxBytes ?? 15;
        return {
          ...p,
          sni: nextSni,
          protocol: nextProto,
          cdnHost: nextCdnHost,
          wsPath: nextWsPath,
          utlsFingerprint: nextUtls,
          sniSplitMinBytes: nextSplitMin,
          sniSplitMaxBytes: nextSplitMax,
          payload: buildValidatedOutboundPayloadJson({
            protocol: nextProto,
            serverHost: p.ip.trim() || "2.29.8.194",
            serverPort: Number(p.port) || 443,
            sni: nextSni,
            cdnHost: nextCdnHost,
            wsPath: nextWsPath,
            utlsFingerprint: nextUtls,
            sniSplitMinBytes: nextSplitMin,
            sniSplitMaxBytes: nextSplitMax,
          }),
        };
      });
      setErrors((e) => {
        const { sni: _omit, ...rest } = e;
        return rest;
      });
    }
  };

  const applyNetworkProfile = (netProfile: NetworkProviderProfile) => {
    if (!canEdit()) return;
    setNetworkProfileId(netProfile.id);
    setProfile(netProfile.profile);
    if (netProfile.id === "stc-ksa-5g-cgnat") {
      setPresetId("stc");
    } else if (netProfile.id === "mobily-ksa-lte-bypass") {
      setPresetId("mobily");
    } else {
      setPresetId("custom");
    }
    setErrors({});
    toast.success(`Applied ${netProfile.name}`, {
      description: `Target: ${netProfile.profile.ip}:${netProfile.profile.port} · MTU ${netProfile.recommendedMtu}`,
    });
  };

  const checkProfile = () => {
    const found = validateProfile(profile);
    setErrors(found);
    const first = Object.values(found)[0];
    if (first) {
      toast.error(first, { description: "Fix the highlighted field." });
      if (tab !== "home" && tab !== "config") setTab("home");
      return false;
    }
    return true;
  };

  const [lastEvent, setLastEvent] = useState("No connection attempt yet");
  const toggle = () => {
    if (sim.status === "connected") {
      sim.disconnect();
      setLastEvent("User disconnected the tunnel");
      toast("Tunnel disconnected");
    } else if (checkProfile()) {
      setLastEvent("Connect started");
      sim.connect();
    } else {
      setLastEvent("Connect blocked: profile failed validation");
    }
  };
  const getDiagnostics = () => ({
    status: sim.status,
    serverConfigured: !!profile.ip.trim(),
    port: profile.port.slice(0, 10),
    sniSet: !!profile.sni.trim(),
    payloadSet: !!profile.payload.trim(),
    profileErrors: Object.fromEntries(
      Object.entries(validateProfile(profile)).map(([k, v]) => [k, String(v).slice(0, 200)]),
    ),
    browserOnline: typeof navigator === "undefined" ? true : navigator.onLine,
    sessionSeconds: sim.seconds,
    downloadMbps: bytesPerSecToMbps(sim.downSpeed),
    uploadMbps: bytesPerSecToMbps(sim.upSpeed),
    lastEvent,
  });

  const doExport = async () => {
    if (!checkProfile()) return;
    const code = exportProfile(profile);
    setExported(code);
    try {
      await navigator.clipboard.writeText(code);
      toast.success("Profile code copied to clipboard");
    } catch {
      toast.message("Couldn't access the clipboard", {
        description: "Copy the code shown below instead.",
      });
    }
  };

  const doImport = (code: string) => {
    if (!canEdit()) return false;
    const imported = importProfile(code);
    if (!imported) {
      toast.error("Invalid profile code");
      return false;
    }
    setProfile(imported);
    const match = ROUTE_PRESETS.find((p) => p.sni && p.sni === imported.sni);
    setPresetId(match?.id ?? "custom");
    setErrors({});
    toast.success("Profile imported");
    if (tab !== "home") setTab("config");
    return true;
  };

  const saveCurrent = (name: string) => {
    if (!checkProfile()) return false;
    const entry: SavedProfile = {
      id: crypto.randomUUID(),
      name: name.trim() || `${profile.ip}:${profile.port}`,
      presetId,
      profile,
      savedAt: Date.now(),
    };
    setSaved((list) => [entry, ...list]);
    toast.success(`Saved "${entry.name}"`);
    return true;
  };

  const loadSaved = (id: string) => {
    if (!canEdit()) return;
    const entry = saved.find((s) => s.id === id);
    if (!entry) return;
    setProfile(entry.profile);
    setPresetId(entry.presetId);
    setErrors({});
    toast.success(`Loaded "${entry.name}"`);
    if (tab !== "home") setTab("config");
  };

  const deleteSaved = (id: string) => {
    const entry = saved.find((s) => s.id === id);
    setSaved((list) => list.filter((s) => s.id !== id));
    if (entry) toast(`Deleted "${entry.name}"`);
  };

  const useServer = (host: string, port: number) => {
    if (!canEdit()) return;
    setProfile((p) => ({ ...p, ip: host, port: String(port), sni: host }));
    setPresetId("custom");
    setErrors({});
    toast.success("Server applied to active configuration");
    setTab("home");
  };

  const serverLabel = profile.ip
    ? `${profile.ip}:${profile.port || "443"}`
    : "No server configured";

  function openTab(value: Tab) {
    if (!TABS.some((item) => item.id === value))
      throw new Error("Unknown screen; expected home, config, servers, share, or android.");
    setTab(value);
  }
  useWebMCP({
    name: "open_workspace_screen",
    description:
      "Open Home, Configure, Servers, Share or Android without starting a VPN or changing saved data.",
    inputSchema: {
      type: "object",
      properties: {
        screen: {
          type: "string",
          enum: ["home", "config", "servers", "share", "android"],
          description: "The workspace screen to open.",
        },
      },
      required: ["screen"],
    },
    execute: ({ screen }: { screen: Tab }) =>
      runWebMCPTool("open_workspace_screen", () => {
        openTab(screen);
        return `Opened ${screen}.`;
      }),
  });
  useWebMCP({
    name: "read_workspace_status",
    description:
      "Read the open screen and, on Home only, the simulated tunnel status and current displayed speeds.",
    annotations: { readOnlyHint: true },
    execute: () =>
      runWebMCPTool("read_workspace_status", () =>
        tab === "home"
          ? `Screen: home. Simulated connection: ${sim.status}. Download: ${bytesPerSecToMbps(sim.downSpeed).toFixed(2)} Mbps. Upload: ${bytesPerSecToMbps(sim.upSpeed).toFixed(2)} Mbps. This is not a real VPN.`
          : `Screen: ${tab}.`,
      ),
  });

  return (
    <div className="tc-app-backdrop min-h-dvh">
      <div className="tc-workspace flex h-dvh w-full flex-col overflow-hidden">
        <header className="tc-topbar flex items-center justify-between gap-3 px-5 py-4 sm:px-8 lg:px-10">
          <div className="flex min-w-0 items-center gap-3">
            <span className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl bg-primary/10 text-primary ring-1 ring-inset ring-primary/20">
              <ShieldCheck className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold leading-tight tracking-tight">
                Tunnel Console
              </h1>
              <p className="truncate text-xs text-muted-foreground">
                VPN · WireGuard · Hetzner CX23
              </p>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
            <Button variant="ghost" size="icon" className="rounded-xl" asChild>
              <Link
                to="/connect"
                aria-label="Connect your assistant"
                title="Connect your assistant"
              >
                <Plug className="h-4 w-4" />
              </Link>
            </Button>
            <span
              role="status"
              aria-label={`Tunnel ${sim.status}`}
              title={`Tunnel ${sim.status}`}
              className={`h-2.5 w-2.5 rounded-full ring-4 ring-white/5 transition-all duration-500 ${
                sim.status === "connected"
                  ? "bg-primary shadow-glow"
                  : sim.status === "connecting"
                    ? "animate-pulse bg-chart-4"
                    : "bg-destructive"
              }`}
            />
          </div>
        </header>

        <div className="tc-workspace-body flex min-h-0 flex-1">
          <aside
            className="tc-sidebar hidden w-64 shrink-0 flex-col border-r border-border px-4 py-6 lg:flex"
            aria-label="Primary navigation"
          >
            <p className="tc-sidebar-label px-3 pb-3">Workspace</p>
            <div className="flex flex-col gap-1">
              {TABS.map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  type="button"
                  onClick={() => openTab(id)}
                  aria-current={tab === id ? "page" : undefined}
                  className={`tc-sidebar-link flex items-center gap-3 rounded-xl px-3 py-3 text-sm font-medium transition-colors ${tab === id ? "bg-primary/10 text-primary" : "text-muted-foreground hover:bg-muted/60 hover:text-foreground"}`}
                >
                  <Icon className="h-4 w-4" />
                  {label}
                </button>
              ))}
            </div>
            <div className="mt-auto rounded-2xl border border-border bg-card/60 p-4">
              <p className="text-xs font-semibold text-foreground">Private by design</p>
              <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                Your tunnel status and server controls, all in one place.
              </p>
            </div>
          </aside>
          <main className="tc-main min-h-0 flex-1 overflow-y-auto px-5 pb-6 pt-5 sm:px-8 lg:px-10 lg:py-8">
            {tab === "home" && (
              <HomeTab
                status={sim.status}
                down={sim.down}
                up={sim.up}
                downSpeed={sim.downSpeed}
                upSpeed={sim.upSpeed}
                seconds={sim.seconds}
                totals={sim.totals}
                serverLabel={serverLabel}
                profile={profile}
                presetId={presetId}
                networkProfileId={networkProfileId}
                errors={errors}
                exportedCode={exported}
                saved={saved}
                onPreset={choosePreset}
                onSelectNetworkProfile={applyNetworkProfile}
                onChange={change}
                onToggle={toggle}
                onExport={doExport}
                onImport={doImport}
                onSave={saveCurrent}
                onLoad={loadSaved}
                onDelete={deleteSaved}
                getDiagnostics={getDiagnostics}
              />
            )}
            {tab === "config" && (
              <ConfigTab
                profile={profile}
                presetId={presetId}
                networkProfileId={networkProfileId}
                errors={errors}
                disabled={sim.status !== "disconnected"}
                onPreset={choosePreset}
                onSelectNetworkProfile={applyNetworkProfile}
                onChange={change}
              />
            )}
            {tab === "servers" && (
              <>
                <ServiceOverview />
                <AdditionalAccount session={additional} />
                <h2 className="tc-section-title mb-3 mt-6">Cloud relay servers</h2>
                <ServersTab onUseServer={useServer} />
              </>
            )}
            {tab === "share" && (
              <ShareTab
                exportedCode={exported}
                saved={saved}
                onExport={doExport}
                onImport={doImport}
                onSave={saveCurrent}
                onLoad={loadSaved}
                onDelete={deleteSaved}
              />
            )}
            {tab === "android" && <BlueprintTab />}
          </main>
        </div>

        <nav
          aria-label="Workspace"
          className="tc-mobile-nav grid shrink-0 grid-cols-5 gap-1 border-t border-border bg-card/80 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-2 backdrop-blur lg:hidden"
        >
          {TABS.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              onClick={() => openTab(id)}
              aria-current={tab === id ? "page" : undefined}
              className={`flex flex-col items-center gap-1 rounded-2xl px-0 py-2 text-[11px] font-medium transition-colors ${
                tab === id
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-muted/60 hover:text-foreground"
              }`}
            >
              <Icon className="h-5 w-5" />
              {label}
            </button>
          ))}
        </nav>
      </div>
    </div>
  );
}
