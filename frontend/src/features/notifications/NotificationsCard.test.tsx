import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '../../test/render';
import { NotificationsCard } from './NotificationsCard';

afterEach(() => vi.unstubAllGlobals());

describe('NotificationsCard', () => {
  it('shows "blocked" with guidance when permission is denied, and never subscribes', async () => {
    const subscribe = vi.fn();
    const requestPermission = vi.fn();
    const registration = { pushManager: { getSubscription: vi.fn(async () => null), subscribe } };
    vi.stubGlobal('Notification', { permission: 'denied', requestPermission });
    vi.stubGlobal('PushManager', function PushManager() {});
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: { getRegistration: vi.fn(async () => registration), register: vi.fn(), ready: Promise.resolve(registration) },
    });
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ publicKey: 'BPub' }), { status: 200 })));

    renderWithProviders(<NotificationsCard />);

    expect(await screen.findByText('Blocked')).toBeInTheDocument();
    expect(screen.getByText(/Notifications are blocked for this site/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /enable notifications/i })).not.toBeInTheDocument();
    await userEvent.tab();
    expect(subscribe).not.toHaveBeenCalled();
    expect(requestPermission).not.toHaveBeenCalled();
  });
});
