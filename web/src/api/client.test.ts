import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, setSession } from './client';

const user = { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER' as const, storeId: 'S01' };

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('token refresh (review issue 1)', () => {
  afterEach(() => setSession(null));

  it('5 requests that hit an expired token share ONE refresh, and all of them succeed', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1', expiresInSeconds: 900, user });
    let refreshCalls = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/auth/refresh') {
        refreshCalls++;
        await new Promise((r) => setTimeout(r, 20));          // the refresh takes a moment; the others must wait for it
        return json(200, { accessToken: 'new', refreshToken: 'r2', expiresInSeconds: 900, user });
      }
      const auth = new Headers(init?.headers).get('Authorization');
      return auth === 'Bearer new' ? json(200, { ok: true }) : json(401, { detail: 'expired' });
    }));

    const results = await Promise.all([1, 2, 3, 4, 5].map((i) => api<{ ok: boolean }>(`/api/list/${i}`)));

    expect(results.every((r) => r.ok)).toBe(true);
    expect(refreshCalls).toBe(1);
  });

  it('a request that fails after another refresh finished retries with the new token instead of refreshing again', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1', expiresInSeconds: 900, user });
    let refreshCalls = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/auth/refresh') {
        refreshCalls++;
        return json(200, { accessToken: 'new', refreshToken: 'r2', expiresInSeconds: 900, user });
      }
      const auth = new Headers(init?.headers).get('Authorization');
      if (url === '/api/slow') {                               // sent with the old token, answered after the refresh
        await new Promise((r) => setTimeout(r, 30));
        return auth === 'Bearer new' ? json(200, { ok: true }) : json(401, {});
      }
      return auth === 'Bearer new' ? json(200, { ok: true }) : json(401, {});
    }));

    await Promise.all([api('/api/slow'), api('/api/fast')]);

    expect(refreshCalls).toBe(1);
  });
});
