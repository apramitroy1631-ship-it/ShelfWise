import { IconAlertTriangle, IconArrowLeft, IconCheck, IconX } from '@tabler/icons-react';
import { useCallback, useEffect, useState } from 'react';
import { api, query } from '../api/client';
import { Chip, ListState, OrderStatusChip } from '../components/ui';
import type { OrderStatus } from '../api/types';
import { dateTime, money } from '../lib/format';

export type ReviewProblem = 'NEGATIVE_QTY' | 'NO_PRICE';

export type ReviewLine = {
  sku: string; productSku: string | null; productName: string | null; qty: number; unitPrice: number; lineTotal: number;
  problem: ReviewProblem | null; originalQty: number | null; originalUnitPrice: number | null;
};

export type ReviewItem = {
  orderId: string; storeId: string; status: OrderStatus; createdAt: string; customerName: string; totalAmount: number;
  originalTotal: number | null; reviewStatus: 'NEEDS_REVIEW' | 'CORRECTED' | 'REJECTED'; reviewNote: string | null;
  reviewedBy: string | null; reviewedByName: string | null; reviewedAt: string | null; version: number;
  problems: string[]; lines: ReviewLine[];
};

type ReviewList = { waiting: number; items: ReviewItem[] };

/**
 * The admin review queue (D10). Imported orders whose money can't be trusted wait here, out of revenue,
 * until an admin corrects them (right quantity or price, with a reason) or rejects them (with a reason).
 */
