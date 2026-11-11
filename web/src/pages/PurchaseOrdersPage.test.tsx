import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { UserSummary } from '../api/auth';
import type { PoDetail } from '../api/types';
import { PurchaseOrdersPage } from './PurchaseOrdersPage';

const FINANCE: UserSummary = { id: 'U09', name: 'Meera Iyer', role: 'FINANCE_APPROVER', storeId: null };
const MANAGER: UserSummary = { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER', storeId: 'S01' };

function detail(overrides: Partial<PoDetail['summary']> = {}, approvals: PoDetail['approvals'] = []): PoDetail {
  return {
    summary: {
      id: 'PO-0312', storeId: 'S01', supplierId: 'SUP03', status: 'SUBMITTED', totalAmount: 74600, createdAt: '2026-10-28T05:30:00Z',
      validApprovals: approvals.filter((a) => a.counts).length, approvalsNeeded: 2, complianceFlag: null,
      requiresReapproval: false, stuck: false, version: 4, ...overrides,
    },
    createdBy: 'U03',
    createdByName: 'Arjun Rao',
    closedReason: null,
    lines: [
      { sku: 'SW-RIC-10234', productName: 'Basmati rice 5kg', qtyOrdered: 10, qtyReceived: 0, unitCost: 5000, lineTotal: 50000 },
      { sku: 'SW-OIL-20011', productName: 'Sunflower oil 1L', qtyOrdered: 12, qtyReceived: 0, unitCost: 2050, lineTotal: 24600 },
    ],
    approvals,
  };
}

const FIRST_APPROVAL = { approverId: 'U10', approverName: 'Anil Gupta', decision: 'APPROVED' as const, decidedAt: '2026-10-29T06:00:00Z', counts: true, step: 1 };

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** A fake core service for the PO screens; records POST bodies by path. */
function backend(po: PoDetail, after?: PoDetail) {
  const posts: Record<string, unknown> = {};
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    const path = url.split('?')[0];
    if (init?.method === 'POST') {
      posts[path] = JSON.parse(String(init.body));
      return Promise.resolve(json(200, after ?? po));
    }
    if (path === '/api/purchase-orders') {
      return Promise.resolve(json(200, { items: [po.summary], page: 0, size: 20, totalItems: 1, totalPages: 1 }));
    }
    if (path === `/api/purchase-orders/${po.summary.id}`) return Promise.resolve(json(200, po));
    return Promise.resolve(json(404, { detail: 'not mocked' }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return posts;
}

async function openPo(user: UserSummary) {
  render(<PurchaseOrdersPage user={user} store="" />);
  await userEvent.click(await screen.findByText('PO-0312'));
  await screen.findByRole('heading', { name: 'PO-0312' });
}

describe('purchase orders', () => {
  it('shows the second approval as the viewer’s step and sends Approve with the PO version', async () => {
    const posts = backend(detail({}, [FIRST_APPROVAL]));
    await openPo(FINANCE);

    expect(screen.getByText('Needs 2 approvers')).toBeInTheDocument();
    const tracker = screen.getByRole('list', { name: 'Progress' });
    expect(within(tracker).getByText('Approver 2').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(within(tracker).getByText('You')).toBeInTheDocument();
    expect(within(tracker).getByText('Arjun Rao')).toBeInTheDocument();                 // N6: names, not user IDs
    expect(within(tracker).getByText(/^Anil Gupta,/)).toBeInTheDocument();
    expect(screen.queryByText(/\bU1[01]\b/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

    expect(posts['/api/purchase-orders/PO-0312/decision']).toEqual({ decision: 'APPROVED', reason: null, version: 4 });
  });

  it('needs a reason before a PO can be rejected', async () => {
    const posts = backend(detail());
    await openPo(FINANCE);

    await userEvent.click(screen.getByRole('button', { name: 'Reject with reason' }));
    const reject = screen.getByRole('button', { name: 'Reject PO' });
    expect(reject).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Why are you rejecting this PO?'), 'Prices above the agreed rate');
    await userEvent.click(reject);

    expect(posts['/api/purchase-orders/PO-0312/decision']).toEqual({ decision: 'REJECTED', reason: 'Prices above the agreed rate', version: 4 });
  });

  it('does not offer a second approval to the person who gave the first (D31)', async () => {
    backend(detail({}, [{ ...FIRST_APPROVAL, approverId: FINANCE.id }]));
    await openPo(FINANCE);

    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.getByText(/second approval must come from a different person/)).toBeInTheDocument();
  });

  it('lets the store manager receive part of an approved PO', async () => {
    const posts = backend(detail({ status: 'APPROVED' }, [FIRST_APPROVAL, { ...FIRST_APPROVAL, approverId: 'U11', step: 2 }]));
    await openPo(MANAGER);

    await userEvent.click(screen.getByRole('button', { name: 'Receive goods' }));
    const rice = screen.getByLabelText('Arrived now: Basmati rice 5kg');
    await userEvent.clear(rice);
    await userEvent.type(rice, '4');
    const oil = screen.getByLabelText('Arrived now: Sunflower oil 1L');
    await userEvent.clear(oil);
    await userEvent.type(oil, '0');
    await userEvent.click(screen.getByRole('button', { name: 'Receive 4 units' }));

    expect(posts['/api/purchase-orders/PO-0312/receive']).toEqual({ lines: [{ sku: 'SW-RIC-10234', qty: 4 }], version: 4 });
  });

  it('refuses to receive more than is still due', async () => {
    backend(detail({ status: 'APPROVED' }));
    await openPo(MANAGER);

    await userEvent.click(screen.getByRole('button', { name: 'Receive goods' }));
    const rice = screen.getByLabelText('Arrived now: Basmati rice 5kg');
    await userEvent.clear(rice);
    await userEvent.type(rice, '11');

    expect(screen.getByText(/between 0 and what is still due/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Receive \d+ units$/ })).toBeDisabled();
  });
});
