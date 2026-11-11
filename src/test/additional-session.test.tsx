import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AdditionalAccount } from '../components/tunnel/AdditionalAccount';
import { useAdditionalSession } from '../lib/use-additional-session';
import type { TunnelStatus } from '../lib/use-tunnel-sim';

/**
 * The client factories are replaced with shared fakes so the tests can see
 * every signOut; all verification gates, the session adoption helper and the
 * URL handling stay real (the settings check runs against the stubbed
 * fetch below, exactly like the additional-account tests).
 */
const h = vi.hoisted(() => {
  const makeFeatureClient = () => ({
    auth: {
      signOut: vi.fn().mockResolvedValue({ error: null }),
      setSession: vi.fn().mockResolvedValue({ data: {}, error: null }),
    },
  });
  return {
    accountClient: {
      auth: {
        signOut: vi.fn().mockResolvedValue({ error: null }),
        getSession: vi.fn().mockResolvedValue({ data: { session: { access_token: 'at', refresh_token: 'rt' } }, error: null }),
        setSession: vi.fn().mockResolvedValue({ data: {}, error: null }),
        signInWithOtp: vi.fn().mockResolvedValue({ data: {}, error: null }),
      },
    },
    healthClient: makeFeatureClient(),
    profilesClient: makeFeatureClient(),
    historyClient: makeFeatureClient(),
    filesClient: makeFeatureClient(),
    completeReturn: vi.fn(),
    recordHistory: vi.fn().mockResolvedValue({ id: 'h1', source: 'simulation', status: 'connected', error_category: null, occurred_at: '2026-11-11T05:00:00Z' }),
  };
});

vi.mock('../lib/additional-account', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/additional-account')>();
  return { ...actual, createAdditionalAccountClient: vi.fn(() => h.accountClient), completeAdditionalEmailReturn: h.completeReturn };
});
vi.mock('../lib/additional-monitoring', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/additional-monitoring')>();
  return { ...actual, createAdditionalHealthClient: vi.fn(() => h.healthClient), fetchLatestServerHealth: vi.fn().mockResolvedValue([]) };
});
vi.mock('../lib/additional-profiles', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/additional-profiles')>();
  return {
    ...actual,
    createAdditionalProfilesClient: vi.fn(() => h.profilesClient),
    listProfiles: vi.fn().mockResolvedValue([]),
    syncProfiles: vi.fn().mockResolvedValue({ uploaded: 0, alreadyPresent: 0, skipped: 0, profiles: [] }),
    readLocalSavedProfiles: vi.fn(() => []),
    deleteProfile: vi.fn().mockResolvedValue(undefined),
  };
});
vi.mock('../lib/additional-history', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/additional-history')>();
  return { ...actual, createAdditionalHistoryClient: vi.fn(() => h.historyClient), listHistory: vi.fn().mockResolvedValue([]), recordHistory: h.recordHistory };
});
vi.mock('../lib/additional-files', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/additional-files')>();
  return {
    ...actual,
    createAdditionalFilesClient: vi.fn(() => h.filesClient),
    listFiles: vi.fn().mockResolvedValue([]),
    uploadFile: vi.fn(),
    downloadFile: vi.fn(),
    deleteFile: vi.fn().mockResolvedValue(undefined),
  };
});

const settingsOk = () => Response.json({ external: { email: true }, mailer_autoconfirm: false, disable_signup: false });

/** Mirrors the route: the session hook stays mounted while the Servers-tab view comes and goes. */
function TogglingHarness() {
  const session = useAdditionalSession('disconnected');
  const [show, setShow] = useState(true);
  return (
    <div>
      {show && <AdditionalAccount session={session} />}
      <button type="button" onClick={() => setShow((value) => !value)}>toggle view</button>
    </div>
  );
}

