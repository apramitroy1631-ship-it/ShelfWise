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
const ORDER = {
  id: 'ORD-100002', storeId: 'S01', status: 'PENDING', customerName: 'Zoya Joshi', customerPhone: '+91 9873819191',
  totalAmount: 62, createdAt: '2026-11-11T08:00:00Z', reviewStatus: null, stuck: false, version: 0,
  placedBy: 'U03', source: 'APP',
  lines: [{ sku: 'SW-GRO-00055', productSku: 'SW-GRO-00055', productName: 'Home Basics Iodised Salt 1kg', qty: 2, unitPrice: 31, lineTotal: 62 }],
};
const SALT = { storeId: 'S01', sku: 'SW-GRO-00055', productName: 'Home Basics Iodised Salt 1kg', category: 'Grocery', productStatus: 'ACTIVE',
  priceInr: 31, quantity: 3, reorderLevel: 10, low: true, recountNeeded: false, lastCountedAt: null, version: 0 };

function json(status: number, body?: unknown) {
  return Promise.resolve({ ok: status < 400, status, json: async () => body } as Response);
}

type Call = { path: string; init?: RequestInit };

function backend(calls: Call[]) {
  return jest.fn((url: string, init?: RequestInit) => {
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    calls.push({ path, init });
    if (path === '/api/auth/login') return json(200, SESSION);
    if (path === '/api/orders' && init?.method === 'POST') return json(201, ORDER);
    if (path === '/api/orders') return json(200, { items: [ORDER], page: 0, size: 30, totalItems: 1, totalPages: 1 });
    if (path === '/api/orders/ORD-100002/status') return json(200, { ...ORDER, status: 'PACKED', version: 1 });
    if (path === '/api/orders/ORD-100002') return json(200, ORDER);
    if (path === '/api/inventory') return json(200, { items: [SALT], page: 0, size: 8, totalItems: 1, totalPages: 1 });
    return json(404, { detail: 'not mocked' });
  });
}

async function openOrders(calls: Call[]) {
  globalThis.fetch = backend(calls) as unknown as typeof fetch;
  await render(<App />);
  await fireEvent.changeText(await screen.findByLabelText('Email'), 'arjun@test.example');
  await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
  await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
  await fireEvent.press(await screen.findByRole('tab', { name: /Orders/ }));
}

describe('orders on mobile', () => {
  it("lists the store's orders and moves one to the next status with its version", async () => {
    const calls: Call[] = [];
    await openOrders(calls);

    await fireEvent.press(await screen.findByLabelText('ORD-100002, Pending'));
    await fireEvent.press(await screen.findByRole('button', { name: 'Mark packed' }));

    await waitFor(() => {
      const change = calls.find((c) => c.path === '/api/orders/ORD-100002/status');
      expect(JSON.parse(String(change?.init?.body))).toEqual({ to: 'PACKED', version: 0 });
    });
    expect(await screen.findByRole('button', { name: 'Mark shipped' })).toBeTruthy();
  });

  it('places a new order with an idempotency key, from products in stock', async () => {
    const calls: Call[] = [];
    await openOrders(calls);

    await fireEvent.press(await screen.findByRole('button', { name: 'New order' }));
    await fireEvent.changeText(screen.getByLabelText('Customer name'), 'Zoya Joshi');
    await fireEvent.changeText(screen.getByLabelText('Customer phone'), '+91 9873819191');
    await fireEvent.changeText(screen.getByLabelText('Add product'), 'salt');
    await fireEvent.press(await screen.findByLabelText('Add Home Basics Iodised Salt 1kg', {}, { timeout: 2000 }));
    await fireEvent.press(screen.getByLabelText('One more Home Basics Iodised Salt 1kg'));
    await fireEvent.press(screen.getByRole('button', { name: 'Place order' }));

    await waitFor(() => {
      const create = calls.find((c) => c.path === '/api/orders' && c.init?.method === 'POST');
      expect(JSON.parse(String(create?.init?.body))).toEqual({
        customerName: 'Zoya Joshi', customerPhone: '+91 9873819191', lines: [{ sku: 'SW-GRO-00055', qty: 2 }],
      });
      expect(new Headers(create?.init?.headers).get('Idempotency-Key')).toMatch(/^m-/);
    });
  });

  it('will not place an order without a valid +91 mobile number', async () => {
    await openOrders([]);

    await fireEvent.press(await screen.findByRole('button', { name: 'New order' }));
    await fireEvent.changeText(screen.getByLabelText('Customer name'), 'Zoya Joshi');
    await fireEvent.changeText(screen.getByLabelText('Customer phone'), '+91 12345');

    expect(screen.getByText('Use +91 and a 10-digit mobile number.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Place order' })).toBeDisabled();
  });
});
