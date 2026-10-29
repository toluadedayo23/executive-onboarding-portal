import type { CaseStatus, Draft, Role, StepKey, StepState } from '../lib/types';
import type { OnFile } from './sensitive';

export type SessionRow = { userId: string; email: string; role: Role; consentGranted: boolean; lastServerContactAt: number };

export type LocalDraft = {
  userId: string;
  version: number;
  currentStep: number | null;
  status: CaseStatus;
  editable: boolean;
  moreInfoMessage: string | null;
  stepStatus: Partial<Record<StepKey, StepState>>;
  data: Partial<Record<StepKey, Record<string, unknown>>>;
  onFile: OnFile;
};

export type QueueEntry = {
  seq?: number;
  step: StepKey;
  data: Record<string, unknown>;
  currentStep: number;
  state: 'queued' | 'sent';
  key?: string;
  sentVersion?: number;
  hadSensitive: boolean;
};

export type SyncMeta = {
  conflict?: { serverDraft: Draft; steps: StepKey[] };
  blocked?: { code: string };
};

export interface LocalStore {
  getSession(): Promise<SessionRow | undefined>;
  putSession(session: SessionRow): Promise<void>;
  getDraft(): Promise<LocalDraft | undefined>;
  putDraft(draft: LocalDraft): Promise<void>;
  listQueue(): Promise<QueueEntry[]>;
  addQueue(entry: QueueEntry): Promise<number>;
  putQueue(entry: QueueEntry): Promise<void>;
  deleteQueue(seq: number): Promise<void>;
  clearQueue(): Promise<void>;
  getMeta(): Promise<SyncMeta>;
  putMeta(meta: SyncMeta): Promise<void>;
  wipe(): Promise<void>;
}

export class MemoryStore implements LocalStore {
  session?: SessionRow;
  draft?: LocalDraft;
  queue: QueueEntry[] = [];
  meta: SyncMeta = {};
  private nextSeq = 1;
  writes: unknown[] = [];

  async getSession() { return this.session; }
  async putSession(s: SessionRow) { this.writes.push(s); this.session = { ...s }; }
  async getDraft() { return this.draft && structuredClone(this.draft); }
  async putDraft(d: LocalDraft) { this.writes.push(d); this.draft = structuredClone(d); }
  async listQueue() { return this.queue.map((e) => structuredClone(e)); }
  async addQueue(e: QueueEntry) { this.writes.push(e); const seq = this.nextSeq++; this.queue.push({ ...structuredClone(e), seq }); return seq; }
  async putQueue(e: QueueEntry) { this.writes.push(e); this.queue = this.queue.map((q) => (q.seq === e.seq ? structuredClone(e) : q)); }
  async deleteQueue(seq: number) { this.queue = this.queue.filter((q) => q.seq !== seq); }
  async clearQueue() { this.queue = []; }
  async getMeta() { return structuredClone(this.meta); }
  async putMeta(m: SyncMeta) { this.writes.push(m); this.meta = structuredClone(m); }
  async wipe() { this.session = undefined; this.draft = undefined; this.queue = []; this.meta = {}; }
}
