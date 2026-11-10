// One place for calling the core service: adds the access token, refreshes it once when it expires,
// and turns error responses into readable messages.
import { ApiError, type Session } from './auth';

let session: Session | null = null;
let listener: ((s: Session | null) => void) | null = null;

export function setSession(next: Session | null) {
  session = next;
  listener?.(next);
}

export function getSession() {
  return session;
}

/** App.tsx subscribes so the UI signs out when a refresh fails. */
export function onSessionChange(fn: (s: Session | null) => void) {
  listener = fn;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response = await send(path, init);
  if (response.status === 401 && session) {
    const refreshed = await refresh();
    if (!refreshed) {
      setSession(null);
      throw new ApiError('Your session has expired. Please sign in again.', 401);
    }
    response = await send(path, init);
  }
  if (!response.ok) {
    throw new ApiError(await message(response), response.status);
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (session) headers.set('Authorization', `Bearer ${session.accessToken}`);
  try {
    return await fetch(path, { ...init, headers });
  } catch {
    throw new ApiError("Can't reach the server. Check your connection and try again.", 0);
  }
}

async function refresh(): Promise<boolean> {
  if (!session) return false;
  try {
    const response = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    });
    if (!response.ok) return false;
    setSession((await response.json()) as Session);
    return true;
  } catch {
    return false;
  }
}

async function message(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string; title?: string };
    return problem.detail ?? problem.title ?? `Request failed (${response.status})`;
  } catch {
    return `Request failed (${response.status})`;
  }
}

/** Builds "?a=1&b=x" from the values that are set. */
export function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '' && value !== false) q.set(key, String(value));
  }
  const text = q.toString();
  return text ? `?${text}` : '';
}
