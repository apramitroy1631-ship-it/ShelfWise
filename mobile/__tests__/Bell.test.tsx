import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import App from '../App';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
  deleteItemAsync: jest.fn(async () => {}),
}));

const SESSION = {
  accessToken: 'access', refreshToken: 'refresh', expiresInSeconds: 900,
  user: { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER', storeId: 'S01' },
};
const FEED = {
  unreadCount: 2,
  items: [
    { id: 7, kind: 'RUNNING_LOW', text: 'Royal Harvest Sticky Notes 100 sheets and 144 other items are running low in S01', count: 145, ref: null, unread: true, at: '2026-11-11T04:50:05Z' },
    { id: 9, kind: 'PO_DECISION', text: 'PO-10001 was approved', count: null, ref: 'PO-10001', unread: true, at: '2026-11-11T04:56:00Z' },
  ],
};
const EMPTY = { items: [], page: 0, size: 30, totalItems: 0, totalPages: 0 };

function json(status: number, body?: unknown) {
  return Promise.resolve({ ok: status < 400, status, json: async () => body } as Response);
}

type Call = { url: string; init?: RequestInit };

function backend(calls: Call[], bell: () => Promise<Response> = () => json(200, FEED)) {
  return jest.fn((url: string, init?: RequestInit) => {
    calls.push({ url, init });
    if (url.endsWith('/api/auth/login')) return json(200, SESSION);
    if (url.includes(':8081/api/notifications/')) return json(204);
    if (url.includes(':8081/api/notifications')) return bell();
    if (url.includes('/api/inventory') || url.includes('/api/orders')) return json(200, EMPTY);
    return json(404, { detail: 'not mocked' });
  });
}

async function signIn(calls: Call[], bell?: () => Promise<Response>) {
  globalThis.fetch = backend(calls, bell) as unknown as typeof fetch;
  await render(<App />);
  await fireEvent.changeText(await screen.findByLabelText('Email'), 'arjun@test.example');
  await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
  await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
}

describe('bell on mobile (D7)', () => {
  it("shows the unread count from the notification service and the store's grouped notifications", async () => {
    const calls: Call[] = [];
    await signIn(calls);

    await fireEvent.press(await screen.findByLabelText('Notifications, 2 unread'));

    expect(await screen.findByText(/144 other items are running low in S01/)).toBeTruthy();
    expect(screen.getByText('PO-10001 was approved')).toBeTruthy();
    expect(calls.some((c) => c.url === 'http://localhost:8081/api/notifications'
      && new Headers(c.init?.headers).get('Authorization') === 'Bearer access')).toBe(true);
  });

  it('opening a stock notification marks it read and opens Stock with "low stock only" on', async () => {
    const calls: Call[] = [];
    await signIn(calls);

    await fireEvent.press(await screen.findByLabelText('Notifications, 2 unread'));
    await fireEvent.press(await screen.findByLabelText(/running low in S01, unread/));

    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/api/notifications/7/read') && c.init?.method === 'POST')).toBe(true));
    await waitFor(() => expect(calls.some((c) => /\/api\/inventory\?storeId=S01&lowOnly=true/.test(c.url))).toBe(true));
    expect(screen.getByLabelText('Notifications, 1 unread')).toBeTruthy();
  });

  it('"Mark all as read" clears the badge', async () => {
    const calls: Call[] = [];
    await signIn(calls);

    await fireEvent.press(await screen.findByLabelText('Notifications, 2 unread'));
    await fireEvent.press(await screen.findByRole('button', { name: 'Mark all as read' }));

    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/api/notifications/read-all'))).toBe(true));
    expect(screen.getByLabelText('Notifications')).toBeTruthy();
  });

  it('if the notification service is down, the app keeps working and says so', async () => {
    await signIn([], () => json(503, { detail: 'unavailable' }));

    await fireEvent.press(await screen.findByLabelText('Notifications'));

    expect(await screen.findByText(/Notifications are unavailable right now/)).toBeTruthy();
    expect(screen.getByText('Store S01 · Arjun Rao')).toBeTruthy();
  });
});
