import { useState } from "react";
import { Copy, Download, Info, Save, Trash2, Upload } from "lucide-react";

import type { SavedProfile } from "@/lib/tunnel";

type Props = {
  exportedCode: string | null;
  saved: SavedProfile[];
  onExport: () => void;
  onImport: (code: string) => boolean;
  onSave: (name: string) => boolean;
  onLoad: (id: string) => void;
  onDelete: (id: string) => void;
};

export function ShareTab({ exportedCode, saved, onExport, onImport, onSave, onLoad, onDelete }: Props) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");

  return (
    <div className="space-y-5">
      <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
        <h2 className="text-sm font-bold tracking-wider">SAVED PROFILES</h2>
        <p className="text-xs text-muted-foreground">
          Stored on this device, so your configurations are still here next visit.
        </p>
        <div className="flex gap-2">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Name, e.g. Office relay"
            className="min-w-0 flex-1 rounded-xl border border-input bg-muted px-3.5 py-2.5 text-sm outline-none placeholder:text-muted-foreground/60 focus:border-ring"
          />
          <button
            type="button"
            onClick={() => {
              if (onSave(name)) setName("");
            }}
            className="flex shrink-0 items-center gap-1.5 rounded-xl bg-primary px-3.5 py-2.5 text-sm font-bold text-primary-foreground"
          >
            <Save className="h-4 w-4" /> Save
          </button>
        </div>
        {saved.length === 0 ? (
          <p className="text-xs text-muted-foreground">No saved profiles yet.</p>
        ) : (
          <ul className="space-y-2">
            {saved.map((s) => (
              <li
                key={s.id}
                className="flex items-center gap-2 rounded-xl border border-border bg-background px-3 py-2.5"
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{s.name}</p>
                  <p className="truncate font-mono text-xs text-muted-foreground">
                    {s.profile.ip}:{s.profile.port} · {s.profile.sni}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => onLoad(s.id)}
                  aria-label={`Load ${s.name}`}
                  className="rounded-lg p-2 text-primary transition-colors hover:bg-muted"
                >
                  <Upload className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  onClick={() => onDelete(s.id)}
                  aria-label={`Delete ${s.name}`}
                  className="rounded-lg p-2 text-destructive transition-colors hover:bg-muted"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
        <h2 className="text-sm font-bold tracking-wider">EXPORT PROFILE</h2>
        <p className="text-xs text-muted-foreground">
          Turns the current configuration into a <code className="font-mono text-primary">TUNNEL://</code> code and
          copies it to your clipboard.
        </p>
        <button
          type="button"
          onClick={onExport}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary py-3 text-sm font-bold text-primary-foreground"
        >
          <Copy className="h-4 w-4" /> Copy profile code
        </button>
        {exportedCode && (
          <pre className="max-h-32 overflow-auto whitespace-pre-wrap break-all rounded-xl border border-border bg-background p-3 font-mono text-xs text-muted-foreground">
            {exportedCode}
          </pre>
        )}
      </section>

      <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
        <h2 className="text-sm font-bold tracking-wider">IMPORT PROFILE</h2>
        <textarea
          value={code}
          onChange={(e) => {
            setCode(e.target.value);
            setError(null);
          }}
          placeholder="TUNNEL://eyJpcCI6…"
          className="min-h-24 w-full rounded-xl border border-input bg-muted px-3.5 py-3 font-mono text-xs outline-none focus:border-ring"
        />
        {error && <p className="text-xs text-destructive">{error}</p>}
        <button
          type="button"
          onClick={() => {
            if (onImport(code)) {
              setCode("");
              setError(null);
            } else setError("That isn't a valid TUNNEL:// profile code.");
          }}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-secondary py-3 text-sm font-bold text-secondary-foreground"
        >
          <Download className="h-4 w-4" /> Import profile
        </button>
      </section>

      <p className="flex gap-2 text-xs text-muted-foreground">
        <Info className="mt-0.5 h-4 w-4 shrink-0" />
        Base64 is encoding, not encryption. Anyone with the code can read it, so don't share profiles that
        contain secrets.
      </p>
    </div>
  );
}
