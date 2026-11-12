// The only place the app talks to the core service: adds the access token, refreshes it once when it
// expires, and turns errors into readable messages. Same rules as the web app, own copy (DL-08: no shared code).
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import type { Session } from './types';

/** Set EXPO_PUBLIC_API_URL to the core service, e.g. http://192.168.1.20:8080 for a phone on the same Wi-Fi. */
export const API_URL = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

/**
 * The notification service (DL-12), which serves the bell. Set EXPO_PUBLIC_NOTIFICATIONS_URL when deployed;
 * locally it is the same machine as the core service on port 8081.
 */
export const NOTIFICATIONS_URL = process.env.EXPO_PUBLIC_NOTIFICATIONS_URL ?? API_URL.replace(/:8080$/, ':8081');

const REFRESH_KEY = 'shelfwise.refreshToken';
const NETWORK_ERROR = "Can't reach the server. Check your connection and try again.";

export class ApiError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

let session: Session | null = null;
let listener: ((s: Session | null) => void) | null = null;

export function onSessionChange(fn: (s: Session | null) => void) {
  listener = fn;
}

export function getSession() {
  return session;
}

/**
 * The refresh token is kept in the phone's secure storage (Keychain / Keystore), so a manager stays signed in
 * between app launches; the short-lived access token stays in memory only. The browser preview has no secure
 * storage, so there nothing is kept.
 */
export async function setSession(next: Session | null) {
  session = next;
  listener?.(next);
  if (Platform.OS === 'web') return;
  if (next) await SecureStore.setItemAsync(REFRESH_KEY, next.refreshToken);
  else await SecureStore.deleteItemAsync(REFRESH_KEY);
}

/** On launch: if a refresh token was saved, exchange it for a fresh session. */
export async function restoreSession(): Promise<Session | null> {
  if (Platform.OS === 'web') return null;
  const saved = await SecureStore.getItemAsync(REFRESH_KEY);
  if (!saved) return null;
  session = { accessToken: '', refreshToken: saved, expiresInSeconds: 0, user: { id: '', name: '', role: 'STORE_MANAGER', storeId: null } };
  return (await refresh()) ? session : (await setSession(null), null);
}

export async function login(email: string, password: string): Promise<Session> {
  const response = await send('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }, false);
  if (!response.ok) throw new ApiError(await message(response), response.status);
  const s = (await response.json()) as Session;
  await setSession(s);
  return s;
}

export async function logout() {
  const current = session;
  await setSession(null);
  if (current) {
    send('/api/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken: current.refreshToken }) }, false).catch(() => {});
  }
}

/** Calls the core service, or another ShelfWise service when {@code base} is given (e.g. the bell). */
export async function api<T>(path: string, init: RequestInit = {}, base: string = API_URL): Promise<T> {
  let response = await send(path, init, true, base);
  if (response.status === 401 && session) {
    if (!(await refresh())) {
      await setSession(null);
      throw new ApiError('Your session has expired. Please sign in again.', 401);
    }
    response = await send(path, init, true, base);
  }
  if (!response.ok) throw new ApiError(await message(response), response.status);
  return (response.status === 204 ? undefined : await response.json()) as T;
}

async function send(path: string, init: RequestInit, withToken: boolean, base: string = API_URL): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  if (withToken && session?.accessToken) headers.set('Authorization', `Bearer ${session.accessToken}`);
  try {
    return await fetch(base + path, { ...init, headers });
  } catch {
    throw new ApiError(NETWORK_ERROR, 0);
  }
}

async function refresh(): Promise<boolean> {
  if (!session) return false;
  try {
    const response = await send('/api/auth/refresh', { method: 'POST', body: JSON.stringify({ refreshToken: session.refreshToken }) }, false);
    if (!response.ok) return false;
    await setSession((await response.json()) as Session);
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

export function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '' && v !== false)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}
