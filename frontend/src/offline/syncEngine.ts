import type { Draft, Role, StepKey, StepState } from '../lib/types';
import type { LocalDraft, LocalStore, QueueEntry, SessionRow } from './localStore';
import { markKeptValues, mergeSensitive, splitSensitive, stripDraftData, type OnFile, type SensitiveValues } from './sensitive';

export const OFFLINE_MAX_HOURS = 12;
const RETRY_START_MS = 2_000;
const RETRY_MAX_MS = 60_000;
const PROBE_MS = 15_000;

export class NetworkError extends Error {}

export type SendResult = { status: number; body: { code?: string; version?: number; stepStatus?: Record<StepKey, StepState> } };
export type StepBody = { version: number; currentStep: number; data: Record<string, unknown> };

export interface Transport {
  saveStep(step: StepKey, body: StepBody, idempotencyKey: string): Promise<SendResult>;
  getDraft(): Promise<Draft>;
  health(): Promise<boolean>;
  currentUserId(): Promise<string | null>;
}

export type SyncPhase = 'idle' | 'syncing' | 'waiting' | 'paused-auth' | 'paused-consent' | 'blocked' | 'conflict';

export type SyncState = {
  ready: boolean;
  online: boolean;
  phase: SyncPhase;
  pending: number;
  draft?: LocalDraft;
  conflictSteps: StepKey[];
  blockedCode?: string;
  unsavedSensitive: boolean;
  lostSensitive: boolean;
  lastSynced: { count: number; at: number };
};

const INITIAL: SyncState = {
  ready: false, online: true, phase: 'idle', pending: 0, conflictSteps: [],
  unsavedSensitive: false, lostSensitive: false, lastSynced: { count: 0, at: 0 },
};

type Timers = { setTimeout: typeof setTimeout; clearTimeout: typeof clearTimeout; setInterval: typeof setInterval; clearInterval: typeof clearInterval };
const PAUSED: SyncPhase[] = ['paused-auth', 'paused-consent', 'blocked', 'conflict'];

export class SyncEngine {
  private state: SyncState = INITIAL;
  private listeners = new Set<() => void>();
  private sensitiveBySeq = new Map<number, SensitiveValues>();
  private running: Promise<void> | null = null;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private probeTimer?: ReturnType<typeof setInterval>;
  private failures = 0;
  private userId?: string;
  private lastTouch = 0;

  constructor(
    private readonly store: LocalStore,
    private readonly transport: Transport,
    // Bound: browsers throw "Illegal invocation" if native timers get another `this`
    private readonly timers: Timers = {
      setTimeout: globalThis.setTimeout.bind(globalThis), clearTimeout: globalThis.clearTimeout.bind(globalThis),
      setInterval: globalThis.setInterval.bind(globalThis), clearInterval: globalThis.clearInterval.bind(globalThis),
    },
    private readonly newKey: () => string = () => crypto.randomUUID(),
  ) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  getState = () => this.state;

  async start(user: { id: string; email: string; role: Role }, options: { offline?: boolean } = {}) {
    const session = await this.store.getSession();
    if (session && session.userId !== user.id) await this.wipe();
    this.userId = user.id;
    if (!options.offline) {
      await this.store.putSession({ userId: user.id, email: user.email, role: user.role,
        consentGranted: session?.userId === user.id ? session.consentGranted : false, lastServerContactAt: Date.now() });
      this.lastTouch = Date.now();
    }
    const [draft, queue, meta] = await Promise.all([this.store.getDraft(), this.store.listQueue(), this.store.getMeta()]);
    this.set({
      ready: true, online: !options.offline, draft, pending: queue.length,
      phase: meta.conflict ? 'conflict' : meta.blocked ? 'blocked' : 'idle',
      conflictSteps: meta.conflict?.steps ?? [], blockedCode: meta.blocked?.code,
      lostSensitive: queue.some((e) => e.hadSensitive), unsavedSensitive: false,
    });
    if (options.offline) this.goOffline();
    else if (queue.length) void this.sync();
  }

