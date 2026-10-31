import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { toast } from "sonner";
import { BLUEPRINT_FILES } from "@/lib/android-blueprint";

export function BlueprintTab() {
  const [active, setActive] = useState(0);
  const [copied, setCopied] = useState(false);
  const file = BLUEPRINT_FILES[active];

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
        Copy these into an Android Studio project with namespace{" "}
        <code className="font-mono text-primary">com.example.advancedtunnel</code>. It's a learning skeleton: it
        needs a relay server you run that understands the packet framing.
      </p>

      <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1">
        {BLUEPRINT_FILES.map((f, i) => (
          <button
            key={f.name}
            type="button"
            onClick={() => setActive(i)}
            className={`shrink-0 rounded-lg px-3 py-1.5 font-mono text-xs transition-colors ${
              i === active ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground"
            }`}
          >
            {f.name}
          </button>
        ))}
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-background">
        <div className="flex items-center justify-between gap-2 border-b border-border px-3 py-2">
          <span className="truncate font-mono text-[11px] text-muted-foreground">{file.path}</span>
          <button
            type="button"
            onClick={copy}
            className="flex shrink-0 items-center gap-1.5 rounded-md bg-secondary px-2.5 py-1 text-xs font-medium text-secondary-foreground"
          >
            {copied ? <Check className="h-3.5 w-3.5 text-primary" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? "Copied" : "Copy"}
          </button>
        </div>
        <pre className="max-h-[28rem] overflow-auto p-3 font-mono text-[11px] leading-relaxed text-foreground">
          <code>{file.code}</code>
        </pre>
      </div>
    </div>
  );
}
