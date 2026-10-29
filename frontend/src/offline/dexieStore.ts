import Dexie, { type Table } from 'dexie';
import type { LocalDraft, LocalStore, QueueEntry, SessionRow, SyncMeta } from './localStore';

class OnboardingDb extends Dexie {
  session!: Table<SessionRow, string>;
  draft!: Table<LocalDraft, string>;
  queue!: Table<QueueEntry, number>;
  meta!: Table<SyncMeta & { id: string }, string>;

  constructor() {
    super('exec-onboarding');
    this.version(1).stores({ session: 'userId', draft: 'userId', queue: '++seq', meta: 'id' });
  }
}

export class DexieStore implements LocalStore {
  private db = new OnboardingDb();

  async getSession() { return (await this.db.session.toArray())[0]; }
  async putSession(s: SessionRow) { await this.db.transaction('rw', this.db.session, async () => { await this.db.session.clear(); await this.db.session.put(s); }); }
  async getDraft() { return (await this.db.draft.toArray())[0]; }
  async putDraft(d: LocalDraft) { await this.db.transaction('rw', this.db.draft, async () => { await this.db.draft.clear(); await this.db.draft.put(d); }); }
  async listQueue() { return this.db.queue.orderBy('seq').toArray(); }
  async addQueue(e: QueueEntry) { return this.db.queue.add(e); }
  async putQueue(e: QueueEntry) { await this.db.queue.put(e); }
  async deleteQueue(seq: number) { await this.db.queue.delete(seq); }
  async clearQueue() { await this.db.queue.clear(); }
  async getMeta() { const { id: _id, ...meta } = (await this.db.meta.get('sync')) ?? { id: 'sync' }; return meta; }
  async putMeta(m: SyncMeta) { await this.db.meta.put({ ...m, id: 'sync' }); }
  async wipe() {
    await this.db.transaction('rw', [this.db.session, this.db.draft, this.db.queue, this.db.meta], async () => {
      await Promise.all([this.db.session.clear(), this.db.draft.clear(), this.db.queue.clear(), this.db.meta.clear()]);
    });
  }
}
