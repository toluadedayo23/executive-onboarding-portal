import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Draft, StepKey } from '../lib/types';
import { MemoryStore } from './localStore';
import { NetworkError, SyncEngine, type SendResult, type StepBody, type Transport } from './syncEngine';

class FakeServer implements Transport {
  version = 1;
  online = true;
  loseNextResponse = false;
  data: Record<string, unknown> = {};
  applied: { step: StepKey; key: string; version: number }[] = [];
  calls: { step: StepKey; key: string; body: StepBody }[] = [];
  private replies = new Map<string, SendResult>();

  async saveStep(step: StepKey, body: StepBody, key: string): Promise<SendResult> {
    if (!this.online) throw new NetworkError();
    this.calls.push({ step, key, body });
    const replay = this.replies.get(key);
    if (replay) return replay;
    if (body.version !== this.version) return { status: 409, body: { code: 'VERSION_CONFLICT' } };
    this.version++;
    this.data[step] = body.data;
    this.applied.push({ step, key, version: body.version });
    const reply: SendResult = { status: 200, body: { version: this.version, stepStatus: { [step]: 'IN_PROGRESS' } as never } };
    this.replies.set(key, reply);
    if (this.loseNextResponse) {
      this.loseNextResponse = false;
      throw new NetworkError();
    }
    return reply;
  }

  async getDraft(): Promise<Draft> {
    if (!this.online) throw new NetworkError();
    return draft(this.version, this.data);
  }

  async health() { return this.online; }

  sessionUserId: string | null = 'user-1';
  async currentUserId() {
    if (!this.online) throw new NetworkError();
    return this.sessionUserId;
  }
}

function draft(version: number, data: Record<string, unknown> = {}): Draft {
  return { status: 'DRAFT', editable: true, version, currentStep: 0, submittedAt: null, moreInfoMessage: null,
    stepStatus: { profile: 'NOT_STARTED', experience: 'NOT_STARTED', sectors: 'NOT_STARTED', qualifications: 'NOT_STARTED', referees: 'NOT_STARTED' },
    data } as Draft;
}

const noTimers = {
  setTimeout: (() => 0) as unknown as typeof setTimeout, clearTimeout: (() => undefined) as typeof clearTimeout,
  setInterval: (() => 0) as unknown as typeof setInterval, clearInterval: (() => undefined) as typeof clearInterval,
};
const exec = { id: 'user-1', email: 'exec1@example.test', role: 'EXECUTIVE' as const };

let store: MemoryStore;
let server: FakeServer;
let engine: SyncEngine;
let keys: number;

beforeEach(async () => {
  store = new MemoryStore();
  server = new FakeServer();
  keys = 0;
  engine = new SyncEngine(store, server, noTimers, () => `key-${++keys}`);
  await engine.start(exec);
  await engine.adoptServerDraft(draft(1));
});

describe('SyncEngine', () => {
  it('queues edits made offline and, on reconnect, sends them in order exactly once with chained versions', async () => {
    server.online = false;
    await engine.saveStep('profile', { fullName: 'Ada' }, 1);
    await engine.saveStep('experience', { items: [] }, 2);
    await engine.sync();
    expect(engine.getState()).toMatchObject({ online: false, pending: 2 });

    server.online = true;
    engine.handleOnline();
    await engine.sync();

    expect(server.applied).toEqual([
      { step: 'profile', key: 'key-1', version: 1 },
      { step: 'experience', key: 'key-2', version: 2 },
    ]);
    expect(engine.getState()).toMatchObject({ pending: 0, phase: 'idle', lastSynced: { count: 2 } });
    expect(engine.getState().draft!.version).toBe(3);
  });

  it('retries a send whose answer was lost with the SAME key and version; later edits queue behind it', async () => {
    server.loseNextResponse = true;
    await engine.saveStep('profile', { fullName: 'Ada' }, 1);
    await engine.sync();
    await engine.saveStep('profile', { fullName: 'Ada Synthetic' }, 1);

    engine.handleOnline();
    await engine.sync();

    expect(server.calls.map((c) => [c.key, c.body.version, c.body.data.fullName])).toEqual([
      ['key-1', 1, 'Ada'],
      ['key-1', 1, 'Ada'],
      ['key-2', 2, 'Ada Synthetic'],
    ]);
    expect(server.applied).toHaveLength(2);
    expect(server.version).toBe(3);
  });

  it('stops on a version conflict; "keep mine" rebases with a new key, "use server\'s" drops local changes', async () => {
    server.version = 5;
    await engine.saveStep('profile', { fullName: 'Mine' }, 1);
    await engine.sync();
    expect(engine.getState()).toMatchObject({ phase: 'conflict', conflictSteps: ['profile'] });
    expect((await store.getMeta()).conflict).toBeDefined();

    await engine.resolveConflict('mine');
    await engine.sync();
    expect(server.applied).toEqual([{ step: 'profile', key: 'key-2', version: 5 }]);
    expect(engine.getState()).toMatchObject({ phase: 'idle', pending: 0 });

    server.version = 9;
    server.data = { profile: { fullName: 'Theirs' } };
    await engine.saveStep('profile', { fullName: 'Mine again' }, 1);
    await engine.sync();
    await engine.resolveConflict('server');
    expect(store.queue).toHaveLength(0);
    expect(engine.getState().draft).toMatchObject({ version: 9, data: { profile: { fullName: 'Theirs' } } });
  });

  it('wipes the device store when a different user signs in, and on sign-out', async () => {
    server.online = false;
    await engine.saveStep('profile', { fullName: 'Ada' }, 1);
    expect(store.queue).toHaveLength(1);

    await engine.start({ id: 'user-2', email: 'exec2@example.test', role: 'EXECUTIVE' });
    expect(store.queue).toHaveLength(0);
    expect(store.draft).toBeUndefined();
    expect(store.session?.userId).toBe('user-2');

    await engine.wipe();
    expect(store.session).toBeUndefined();
  });

  it('never writes sensitive values to the device, but sends them to the server', async () => {
    await engine.saveStep('profile', { fullName: 'Ada', nationalId: 'NIN-SECRET-123' }, 1);
    await engine.saveStep('referees', { items: [{ id: 'r1', name: 'Ref', email: 'secret.ref@example.test', phone: '+234 800 000 9999' }] }, 5);
    await engine.saveStep('profile', { fullName: 'Ada', nationalId: '••••-123' }, 1);
    await engine.sync();

    const everythingStored = JSON.stringify(store.writes);
    for (const secret of ['NIN-SECRET-123', 'secret.ref@example.test', '+234 800 000 9999']) {
      expect(everythingStored).not.toContain(secret);
    }
    const sent = JSON.stringify(server.calls.map((c) => c.body));
    expect(sent).toContain('NIN-SECRET-123');
    expect(sent).toContain('secret.ref@example.test');
    expect(sent).not.toContain('••••');
  });
});