/** Mirrors the route's sim wiring: status changes happen while the session hook is mounted. */
function SimHarness() {
  const [status, setStatus] = useState<TunnelStatus>('disconnected');
  const session = useAdditionalSession(status);
  return (
    <div>
      <AdditionalAccount session={session} />
      <button type="button" onClick={() => setStatus('connecting')}>to-connecting</button>
      <button type="button" onClick={() => setStatus('connected')}>to-connected</button>
      <button type="button" onClick={() => setStatus('disconnected')}>to-disconnected</button>
    </div>
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(settingsOk()));
  h.completeReturn.mockReset();
  h.recordHistory.mockClear();
  for (const client of [h.accountClient, h.healthClient, h.profilesClient, h.historyClient, h.filesClient]) {
    client.auth.signOut.mockClear();
  }
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('route-level additional session', () => {
  it('keeps the verified session when the view unmounts; only explicit disconnect signs out', async () => {
    h.completeReturn.mockResolvedValue({ id: 'owner', email: 'owner@example.com' });
    window.history.replaceState(null, '', '/?additional_confirm=1&token_hash=hash-1&type=email');
    render(<TogglingHarness />);

    await screen.findByText('owner@example.com');
    // Wait until every feature block has loaded, so all feature clients exist.
    await screen.findByText(/PRIVATE FILES/);
    expect(screen.getByRole('button', { name: 'Disconnect additional account' })).toBeTruthy();

    // Tab switch: the view unmounts, the session must not.
    fireEvent.click(screen.getByText('toggle view'));
    expect(screen.queryByText('owner@example.com')).toBeNull();
    expect(h.accountClient.auth.signOut).not.toHaveBeenCalled();
    expect(h.healthClient.auth.signOut).not.toHaveBeenCalled();
    expect(h.profilesClient.auth.signOut).not.toHaveBeenCalled();
    expect(h.historyClient.auth.signOut).not.toHaveBeenCalled();
    expect(h.filesClient.auth.signOut).not.toHaveBeenCalled();

    // Back on the Servers tab the account is still verified.
    fireEvent.click(screen.getByText('toggle view'));
    await screen.findByText('owner@example.com');
    expect(h.accountClient.auth.signOut).not.toHaveBeenCalled();

    // Explicit disconnect signs out the account and every feature client.
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect additional account' }));
    // disconnect() signs out sequentially; wait for the last feature client.
    await vi.waitFor(() => expect(h.filesClient.auth.signOut).toHaveBeenCalledTimes(1));
    expect(h.accountClient.auth.signOut).toHaveBeenCalledTimes(1);
    expect(h.accountClient.auth.signOut).toHaveBeenCalledWith({ scope: 'local' });
    expect(h.healthClient.auth.signOut).toHaveBeenCalledTimes(1);
    expect(h.profilesClient.auth.signOut).toHaveBeenCalledTimes(1);
    expect(h.historyClient.auth.signOut).toHaveBeenCalledTimes(1);
    expect(h.filesClient.auth.signOut).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('owner@example.com')).toBeNull();
  });

  it('records sim transitions from the route-level hook while verified', async () => {
    h.completeReturn.mockResolvedValue({ id: 'owner', email: 'owner@example.com' });
    window.history.replaceState(null, '', '/?additional_confirm=1&token_hash=hash-1&type=email');
    render(<SimHarness />);
    await screen.findByText('owner@example.com');
    // Let feature loading finish so the history client exists.
    await vi.waitFor(() => expect(screen.getByText(/History ·/)).toBeTruthy());

    fireEvent.click(screen.getByText('to-connecting'));
    expect(h.recordHistory).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('to-connected'));
    await vi.waitFor(() => expect(h.recordHistory).toHaveBeenCalledTimes(1));
    expect(h.recordHistory).toHaveBeenCalledWith(h.historyClient, { source: 'simulation', status: 'connected' });

    fireEvent.click(screen.getByText('to-disconnected'));
    await vi.waitFor(() => expect(h.recordHistory).toHaveBeenCalledTimes(2));
    expect(h.recordHistory).toHaveBeenLastCalledWith(h.historyClient, { source: 'simulation', status: 'disconnected' });
  });

  it('records nothing while unverified', async () => {
    window.history.replaceState(null, '', '/');
    render(<SimHarness />);
    fireEvent.click(screen.getByText('to-connecting'));
    fireEvent.click(screen.getByText('to-connected'));
    fireEvent.click(screen.getByText('to-disconnected'));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    expect(h.completeReturn).not.toHaveBeenCalled();
    expect(h.recordHistory).not.toHaveBeenCalled();
  });
});
