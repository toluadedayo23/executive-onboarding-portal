import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Field, PageHeader } from '../../components/ui';
import { errorMessage } from '../../lib/api';
import { changePassword, deleteAccount, getConsent, getSensitive } from '../../lib/endpoints';
import { useAuth, useUser } from '../auth/AuthProvider';
import { NotificationsCard } from '../notifications/NotificationsCard';

export function AccountPage() {
  const user = useUser();
  return (
    <div className="page page--narrow stack--lg">
      <PageHeader eyebrow="Account" title={user.role === 'EXECUTIVE' ? 'Account & privacy' : 'Account'} />
      <div className="card">
        <div className="card__body">
          <dl className="dl"><dt>Email</dt><dd>{user.email}</dd><dt>Role</dt><dd>{user.role}</dd></dl>
        </div>
      </div>
      {user.role === 'EXECUTIVE' && <PrivacyCard />}
      {user.role === 'EXECUTIVE' && <NotificationsCard />}
      {user.role === 'EXECUTIVE' && <SensitiveCard />}
      <PasswordCard />
      {user.role === 'EXECUTIVE' && <DeleteCard />}
    </div>
  );
}

function PrivacyCard() {
  const consent = useQuery({ queryKey: ['consent'], queryFn: getConsent });
  return (
    <div className="card">
      <div className="card__header"><h2>Consent</h2>
        {consent.data && (consent.data.granted ? <span className="badge badge--VERIFIED">Granted</span> : <span className="badge badge--DRAFT">Not given</span>)}
      </div>
      <div className="card__body stack">
        <p className="small">{consent.data?.notice}</p>
        <p className="small muted">To withdraw consent or request a copy of your data, contact the RiskTech data protection officer.</p>
      </div>
    </div>
  );
}

function SensitiveCard() {
  const reveal = useMutation({ mutationFn: getSensitive });
  return (
    <div className="card">
      <div className="card__header"><h2>Protected details</h2>
        {reveal.data
          ? <button className="btn btn--secondary btn--small" onClick={() => reveal.reset()}>Hide</button>
          : <button className="btn btn--secondary btn--small" disabled={reveal.isPending} onClick={() => reveal.mutate()}>Reveal</button>}
      </div>
      <div className="card__body stack">
        {!reveal.data && <p className="small muted">Your national ID and referee contacts are encrypted and masked everywhere else.</p>}
        {reveal.isError && <Alert tone="danger">{errorMessage(reveal.error)}</Alert>}
        {reveal.data && (
          <dl className="dl">
            <dt>National ID</dt><dd>{reveal.data.nationalId ?? 'Not provided'}</dd>
            {reveal.data.referees.map((r, i) => (
              <div key={i} style={{ display: 'contents' }}><dt>{r.name || `Referee ${i + 1}`}</dt><dd>{r.email} · {r.phone}</dd></div>
            ))}
          </dl>
        )}
      </div>
    </div>
  );
}

function PasswordCard() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const change = useMutation({
    mutationFn: () => changePassword(current, next),
    onSuccess: async () => { await logout(); navigate('/'); },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    if (next === confirm) change.mutate();
  }
  return (
    <form className="card" onSubmit={submit}>
      <div className="card__header"><h2>Change password</h2></div>
      <div className="card__body stack">
        <Field label="Current password" htmlFor="pw-current">
          <input id="pw-current" className="input" type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
        </Field>
        <div className="form-grid">
          <Field label="New password" hint="12+ characters" htmlFor="pw-new">
            <input id="pw-new" className="input" type="password" autoComplete="new-password" required minLength={12} maxLength={72} value={next} onChange={(e) => setNext(e.target.value)} />
          </Field>
          <Field label="Confirm new password" htmlFor="pw-confirm" error={confirm && confirm !== next ? 'Passwords do not match' : undefined}>
            <input id="pw-confirm" className="input" type="password" autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          </Field>
        </div>
        {change.isError && <Alert tone="danger">Could not change the password. Check your current password and the 12-character minimum.</Alert>}
        <p className="small muted">Changing your password signs you out everywhere.</p>
        <div className="row row--end"><button className="btn btn--primary" disabled={change.isPending || next !== confirm}>Change password</button></div>
      </div>
    </form>
  );
}

function DeleteCard() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [password, setPassword] = useState('');
  const [typed, setTyped] = useState('');
  const remove = useMutation({
    mutationFn: () => deleteAccount(password),
    onSuccess: async () => { await logout(); navigate('/'); },
  });
  return (
    <form className="card" onSubmit={(e) => { e.preventDefault(); remove.mutate(); }}>
      <div className="card__header"><h2>Delete my account</h2></div>
      <div className="card__body stack">
        <p className="small">Your account is anonymised and your onboarding data and documents are erased. The audit trail and
          the record that a vetting case existed are retained, as required for accountability.</p>
        <div className="form-grid">
          <Field label="Current password" htmlFor="del-password">
            <input id="del-password" className="input" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <Field label='Type "DELETE" to confirm' htmlFor="del-confirm">
            <input id="del-confirm" className="input" value={typed} onChange={(e) => setTyped(e.target.value)} />
          </Field>
        </div>
        {remove.isError && <Alert tone="danger">Deletion failed. Check your password; nothing was changed.</Alert>}
        <div className="row row--end"><button className="btn btn--danger" disabled={typed !== 'DELETE' || !password || remove.isPending}>Delete account</button></div>
      </div>
    </form>
  );
}
