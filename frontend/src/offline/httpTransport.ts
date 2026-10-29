import { ApiError, csrfToken } from '../lib/api';
import { getDraft } from '../lib/endpoints';
import type { StepKey } from '../lib/types';
import { NetworkError, type SendResult, type StepBody, type Transport } from './syncEngine';

export const httpTransport: Transport = {
  async saveStep(step: StepKey, body: StepBody, idempotencyKey: string): Promise<SendResult> {
    let response: Response;
    try {
      response = await fetch(`/api/onboarding/steps/${step}`, {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': await csrfToken(), 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      });
    } catch {
      throw new NetworkError();
    }
    return { status: response.status, body: await response.json().catch(() => ({})) };
  },

  async getDraft() {
    try {
      return await getDraft();
    } catch (error) {
      if (error instanceof ApiError && error.code === 'NETWORK') throw new NetworkError();
      throw error;
    }
  },

  async currentUserId() {
    let response: Response;
    try {
      response = await fetch('/api/auth/me', { credentials: 'same-origin', cache: 'no-store' });
    } catch {
      throw new NetworkError();
    }
    if (!response.ok) return null;
    return ((await response.json()) as { id: string }).id;
  },

  async health() {
    try {
      return (await fetch('/api/health', { cache: 'no-store' })).ok;
    } catch {
      return false;
    }
  },
};
