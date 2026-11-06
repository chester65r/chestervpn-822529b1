import { Cloud, Database, LockKeyhole } from 'lucide-react';

export function ServiceOverview() {
  return <section aria-label="Connected services" className="mb-5 space-y-3 border-b border-border pb-5">
    <h2 className="text-sm font-semibold">Connected services</h2>
    <div className="flex items-start gap-3"><Cloud className="mt-0.5 h-4 w-4 shrink-0 text-primary" /><div className="min-w-0"><p className="text-xs font-medium">Lovable Cloud</p><p className="text-xs text-muted-foreground">App accounts · Relay servers · AI helper</p></div></div>
    <div className="flex items-start gap-3"><Database className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" /><div className="min-w-0"><p className="text-xs font-medium">Additional project</p><p className="text-xs text-muted-foreground">Separate account · Private features pending</p></div></div>
    <p className="flex items-center gap-2 text-xs text-muted-foreground"><LockKeyhole className="h-3 w-3 shrink-0" />Accounts and private data remain separate.</p>
  </section>;
}