import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import App from '../App';

jest.mock('expo-secure-store', () => {
  const store: Record<string, string> = {};
  return {
    getItemAsync: jest.fn(async (k: string) => store[k] ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => { store[k] = v; }),
    deleteItemAsync: jest.fn(async (k: string) => { delete store[k]; }),
  };
});

const SESSION = {
  accessToken: 'access', refreshToken: 'refresh', expiresInSeconds: 900,
  user: { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER', storeId: 'S01' },
};

const STOCK = {
  items: [
    { storeId: 'S01', sku: 'SW-GRO-00055', productName: 'Home Basics Iodised Salt 1kg', category: 'Grocery', productStatus: 'ACTIVE',
      priceInr: 31, quantity: 3, reorderLevel: 10, low: true, recountNeeded: false, lastCountedAt: null, version: 0 },
  ],
  page: 0, size: 30, totalItems: 1, totalPages: 1,
};

function json(status: number, body?: unknown) {
  return Promise.resolve({ ok: status < 400, status, json: async () => body } as Response);
}

function backend(overrides: Record<string, (init?: RequestInit) => Promise<Response>> = {}) {
  return jest.fn((url: string, init?: RequestInit) => {
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    if (overrides[path]) return overrides[path](init);
    if (path === '/api/auth/login') return json(200, SESSION);
    if (path === '/api/inventory') return json(200, STOCK);
    return json(404, { detail: 'not mocked' });
  });
}

async function signIn() {
  await render(<App />);
  await fireEvent.changeText(await screen.findByLabelText('Email'), 'arjun@test.example');
  await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
  await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
}

describe('mobile app', () => {
  afterEach(() => jest.useRealTimers());

  it("signs in and shows the manager's own store stock, with low items marked", async () => {
    const fetchMock = backend();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    await signIn();

    expect(await screen.findByText('Home Basics Iodised Salt 1kg')).toBeTruthy();
    expect(screen.getByText('▼ Low')).toBeTruthy();
    expect(screen.getByText('Store S01 · Arjun Rao')).toBeTruthy();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/inventory?storeId=S01'), expect.anything()));
  });

  it("shows the server's message when sign-in is refused", async () => {
    globalThis.fetch = backend({ '/api/auth/login': () => json(401, { detail: 'Invalid email or password' }) }) as unknown as typeof fetch;
    await signIn();

    expect(await screen.findByText('Invalid email or password')).toBeTruthy();
  });

  it('records a damaged write-off with the reason the server needs', async () => {
    let sent: unknown = null;
    globalThis.fetch = backend({
      '/api/inventory/S01/SW-GRO-00055/adjustments': (init) => {
        sent = JSON.parse(String(init?.body));
        return json(200, { item: { ...STOCK.items[0], quantity: 1 } });
      },
    }) as unknown as typeof fetch;
    await signIn();

    await fireEvent.press(await screen.findByLabelText('Home Basics Iodised Salt 1kg, 3 in stock'));
    await fireEvent.changeText(screen.getByLabelText('Quantity'), '2');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(sent).toEqual({ type: 'DAMAGED', quantity: 2, note: null }));
    expect(await screen.findByLabelText('Home Basics Iodised Salt 1kg, 1 in stock')).toBeTruthy();
  });

  it('sends head-office users to the web app', async () => {
    globalThis.fetch = backend({ '/api/auth/login': () => json(200, { ...SESSION, user: { ...SESSION.user, role: 'ADMIN', storeId: null } }) }) as unknown as typeof fetch;
    await signIn();

    expect(await screen.findByText(/The mobile app is for store managers/)).toBeTruthy();
  });
});
