import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import type { SupabaseClient } from '@supabase/supabase-js';
import { additionalEmailRedirectTo, checkAdditionalEmailVerification, completeAdditionalEmailReturn, confirmAdditionalEmailCode, createAdditionalAccountClient, hasAdditionalEmailReturnMarker, stripAdditionalEmailReturn } from './additional-account';
import { adoptVerifiedSession, createAdditionalHealthClient, fetchLatestServerHealth, type LatestServerHealth } from './additional-monitoring';
import { createAdditionalProfilesClient, deleteProfile, listProfiles, readLocalSavedProfiles, syncProfiles, type RemoteProfile } from './additional-profiles';
import { createAdditionalHistoryClient, listHistory, recordHistory, type ConnectionHistoryRow } from './additional-history';
import { createAdditionalFilesClient, deleteFile, downloadFile, listFiles, uploadFile, type UserFileEntry } from './additional-files';
import type { TunnelStatus } from './use-tunnel-sim';

export type AdditionalFeatureState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * Watches tunnel-status transitions and records the real ones.
 *
 * The first observed status only seeds the tracker: the state the page
 * mounts with is not an event. 'connecting' is tracked but never recorded
 * (the history table has no such status). A transition to 'connected' or
 * 'disconnected' is recorded exactly once, labeled source 'simulation' —
 * exactly what the history table's source column distinguishes — and only
 * while the additional account is verified and a history client exists.
 * recordHistory applies its own fail-closed owner gate before inserting,
 * so an identity that fails the live check still records nothing.
 */
export function createSimTransitionRecorder(deps: {
  isVerified: () => boolean;
  getHistoryClient: () => SupabaseClient | null;
  onRecorded?: () => void;
}) {
  let previous: TunnelStatus | null = null;
  return {
    observe(status: TunnelStatus) {
      const prior = previous;
      previous = status;
      if (prior === null || prior === status) return;
      if (status !== 'connected' && status !== 'disconnected') return;
      if (!deps.isVerified()) return;
      const client = deps.getHistoryClient();
      if (!client) return;
      void recordHistory(client, { source: 'simulation', status })
        .then(() => deps.onRecorded?.())
        .catch(() => {});
    },
  };
}

/**
 * Route-level owner of the additional-account session.
 *
 * The dashboard route mounts this hook once per page session, so the
 * account client, the verified state and every feature client survive tab
 * switches; the AdditionalAccount section is only a view over this state
 * and its mount/unmount has no session side effects. Nothing signs out on
 * unmount — sign-out happens only in disconnect(), the user's explicit
 * "Disconnect additional account" action.
 *
 * The sim-transition recorder also lives here, next to the session: the
 * connect/disconnect control is used from the Home tab, where the Servers
 * section is not rendered, so recording cannot live in that section.
 */
export function useAdditionalSession(simStatus: TunnelStatus) {
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
  const [healthState, setHealthState] = useState<AdditionalFeatureState>('idle');
  const profilesClient = useRef<SupabaseClient | null>(null);
  const [profiles, setProfiles] = useState<RemoteProfile[] | null>(null);
  const [profilesState, setProfilesState] = useState<AdditionalFeatureState>('idle');
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState('');
  const historyClient = useRef<SupabaseClient | null>(null);
  const [history, setHistory] = useState<ConnectionHistoryRow[] | null>(null);
  const [historyState, setHistoryState] = useState<AdditionalFeatureState>('idle');
  const filesClient = useRef<SupabaseClient | null>(null);
  const [files, setFiles] = useState<UserFileEntry[] | null>(null);
  const [filesState, setFilesState] = useState<AdditionalFeatureState>('idle');
  const [fileBusy, setFileBusy] = useState(false);
  const [fileMessage, setFileMessage] = useState('');
  // Mirror of `verified` the recorder can read without re-subscribing.
  const verifiedRef = useRef(false);

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
            verifiedRef.current = true;
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
      // Deliberately no sign-out: the session belongs to the route, not to
      // whichever tab section happens to be mounted. Sign-out happens only
      // through disconnect(), the explicit user action.
      active = false;
      generation.current += 1;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  // Connection history records the tunnel simulation's real transitions.
  // The recorder is created once and reads the live verified flag and
  // history client through refs, so it stays correct as verification and
  // feature loading complete — and it keeps working while the Servers tab
  // (and its AdditionalAccount view) is unmounted.
  const recorder = useRef<ReturnType<typeof createSimTransitionRecorder> | null>(null);
  if (recorder.current === null) {
    recorder.current = createSimTransitionRecorder({
      isVerified: () => verifiedRef.current,
      getHistoryClient: () => historyClient.current,
      onRecorded: () => {
        const logClient = historyClient.current;
        if (!logClient) return;
        const current = generation.current;
        void listHistory(logClient)
          .then(rows => {
            if (current !== generation.current) return;
            setHistory(rows);
            setHistoryState('ready');
          })
          .catch(() => {});
      },
    });
  }
  useEffect(() => {
    recorder.current?.observe(simStatus);
  }, [simStatus]);

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

  async function pickFile(event: ChangeEvent<HTMLInputElement>) {
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

  async function submit(event: FormEvent) {
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
        verifiedRef.current = true;
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

  /**
   * The only sign-out path: the user's explicit disconnect (or "Start
   * again" during a pending verification). Signs out the account client
   * and every feature client, then starts over with a fresh account
   * client. Unmounting any view never calls this.
   */
  async function disconnect() {
    if (inFlight.current) return;
    generation.current++;
    const accountClient = client.current;
    client.current = createAdditionalAccountClient();
    const featureClients = [healthClient.current, profilesClient.current, historyClient.current, filesClient.current];
    healthClient.current = null;
    profilesClient.current = null;
    historyClient.current = null;
    filesClient.current = null;
    verifiedRef.current = false;
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

  return {
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
  };
}

export type AdditionalSession = ReturnType<typeof useAdditionalSession>;
