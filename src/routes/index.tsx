import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { Code2, Home, Share2, SlidersHorizontal } from "lucide-react";
import { toast } from "sonner";

import { HomeTab } from "@/components/tunnel/HomeTab";
import { ConfigTab } from "@/components/tunnel/ConfigTab";
import { ShareTab } from "@/components/tunnel/ShareTab";
import { BlueprintTab } from "@/components/tunnel/BlueprintTab";
import { useTunnelSim } from "@/lib/use-tunnel-sim";
import {
  EMPTY_PROFILE,
  ROUTE_PRESETS,
  exportProfile,
  importProfile,
  validateProfile,
  type ProfileErrors,
  type TunnelProfile,
} from "@/lib/tunnel";

export const Route = createFileRoute("/")({
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

type Tab = "home" | "config" | "share" | "android";

const TABS: { id: Tab; label: string; icon: typeof Home }[] = [
  { id: "home", label: "Home", icon: Home },
  { id: "config", label: "Configure", icon: SlidersHorizontal },
  { id: "share", label: "Share", icon: Share2 },
  { id: "android", label: "Android", icon: Code2 },
];

const STORAGE_KEY = "tunnel-console-profile";

function Index() {
  const [tab, setTab] = useState<Tab>("home");
  const [profile, setProfile] = useState<TunnelProfile>(EMPTY_PROFILE);
  const [presetId, setPresetId] = useState("custom");
  const [errors, setErrors] = useState<ProfileErrors>({});
  const [exported, setExported] = useState<string | null>(null);
  const loaded = useRef(false);
  const sim = useTunnelSim();

  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved) {
        const parsed = JSON.parse(saved);
        setProfile({ ...EMPTY_PROFILE, ...parsed.profile });
        setPresetId(parsed.presetId ?? "custom");
      }
    } catch {
      /* ignore corrupt storage */
    }
    loaded.current = true;
  }, []);

  useEffect(() => {
    if (!loaded.current) return;
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ profile, presetId }));
  }, [profile, presetId]);

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

  const toggle = () => {
    if (sim.status === "connected") {
      sim.disconnect();
      toast("Tunnel disconnected");
    } else if (checkProfile()) {
      sim.connect();
    }
  };

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

  const serverLabel = profile.ip ? `${profile.ip}:${profile.port || "443"}` : "No server configured";

  return (
    <div className="flex min-h-screen justify-center bg-background sm:items-center sm:py-6">
      <div className="flex h-screen w-full max-w-[420px] flex-col overflow-hidden bg-background sm:h-[min(860px,calc(100vh-3rem))] sm:rounded-[2rem] sm:border sm:border-border sm:shadow-card">
        <header className="flex items-center justify-between px-5 pb-2 pt-5">
          <div>
            <h1 className="text-lg font-bold tracking-tight">Tunnel Console</h1>
            <p className="text-xs text-muted-foreground">Network optimization utility</p>
          </div>
          <span
            className={`h-2.5 w-2.5 rounded-full transition-colors duration-500 ${
              sim.status === "connected" ? "bg-primary" : sim.status === "connecting" ? "bg-chart-4" : "bg-destructive"
            }`}
          />
        </header>

        <main className="flex-1 overflow-y-auto px-5 pb-6 pt-2">
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
          {tab === "share" && <ShareTab exportedCode={exported} onExport={doExport} onImport={doImport} />}
          {tab === "android" && <BlueprintTab />}
        </main>

        <nav className="grid grid-cols-4 border-t border-border bg-card">
          {TABS.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              onClick={() => setTab(id)}
              className={`flex flex-col items-center gap-1 py-3 text-[11px] font-medium transition-colors ${
                tab === id ? "text-primary" : "text-muted-foreground"
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
