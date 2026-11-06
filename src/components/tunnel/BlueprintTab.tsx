import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { toast } from "sonner";
import { BLUEPRINT_FILES } from "@/lib/android-blueprint";
import { Button } from "@/components/ui/button";
import { AndroidChecklist } from "@/components/tunnel/AndroidChecklist";

export function BlueprintTab() {
  const [active, setActive] = useState(0);
  const [copied, setCopied] = useState(false);
  const file = BLUEPRINT_FILES[active] ?? BLUEPRINT_FILES[0];
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
    <div className="space-y-3">
      <p className="text-xs text-muted-foreground">
        The Android app and the server now both use WireGuard, an established VPN protocol, with
        per-device keys. Their shared profile format passed local packet tests, but the Android app
        has not been built or run on a phone yet, so it is not production-ready.
      </p>

      <AndroidChecklist />

      <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1">
        {BLUEPRINT_FILES.map((f, i) => (
          <Button variant="ghost"
            key={f.name}
            type="button"
            onClick={() => { setActive(i); setCopied(false); }}
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
          <Button variant="secondary" size="sm"
            type="button"
            onClick={copy}
            className="flex shrink-0 items-center gap-1.5 rounded-md bg-secondary px-2.5 py-1 text-xs font-medium text-secondary-foreground"
          >
            {copied ? <Check className="h-3.5 w-3.5 text-primary" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? "Copied" : "Copy"}
          </Button>
        </div>
        <pre className="max-h-[28rem] overflow-auto p-3 font-mono text-[11px] leading-relaxed text-foreground">
          <code>{file.code}</code>
        </pre>
      </div>
    </div>
  );
}
