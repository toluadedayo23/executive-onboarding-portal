import { useEffect, useState } from 'react';
import { ApiError, isNetworkError } from '../../lib/api';
import { getPushPublicKey } from '../../lib/endpoints';
import { currentSubscription, pushSupported, registerWithServer, subscribeBrowser, unsubscribeEverywhere } from './push';

type State = 'checking' | 'unsupported' | 'server-disabled' | 'offline' | 'blocked' | 'off' | 'enabling' | 'on' | 'disabling';

const BADGE: Partial<Record<State, { label: string; tone: string }>> = {
  on: { label: 'On', tone: 'VERIFIED' },
  off: { label: 'Off', tone: 'DRAFT' },
  blocked: { label: 'Blocked', tone: 'MORE_INFO_REQUIRED' },
  unsupported: { label: 'Unavailable', tone: 'DRAFT' },
  'server-disabled': { label: 'Unavailable', tone: 'DRAFT' },
};

export function NotificationsCard() {
  const [state, setState] = useState<State>('checking');
  const [message, setMessage] = useState('');

  useEffect(() => {
    let cancelled = false;
    const set = (s: State) => !cancelled && setState(s);
    (async () => {
      if (!pushSupported()) return set('unsupported');
      try {
        await getPushPublicKey();
      } catch (e) {
        if (isNetworkError(e)) return set('offline');
        return set(e instanceof ApiError && e.status === 503 ? 'server-disabled' : 'off');
      }
      if (Notification.permission === 'denied') return set('blocked');
      try {
        const subscription = await currentSubscription();
        if (subscription && Notification.permission === 'granted') {
          await registerWithServer(subscription).catch(() => undefined);
          return set('on');
        }
      } catch {
      }
      set('off');
    })();
    return () => { cancelled = true; };
  }, []);

  async function enable() {
    setMessage('');
    setState('enabling');
    const permission = await Notification.requestPermission();
    if (permission === 'denied') return setState('blocked');
    if (permission !== 'granted') {
      setMessage('Notifications were not allowed. You can try again at any time.');
      return setState('off');
    }
    let subscription: PushSubscription | null = null;
    try {
      const { publicKey } = await getPushPublicKey();
      subscription = await subscribeBrowser(publicKey);
      await registerWithServer(subscription);
      setMessage('Notifications are on for this browser.');
      setState('on');
    } catch (e) {
      if (subscription) await subscription.unsubscribe().catch(() => undefined);
      setMessage(e instanceof ApiError && e.code === 'LIMIT_REACHED'
        ? 'Notifications are already on for 5 browsers. Turn them off on one of those first.'
        : 'We could not turn on notifications. Please try again in a moment.');
      setState('off');
    }
  }

  async function disable() {
    setMessage('');
    setState('disabling');
    try {
      const subscription = await currentSubscription();
      if (subscription) await unsubscribeEverywhere(subscription);
      setMessage('Notifications are off for this browser.');
    } catch {
      setMessage('Notifications are off for this browser.');
    }
    setState('off');
  }

  const badge = BADGE[state];
  return (
    <section className="card" aria-labelledby="notifications-title">
      <div className="card__header">
        <div>
          <h2 id="notifications-title">Notifications</h2>
          <span className="small muted">Get a heads-up when your case needs you or is decided</span>
        </div>
        {badge && <span className={`badge badge--${badge.tone}`}>{badge.label}</span>}
      </div>
      <div className="card__body stack">
        <div aria-live="polite" className="stack">
          {state === 'checking' && <p className="small muted">Checking this browser…</p>}
          {state === 'unsupported' && (
            <p className="small">This browser cannot receive notifications. On iPhone and iPad, notifications only work
              for apps added to the Home Screen (Share → Add to Home Screen), which this prototype does not support yet.</p>
          )}
          {state === 'server-disabled' && <p className="small">Notifications are not available on this server right now.</p>}
          {state === 'offline' && <p className="small">Turning notifications on or off needs a connection.</p>}
          {state === 'blocked' && (
            <p className="small">Notifications are blocked for this site. To allow them, open your browser's site settings
              (the icon to the left of the address bar), set Notifications to Allow, then reload this page.</p>
          )}
          {(state === 'off' || state === 'enabling') && (
            <p className="small">We will only say that your status changed. No details about your case are ever included in the notification.</p>
          )}
          {(state === 'on' || state === 'disabling') && (
            <p className="small">This browser will be notified when your case needs your input or a decision is made. Sign in to see the details.</p>
          )}
          {message && <p className="small muted">{message}</p>}
        </div>
        {(state === 'off' || state === 'enabling') && (
          <div><button className="btn btn--primary" onClick={enable} disabled={state === 'enabling'}>
            {state === 'enabling' ? 'Enabling…' : 'Enable notifications'}</button></div>
        )}
        {(state === 'on' || state === 'disabling') && (
          <div><button className="btn btn--secondary" onClick={disable} disabled={state === 'disabling'}>
            {state === 'disabling' ? 'Turning off…' : 'Turn off'}</button></div>
        )}
      </div>
    </section>
  );
}
