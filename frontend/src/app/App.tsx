import { Navigate, Route, Routes } from 'react-router-dom';
import { Alert, Loading } from '../components/ui';
import { AccountPage } from '../features/account/AccountPage';
import { AuditPage } from '../features/admin/AuditPage';
import { UsersPage } from '../features/admin/UsersPage';
import { LoginPage } from '../features/auth/LoginPage';
import { useAuth } from '../features/auth/AuthProvider';
import { OnboardingPage } from '../features/onboarding/OnboardingPage';
import { CasePage } from '../features/vetting/CasePage';
import { QueuePage } from '../features/vetting/QueuePage';
import { AppShell } from './AppShell';

export function App() {
  const { user, loading, unavailable, offline } = useAuth();
  if (loading) return <Loading />;
  if (unavailable) {
    return (
      <div className="page page--narrow">
        <Alert tone={offline ? 'info' : 'danger'} title={offline ? "You're offline" : 'The service is unavailable'}>
          {offline ? 'Reconnect to continue.' : 'We could not reach the server. Please try again in a moment.'}
          <div style={{ marginTop: 8 }}><button className="btn btn--secondary btn--small" onClick={() => window.location.reload()}>Try again</button></div>
        </Alert>
      </div>
    );
  }
  if (!user) {
    return (
      <Routes>
        <Route path="*" element={<LoginPage />} />
      </Routes>
    );
  }

  return (
    <AppShell>
      <Routes>
        {user.role === 'EXECUTIVE' && <Route path="/" element={<OnboardingPage />} />}
        {user.role === 'CONSULTANT' && <Route path="/" element={<QueuePage />} />}
        {user.role === 'ADMIN' && (
          <>
            <Route path="/" element={<UsersPage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/queue" element={<QueuePage />} />
          </>
        )}
        {user.role !== 'EXECUTIVE' && <Route path="/cases/:id" element={<CasePage />} />}
        <Route path="/account" element={<AccountPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppShell>
  );
}
