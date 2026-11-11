import { describe, expect, it, vi } from 'vitest';
import type { SupabaseClient } from '@supabase/supabase-js';
import { createSimTransitionRecorder } from '../lib/use-additional-session';

/** Drain pending microtasks so the recorder's fire-and-forget insert settles. */
const flush = async () => {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
};

const confirmedOwner = {
  data: { user: { id: 'owner', email: 'owner@example.com', email_confirmed_at: '2026-11-01', is_anonymous: false } },
  error: null,
};

function historyClient(user: unknown = confirmedOwner.data.user) {
  const row = { id: 'h1', source: 'simulation', status: 'connected', error_category: null, occurred_at: '2026-11-11T05:00:00Z' };
  const single = vi.fn(() => Promise.resolve({ data: row, error: null }));
  const select = vi.fn(() => ({ single }));
  const insert = vi.fn(() => ({ select }));
  const from = vi.fn(() => ({ insert }));
  const client = { auth: { getUser: vi.fn().mockResolvedValue({ data: { user }, error: null }) }, from } as unknown as SupabaseClient;
  return { client, from, insert };
}

describe('sim transition recorder', () => {
  it('records exactly one connected and one disconnected row per real cycle, labeled simulation', async () => {
    const { client, insert } = historyClient();
    const onRecorded = vi.fn();
    const recorder = createSimTransitionRecorder({ isVerified: () => true, getHistoryClient: () => client, onRecorded });

    recorder.observe('disconnected'); // mount state: seeds only
    recorder.observe('connecting'); // not a recordable status
    recorder.observe('connected');
    recorder.observe('connected'); // repeat observation is not a new transition
    await flush();
    expect(insert).toHaveBeenCalledTimes(1);
    expect(insert).toHaveBeenCalledWith({ source: 'simulation', status: 'connected', error_category: null });
    expect(onRecorded).toHaveBeenCalledTimes(1);

    recorder.observe('disconnected');
    await flush();
    expect(insert).toHaveBeenCalledTimes(2);
    expect(insert).toHaveBeenLastCalledWith({ source: 'simulation', status: 'disconnected', error_category: null });
    expect(onRecorded).toHaveBeenCalledTimes(2);
  });

  it('records nothing while the additional account is unverified', async () => {
    const { client, insert } = historyClient();
    const recorder = createSimTransitionRecorder({ isVerified: () => false, getHistoryClient: () => client });
    recorder.observe('disconnected');
    recorder.observe('connecting');
    recorder.observe('connected');
    recorder.observe('disconnected');
    await flush();
    expect(insert).not.toHaveBeenCalled();
  });

  it('treats the first observed status as the mount state, never an event', async () => {
    const { client, insert } = historyClient();
    const recorder = createSimTransitionRecorder({ isVerified: () => true, getHistoryClient: () => client });
    recorder.observe('connected'); // page mounted already connected: no row
    await flush();
    expect(insert).not.toHaveBeenCalled();
    recorder.observe('disconnected'); // the later real transition does record
    await flush();
    expect(insert).toHaveBeenCalledTimes(1);
    expect(insert).toHaveBeenCalledWith({ source: 'simulation', status: 'disconnected', error_category: null });
  });

  it('never records the connecting state', async () => {
    const { client, insert } = historyClient();
    const recorder = createSimTransitionRecorder({ isVerified: () => true, getHistoryClient: () => client });
    recorder.observe('disconnected');
    recorder.observe('connecting');
    await flush();
    expect(insert).not.toHaveBeenCalled();
  });

  it('records nothing when no history client exists yet', async () => {
    const recorder = createSimTransitionRecorder({ isVerified: () => true, getHistoryClient: () => null });
    recorder.observe('disconnected');
    recorder.observe('connected');
    recorder.observe('disconnected');
    await flush(); // must settle without throwing
  });

  it('keeps the fail-closed owner gate in the record path: an unconfirmed live identity inserts nothing', async () => {
    const { client, from, insert } = historyClient({ id: 'external', email_confirmed_at: null, is_anonymous: false });
    const onRecorded = vi.fn();
    const recorder = createSimTransitionRecorder({ isVerified: () => true, getHistoryClient: () => client, onRecorded });
    recorder.observe('disconnected');
    recorder.observe('connected');
    await flush();
    expect(from).not.toHaveBeenCalled();
    expect(insert).not.toHaveBeenCalled();
    expect(onRecorded).not.toHaveBeenCalled();
  });
});
