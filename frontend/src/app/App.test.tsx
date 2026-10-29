import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../features/auth/AuthProvider';
import { renderWithProviders } from '../test/render';
import { App } from './App';

afterEach(() => vi.unstubAllGlobals());

describe('App', () => {
  it('shows the sign-in page when the server says the session is not authenticated', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ message: 'Authentication required' }), { status: 401 })));
    renderWithProviders(<AuthProvider><App /></AuthProvider>);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveAttribute('minLength', '12');
  });
});

describe('App sign-in', () => {
  it('switches to the signed-in app immediately after a successful login', async () => {
    const user = { id: 'u1', email: 'exec1@example.test', role: 'EXECUTIVE' };
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      if (path === '/api/auth/me') return new Response('{}', { status: 401 });
      if (path === '/api/auth/login') return new Response(JSON.stringify(user), { status: 200 });
      return new Response(JSON.stringify({ granted: false, notice: 'Synthetic notice' }), { status: 200 });
    }));
    document.cookie = 'XSRF-TOKEN=test-token';
    renderWithProviders(<AuthProvider><App /></AuthProvider>);
    await userEvent.type(await screen.findByLabelText('Email'), 'exec1@example.test');
    await userEvent.type(screen.getByLabelText('Password'), 'dev-only-change-me-12');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('exec1@example.test')).toBeInTheDocument();
  });

  it('says the service is unavailable instead of showing sign-in when the API is down', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('Bad gateway', { status: 502 })));
    renderWithProviders(<AuthProvider><App /></AuthProvider>);
    expect(await screen.findByText('The service is unavailable')).toBeInTheDocument();
  });
});

describe('App home links', () => {
  it('re-fetches the executive home when "My onboarding" is clicked while already on it', async () => {
    const user = { id: 'u1', email: 'exec1@example.test', role: 'EXECUTIVE' };
    const draft = { status: 'SUBMITTED', editable: false, version: 3, currentStep: 5, stepStatus: {}, submittedAt: '2026-01-01T00:00:00Z', moreInfoMessage: null, data: {} };
    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/api/auth/me') return new Response(JSON.stringify(user), { status: 200 });
      if (path === '/api/consent') return new Response(JSON.stringify({ granted: true, notice: 'n' }), { status: 200 });
      if (path === '/api/onboarding') return new Response(JSON.stringify(draft), { status: 200 });
      if (path === '/api/push/public-key') return new Response('{}', { status: 503 });
      return new Response('[]', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderWithProviders(<AuthProvider><App /></AuthProvider>);
    await screen.findByText('Your submission');
    const draftCalls = () => fetchMock.mock.calls.filter(([p]) => p === '/api/onboarding').length;
    const before = draftCalls();

    await userEvent.click(screen.getByRole('link', { name: 'My onboarding' }));

    await vi.waitFor(() => expect(draftCalls()).toBeGreaterThan(before));
  });
});
