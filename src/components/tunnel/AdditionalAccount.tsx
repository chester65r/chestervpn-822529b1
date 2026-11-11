import { useEffect, useRef, useState } from 'react';
import type { SupabaseClient } from '@supabase/supabase-js';
import { KeyRound, Loader2, LogOut, RefreshCw, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { additionalEmailRedirectTo, checkAdditionalEmailVerification, completeAdditionalEmailReturn, confirmAdditionalEmailCode, createAdditionalAccountClient, hasAdditionalEmailReturnMarker, stripAdditionalEmailReturn } from '@/lib/additional-account';
import { adoptVerifiedSession, createAdditionalHealthClient, fetchLatestServerHealth, type LatestServerHealth } from '@/lib/additional-monitoring';

export function AdditionalAccount() {
  const client = useRef<ReturnType<typeof createAdditionalAccountClient> | null>(null);
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [verified, setVerified] = useState(false);
  const [message, setMessage] = useState('');
  const [ready, setReady] = useState(false);
  const inFlight = useRef(false);
  const generation = useRef(0);
  const [checking, setChecking] = useState(false);
  const healthClient = useRef<SupabaseClient | null>(null);
  const [health, setHealth] = useState<LatestServerHealth[] | null>(null);
  const [healthState, setHealthState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');

  async function recheck() {
    if (inFlight.current || checking) return;
    const current = generation.current;
    setChecking(true);
    setReady(false);
    try {
      await checkAdditionalEmailVerification();
      if (current !== generation.current) return;
      setReady(true);
      setMessage('Email confirmation is enabled. Account ownership is not verified yet.');
    } catch {
      if (current === generation.current) setMessage('Additional email confirmation is not safely configured. Verification is paused.');
    } finally {
      if (current === generation.current) setChecking(false);
    }
  }
  useEffect(() => {
    const invalidateGeneration = () => { generation.current += 1 };
    client.current = createAdditionalAccountClient();
    let active = true;
    checkAdditionalEmailVerification().then(() => {
      if (active) setReady(true);
    }).catch(() => {
      if (active) setMessage('Additional email confirmation is not safely configured. Verification is paused.');
    });
    // Email-link return: the confirmation email carries a link, not a code.
    // Complete it against the additional backend only, then scrub the auth
    // parameters from the URL so a refresh cannot replay them.
    const accountClient = client.current;
    const returnUrl = new URL(window.location.href);
    if (accountClient && hasAdditionalEmailReturnMarker(returnUrl)) {
      const current = generation.current;
      inFlight.current = true;
      setMessage('Confirming your email link…');
      completeAdditionalEmailReturn(accountClient, returnUrl)
        .then(account => {
          if (!active || current !== generation.current) return;
          if (account) {
            setEmail(account.email);
            setVerified(true);
            setMessage('Additional account verified. Profile sync, history and files remain locked.');
            void loadHealth(accountClient);
          } else {
            setMessage('That confirmation link could not be verified. Request a new link, or enter a code instead.');
          }
        })
        .catch(() => {
          if (active && current === generation.current) setMessage('That confirmation link could not be verified. Request a new link, or enter a code instead.');
        })
        .finally(() => {
          window.history.replaceState(null, '', stripAdditionalEmailReturn(returnUrl));
          if (active && current === generation.current) inFlight.current = false;
        });
    }
    return () => {
      active = false;
      invalidateGeneration();
      const previous = client.current;
      client.current = null;
      void previous?.auth.signOut({ scope: 'local' }).catch(() => {});
      const previousHealth = healthClient.current;
      healthClient.current = null;
      void previousHealth?.auth.signOut({ scope: 'local' }).catch(() => {});
    };
  }, []);

  async function loadHealth(accountClient: SupabaseClient) {
    const current = generation.current;
    setHealthState('loading');
    try {
      const monitoringClient = createAdditionalHealthClient();
      await adoptVerifiedSession(monitoringClient, accountClient);
      const rows = await fetchLatestServerHealth(monitoringClient);
      if (current !== generation.current) return;
      healthClient.current = monitoringClient;
      setHealth(rows);
      setHealthState('ready');
    } catch {
      if (current === generation.current) setHealthState('error');
    }
  }

  async function refreshHealth() {
    const monitoringClient = healthClient.current;
    if (!monitoringClient || healthState === 'loading') return;
    const current = generation.current;
    setHealthState('loading');
    try {
      const rows = await fetchLatestServerHealth(monitoringClient);
      if (current !== generation.current) return;
      setHealth(rows);
      setHealthState('ready');
    } catch {
      if (current === generation.current) setHealthState('error');
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const accountClient = client.current;
    if (!accountClient || !ready || inFlight.current) return;
    inFlight.current = true;
    const current = ++generation.current;
    setBusy(true);
    setMessage('');
    try {
      if (!sent) {
        await checkAdditionalEmailVerification();
        if (current !== generation.current) return;
        const { error } = await accountClient.auth.signInWithOtp({ email: email.trim(), options: { shouldCreateUser: true, emailRedirectTo: additionalEmailRedirectTo(window.location.origin) } });
        if (error) throw error;
        if (current !== generation.current) return;
        setSent(true);
        setMessage('Check your email for the confirmation link and open it in this browser — you will return here verified. If your email shows a numeric code instead, enter it below.');
      } else {
        const account = await confirmAdditionalEmailCode(accountClient, email, code);
        if (current !== generation.current) return;
        setEmail(account.email);
        setCode('');
        setVerified(true);
        setMessage('Additional account verified. Profile sync, history and files remain locked.');
        void loadHealth(accountClient);
      }
    } catch {
      if (current === generation.current) setMessage('Verification could not complete. Open the confirmation link from your email in this browser, or check the code and try again.');
    } finally {
      if (current === generation.current) { setBusy(false); inFlight.current = false; }
    }
  }

  async function reset() {
    if (inFlight.current) return;
    generation.current++;
    const accountClient = client.current;
    client.current = createAdditionalAccountClient();
    const monitoringClient = healthClient.current;
    healthClient.current = null;
    setVerified(false); setSent(false); setCode(''); setMessage(''); setBusy(false);
    setHealth(null); setHealthState('idle');
    await accountClient?.auth.signOut({ scope: 'local' }).catch(() => {});
    await monitoringClient?.auth.signOut({ scope: 'local' }).catch(() => {});
  }

  return <section className="mb-6 space-y-3 border-b border-border pb-5" aria-label="Additional account">
    <div className="flex items-center justify-between gap-2"><h2 className="flex items-center gap-2 text-sm font-semibold"><ShieldCheck className="h-4 w-4 text-primary" />Additional account</h2><Button variant="ghost" size="icon" onClick={recheck} disabled={busy || checking} aria-label="Recheck additional connection" title="Recheck additional connection"><RefreshCw className={checking ? 'animate-spin' : ''} /></Button></div>
    <p className="text-xs text-muted-foreground">Separate email confirmation · Cloud account unchanged</p>
    {verified ? <div className="flex items-center justify-between gap-2"><span className="min-w-0 break-all text-xs text-primary">{email}</span><Button variant="ghost" size="icon" onClick={reset} aria-label="Disconnect additional account" title="Disconnect additional account"><LogOut /></Button></div> :
      <form onSubmit={submit} className="space-y-3">
        <label className="block text-xs text-muted-foreground">Additional account email<input required type="email" autoComplete="email" value={email} disabled={sent || busy} onChange={event => setEmail(event.target.value)} className="mt-1 w-full rounded-md border border-input bg-muted px-3 py-2 text-sm text-foreground" /></label>
        {sent && <label className="block text-xs text-muted-foreground">Email confirmation code (only if your email shows one)<input required autoComplete="one-time-code" value={code} disabled={busy} onChange={event => setCode(event.target.value)} className="mt-1 w-full rounded-md border border-input bg-muted px-3 py-2 font-mono text-sm text-foreground" /></label>}
        <div className="flex flex-wrap gap-2"><Button type="submit" size="sm" disabled={busy || !ready}>{busy ? <Loader2 className="animate-spin" /> : <KeyRound />}{sent ? 'Verify account' : 'Send confirmation'}</Button>{sent && <Button variant="ghost" size="sm" type="button" disabled={busy} onClick={reset}>Start again</Button>}</div>
      </form>}
    {message && <p role="status" className="text-xs text-muted-foreground">{message}</p>}
    <ul className="grid grid-cols-2 gap-2 text-xs text-muted-foreground"><li>Profile sync · Locked</li><li>Monitoring · {!verified ? 'Locked' : healthState === 'loading' ? 'Checking…' : healthState === 'error' ? 'Unavailable' : health && health.length > 0 ? 'Live' : 'No reports yet'}</li><li>History · Locked</li><li>Files · Locked</li></ul>
    {verified && healthState !== 'idle' && <div className="space-y-1.5" aria-label="Collector health reports">
      <div className="flex items-center justify-between gap-2"><p className="text-[11px] font-medium tracking-wider text-muted-foreground">COLLECTOR HEALTH REPORTS (ADDITIONAL BACKEND)</p><Button variant="ghost" size="icon" onClick={refreshHealth} disabled={healthState === 'loading'} aria-label="Refresh monitoring" title="Refresh monitoring"><RefreshCw className={healthState === 'loading' ? 'animate-spin' : ''} /></Button></div>
      {healthState === 'loading' && <p className="text-xs text-muted-foreground">Loading collector reports…</p>}
      {healthState === 'error' && <p className="text-xs text-muted-foreground">Collector reports could not be loaded. Try refresh.</p>}
      {healthState === 'ready' && health && health.length === 0 && <p className="text-xs text-muted-foreground">No collector health reports yet. Real reports appear here after your server's collector posts its first health row — nothing is simulated.</p>}
      {healthState === 'ready' && health && health.length > 0 && <ul className="space-y-1">{health.map(row => <li key={row.id} className="flex items-center justify-between gap-2 font-mono text-[11px] text-muted-foreground"><span className="min-w-0 truncate">server {row.server_id.slice(0, 8)}…</span><span className={row.status === 'healthy' ? 'text-primary' : row.status === 'unreachable' ? 'text-destructive' : ''}>{row.status}{row.latency_ms !== null ? ` · ${row.latency_ms} ms` : ''} · {new Date(row.observed_at).toLocaleString()}</span></li>)}</ul>}
    </div>}
  </section>;
}
