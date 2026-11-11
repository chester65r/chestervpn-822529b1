import { useEffect, useRef, useState } from 'react';
import type { SupabaseClient } from '@supabase/supabase-js';
import { Download, KeyRound, Loader2, LogOut, RefreshCw, ShieldCheck, Trash2, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { additionalEmailRedirectTo, checkAdditionalEmailVerification, completeAdditionalEmailReturn, confirmAdditionalEmailCode, createAdditionalAccountClient, hasAdditionalEmailReturnMarker, stripAdditionalEmailReturn } from '@/lib/additional-account';
import { adoptVerifiedSession, createAdditionalHealthClient, fetchLatestServerHealth, type LatestServerHealth } from '@/lib/additional-monitoring';
import { createAdditionalProfilesClient, deleteProfile, listProfiles, readLocalSavedProfiles, syncProfiles, type RemoteProfile } from '@/lib/additional-profiles';
import { createAdditionalHistoryClient, listHistory, recordHistory, type ConnectionHistoryRow } from '@/lib/additional-history';
import { createAdditionalFilesClient, deleteFile, downloadFile, listFiles, uploadFile, type UserFileEntry } from '@/lib/additional-files';
import { formatBytes } from '@/lib/tunnel';
import type { TunnelStatus } from '@/lib/use-tunnel-sim';

type FeatureState = 'idle' | 'loading' | 'ready' | 'error';

export function AdditionalAccount({ simStatus = 'disconnected' }: { simStatus?: TunnelStatus }) {
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
  const [healthState, setHealthState] = useState<FeatureState>('idle');
  const profilesClient = useRef<SupabaseClient | null>(null);
  const [profiles, setProfiles] = useState<RemoteProfile[] | null>(null);
  const [profilesState, setProfilesState] = useState<FeatureState>('idle');
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState('');
  const historyClient = useRef<SupabaseClient | null>(null);
  const [history, setHistory] = useState<ConnectionHistoryRow[] | null>(null);
  const [historyState, setHistoryState] = useState<FeatureState>('idle');
  const filesClient = useRef<SupabaseClient | null>(null);
  const [files, setFiles] = useState<UserFileEntry[] | null>(null);
  const [filesState, setFilesState] = useState<FeatureState>('idle');
  const [fileBusy, setFileBusy] = useState(false);
  const [fileMessage, setFileMessage] = useState('');

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
            setMessage('Additional account verified. Profile sync, history and files are live on the additional backend.');
            void loadHealth(accountClient);
            void loadFeatures(accountClient);
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
      for (const ref of [healthClient, profilesClient, historyClient, filesClient]) {
        const previousFeature = ref.current;
        ref.current = null;
        void previousFeature?.auth.signOut({ scope: 'local' }).catch(() => {});
      }
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

  /** After verification: adopt the verified session into each isolated feature client and load its real data. */
  async function loadFeatures(accountClient: SupabaseClient) {
    const current = generation.current;
    setProfilesState('loading');
    try {
      const syncClient = createAdditionalProfilesClient();
      await adoptVerifiedSession(syncClient, accountClient);
      const rows = await listProfiles(syncClient);
      if (current !== generation.current) return;
      profilesClient.current = syncClient;
      setProfiles(rows);
      setProfilesState('ready');
    } catch {
      if (current === generation.current) setProfilesState('error');
    }
    if (current !== generation.current) return;
    setHistoryState('loading');
    try {
      const logClient = createAdditionalHistoryClient();
      await adoptVerifiedSession(logClient, accountClient);
      const rows = await listHistory(logClient);
      if (current !== generation.current) return;
      historyClient.current = logClient;
      setHistory(rows);
      setHistoryState('ready');
    } catch {
      if (current === generation.current) setHistoryState('error');
    }
    if (current !== generation.current) return;
    setFilesState('loading');
    try {
      const storageClient = createAdditionalFilesClient();
      await adoptVerifiedSession(storageClient, accountClient);
      const rows = await listFiles(storageClient);
      if (current !== generation.current) return;
      filesClient.current = storageClient;
      setFiles(rows);
      setFilesState('ready');
    } catch {
      if (current === generation.current) setFilesState('error');
    }
  }

  // Connection history records the tunnel simulation's real transitions,
  // labeled with source 'simulation' — exactly what the history table's
  // source column distinguishes. Only transitions observed while the
  // additional account is verified are recorded; the state the component
  // first mounts with is not an event.
  const observedSimStatus = useRef<TunnelStatus | null>(null);
  useEffect(() => {
    const previous = observedSimStatus.current;
    observedSimStatus.current = simStatus;
    if (previous === null || previous === simStatus) return;
    if (simStatus !== 'connected' && simStatus !== 'disconnected') return;
    const logClient = historyClient.current;
    if (!verified || !logClient) return;
    const current = generation.current;
    void recordHistory(logClient, { source: 'simulation', status: simStatus })
      .then(async () => {
        const rows = await listHistory(logClient);
        if (current !== generation.current) return;
        setHistory(rows);
        setHistoryState('ready');
      })
      .catch(() => {});
  }, [simStatus, verified]);

  async function syncNow() {
    const syncClient = profilesClient.current;
    if (!syncClient || syncing) return;
    const current = generation.current;
    setSyncing(true);
    setSyncMessage('');
    try {
      const result = await syncProfiles(syncClient, readLocalSavedProfiles());
      if (current !== generation.current) return;
      setProfiles(result.profiles);
      setProfilesState('ready');
      setSyncMessage(`Sync complete: ${result.uploaded} uploaded, ${result.alreadyPresent} already stored, ${result.skipped} skipped (invalid port). ${result.profiles.length} profile(s) on the additional backend.`);
    } catch {
      if (current === generation.current) setSyncMessage('Profile sync failed. Your local profiles are unchanged.');
    } finally {
      setSyncing(false);
    }
  }

  async function removeRemoteProfile(id: string) {
    const syncClient = profilesClient.current;
    if (!syncClient || syncing) return;
    const current = generation.current;
    setSyncing(true);
    try {
      await deleteProfile(syncClient, id);
      const rows = await listProfiles(syncClient);
      if (current !== generation.current) return;
      setProfiles(rows);
      setSyncMessage('Profile removed from the additional backend.');
    } catch {
      if (current === generation.current) setSyncMessage('Profile could not be removed. Try again.');
    } finally {
      setSyncing(false);
    }
  }

  async function refreshHistory() {
    const logClient = historyClient.current;
    if (!logClient || historyState === 'loading') return;
    const current = generation.current;
    setHistoryState('loading');
    try {
      const rows = await listHistory(logClient);
      if (current !== generation.current) return;
      setHistory(rows);
      setHistoryState('ready');
    } catch {
      if (current === generation.current) setHistoryState('error');
    }
  }

  async function pickFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    const storageClient = filesClient.current;
    if (!file || !storageClient || fileBusy) return;
    const current = generation.current;
    setFileBusy(true);
    setFileMessage('');
    try {
      await uploadFile(storageClient, file.name, file);
      const rows = await listFiles(storageClient);
      if (current !== generation.current) return;
      setFiles(rows);
      setFilesState('ready');
      setFileMessage(`Stored "${file.name}" on the additional backend.`);
    } catch (error) {
      if (current === generation.current) setFileMessage(error instanceof Error ? error.message : 'File could not be uploaded.');
    } finally {
      setFileBusy(false);
    }
  }

  async function saveFile(name: string) {
    const storageClient = filesClient.current;
    if (!storageClient || fileBusy) return;
    const current = generation.current;
    setFileBusy(true);
    setFileMessage('');
    try {
      const blob = await downloadFile(storageClient, name);
      if (current !== generation.current) return;
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = name;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch {
      if (current === generation.current) setFileMessage('File could not be downloaded.');
    } finally {
      setFileBusy(false);
    }
  }

  async function removeFile(name: string) {
    const storageClient = filesClient.current;
    if (!storageClient || fileBusy) return;
    const current = generation.current;
    setFileBusy(true);
    setFileMessage('');
    try {
      await deleteFile(storageClient, name);
      const rows = await listFiles(storageClient);
      if (current !== generation.current) return;
      setFiles(rows);
      setFileMessage(`Deleted "${name}" from the additional backend.`);
    } catch {
      if (current === generation.current) setFileMessage('File could not be deleted.');
    } finally {
      setFileBusy(false);
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
        setMessage('Additional account verified. Profile sync, history and files are live on the additional backend.');
        void loadHealth(accountClient);
        void loadFeatures(accountClient);
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
    const featureClients = [healthClient.current, profilesClient.current, historyClient.current, filesClient.current];
    healthClient.current = null;
    profilesClient.current = null;
    historyClient.current = null;
    filesClient.current = null;
    setVerified(false); setSent(false); setCode(''); setMessage(''); setBusy(false);
    setHealth(null); setHealthState('idle');
    setProfiles(null); setProfilesState('idle'); setSyncing(false); setSyncMessage('');
    setHistory(null); setHistoryState('idle');
    setFiles(null); setFilesState('idle'); setFileBusy(false); setFileMessage('');
    await accountClient?.auth.signOut({ scope: 'local' }).catch(() => {});
    for (const featureClient of featureClients) {
      await featureClient?.auth.signOut({ scope: 'local' }).catch(() => {});
    }
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
    <ul className="grid grid-cols-2 gap-2 text-xs text-muted-foreground"><li>Profile sync · {!verified ? 'Locked' : profilesState === 'loading' ? 'Checking…' : profilesState === 'error' ? 'Unavailable' : profiles ? `${profiles.length} stored` : 'Checking…'}</li><li>Monitoring · {!verified ? 'Locked' : healthState === 'loading' ? 'Checking…' : healthState === 'error' ? 'Unavailable' : health && health.length > 0 ? 'Live' : 'No reports yet'}</li><li>History · {!verified ? 'Locked' : historyState === 'loading' ? 'Checking…' : historyState === 'error' ? 'Unavailable' : history && history.length > 0 ? 'Live' : 'No history yet'}</li><li>Files · {!verified ? 'Locked' : filesState === 'loading' ? 'Checking…' : filesState === 'error' ? 'Unavailable' : files ? `${files.length} stored` : 'Checking…'}</li></ul>
    {verified && profilesState !== 'idle' && <div className="space-y-1.5" aria-label="Synced profiles">
      <div className="flex items-center justify-between gap-2"><p className="text-[11px] font-medium tracking-wider text-muted-foreground">SYNCED PROFILES (ADDITIONAL BACKEND)</p><Button variant="ghost" size="sm" onClick={syncNow} disabled={syncing || profilesState === 'loading'}>{syncing ? 'Syncing…' : 'Sync now'}</Button></div>
      <p className="text-xs text-muted-foreground">Stores each locally saved profile's preset and port — the only columns the additional backend's profile table holds. Names, hosts and payloads stay only on this device.</p>
      {profilesState === 'loading' && <p className="text-xs text-muted-foreground">Loading synced profiles…</p>}
      {profilesState === 'error' && <p className="text-xs text-muted-foreground">Synced profiles could not be loaded.</p>}
      {profilesState === 'ready' && profiles && profiles.length === 0 && <p className="text-xs text-muted-foreground">No profiles synced yet. Sync now uploads the preset and port of each profile saved on this device.</p>}
      {profilesState === 'ready' && profiles && profiles.length > 0 && <ul className="space-y-1">{profiles.map(row => <li key={row.id} className="flex items-center justify-between gap-2 font-mono text-[11px] text-muted-foreground"><span className="min-w-0 truncate">{row.preset} · port {row.port}</span><span className="flex items-center gap-1"><span>{new Date(row.created_at).toLocaleString()}</span><Button variant="ghost" size="icon" onClick={() => removeRemoteProfile(row.id)} disabled={syncing} aria-label={`Remove synced profile ${row.preset} port ${row.port}`} title="Remove synced profile"><Trash2 className="h-3.5 w-3.5" /></Button></span></li>)}</ul>}
      {syncMessage && <p role="status" className="text-xs text-muted-foreground">{syncMessage}</p>}
    </div>}
    {verified && healthState !== 'idle' && <div className="space-y-1.5" aria-label="Collector health reports">
      <div className="flex items-center justify-between gap-2"><p className="text-[11px] font-medium tracking-wider text-muted-foreground">COLLECTOR HEALTH REPORTS (ADDITIONAL BACKEND)</p><Button variant="ghost" size="icon" onClick={refreshHealth} disabled={healthState === 'loading'} aria-label="Refresh monitoring" title="Refresh monitoring"><RefreshCw className={healthState === 'loading' ? 'animate-spin' : ''} /></Button></div>
      {healthState === 'loading' && <p className="text-xs text-muted-foreground">Loading collector reports…</p>}
      {healthState === 'error' && <p className="text-xs text-muted-foreground">Collector reports could not be loaded. Try refresh.</p>}
      {healthState === 'ready' && health && health.length === 0 && <p className="text-xs text-muted-foreground">No collector health reports yet. Real reports appear here after your server's collector posts its first health row — nothing is simulated.</p>}
      {healthState === 'ready' && health && health.length > 0 && <ul className="space-y-1">{health.map(row => <li key={row.id} className="flex items-center justify-between gap-2 font-mono text-[11px] text-muted-foreground"><span className="min-w-0 truncate">server {row.server_id.slice(0, 8)}…</span><span className={row.status === 'healthy' ? 'text-primary' : row.status === 'unreachable' ? 'text-destructive' : ''}>{row.status}{row.latency_ms !== null ? ` · ${row.latency_ms} ms` : ''} · {new Date(row.observed_at).toLocaleString()}</span></li>)}</ul>}
    </div>}
    {verified && historyState !== 'idle' && <div className="space-y-1.5" aria-label="Connection history">
      <div className="flex items-center justify-between gap-2"><p className="text-[11px] font-medium tracking-wider text-muted-foreground">CONNECTION HISTORY (ADDITIONAL BACKEND)</p><Button variant="ghost" size="icon" onClick={refreshHistory} disabled={historyState === 'loading'} aria-label="Refresh history" title="Refresh history"><RefreshCw className={historyState === 'loading' ? 'animate-spin' : ''} /></Button></div>
      {historyState === 'loading' && <p className="text-xs text-muted-foreground">Loading connection history…</p>}
      {historyState === 'error' && <p className="text-xs text-muted-foreground">Connection history could not be loaded. Try refresh.</p>}
      {historyState === 'ready' && history && history.length === 0 && <p className="text-xs text-muted-foreground">No connection history yet. Connect or disconnect the tunnel from the Home tab and the event is recorded here, labeled as simulation — the web dashboard cannot open a real VPN.</p>}
      {historyState === 'ready' && history && history.length > 0 && <ul className="space-y-1">{history.map(row => <li key={row.id} className="flex items-center justify-between gap-2 font-mono text-[11px] text-muted-foreground"><span className={row.status === 'connected' ? 'text-primary' : row.status === 'error' ? 'text-destructive' : ''}>{row.status}</span><span>{row.source} · {new Date(row.occurred_at).toLocaleString()}</span></li>)}</ul>}
    </div>}
    {verified && filesState !== 'idle' && <div className="space-y-1.5" aria-label="Private files">
      <div className="flex items-center justify-between gap-2"><p className="text-[11px] font-medium tracking-wider text-muted-foreground">PRIVATE FILES (ADDITIONAL BACKEND)</p><label className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground hover:text-foreground ${fileBusy || filesState === 'loading' ? 'pointer-events-none opacity-50' : 'cursor-pointer'}`}><Upload className="h-3.5 w-3.5" />Upload<input type="file" accept=".txt,.pdf,text/plain,application/pdf" className="hidden" onChange={pickFile} disabled={fileBusy || filesState === 'loading'} /></label></div>
      <p className="text-xs text-muted-foreground">Private storage, visible only to your verified additional account. Text and PDF files up to 10 MB.</p>
      {filesState === 'loading' && <p className="text-xs text-muted-foreground">Loading files…</p>}
      {filesState === 'error' && <p className="text-xs text-muted-foreground">Files could not be loaded.</p>}
      {filesState === 'ready' && files && files.length === 0 && <p className="text-xs text-muted-foreground">No files stored yet.</p>}
      {filesState === 'ready' && files && files.length > 0 && <ul className="space-y-1">{files.map(row => <li key={row.name} className="flex items-center justify-between gap-2 font-mono text-[11px] text-muted-foreground"><span className="min-w-0 truncate">{row.name}</span><span className="flex items-center gap-1"><span>{row.size !== null ? formatBytes(row.size) : ''}</span><Button variant="ghost" size="icon" onClick={() => saveFile(row.name)} disabled={fileBusy} aria-label={`Download ${row.name}`} title={`Download ${row.name}`}><Download className="h-3.5 w-3.5" /></Button><Button variant="ghost" size="icon" onClick={() => removeFile(row.name)} disabled={fileBusy} aria-label={`Delete ${row.name}`} title={`Delete ${row.name}`}><Trash2 className="h-3.5 w-3.5" /></Button></span></li>)}</ul>}
      {fileMessage && <p role="status" className="text-xs text-muted-foreground">{fileMessage}</p>}
    </div>}
  </section>;
}
