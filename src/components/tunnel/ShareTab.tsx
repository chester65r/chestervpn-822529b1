import { useState } from "react";
import { Copy, Download, Info } from "lucide-react";

type Props = {
  exportedCode: string | null;
  onExport: () => void;
  onImport: (code: string) => boolean;
};

export function ShareTab({ exportedCode, onExport, onImport }: Props) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <div className="space-y-5">
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
