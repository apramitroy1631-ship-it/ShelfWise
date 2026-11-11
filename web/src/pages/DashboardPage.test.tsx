import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DashboardPage } from './DashboardPage';
import { setSession } from '../api/client';

const MANAGER = { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER' as const, storeId: 'S01' };
const ADMIN = { id: 'U01', name: 'Rohit Menon', role: 'ADMIN' as const, storeId: null };

const SUMMARY = {
  storeId: null, from: '2026-10-01', to: '2026-10-31',
  revenue: 1253400, deliveredOrders: 1100, orderCount: 1260,
  ordersByStatus: { PENDING: 40, PACKED: 30, SHIPPED: 20, DELIVERED: 1100, CANCELLED: 70 },
  lowStockCount: 1046, outOfStockCount: 190,
  topProducts: [{ sku: 'SW-GRO-00151', name: 'Everyday Atta 10kg', units: 210, revenue: 98000 }],
  unknownProducts: { units: 3, revenue: 1200 },
  trendBy: 'day',
  trend: [
    { period: '2026-10-01', storeId: 'S01', revenue: 30000, orders: 30 },
    { period: '2026-10-01', storeId: 'S02', revenue: 20000, orders: 25 },
    { period: '2026-10-02', storeId: 'S01', revenue: 41000, orders: 33 },
  ],
};

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

function backend() {
  return vi.fn((url: string) => {
    if (url.startsWith('/api/dashboard')) return Promise.resolve(json(200, SUMMARY));
    if (url.startsWith('/api/orders/export.csv')) {
      return Promise.resolve(new Response('Order ID\r\n', {
        status: 200, headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="orders-all-stores.csv"' },
      }));
    }
    return Promise.resolve(json(404, { detail: 'not mocked' }));
  });
}

describe('dashboard', () => {
  it('shows revenue, orders by status, low stock and top products', async () => {
    vi.stubGlobal('fetch', backend());
    setSession({ accessToken: 'a', refreshToken: 'r', expiresInSeconds: 900, user: ADMIN });
    render(<DashboardPage user={ADMIN} store="" />);

    expect(await screen.findByText('₹12,53,400')).toBeInTheDocument();        // Indian grouping
    expect(screen.getByText(/Delivered 1,100 · Cancelled 70/)).toBeInTheDocument();
    expect(screen.getByText('1,046')).toBeInTheDocument();
    expect(screen.getByText('Everyday Atta 10kg')).toBeInTheDocument();
    expect(screen.getByText(/Unknown product codes \(D12\)/)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Revenue by day, 2 bars' })).toBeInTheDocument();   // stores added per day
    expect(screen.getByText('Revenue by store')).toBeInTheDocument();
  });

  it('sends the store chosen in the sidebar and the dates, as IST days', async () => {
    const fetchMock = backend();
    vi.stubGlobal('fetch', fetchMock);
    render(<DashboardPage user={ADMIN} store="S03" />);
    await screen.findByText('₹12,53,400');

    await userEvent.click(screen.getByRole('button', { name: 'All time' }));

    expect(fetchMock).toHaveBeenLastCalledWith('/api/dashboard?storeId=S03', expect.anything());
  });

  it('a single store shows no store comparison', async () => {
    vi.stubGlobal('fetch', backend());
    render(<DashboardPage user={MANAGER} store="S01" />);
    await screen.findByText('₹12,53,400');

    expect(screen.queryByText('Revenue by store')).not.toBeInTheDocument();
  });

  it('exports the same orders as a CSV file', async () => {
    const fetchMock = backend();
    vi.stubGlobal('fetch', fetchMock);
    URL.createObjectURL = vi.fn(() => 'blob:csv');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    render(<DashboardPage user={ADMIN} store="" />);
    await screen.findByText('₹12,53,400');

    await userEvent.click(screen.getByRole('button', { name: 'Export orders (CSV)' }));

    expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/orders\/export\.csv\?from=.*&to=/), expect.anything());
    expect(click).toHaveBeenCalled();
  });
});
