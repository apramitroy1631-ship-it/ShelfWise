import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ReviewsPage, type ReviewItem } from './ReviewsPage';

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

// Like ORD-007191 in the provided data: one negative line among normal ones (D10).
const WAITING: ReviewItem = {
  orderId: 'ORD-007191', storeId: 'S01', status: 'DELIVERED', createdAt: '2026-04-27T06:28:06Z', customerName: 'Tara Bose',
  totalAmount: -2, originalTotal: null, reviewStatus: 'NEEDS_REVIEW', reviewNote: null, reviewedBy: null, reviewedByName: null,
  reviewedAt: null, version: 0, problems: ["'SW-PER-00079' has quantity -2 (D10)"],
  lines: [
    { sku: 'SW-PER-00079', productSku: 'SW-PER-00079', productName: 'Glow Soap 100g', qty: -2, unitPrice: 113, lineTotal: -226,
      problem: 'NEGATIVE_QTY', originalQty: null, originalUnitPrice: null },
    { sku: 'SW-GRO-00072', productSku: 'SW-GRO-00072', productName: 'Home Basics Rice 1kg', qty: 2, unitPrice: 112, lineTotal: 224,
      problem: null, originalQty: null, originalUnitPrice: null },
  ],
};

const CORRECTED: ReviewItem = {
  ...WAITING, totalAmount: 450, originalTotal: -2, reviewStatus: 'CORRECTED', reviewNote: 'Receipt shows 2', reviewedBy: 'U01',
  reviewedByName: 'Rohit Menon', reviewedAt: '2026-11-12T11:00:00Z', version: 1,
  lines: [{ ...WAITING.lines[0], qty: 2, lineTotal: 226, originalQty: -2, originalUnitPrice: 113 }, WAITING.lines[1]],
};

describe('review queue (D10)', () => {
  it('lists waiting orders with what is wrong, and corrects one with a reason', async () => {
    const fetchMock = vi.fn((url: string, init?: RequestInit) => Promise.resolve(
      url.startsWith('/api/admin/reviews?') || url === '/api/admin/reviews' ? json(200, { waiting: 1, items: [WAITING] })
        : url === '/api/admin/reviews/ORD-007191' ? json(200, WAITING)
          : url === '/api/admin/reviews/ORD-007191/correct' && init?.method === 'POST' ? json(200, CORRECTED)
            : json(404, {})));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<ReviewsPage store="" />);

    expect(await screen.findByText("'SW-PER-00079' has quantity -2 (D10)")).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Waiting (1)' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'ORD-007191' }));
    expect(await screen.findByText('Negative quantity')).toBeInTheDocument();
    const save = screen.getByRole('button', { name: /Correct and count in revenue/ });
    expect(save).toBeDisabled();                                     // needs the quantity and a reason

    await user.type(screen.getByLabelText('Right quantity: SW-PER-00079'), '2');
    expect(screen.getByText('New total ₹450')).toBeInTheDocument();
    expect(save).toBeDisabled();
    await user.type(screen.getByRole('textbox'), 'Receipt shows 2');
    await user.click(save);

    await waitFor(() => expect(screen.getByText('Corrected')).toBeInTheDocument());
    const [, init] = fetchMock.mock.calls.find(([url]) => url === '/api/admin/reviews/ORD-007191/correct')!;
    expect(JSON.parse(init!.body as string)).toEqual({ version: 0, reason: 'Receipt shows 2', lines: [{ sku: 'SW-PER-00079', qty: 2 }] });
    expect(screen.getByText('was -2')).toBeInTheDocument();          // the before stays visible
    expect(screen.queryByRole('button', { name: /Correct and count in revenue/ })).not.toBeInTheDocument();
  });

  it('rejects with a reason and shows the server message when someone else decided first', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(
      url === '/api/admin/reviews/ORD-007191' ? json(200, WAITING)
        : url === '/api/admin/reviews/ORD-007191/reject'
          ? json(409, { detail: 'ORD-007191 was just decided or changed by someone else; reload and try again' })
          : json(200, { waiting: 1, items: [WAITING] }))));
    const user = userEvent.setup();
    render(<ReviewsPage store="" startOrder="ORD-007191" />);

    await user.click(await screen.findByRole('tab', { name: 'Reject' }));
    const reject = screen.getByRole('button', { name: /Reject order/ });
    expect(reject).toBeDisabled();
    await user.type(screen.getByRole('textbox'), "Can't tell what was sold");
    await user.click(reject);

    expect(await screen.findByRole('alert')).toHaveTextContent('decided or changed by someone else');
  });
});
