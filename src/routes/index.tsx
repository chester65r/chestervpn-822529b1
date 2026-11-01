import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Home, Server, Share2, SlidersHorizontal, Plug } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { useWebMCP } from "use-webmcp-tool";
import { runWebMCPTool } from "@/lib/webmcp-telemetry";

import { HomeTab } from "@/components/tunnel/HomeTab";
import { ConfigTab } from "@/components/tunnel/ConfigTab";
import { ShareTab } from "@/components/tunnel/ShareTab";
import { ServersTab } from "@/components/tunnel/ServersTab";
import { AdditionalAccount } from "@/components/tunnel/AdditionalAccount";
import { ServiceOverview } from "@/components/tunnel/ServiceOverview";
import { useTunnelSim } from "@/lib/use-tunnel-sim";
import {
  EMPTY_PROFILE,
  ROUTE_PRESETS,
  SAVED_PROFILES_KEY,
  exportProfile,
  bytesPerSecToMbps,
  importProfile,
  parseActiveProfile,
  parseSavedProfiles,
  serializeSavedProfiles,
  validateProfile,
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
        content: "Simulated live bandwidth, profile import/export and copyable Android VPN service code.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: Index,
});

type Tab = "home" | "config" | "servers" | "share";

const TABS: { id: Tab; label: string; icon: typeof Home }[] = [
  { id: "home", label: "Home", icon: Home },
  { id: "config", label: "Configure", icon: SlidersHorizontal },
  { id: "servers", label: "Servers", icon: Server },
  { id: "share", label: "Share", icon: Share2 },
];

const STORAGE_KEY = "tunnel-console-profile";

function Index() {
  const [tab, setTab] = useState<Tab>("home");
  const [profile, setProfile] = useState<TunnelProfile>(EMPTY_PROFILE);
  const [presetId, setPresetId] = useState("custom");
  const [errors, setErrors] = useState<ProfileErrors>({});
  const [exported, setExported] = useState<string | null>(null);
  const [saved, setSaved] = useState<SavedProfile[]>([]);
  const [loaded, setLoaded] = useState(false);
  const sim = useTunnelSim();

  useEffect(() => {
    try {
      const restored = parseActiveProfile(localStorage.getItem(STORAGE_KEY));
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
      toast.error("Changes can't be stored on this device", { description: "Export your profile to keep a copy." });
    }
  }, [loaded, profile, presetId]);

  useEffect(() => {
    if (!loaded) return;
    try {
      localStorage.setItem(SAVED_PROFILES_KEY, serializeSavedProfiles(saved));
    } catch {
      toast.error("Saved profiles can't be stored on this device", { description: "Export your profile to keep a copy." });
    }
  }, [loaded, saved]);

  useEffect(() => setExported(null), [profile]);

  const canEdit = () => {
    if (sim.status === "disconnected") return true;
    toast.error("Disconnect before changing the configuration");
    return false;
  };

  const change = (patch: Partial<TunnelProfile>) => {
    setProfile((p) => ({ ...p, ...patch }));
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
    if (preset) {
      setProfile((p) => ({ ...p, sni: preset.sni }));
      setErrors((e) => {
        const { sni: _omit, ...rest } = e;
        return rest;
      });
    }
  };

  const checkProfile = () => {
    const found = validateProfile(profile);
    setErrors(found);
    const first = Object.values(found)[0];
    if (first) {
      toast.error(first, { description: "Fix the highlighted field in Configure." });
      setTab("config");
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
      toast.message("Couldn't access the clipboard", { description: "Copy the code shown below instead." });
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
    setTab("config");
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
    setTab("config");
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
    toast.success("Server applied to Configure");
    setTab("config");
  };

  const serverLabel = profile.ip ? `${profile.ip}:${profile.port || "443"}` : "No server configured";

  function openTab(value: Tab) {
    if (!TABS.some(item => item.id === value)) throw new Error("Unknown screen; expected home, config, servers, or share.");
    setTab(value);
  }
  useWebMCP({
    name: "open_workspace_screen",
    description: "Open Home, Configure, Servers or Share without starting a VPN or changing saved data.",
    inputSchema: { type: "object", properties: { screen: { type: "string", enum: ["home", "config", "servers", "share"], description: "The workspace screen to open." } }, required: ["screen"] },
    execute: ({ screen }: { screen: Tab }) => runWebMCPTool("open_workspace_screen", () => { openTab(screen); return `Opened ${screen}.`; }),
  });
  useWebMCP({
    name: "read_workspace_status",
    description: "Read the open screen and, on Home only, the simulated tunnel status and current displayed speeds.",
    annotations: { readOnlyHint: true },
    execute: () => runWebMCPTool("read_workspace_status", () => tab === "home" ? `Screen: home. Simulated connection: ${sim.status}. Download: ${bytesPerSecToMbps(sim.downSpeed).toFixed(2)} Mbps. Upload: ${bytesPerSecToMbps(sim.upSpeed).toFixed(2)} Mbps. This is not a real VPN.` : `Screen: ${tab}.`),
  });

  return (
    <div className="flex min-h-dvh justify-center bg-background sm:items-center sm:py-6">
      <div className="flex h-dvh w-full max-w-[420px] flex-col overflow-hidden bg-background sm:h-[min(860px,calc(100dvh-3rem))] sm:rounded-[2rem] sm:border sm:border-border sm:shadow-card">
        <header className="flex items-center justify-between px-5 pb-2 pt-5">
          <div>
            <h1 className="text-lg font-bold tracking-tight">Tunnel Console</h1>
            <p className="text-xs text-muted-foreground">Network optimization utility</p>
          </div>
          <div className="flex items-center gap-2">
          <Button variant="ghost" size="icon" asChild><Link to="/connect" aria-label="Connect your assistant" title="Connect your assistant"><Plug className="h-4 w-4" /></Link></Button>
          <span
            className={`h-2.5 w-2.5 rounded-full transition-colors duration-500 ${
              sim.status === "connected" ? "bg-primary" : sim.status === "connecting" ? "bg-chart-4" : "bg-destructive"
            }`}
          />
          </div>
        </header>

        <main className="min-h-0 flex-1 overflow-y-auto px-5 pb-6 pt-2">
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
              onToggle={toggle}
              getDiagnostics={getDiagnostics}
            />
          )}
          {tab === "config" && (
            <ConfigTab
              profile={profile}
              presetId={presetId}
              errors={errors}
              disabled={sim.status !== "disconnected"}
              onPreset={choosePreset}
              onChange={change}
            />
          )}
          {tab === "servers" && <><ServiceOverview /><AdditionalAccount /><h2 className="mb-3 text-sm font-semibold">Cloud relay servers</h2><ServersTab onUseServer={useServer} /></>}
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
        </main>

        <nav aria-label="Workspace" className="grid shrink-0 grid-cols-4 border-t border-border bg-card">
          {TABS.map(({ id, label, icon: Icon }) => (
            <Button variant="ghost"
              key={id}
              type="button"
              onClick={() => openTab(id)}
              aria-current={tab === id ? "page" : undefined}
              className={`flex h-auto flex-col items-center gap-1 rounded-none px-0 py-3 text-[11px] font-medium transition-colors ${
                tab === id ? "text-primary" : "text-muted-foreground"
              }`}
            >
              <Icon className="h-5 w-5" />
              {label}
            </Button>
          ))}
        </nav>
      </div>
    </div>
  );
}