  async offlineSession(now = Date.now()): Promise<SessionRow | undefined> {
    const session = await this.store.getSession();
    if (!session || session.role !== 'EXECUTIVE') return undefined;
    return now - session.lastServerContactAt < OFFLINE_MAX_HOURS * 3_600_000 ? session : undefined;
  }

  async rememberConsent(granted: boolean) {
    const session = await this.store.getSession();
    if (session && session.consentGranted !== granted) await this.store.putSession({ ...session, consentGranted: granted });
  }

  async adoptServerDraft(server: Draft) {
    if (!this.userId) return;
    this.markOnline();
    const [queue, meta] = await Promise.all([this.store.listQueue(), this.store.getMeta()]);
    const current = this.state.draft;
    let draft: LocalDraft;
    if (queue.length === 0 && !meta.conflict) {
      draft = this.fromServer(server);
    } else if (current) {
      draft = { ...current, status: server.status, editable: server.editable, moreInfoMessage: server.moreInfoMessage, stepStatus: server.stepStatus };
    } else {
      draft = this.fromServer(server);
    }
    await this.store.putDraft(draft);
    this.set({ draft });
  }

  async saveStep(step: StepKey, formData: Record<string, unknown>, currentStep: number) {
    const current = this.state.draft;
    if (!current) throw new Error('No draft loaded');
    const { safe, sensitive } = splitSensitive(step, formData);
    const draft: LocalDraft = {
      ...current, currentStep, data: { ...current.data, [step]: safe }, onFile: withNewValues(current.onFile, sensitive),
      stepStatus: { ...current.stepStatus, [step]: current.stepStatus[step] === 'COMPLETE' ? 'COMPLETE' : 'IN_PROGRESS' },
    };
    this.set({ draft });
    await this.store.putDraft(this.state.draft!);

    const queue = await this.store.listQueue();
    const open = [...queue].reverse().find((e) => e.step === step && e.state === 'queued');
    let seq: number;
    if (open) {
      seq = open.seq!;
      await this.store.putQueue({ ...open, data: safe, currentStep, hadSensitive: open.hadSensitive || Boolean(sensitive) });
    } else {
      seq = await this.store.addQueue({ step, data: safe, currentStep, state: 'queued', hadSensitive: Boolean(sensitive) });
    }
    if (sensitive) this.sensitiveBySeq.set(seq, mergeValues(this.sensitiveBySeq.get(seq), sensitive));
    this.set({ pending: open ? queue.length : queue.length + 1, unsavedSensitive: this.sensitiveBySeq.size > 0 });
    void this.sync();
  }

  sync(): Promise<void> {
    if (!this.running) this.running = this.run().finally(() => { this.running = null; });
    return this.running;
  }

  async flush(timeoutMs = 5_000): Promise<number> {
    await Promise.race([this.sync(), new Promise((r) => this.timers.setTimeout(r, timeoutMs))]);
    return this.state.pending;
  }

  canSubmit() {
    return this.state.online && this.state.pending === 0 && this.state.phase === 'idle';
  }

  resume() {
    if (this.state.phase === 'paused-auth' || this.state.phase === 'paused-consent') {
      this.set({ phase: 'idle' });
      void this.sync();
    }
  }

  async resolveConflict(choice: 'mine' | 'server') {
    const meta = await this.store.getMeta();
    const server = meta.conflict?.serverDraft;
    if (!server) return;
    if (choice === 'mine') {
      for (const entry of await this.store.listQueue()) {
        await this.store.putQueue({ ...entry, state: 'queued', key: undefined, sentVersion: undefined });
      }
      const draft = { ...this.state.draft!, version: server.version, status: server.status, editable: server.editable, stepStatus: server.stepStatus };
      await this.store.putDraft(draft);
      await this.store.putMeta({});
      this.set({ draft, phase: 'idle', conflictSteps: [] });
      void this.sync();
    } else {
      await this.replaceWithServer(await this.transport.getDraft());
    }
  }

