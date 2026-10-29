import { useSyncExternalStore } from 'react';
import { DexieStore } from './dexieStore';
import { httpTransport } from './httpTransport';
import { MemoryStore } from './localStore';
import { SyncEngine } from './syncEngine';

export const syncEngine = new SyncEngine(typeof indexedDB === 'undefined' ? new MemoryStore() : new DexieStore(), httpTransport);

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => syncEngine.handleOnline());
  window.addEventListener('offline', () => syncEngine.handleOffline());
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void syncEngine.sync();
  });
  window.addEventListener('beforeunload', (event) => {
    if (syncEngine.getState().unsavedSensitive) event.preventDefault();
  });
}

export function useSyncState() {
  return useSyncExternalStore(syncEngine.subscribe, syncEngine.getState);
}
