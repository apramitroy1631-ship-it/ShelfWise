import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { App } from '../App';

const SESSION = {
  accessToken: 'access',
  refreshToken: 'refresh',
  expiresInSeconds: 900,
  user: { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER', storeId: 'S01' },
};

const EMPTY_PAGE = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };

const FEED = {
  unreadCount: 2,
  items: [
    { id: 7, kind: 'RUNNING_LOW', text: 'Royal Harvest Sticky Notes 100 sheets and 144 other items are running low in S01',
      count: 145, ref: null, unread: true, at: '2026-11-11T04:50:05Z' },
    { id: 9, kind: 'PO_DECISION', text: 'PO-10001 was approved', count: null, ref: 'PO-10001', unread: true, at: '2026-11-11T04:56:00Z' },
    { id: 3, kind: 'OUT_OF_STOCK', text: 'Fresh Basket Energy Drink 250ml and 39 other items are out of stock in S01',
      count: 40, ref: null, unread: false, at: '2026-11-11T04:46:56Z' },
  ],
};

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** A fake backend that answers by URL; the bell's calls go to the notification service paths. */
function backend(notifications: () => Response) {
  return vi.fn((url: string, _init?: RequestInit) => {
    const path = url.split('?')[0];
    if (path === '/api/auth/login') return Promise.resolve(json(200, SESSION));
    if (path === '/api/notifications') return Promise.resolve(notifications());
    if (path.startsWith('/api/notifications/')) return Promise.resolve(json(204));
    if (path === '/api/orders' || path === '/api/inventory') return Promise.resolve(json(200, EMPTY_PAGE));
    return Promise.resolve(json(404, { detail: 'not mocked' }));
  });
}

async function signIn() {
  render(<App />);
  await userEvent.type(screen.getByLabelText('Email'), 'arjun@test.example');
  await userEvent.type(screen.getByLabelText('Password'), 'secret');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByText('Arjun Rao');
}

describe('notification bell', () => {
  it('shows the unread count and the grouped notifications (D7)', async () => {
    vi.stubGlobal('fetch', backend(() => json(200, FEED)));
    await signIn();

    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }));

    const panel = screen.getByRole('dialog', { name: 'Notifications' });
    expect(within(panel).getByText(/144 other items are running low in S01/)).toBeInTheDocument();
    expect(within(panel).getByText('PO-10001 was approved')).toBeInTheDocument();
    expect(within(panel).getAllByLabelText('Unread')).toHaveLength(2);
  });

  it('opening a notification marks it read on the server', async () => {
    const fetchMock = backend(() => json(200, FEED));
    vi.stubGlobal('fetch', fetchMock);
    await signIn();

    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }));
    await userEvent.click(screen.getByText('PO-10001 was approved'));

    expect(fetchMock).toHaveBeenCalledWith('/api/notifications/9/read', expect.objectContaining({ method: 'POST' }));
    expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toBeInTheDocument();
  });

  it('"Mark all as read" clears the badge', async () => {
    const fetchMock = backend(() => json(200, FEED));
    vi.stubGlobal('fetch', fetchMock);
    await signIn();

    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }));
    await userEvent.click(screen.getByRole('button', { name: 'Mark all as read' }));

    expect(fetchMock).toHaveBeenCalledWith('/api/notifications/read-all', expect.objectContaining({ method: 'POST' }));
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument();
  });

  it('a stock notification opens the inventory with "low stock only" on', async () => {
    const fetchMock = backend(() => json(200, FEED));
    vi.stubGlobal('fetch', fetchMock);
    await signIn();

    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }));
    await userEvent.click(screen.getByText(/running low in S01/));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/inventory\?.*lowOnly=true.*page=0/), expect.anything()));
  });

  it('if the notification service is down, the app keeps working and the panel says so (DL-12)', async () => {
    vi.stubGlobal('fetch', backend(() => json(503, { detail: 'unavailable' })));
    await signIn();

    await userEvent.click(await screen.findByRole('button', { name: 'Notifications' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Notifications are unavailable right now');
    expect(screen.getByText('Arjun Rao')).toBeInTheDocument();
  });
});