  async discard() {
    await this.replaceWithServer(await this.transport.getDraft());
  }

  async wipe() {
    this.clearTimers();
    this.sensitiveBySeq.clear();
    this.userId = undefined;
    await this.store.wipe();
    this.set({ ...INITIAL });
  }

  handleOnline() {
    this.markOnline();
    void this.sync();
  }

  handleOffline() {
    this.goOffline();
  }

  private async run() {
    if (PAUSED.includes(this.state.phase)) return;
    let synced = 0;
    let identityChecked = false;
    for (;;) {
      const queue = await this.store.listQueue();
      if (queue.length === 0) {
        this.set({ phase: 'idle', pending: 0, unsavedSensitive: false, lostSensitive: false,
          ...(synced ? { lastSynced: { count: synced, at: Date.now() } } : {}) });
        return;
      }
      this.set({ phase: 'syncing', pending: queue.length, lostSensitive: queue.some((e) => e.hadSensitive && !this.sensitiveBySeq.has(e.seq!)) });

      // Tabs share one cookie: never send this queue under another user's session
      if (!identityChecked) {
        let sessionUser: string | null;
        try {
          sessionUser = await this.transport.currentUserId();
        } catch (error) {
          if (!(error instanceof NetworkError)) throw error;
          this.set({ phase: 'waiting' });
          this.goOffline();
          this.scheduleRetry();
          return;
        }
        if (sessionUser !== this.userId) return this.set({ phase: 'paused-auth' });
        identityChecked = true;
      }

      let head: QueueEntry = queue[0];
      if (head.state === 'queued') {
        head = { ...head, state: 'sent', key: this.newKey(), sentVersion: this.state.draft!.version };
        await this.store.putQueue(head);
      }
      const body: StepBody = {
        version: head.sentVersion!,
        currentStep: head.currentStep,
        data: markKeptValues(head.step, mergeSensitive(head.step, head.data, this.sensitiveBySeq.get(head.seq!)), this.state.draft!.onFile),
      };

      let result: SendResult;
      try {
        result = await this.transport.saveStep(head.step, body, head.key!);
      } catch (error) {
        if (!(error instanceof NetworkError)) throw error;
        this.set({ phase: 'waiting' });
        this.goOffline();
        this.scheduleRetry();
        return;
      }
      this.markOnline();
      const code = result.body?.code;

      if (result.status >= 200 && result.status < 300) {
        await this.acknowledge(head, result.body.version!, result.body.stepStatus);
        synced++;
        continue;
      }
      if (result.status >= 500 || result.status === 429) {
        this.set({ phase: 'waiting' });
        this.scheduleRetry();
        return;
      }
      if (result.status === 401) return this.set({ phase: 'paused-auth' });
      if (result.status === 403 && code === 'CONSENT_REQUIRED') return this.set({ phase: 'paused-consent' });
      if (result.status === 409 && code === 'VERSION_CONFLICT') return this.enterConflict(queue);
      if (result.status === 422 && code === 'IDEMPOTENCY_KEY_REUSED' && head.hadSensitive && !this.sensitiveBySeq.has(head.seq!)) {
        const server = await this.transport.getDraft();
        await this.acknowledge(head, server.version, server.stepStatus);
        continue;
      }
      await this.store.putMeta({ blocked: { code: code ?? `HTTP_${result.status}` } });
      return this.set({ phase: 'blocked', blockedCode: code ?? `HTTP_${result.status}` });
    }
  }

  private async acknowledge(head: QueueEntry, version: number, stepStatus?: Record<StepKey, StepState>) {
    await this.store.deleteQueue(head.seq!);
    this.sensitiveBySeq.delete(head.seq!);
    this.set({ draft: { ...this.state.draft!, version, stepStatus: stepStatus ?? this.state.draft!.stepStatus },
      unsavedSensitive: this.sensitiveBySeq.size > 0 });
    await this.store.putDraft(this.state.draft!);
    this.failures = 0;
    await this.touch();
  }

