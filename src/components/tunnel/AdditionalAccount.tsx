import { useEffect, useRef, useState } from 'react';
import { KeyRound, Loader2, LogOut, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { createAdditionalAccountClient, verifyAdditionalAccount } from '@/lib/additional-account';

export function AdditionalAccount() {
  const client = useRef<ReturnType<typeof createAdditionalAccountClient> | null>(null);
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [verified, setVerified] = useState(false);
  const [message, setMessage] = useState('');
  const generation = useRef(0);
  useEffect(() => {
    client.current = createAdditionalAccountClient();
    return () => { generation.current++; client.current = null; };
  }, []);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const accountClient = client.current;
    if (!accountClient) return;
    const current = ++generation.current;
    setBusy(true);
    setMessage('');
    try {
      if (!sent) {
        const { error } = await accountClient.auth.signInWithOtp({ email: email.trim(), options: { shouldCreateUser: true } });
        if (error) throw error;
        if (current !== generation.current) return;
        setSent(true);
        setMessage('Check your email for the confirmation code.');
      } else {
        const { error } = await accountClient.auth.verifyOtp({ email: email.trim(), token: code.trim(), type: 'email' });
        if (error) throw error;
        const account = await verifyAdditionalAccount(accountClient);
        if (current !== generation.current) return;
        setEmail(account.email);
        setCode('');
        setVerified(true);
        setMessage('Additional account verified. Private feature access is still locked.');
      }
    } catch {
      if (current === generation.current) setMessage('Verification could not complete. Check your email and code, or request a new code.');
    } finally {
      if (current === generation.current) setBusy(false);
    }
  }

  async function reset() {
    generation.current++;
    const accountClient = client.current;
    client.current = createAdditionalAccountClient();
    setVerified(false); setSent(false); setCode(''); setMessage(''); setBusy(false);
    await accountClient?.auth.signOut({ scope: 'local' });
  }

  return <section className="mb-6 space-y-3 border-b border-border pb-5" aria-label="Additional account">
    <h2 className="flex items-center gap-2 text-sm font-semibold"><ShieldCheck className="h-4 w-4 text-primary" />Additional account</h2>
    <p className="text-xs text-muted-foreground">Separate email confirmation · Cloud account unchanged</p>
    {verified ? <div className="flex items-center justify-between gap-2"><span className="min-w-0 break-all text-xs text-primary">{email}</span><Button variant="ghost" size="icon" onClick={reset} aria-label="Disconnect additional account" title="Disconnect additional account"><LogOut /></Button></div> :
      <form onSubmit={submit} className="space-y-3">
        <label className="block text-xs text-muted-foreground">Additional account email<input required type="email" autoComplete="email" value={email} disabled={sent || busy} onChange={event => setEmail(event.target.value)} className="mt-1 w-full rounded-md border border-input bg-muted px-3 py-2 text-sm text-foreground" /></label>
        {sent && <label className="block text-xs text-muted-foreground">Email confirmation code<input required autoComplete="one-time-code" value={code} disabled={busy} onChange={event => setCode(event.target.value)} className="mt-1 w-full rounded-md border border-input bg-muted px-3 py-2 font-mono text-sm text-foreground" /></label>}
        <div className="flex flex-wrap gap-2"><Button type="submit" size="sm" disabled={busy}>{busy ? <Loader2 className="animate-spin" /> : <KeyRound />}{sent ? 'Verify account' : 'Send confirmation'}</Button>{sent && <Button variant="ghost" size="sm" type="button" disabled={busy} onClick={reset}>Start again</Button>}</div>
      </form>}
    {message && <p role="status" className="text-xs text-muted-foreground">{message}</p>}
    <ul className="grid grid-cols-2 gap-2 text-xs text-muted-foreground"><li>Profile sync · Locked</li><li>Monitoring · Locked</li><li>History · Locked</li><li>Files · Locked</li></ul>
  </section>;
}