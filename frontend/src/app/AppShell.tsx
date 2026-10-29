import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth, useUser } from '../features/auth/AuthProvider';
import type { Role } from '../lib/types';
import { syncEngine, useSyncState } from '../offline';

const NAV: Record<Role, { to: string; label: string }[]> = {
  EXECUTIVE: [{ to: '/', label: 'My onboarding' }, { to: '/account', label: 'Account & privacy' }],
  CONSULTANT: [{ to: '/', label: 'Review queue' }, { to: '/account', label: 'Account' }],
  ADMIN: [{ to: '/', label: 'Users' }, { to: '/audit', label: 'Audit trail' }, { to: '/queue', label: 'Cases' }, { to: '/account', label: 'Account' }],
};

export function AppShell({ children }: { children: ReactNode }) {
  const user = useUser();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  function goHome() {
    void queryClient.invalidateQueries({ predicate: (query) => query.queryKey[0] !== 'me' });
    window.scrollTo({ top: 0 });
  }

  const sync = useSyncState();
  const isExecutive = user.role === 'EXECUTIVE';
  const offline = isExecutive && !sync.online;
  const [confirmDiscard, setConfirmDiscard] = useState<number | null>(null);
  const [toast, setToast] = useState('');
  const wasOffline = useRef(false);

  useEffect(() => { if (offline) wasOffline.current = true; }, [offline]);
  useEffect(() => {
    if (!wasOffline.current || !sync.online || sync.lastSynced.count === 0) return;
    wasOffline.current = false;
    setToast(`Back online, ${sync.lastSynced.count} change${sync.lastSynced.count === 1 ? '' : 's'} synced`);
    const t = setTimeout(() => setToast(''), 5000);
    return () => clearTimeout(t);
  }, [sync.lastSynced.at, sync.online, sync.lastSynced.count]);

  async function signOut(force = false) {
    if (!force && isExecutive && sync.pending > 0) {
      const left = await syncEngine.flush();
      if (left > 0) return setConfirmDiscard(left);
    }
    setConfirmDiscard(null);
    await logout();
    navigate('/', { replace: true });
  }

  return (
    <>
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="topbar">
        <div className="topbar__inner">
          <Link to="/" className="brand" onClick={goHome}><span className="brand__mark">RiskTech</span><span className="brand__sub">Executive Platform</span></Link>
          <nav className="nav" aria-label="Main">
            {NAV[user.role].map((item) => (
              <NavLink key={item.to} to={item.to} end={item.to === '/'} onClick={item.to === '/' ? goHome : undefined}>{item.label}</NavLink>
            ))}
          </nav>
          <div className="whoami">
            <span className="whoami__email">{user.email}</span>
            <span className="role-chip">{user.role}</span>
            <button className="btn btn--ghost btn--small" onClick={() => void signOut()} disabled={offline}
              title={offline ? 'Reconnect to sign out securely' : undefined}>Sign out</button>
          </div>
        </div>
      </header>
      {offline && (
        <div className="offline-banner" role="status">
          You're offline. Changes are saved on this device and will sync when you reconnect
          {sync.pending > 0 ? ` (${sync.pending} waiting)` : ''}. Reconnect to sign out securely.
        </div>
      )}
      {toast && <div className="toast" role="status">{toast}</div>}
      {confirmDiscard !== null && (
        <div className="modal-backdrop">
          <section className="card modal" role="alertdialog" aria-modal="true" aria-labelledby="discard-title">
            <div className="card__body stack">
              <h2 id="discard-title">Sign out and discard unsynced changes?</h2>
              <p className="small">{confirmDiscard} change{confirmDiscard === 1 ? '' : 's'} on this device could not be synced.
                Signing out removes them from this device.</p>
              <div className="row row--end">
                <button className="btn btn--secondary" onClick={() => setConfirmDiscard(null)} autoFocus>Stay signed in</button>
                <button className="btn btn--danger" onClick={() => void signOut(true)}>Sign out and discard</button>
              </div>
            </div>
          </section>
        </div>
      )}
      <main id="main">{children}</main>
    </>
  );
}