export function ReviewsPage({ store, startOrder }: { store: string; startOrder?: string | null }) {
  const [state, setState] = useState<'WAITING' | 'DECIDED'>('WAITING');
  const [list, setList] = useState<ReviewList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(startOrder ?? null);

  const load = useCallback(() => {
    setList(null);
    setError(null);
    api<ReviewList>(`/api/admin/reviews${query({ state, storeId: store })}`).then(setList).catch((e: Error) => setError(e.message));
  }, [state, store]);

  useEffect(load, [load]);

  if (open) {
    return <ReviewDetail orderId={open} onBack={() => { setOpen(null); load(); }} />;
  }

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Review queue</h1>
          <p>Imported orders whose money can't be trusted. They stay out of revenue until you correct or reject them.</p>
        </div>
      </header>
      <div className="toolbar">
        <div className="segmented" role="tablist" aria-label="Which orders">
          <button type="button" role="tab" aria-selected={state === 'WAITING'} className={state === 'WAITING' ? 'active' : ''} onClick={() => setState('WAITING')}>
            Waiting{list ? ` (${list.waiting})` : ''}
          </button>
          <button type="button" role="tab" aria-selected={state === 'DECIDED'} className={state === 'DECIDED' ? 'active' : ''} onClick={() => setState('DECIDED')}>
            Decided
          </button>
        </div>
      </div>
      <ListState loading={!list && !error} error={error} empty={!!list && list.items.length === 0}
                 emptyText={state === 'WAITING' ? 'Nothing waiting for review.' : 'No decisions yet.'} />
      {list && list.items.length > 0 && (
        <div className="listing">
          <table>
            <thead>
              <tr><th>Order</th><th>Store</th><th>What's wrong</th><th className="num">Total</th>
                <th>{state === 'WAITING' ? 'Placed' : 'Decision'}</th></tr>
            </thead>
            <tbody>
              {list.items.map((r) => (
                <tr key={r.orderId} className="clickable" onClick={() => setOpen(r.orderId)}>
                  <td><button type="button" className="link mono" onClick={() => setOpen(r.orderId)}>{r.orderId}</button>
                    <div className="muted small">{r.customerName}</div></td>
                  <td>{r.storeId}</td>
                  <td className="small">{r.problems.join(' · ')}</td>
                  <td className="num">{money(r.totalAmount)}</td>
                  <td>{state === 'WAITING' ? dateTime(r.createdAt) : <Decision item={r} />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Decision({ item }: { item: ReviewItem }) {
  return (
    <>
      {item.reviewStatus === 'CORRECTED' ? <Chip kind="success" icon={IconCheck}>Corrected</Chip> : <Chip kind="danger" icon={IconX}>Rejected</Chip>}
      <div className="muted small">{item.reviewedByName ?? item.reviewedBy} · {dateTime(item.reviewedAt)}</div>
    </>
  );
}

function ReviewDetail({ orderId, onBack }: { orderId: string; onBack: () => void }) {
  const [item, setItem] = useState<ReviewItem | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fixes, setFixes] = useState<Record<string, { qty: string; unitPrice: string }>>({});
  const [reason, setReason] = useState('');
  const [mode, setMode] = useState<'correct' | 'reject'>('correct');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<ReviewItem>(`/api/admin/reviews/${encodeURIComponent(orderId)}`).then(setItem).catch((e: Error) => setError(e.message));
  }, [orderId]);

  if (!item) {
    return (
      <section className="page">
        <button type="button" className="secondary back" onClick={onBack}><IconArrowLeft size={15} aria-hidden="true" />Review queue</button>
        {error ? <p className="notice danger" role="alert">{error}</p> : <p className="muted">Loading…</p>}
      </section>
    );
  }

  const waiting = item.reviewStatus === 'NEEDS_REVIEW';
  const flagged = item.lines.filter((l) => l.problem);
  const fixOf = (l: ReviewLine) => fixes[l.sku] ?? { qty: '', unitPrice: '' };
  // What the total becomes with the values typed so far (unfilled flagged lines count as they are now).
  const preview = item.lines.reduce((sum, l) => {
    const f = fixOf(l);
    const qty = l.problem && Number(f.qty) > 0 ? Number(f.qty) : l.qty;
    const price = l.problem === 'NO_PRICE' && Number(f.unitPrice) > 0 ? Number(f.unitPrice) : l.unitPrice;
    return sum + qty * price;
  }, 0);
  const complete = flagged.every((l) => {
    const f = fixOf(l);
    const qtyOk = l.problem === 'NEGATIVE_QTY' ? Number.isInteger(Number(f.qty)) && Number(f.qty) >= 1 : (f.qty === '' && l.qty >= 1) || Number(f.qty) >= 1;
    const priceOk = l.problem === 'NO_PRICE' ? Number(f.unitPrice) > 0 : true;
    return qtyOk && priceOk;
  });

  async function decide(action: 'correct' | 'reject') {
    if (!item) return;
    setBusy(true);
    setError(null);
    const body = action === 'reject' ? { version: item.version, reason: reason.trim() } : {
      version: item.version,
      reason: reason.trim(),
      lines: flagged.map((l) => {
        const f = fixOf(l);
        return { sku: l.sku, qty: f.qty === '' ? undefined : Number(f.qty), unitPrice: l.problem === 'NO_PRICE' ? Number(f.unitPrice) : undefined };
      }),
    };
    try {
      setItem(await api<ReviewItem>(`/api/admin/reviews/${encodeURIComponent(item.orderId)}/${action}`, { method: 'POST', body: JSON.stringify(body) }));
      setReason('');
    } catch (e) {
      setError((e as Error).message);          // e.g. "was just decided by someone else; reload and try again"
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="page">
      <button type="button" className="secondary back" onClick={onBack}><IconArrowLeft size={15} aria-hidden="true" />Review queue</button>
      <div className="panel">
        <div className="po-head">
          <div>
            <div className="muted small">Imported order</div>
            <h1 className="mono" style={{ fontSize: 18 }}>{item.orderId}</h1>
            <div className="text-2">Store {item.storeId} · {item.customerName} · placed {dateTime(item.createdAt)}</div>
          </div>
          <div className="po-amount">
            <p className="big">{money(item.totalAmount)}</p>
            {item.originalTotal !== null && <div className="muted small">was {money(item.originalTotal)} in the file</div>}
            <OrderStatusChip status={item.status} />
          </div>
        </div>
        {waiting
          ? <p className="notice danger"><IconAlertTriangle size={15} aria-hidden="true" /> {item.problems.join(' · ')}. Not counted in revenue until you decide.</p>
          : <p className="notice"><Decision item={item} /> {item.reviewNote}</p>}
      </div>

      <div className="listing">
        <table>
          <thead><tr><th>Item</th><th className="num">Qty</th><th className="num">Unit price</th><th className="num">Line total</th>{waiting && <th>Correction</th>}</tr></thead>
          <tbody>
            {item.lines.map((l) => (
              <tr key={l.sku} className={l.problem ? 'low' : ''}>
                <td>{l.productName ?? 'Unknown product'}<div className="muted small mono">{l.sku}</div>
                  {l.problem && <Chip kind="danger" icon={IconAlertTriangle}>{l.problem === 'NEGATIVE_QTY' ? 'Negative quantity' : 'No price'}</Chip>}</td>
                <td className={`num${l.qty <= 0 ? ' neg' : ''}`}>{l.qty}{l.originalQty !== null && <div className="muted small">was {l.originalQty}</div>}</td>
                <td className="num">{money(l.unitPrice)}{l.originalUnitPrice !== null && <div className="muted small">was {money(l.originalUnitPrice)}</div>}</td>
                <td className="num">{money(l.lineTotal)}</td>
                {waiting && (
                  <td>
                    {l.problem && (
                      <div className="toolbar" style={{ padding: 0, border: 0 }}>
                        <input className="qty" type="number" min={1} step={1} aria-label={`Right quantity: ${l.sku}`} placeholder="Qty"
                               value={fixOf(l).qty} onChange={(e) => setFixes({ ...fixes, [l.sku]: { ...fixOf(l), qty: e.target.value } })} />
                        {l.problem === 'NO_PRICE' && (
                          <input className="qty" type="number" min={0.01} step={0.01} aria-label={`Price: ${l.sku}`} placeholder="Price"
                                 value={fixOf(l).unitPrice} onChange={(e) => setFixes({ ...fixes, [l.sku]: { ...fixOf(l), unitPrice: e.target.value } })} />
                        )}
                      </div>
                    )}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {waiting && (
        <div className="panel">
          <div className="segmented" role="tablist" aria-label="Decision">
            <button type="button" role="tab" aria-selected={mode === 'correct'} className={mode === 'correct' ? 'active' : ''} onClick={() => setMode('correct')}>Correct</button>
            <button type="button" role="tab" aria-selected={mode === 'reject'} className={mode === 'reject' ? 'active' : ''} onClick={() => setMode('reject')}>Reject</button>
          </div>
          <form className="form-grid" onSubmit={(e) => { e.preventDefault(); void decide(mode); }}>
            <label className="wide">
              {mode === 'correct' ? 'Why is this the right value? (e.g. what the paper receipt shows)' : 'Why are you rejecting it? It will stay out of revenue.'}
              <textarea rows={2} maxLength={500} required value={reason} onChange={(e) => setReason(e.target.value)} />
            </label>
            {error && <p className="notice danger wide" role="alert">{error}</p>}
            <div className="form-actions wide">
              {mode === 'correct' && <span className="big" style={{ marginRight: 'auto' }}>New total {money(preview)}</span>}
              {mode === 'correct'
                ? <button type="submit" disabled={busy || !complete || !reason.trim()}><IconCheck size={15} aria-hidden="true" />{busy ? 'Saving…' : 'Correct and count in revenue'}</button>
                : <button type="submit" className="danger" disabled={busy || !reason.trim()}><IconX size={15} aria-hidden="true" />{busy ? 'Saving…' : 'Reject order'}</button>}
            </div>
          </form>
        </div>
      )}
    </section>
  );
}
