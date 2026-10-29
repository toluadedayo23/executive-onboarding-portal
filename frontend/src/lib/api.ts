export type Problem = { step: string; field: string; code: string; item?: number | null };

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly problems?: unknown,
  ) {
    super(message);
  }
}

function readCookie(name: string): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : undefined;
}

export async function csrfToken(): Promise<string> {
  let token = readCookie('XSRF-TOKEN');
  if (!token) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    token = readCookie('XSRF-TOKEN');
  }
  if (!token) throw new ApiError('Could not start a secure session. Refresh and try again.', 0);
  return token;
}

type RequestOptions = { method?: string; json?: unknown; form?: FormData };

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? (options.json !== undefined || options.form ? 'POST' : 'GET');
  const headers: Record<string, string> = {};
  let body: BodyInit | undefined;
  if (options.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.json);
  } else if (options.form) {
    body = options.form;
  }
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = await csrfToken();

  let response: Response;
  try {
    response = await fetch(path, { method, headers, body, credentials: 'same-origin' });
  } catch {
    throw new ApiError('You appear to be offline.', 0, 'NETWORK');
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(error.message ?? 'Something went wrong. Please try again.', response.status, error.code, error.problems);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export const isNetworkError = (error: unknown) => error instanceof ApiError && error.code === 'NETWORK';

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return 'Something went wrong. Please try again.';
}
