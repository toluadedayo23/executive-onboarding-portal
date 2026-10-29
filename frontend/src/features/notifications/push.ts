import { deletePushSubscription, savePushSubscription } from '../../lib/endpoints';

export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

async function pushRegistration(): Promise<ServiceWorkerRegistration> {
  const existing = await navigator.serviceWorker.getRegistration('/');
  if (!existing) await navigator.serviceWorker.register('/sw.js', { scope: '/' });
  return withTimeout(navigator.serviceWorker.ready, 10_000);
}

export async function currentSubscription(): Promise<PushSubscription | null> {
  return (await pushRegistration()).pushManager.getSubscription();
}

export async function registerWithServer(subscription: PushSubscription): Promise<void> {
  const json = subscription.toJSON();
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) throw new Error('Incomplete subscription');
  await savePushSubscription(json.endpoint, json.keys.p256dh, json.keys.auth);
}

export async function subscribeBrowser(publicKey: string): Promise<PushSubscription> {
  const registration = await pushRegistration();
  return registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(publicKey) });
}

export async function unsubscribeEverywhere(subscription: PushSubscription): Promise<void> {
  await deletePushSubscription(subscription.endpoint).catch(() => undefined);
  await subscription.unsubscribe().catch(() => undefined);
}

export async function unsubscribeOnLogout(): Promise<void> {
  if (!pushSupported()) return;
  try {
    const registration = await navigator.serviceWorker.getRegistration('/');
    const subscription = await registration?.pushManager.getSubscription();
    if (subscription) await withTimeout(unsubscribeEverywhere(subscription), 3_000);
  } catch {
  }
}

function base64UrlToBytes(value: string): Uint8Array {
  const base64 = (value + '='.repeat((4 - (value.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(base64);
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms);
    promise.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); reject(e); });
  });
}
