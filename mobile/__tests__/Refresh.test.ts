import { api, setSession } from '../src/api/client';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
  deleteItemAsync: jest.fn(async () => {}),
}));

const user = { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER' as const, storeId: 'S01' };
const json = (status: number, body?: unknown) => ({ ok: status < 400, status, json: async () => body } as Response);

describe('token refresh on mobile (review issue 1)', () => {
  it('5 requests that hit an expired token share ONE refresh, and all of them succeed', async () => {
    await setSession({ accessToken: 'old', refreshToken: 'r1', expiresInSeconds: 900, user });
    let refreshCalls = 0;
    globalThis.fetch = jest.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/api/auth/refresh')) {
        refreshCalls++;
        await new Promise((r) => setTimeout(r, 20));
        return json(200, { accessToken: 'new', refreshToken: 'r2', expiresInSeconds: 900, user });
      }
      return new Headers(init?.headers).get('Authorization') === 'Bearer new' ? json(200, { ok: true }) : json(401, {});
    }) as unknown as typeof fetch;

    const results = await Promise.all([1, 2, 3, 4, 5].map((i) => api<{ ok: boolean }>(`/api/list/${i}`)));

    expect(results.every((r) => r.ok)).toBe(true);
    expect(refreshCalls).toBe(1);
  });
});