describe('SyncEngine and the shared session cookie', () => {
  it('sends nothing when the signed-in user is no longer the owner of the queue', async () => {
    server.sessionUserId = 'user-2';
    await engine.saveStep('profile', { fullName: 'Ada' }, 1);
    await engine.sync();
    expect(server.calls).toHaveLength(0);
    expect(engine.getState()).toMatchObject({ phase: 'paused-auth', pending: 1 });

    server.sessionUserId = 'user-1';
    engine.resume();
    await engine.sync();
    expect(server.applied).toHaveLength(1);
  });
});

describe('SyncEngine keeps stored sensitive values', () => {
  it('sends "On file" for sensitive values that are on file and were not retyped', async () => {
    const withValues = draft(1, {
      profile: { fullName: 'Ada', nationalId: '••••1234' },
      referees: { items: [{ id: 'r1', name: 'Ref', email: '••••@example.test', phone: '••••0001' }] },
    });
    await engine.adoptServerDraft(withValues);
    await engine.saveStep('referees', { items: [{ id: 'r1', name: 'Ref renamed', email: '••••@example.test', phone: '+234 800 000 9999' }] }, 5);
    await engine.saveStep('profile', { fullName: 'Ada Synthetic', nationalId: '••••1234' }, 1);
    await engine.sync();

    const [referees, profile] = server.calls.map((c) => c.body.data);
    expect(referees.items).toEqual([{ id: 'r1', name: 'Ref renamed', email: 'On file', phone: '+234 800 000 9999' }]);
    expect(profile).toEqual({ fullName: 'Ada Synthetic', nationalId: 'On file' });
    expect(JSON.stringify(store.writes)).not.toContain('+234 800 000 9999');
  });
});

describe('SyncEngine with real timers', () => {
  it('can start offline and schedule a retry using the default timers', async () => {
    for (const name of ['setTimeout', 'setInterval'] as const) {
      const native = globalThis[name];
      vi.stubGlobal(name, function (this: unknown, ...args: Parameters<typeof native>) {
        if (this !== undefined && this !== globalThis) throw new TypeError('Illegal invocation');
        return (native as (...a: unknown[]) => unknown)(...args);
      });
    }
    const real = new SyncEngine(new MemoryStore(), Object.assign(new FakeServer(), { online: false }));
    await real.start(exec, { offline: true });
    await real.adoptServerDraft(draft(1));
    await real.saveStep('profile', { fullName: 'Ada' }, 1);
    await expect(real.sync()).resolves.toBeUndefined();
    expect(real.getState()).toMatchObject({ online: false, phase: 'waiting', pending: 1 });
    await real.wipe();
    vi.unstubAllGlobals();
  });
});

describe('Use server\'s after a conflict', () => {
  it('keeps the "on file" flags so later saves do not erase stored sensitive values', async () => {
    server.version = 4;
    server.data = { profile: { fullName: 'Theirs', nationalId: '••••1234' } };
    await engine.saveStep('profile', { fullName: 'Mine' }, 1);
    await engine.sync();
    expect(store.meta.conflict).toBeDefined();
    expect(JSON.stringify(store.meta)).not.toContain('••••');
    await engine.resolveConflict('server');
    expect(engine.getState().draft?.onFile.nationalId).toBe(true);
  });
});