  private async enterConflict(queue: QueueEntry[]) {
    let serverDraft: Draft;
    try {
      serverDraft = await this.transport.getDraft();
    } catch {
      this.goOffline();
      this.scheduleRetry();
      return;
    }
    const steps = [...new Set(queue.map((e) => e.step))];
    const stored = { ...serverDraft, data: stripDraftData(serverDraft.data as Partial<Record<StepKey, Record<string, unknown>>>).data };
    await this.store.putMeta({ conflict: { serverDraft: stored, steps } });
    this.set({ phase: 'conflict', conflictSteps: steps });
  }

  private async replaceWithServer(server: Draft) {
    await this.store.clearQueue();
    this.sensitiveBySeq.clear();
    const draft = this.fromServer(server);
    await this.store.putDraft(draft);
    await this.store.putMeta({});
    this.set({ draft, pending: 0, phase: 'idle', conflictSteps: [], blockedCode: undefined, unsavedSensitive: false, lostSensitive: false });
  }

  private fromServer(server: Draft): LocalDraft {
    const { data, onFile } = stripDraftData(server.data as Partial<Record<StepKey, Record<string, unknown>>>);
    return {
      userId: this.userId!, version: server.version, currentStep: server.currentStep, status: server.status,
      editable: server.editable, moreInfoMessage: server.moreInfoMessage, stepStatus: server.stepStatus, data, onFile,
    };
  }

  private async touch() {
    if (Date.now() - this.lastTouch < 60_000) return;
    const session = await this.store.getSession();
    if (session) await this.store.putSession({ ...session, lastServerContactAt: Date.now() });
    this.lastTouch = Date.now();
  }

  private scheduleRetry() {
    this.failures++;
    const delay = Math.min(RETRY_START_MS * 2 ** (this.failures - 1), RETRY_MAX_MS);
    if (this.retryTimer) this.timers.clearTimeout(this.retryTimer);
    this.retryTimer = this.timers.setTimeout(() => { void this.sync(); }, delay);
  }

  private goOffline() {
    if (this.state.online) this.set({ online: false });
    if (this.probeTimer) return;
    this.probeTimer = this.timers.setInterval(async () => {
      if (await this.transport.health().catch(() => false)) this.handleOnline();
    }, PROBE_MS);
  }

  private markOnline() {
    if (this.probeTimer) {
      this.timers.clearInterval(this.probeTimer);
      this.probeTimer = undefined;
    }
    if (!this.state.online) this.set({ online: true });
  }

  private clearTimers() {
    if (this.retryTimer) this.timers.clearTimeout(this.retryTimer);
    if (this.probeTimer) this.timers.clearInterval(this.probeTimer);
    this.retryTimer = undefined;
    this.probeTimer = undefined;
    this.failures = 0;
  }

  private set(patch: Partial<SyncState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener());
  }
}

function mergeValues(a: SensitiveValues | undefined, b: SensitiveValues): SensitiveValues {
  if (!a) return b;
  const referees = { ...a.referees };
  for (const [id, values] of Object.entries(b.referees ?? {})) referees[id] = { ...referees[id], ...values };
  return { nationalId: b.nationalId ?? a.nationalId, ...(Object.keys(referees).length ? { referees } : {}) };
}

function withNewValues(onFile: OnFile, sensitive: SensitiveValues | null): OnFile {
  if (!sensitive) return onFile;
  const referees = { ...onFile.referees };
  for (const [id, values] of Object.entries(sensitive.referees ?? {})) {
    referees[id] = { email: Boolean(referees[id]?.email || values.email), phone: Boolean(referees[id]?.phone || values.phone) };
  }
  return { nationalId: onFile.nationalId || Boolean(sensitive.nationalId), referees };
}
