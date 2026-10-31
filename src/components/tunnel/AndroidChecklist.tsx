import { ClipboardList, Copy, ShieldAlert } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ANDROID_COMPATIBILITY_CHECKS, androidChecklistMarkdown } from "@/lib/android-checklist";

export function AndroidChecklist() {
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(androidChecklistMarkdown());
      toast.success("Compatibility checklist copied");
    } catch {
      toast.error("Couldn't access the clipboard");
    }
  };

  return (
    <section aria-labelledby="android-checklist-title" className="space-y-3 border-t border-border pt-4">
      <div className="flex items-start justify-between gap-2">
        <h2 id="android-checklist-title" className="flex items-center gap-2 text-sm font-bold">
          <ClipboardList className="h-4 w-4 shrink-0 text-primary" /> Compatibility checklist
        </h2>
        <Button variant="secondary" size="icon" onClick={copy} aria-label="Copy compatibility checklist" title="Copy checklist and test record">
          <Copy className="h-4 w-4" />
        </Button>
      </div>
      <p className="flex items-center gap-2 text-xs font-semibold text-destructive">
        <ShieldAlert className="h-4 w-4 shrink-0" /> Not production ready · 2 known blockers
      </p>
      <p className="text-xs leading-relaxed text-muted-foreground">
        No native build, device run or relay interoperability test is verified. All production requirements below
        remain unverified. Browser checks do not establish Android compatibility.
      </p>
      <div className="divide-y divide-border">
        {ANDROID_COMPATIBILITY_CHECKS.map((check) => (
          <details key={check.id} className="py-3">
            <summary className="cursor-pointer text-xs font-medium text-foreground">
              {check.title}
              <span className={`ml-2 inline-block font-mono text-[10px] ${check.status === "blocked" ? "text-destructive" : "text-muted-foreground"}`}>
                {check.status === "blocked" ? "KNOWN BLOCKER" : "UNVERIFIED"}
              </span>
            </summary>
            <div className="mt-2 space-y-2 text-xs leading-relaxed text-muted-foreground">
              <p>{check.finding}</p>
              <p><strong className="text-foreground">Pass criteria: </strong>{check.passCriteria}</p>
              <p className="font-mono text-[10px]">Result: NOT RUN · Evidence: not recorded</p>
            </div>
          </details>
        ))}
      </div>
      <p className="text-xs leading-relaxed text-muted-foreground">
        Release gate: resolve both blockers and record passing evidence for every applicable requirement,
        including device/API, app and relay versions, tester and date. No production approval is implied.
      </p>
    </section>
  );
}