import { Download, KeyRound, Loader2, LogOut, RefreshCw, ShieldCheck, Trash2, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { AdditionalSession } from '@/lib/use-additional-session';
import { formatBytes } from '@/lib/tunnel';

/**
 * View over the route-level additional-account session
 * (useAdditionalSession). This section renders only on the Servers tab, so
 * it mounts and unmounts as the user navigates — deliberately with no
 * session side effects: mounting creates nothing and unmounting signs
 * nothing out. The session, its clients and the history recorder live in
 * the route-level hook and survive tab switches.
 */
export function AdditionalAccount({ session }: { session: AdditionalSession }) {
  const {
    email, setEmail,
    code, setCode,
    sent, busy, verified, message, ready, checking,
    health, healthState,
    profiles, profilesState, syncing, syncMessage,
    history, historyState,
    files, filesState, fileBusy, fileMessage,
    recheck, submit, disconnect,
    refreshHealth, syncNow, removeRemoteProfile, refreshHistory,
    pickFile, saveFile, removeFile,
  } = session;

  return <section className="tc-card mb-6 space-y-3 p-4 sm:p-5" aria-label="Additional account">
    <div className="flex items-center justify-between gap-2"><h2 className="flex items-center gap-2 text-sm font-semibold"><ShieldCheck className="h-4 w-4 text-primary" />Additional account</h2><Button variant="ghost" size="icon" onClick={recheck} disabled={busy || checking} aria-label="Recheck additional connection" title="Recheck additional connection"><RefreshCw className={checking ? 'animate-spin' : ''} /></Button></div>
    <p className="text-xs text-muted-foreground">Separate email confirmation · Cloud account unchanged</p>
    {verified ? <div className="flex items-center justify-between gap-2"><span className="min-w-0 break-all text-xs text-primary">{email}</span><Button variant="ghost" size="icon" onClick={disconnect} aria-label="Disconnect additional account" title="Disconnect additional account"><LogOut /></Button></div> :
      <form onSubmit={submit} className="space-y-3">
        <label className="block text-xs text-muted-foreground">Additional account email<input required type="email" autoComplete="email" value={email} disabled={sent || busy} onChange={event => setEmail(event.target.value)} className="tc-field mt-1" /></label>
        {sent && <label className="block text-xs text-muted-foreground">Email confirmation code (only if your email shows one)<input required autoComplete="one-time-code" value={code} disabled={busy} onChange={event => setCode(event.target.value)} className="tc-field mt-1 font-mono" /></label>}
        <div className="flex flex-wrap gap-2"><Button type="submit" size="sm" disabled={busy || !ready}>{busy ? <Loader2 className="animate-spin" /> : <KeyRound />}{sent ? 'Verify account' : 'Send confirmation'}</Button>{sent && <Button variant="ghost" size="sm" type="button" disabled={busy} onClick={disconnect}>Start again</Button>}</div>
      </form>}
    {message && <p role="status" className="text-xs text-muted-foreground">{message}</p>}
    <ul className="grid grid-cols-2 gap-2 text-xs text-muted-foreground"><li>Profile sync · {!verified ? 'Locked' : profilesState === 'loading' ? 'Checking…' : profilesState === 'error' ? 'Unavailable' : profiles ? `${profiles.length} stored` : 'Checking…'}</li><li>Monitoring · {!verified ? 'Locked' : healthState === 'loading' ? 'Checking…' : healthState === 'error' ? 'Unavailable' : health && health.length > 0 ? 'Live' : 'No reports yet'}</li><li>History · {!verified ? 'Locked' : historyState === 'loading' ? 'Checking…' : historyState === 'error' ? 'Unavailable' : history && history.length > 0 ? 'Live' : 'No history yet'}</li><li>Files · {!verified ? 'Locked' : filesState === 'loading' ? 'Checking…' : filesState === 'error' ? 'Unavailable' : files ? `${files.length} stored` : 'Checking…'}</li></ul>
    {verified && profilesState !== 'idle' && <div className="space-y-1.5" aria-label="Synced profiles">
      <div className="flex items-center justify-between gap-2"><p className="text-[11px] font-medium tracking-wider text-muted-foreground">SYNCED PROFILES (ADDITIONAL BACKEND)</p><Button variant="ghost" size="sm" onClick={syncNow} disabled={syncing || profilesState === 'loading'}>{syncing ? 'Syncing…' : 'Sync now'}</Button></div>
      <p className="text-xs text-muted-foreground">Only the preset and port are stored on the additional backend. The profile name and host stay on this device.</p>
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
      {historyState === 'error' && <p className="text-xs text-muted-foreground">Connection history could not be loaded.</p>}
      {historyState === 'ready' && history && history.length === 0 && <p className="text-xs text-muted-foreground">No connection history yet. Connect or disconnect the tunnel from the Home tab and the event is recorded here, labeled as simulation — the web dashboard cannot open a real VPN.</p>}
      {historyState === 'ready' && history && history.length > 0 && <ul className="space-y-1">{history.map(row => <li key={row.id} className="flex items-center justify-between gap-2 font-mono text-[11px] text-muted-foreground"><span className={row.status === 'connected' ? 'text-primary' : row.status === 'error' ? 'text-destructive' : ''}>{row.status}</span><span>{row.source} · {new Date(row.occurred_at).toLocaleString()}</span></li>)}</ul>}
    </div>}
    {verified && filesState !== 'idle' && <div className="space-y-1.5" aria-label="Private files">
      <div className="flex items-center justify-between gap-2"><p className="text-[11px] font-medium tracking-wider text-muted-foreground">PRIVATE FILES (ADDITIONAL BACKEND)</p><label className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground ${fileBusy || filesState === 'loading' ? 'pointer-events-none opacity-50' : 'cursor-pointer'}`}><Upload className="h-3.5 w-3.5" />Upload<input type="file" accept=".txt,.pdf,text/plain,application/pdf" className="hidden" onChange={pickFile} disabled={fileBusy || filesState === 'loading'} /></label></div>
      <p className="text-xs text-muted-foreground">Private storage, visible only to your verified additional account. Text and PDF files up to 10 MB.</p>
      {filesState === 'loading' && <p className="text-xs text-muted-foreground">Loading files…</p>}
      {filesState === 'error' && <p className="text-xs text-muted-foreground">Files could not be loaded.</p>}
      {filesState === 'ready' && files && files.length === 0 && <p className="text-xs text-muted-foreground">No files stored yet.</p>}
      {filesState === 'ready' && files && files.length > 0 && <ul className="space-y-1">{files.map(row => <li key={row.name} className="flex items-center justify-between gap-2 font-mono text-[11px] text-muted-foreground"><span className="min-w-0 truncate">{row.name}</span><span className="flex items-center gap-1"><span>{row.size !== null ? formatBytes(row.size) : ''}</span><Button variant="ghost" size="icon" onClick={() => saveFile(row.name)} disabled={fileBusy} aria-label={`Download ${row.name}`} title={`Download ${row.name}`}><Download className="h-3.5 w-3.5" /></Button><Button variant="ghost" size="icon" onClick={() => removeFile(row.name)} disabled={fileBusy} aria-label={`Delete ${row.name}`} title={`Delete ${row.name}`}><Trash2 className="h-3.5 w-3.5" /></Button></span></li>)}</ul>}
      {fileMessage && <p role="status" className="text-xs text-muted-foreground">{fileMessage}</p>}
    </div>}
  </section>;
}
