import { useEffect, useState } from "react";
import { useServerFn } from "@tanstack/react-start";
import { Check, Code2, Copy, Search } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  listAcodeSnippetLanguages,
  readAcodeSnippetLanguage,
} from "@/lib/acode-snippets.functions";
import type { AcodeSnippet, AcodeSnippetLanguageSummary } from "@/lib/acode-snippets";

export function AcodeSnippetsPanel() {
  const fetchLanguages = useServerFn(listAcodeSnippetLanguages);
  const fetchSnippets = useServerFn(readAcodeSnippetLanguage);

  const [languages, setLanguages] = useState<AcodeSnippetLanguageSummary[]>([]);
  const [selectedLang, setSelectedLang] = useState("kotlin");
  const [snippets, setSnippets] = useState<AcodeSnippet[]>([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [copiedTrigger, setCopiedTrigger] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const list = await fetchLanguages();
        if (!active) return;
        if (Array.isArray(list)) {
          setLanguages(list);
        }
      } catch {
        /* Fallback when server unavailable */
      }
    })();
    return () => {
      active = false;
    };
  }, [fetchLanguages]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    void (async () => {
      try {
        const res = await fetchSnippets({ data: { language: selectedLang } });
        if (!active) return;
        setSnippets(Array.isArray(res?.snippets) ? res.snippets : []);
      } catch {
        if (active) setSnippets([]);
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [fetchSnippets, selectedLang]);

  const safeSnippets = Array.isArray(snippets) ? snippets : [];
  const filtered = safeSnippets.filter((item) => {
    const q = query.trim().toLowerCase();
    if (!q) return true;
    return (
      item.trigger.toLowerCase().includes(q) ||
      item.body.toLowerCase().includes(q)
    );
  });

  const copySnippet = async (snippet: AcodeSnippet) => {
    try {
      await navigator.clipboard.writeText(snippet.body);
      setCopiedTrigger(snippet.trigger);
      toast.success(`Copied ${snippet.language} snippet "${snippet.trigger}"`);
      window.setTimeout(() => setCopiedTrigger(null), 1500);
    } catch {
      toast.error("Couldn't access the clipboard");
    }
  };

  return (
    <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="flex items-center gap-1.5 text-xs font-bold tracking-wider text-primary">
            <Code2 className="h-4 w-4" /> ACODE SNIPPETS LIBRARY (.acode-snippets)
          </p>
          <p className="text-[11px] text-muted-foreground">
            {languages.length > 0
              ? `${languages.length} language packs loaded from repository (.acode-snippets/)`
              : "Repository .acode-snippets templates"}
          </p>
        </div>
        <select
          aria-label="Snippet language"
          value={selectedLang}
          onChange={(e) => setSelectedLang(e.target.value)}
          className="rounded-xl border border-input bg-muted px-3 py-1.5 font-mono text-xs text-foreground outline-none focus:border-ring"
        >
          {(languages.length > 0
            ? languages
            : [
                { id: "kotlin", filename: "kotlin.snippets", snippetCount: 0 },
                { id: "typescript", filename: "typescript.snippets", snippetCount: 0 },
                { id: "tsx", filename: "tsx.snippets", snippetCount: 0 },
                { id: "sh", filename: "sh.snippets", snippetCount: 0 },
                { id: "python", filename: "python.snippets", snippetCount: 0 },
                { id: "sql", filename: "sql.snippets", snippetCount: 0 },
              ]
          ).map((lang) => (
            <option key={lang.id} value={lang.id}>
              {lang.id} ({lang.snippetCount > 0 ? lang.snippetCount : "…"})
            </option>
          ))}
        </select>
      </div>

      <label className="relative block">
        <Search className="pointer-events-none absolute left-3 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
        <input
          aria-label="Search acode snippets"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={`Search ${selectedLang} triggers or code (e.g. class, fun, try, import)…`}
          className="w-full rounded-xl border border-input bg-muted py-2 pl-8 pr-3 text-xs text-foreground outline-none placeholder:text-muted-foreground/60 focus:border-ring"
        />
      </label>

      {loading ? (
        <p className="py-4 text-center font-mono text-xs text-muted-foreground">
          Loading {selectedLang}.snippets…
        </p>
      ) : filtered.length === 0 ? (
        <p className="py-4 text-center text-xs text-muted-foreground">
          No snippets match "{query}".
        </p>
      ) : (
        <div className="max-h-64 space-y-2 overflow-y-auto pr-1">
          {filtered.slice(0, 40).map((snippet, idx) => (
            <div
              key={`${snippet.trigger}-${idx}`}
              className="rounded-xl border border-border bg-background p-2.5"
            >
              <div className="mb-1.5 flex items-center justify-between gap-2">
                <span className="rounded bg-primary/10 px-2 py-0.5 font-mono text-[11px] font-semibold text-primary">
                  {snippet.trigger}
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  type="button"
                  onClick={() => void copySnippet(snippet)}
                  className="h-6 gap-1 px-2 text-[11px]"
                >
                  {copiedTrigger === snippet.trigger ? (
                    <Check className="h-3 w-3 text-primary" />
                  ) : (
                    <Copy className="h-3 w-3" />
                  )}
                  Copy
                </Button>
              </div>
              <pre className="overflow-x-auto font-mono text-[11px] leading-relaxed text-foreground">
                <code>{snippet.body}</code>
              </pre>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
