import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Field } from '../../components/ui';
import { errorMessage } from '../../lib/api';
import { register } from '../../lib/endpoints';
import { useAuth } from './AuthProvider';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (mode === 'register') {
        await register(email, password);
        setMode('login');
        setPassword('');
        setNotice('Account created. Sign in to begin your onboarding.');
      } else {
        await login(email, password);
        navigate('/', { replace: true });
      }
    } catch (e) {
      setError(mode === 'login' ? 'Invalid email or password.' : errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const isRegister = mode === 'register';
  return (
    <div className="auth">
      <section className="auth__panel">
        <div className="brand"><span className="brand__mark">RiskTech</span><span className="brand__sub">Executive Platform</span></div>
        <div>
          <h1>Executive onboarding &amp; vetting</h1>
          <p>Executives present their record once, securely. Consultants verify it through a controlled, fully audited review.</p>
          <ul className="auth__points">
            <li>Personal identifiers encrypted at rest</li>
            <li>Explicit consent under the NDPA 2023</li>
            <li>Tamper-evident audit trail of every decision</li>
          </ul>
        </div>
        <p className="small">Proof of concept · synthetic data only</p>
      </section>

      <div className="auth__form-wrap">
        <form className="auth__form" onSubmit={submit} noValidate={false}>
          <div>
            <div className="eyebrow">{isRegister ? 'Executives' : 'Welcome back'}</div>
            <h2>{isRegister ? 'Create your account' : 'Sign in'}</h2>
          </div>
          {notice && <Alert tone="ok">{notice}</Alert>}
          {error && <Alert tone="danger">{error}</Alert>}
          <Field label="Email" htmlFor="email">
            <input id="email" className="input" type="email" autoComplete="email" required maxLength={254}
              value={email} onChange={(e) => setEmail(e.target.value)} />
          </Field>
          <Field label="Password" hint={isRegister ? 'At least 12 characters' : undefined} htmlFor="password">
            <input id="password" className="input" type="password" required minLength={12} maxLength={72}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <button className="btn btn--primary" disabled={busy}>
            {busy ? 'Please wait…' : isRegister ? 'Create account' : 'Sign in'}
          </button>
          <button type="button" className="link-btn" onClick={() => { setMode(isRegister ? 'login' : 'register'); setError(''); setNotice(''); }}>
            {isRegister ? 'Already registered? Sign in' : 'New executive? Create an account'}
          </button>
          <p className="demo-hint">
            Consultant and administrator accounts are created by an administrator. Demo accounts use
            <code> @example.test</code> addresses; see the README.
          </p>
        </form>
      </div>
    </div>
  );
}
