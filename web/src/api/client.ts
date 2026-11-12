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

/**
 * Downloads a file (e.g. the CSV export) with the signed-in user's token, then hands it to the browser to save.
 * Uses the server's file name when it sends one.
 */
export async function downloadFile(path: string, fallbackName: string): Promise<void> {
  let response = await send(path, {});
  if (response.status === 401 && session && (await refresh())) {
    response = await send(path, {});
  }
  if (!response.ok) {
    throw new ApiError(await message(response), response.status);
  }
  const name = /filename="([^"]+)"/.exec(response.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName;
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * Opens a file (e.g. a supplier invoice) in a new tab. The request needs the signed-in user's token, so the file
 * is fetched first and shown from a temporary local address. The tab is opened straight away so the browser
 * doesn't block it as a pop-up.
 */
export async function viewFile(path: string): Promise<void> {
  const tab = window.open('', '_blank');
  try {
    let response = await send(path, {});
    if (response.status === 401 && session && (await refresh())) {
      response = await send(path, {});
    }
    if (!response.ok) {
      throw new ApiError(await message(response), response.status);
    }
    const url = URL.createObjectURL(await response.blob());
    if (tab) tab.location.href = url;
    else window.location.href = url;
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    tab?.close();
    throw e;
  }
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  // A file upload (FormData) must not get a JSON type: the browser adds its own with the multipart boundary.
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
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
