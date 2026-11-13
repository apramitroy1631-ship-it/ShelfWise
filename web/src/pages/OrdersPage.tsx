import { IconAlertTriangle, IconClock, IconDownload, IconPackage, IconPlus, IconTruck, IconX } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import type { UserSummary } from '../api/auth';
import { api, downloadFile, query } from '../api/client';
import type { OrderDetail, OrderStatus, OrderSummary } from '../api/types';
import { Chip, Kpi, ListState, ORDER_STATUSES, OrderStatusChip, Pager, SearchBox, orderStatusLabel } from '../components/ui';
import { dateTime, money } from '../lib/format';
import { useCounts, useList } from '../lib/useList';
import { NewOrderForm } from './NewOrderForm';

const SIZE = 20;

/** What each status can move to (D29: cancel only before shipping). */
const NEXT: Partial<Record<OrderStatus, { to: OrderStatus; label: string }[]>> = {
  PENDING: [{ to: 'PACKED', label: 'Mark packed' }, { to: 'CANCELLED', label: 'Cancel order' }],
  PACKED: [{ to: 'SHIPPED', label: 'Mark shipped' }, { to: 'CANCELLED', label: 'Cancel order' }],
  SHIPPED: [{ to: 'DELIVERED', label: 'Mark delivered' }],
};

/** Bulk moves offered when rows are selected; each applies only to the selected orders in the matching status. */
const BULK: { from: OrderStatus; to: OrderStatus; label: string }[] = [
  { from: 'PENDING', to: 'PACKED', label: 'Mark packed' },
  { from: 'PACKED', to: 'SHIPPED', label: 'Mark shipped' },
  { from: 'SHIPPED', to: 'DELIVERED', label: 'Mark delivered' },
];

/** Orders (M11): KPIs that filter, list with search and date range, bulk status moves, detail panel, New order form. */
/** {@code startStuck}: open with "stuck only" on, as a stuck-orders notification does (D32). */
export function OrdersPage({ user, store, startStuck = false }: { user: UserSummary; store: string; startStuck?: boolean }) {
  const canAct = user.role !== 'FINANCE_APPROVER';
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const [stuck, setStuck] = useState(startStuck);
  const [text, setText] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [checked, setChecked] = useState<Map<string, OrderSummary>>(new Map());
  const [creating, setCreating] = useState(false);
  const [reload, setReload] = useState(0);
  const [bulkMessage, setBulkMessage] = useState<string | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  useEffect(() => { setPage(0); setChecked(new Map()); }, [store]);

  const filters = { storeId: store, q: text, from, to };
  const { data, error, loading } = useList<OrderSummary>(`/api/orders${query({ ...filters, status, stuck, page, size: SIZE })}`, reload);
  const counts = useCounts({
    ...Object.fromEntries(['', ...ORDER_STATUSES].map((s) => [s || 'ALL', `/api/orders${query({ ...filters, status: s, size: 1 })}`])),
    STUCK: `/api/orders${query({ ...filters, stuck: true, size: 1 })}`,
  }, reload);

  function changeFilter(apply: () => void) {
    apply();
    setPage(0);
    setChecked(new Map());
  }

  function toggle(order: OrderSummary) {
    setChecked((current) => {
      const next = new Map(current);
      if (next.has(order.id)) next.delete(order.id);
      else next.set(order.id, order);
      return next;
    });
  }

  const pageItems = data?.items ?? [];
  const allChecked = pageItems.length > 0 && pageItems.every((o) => checked.has(o.id));

  function toggleAll() {
    setChecked((current) => {
      const next = new Map(current);
      if (allChecked) pageItems.forEach((o) => next.delete(o.id));
      else pageItems.forEach((o) => next.set(o.id, o));
      return next;
    });
  }

  /** One request per order, each with its own version; a refusal (e.g. someone else moved it first) is reported, not hidden. */
  async function bulkMove(fromStatus: OrderStatus, toStatus: OrderStatus) {
    const targets = [...checked.values()].filter((o) => o.status === fromStatus);
    setBulkBusy(true);
    setBulkMessage(null);
    const failed: string[] = [];
    for (const o of targets) {
      try {
        await api(`/api/orders/${o.id}/status`, { method: 'POST', body: JSON.stringify({ to: toStatus, version: o.version }) });
      } catch (e) {
        failed.push(`${o.id}: ${(e as Error).message}`);
      }
    }
    const done = targets.length - failed.length;
    setBulkMessage(`${done} of ${targets.length} marked ${orderStatusLabel(toStatus).toLowerCase()}.${failed.length ? ` Not changed: ${failed.join('; ')}` : ''}`);
    setChecked(new Map());
    setBulkBusy(false);
    setReload((n) => n + 1);
  }

  const checkedList = [...checked.values()];
  const filtersOn = !!(status || text || from || to || stuck);

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Orders</h1>
          <p>{store ? `Store ${store}` : 'All 5 stores'}{from || to ? ` · ${from || '…'} to ${to || 'today'}` : ''}</p>
        </div>
        <div className="page-actions">
          <button type="button" className="secondary"
                  onClick={() => downloadFile(`/api/orders/export.csv${query({ ...filters, status })}`, 'orders.csv')
                    .then(() => setExportError(null)).catch((e: Error) => setExportError(e.message))}>
            <IconDownload size={15} aria-hidden="true" />Export CSV
          </button>
          {user.role === 'STORE_MANAGER' && !creating && (
            <button type="button" onClick={() => setCreating(true)}><IconPlus size={15} aria-hidden="true" />New order</button>
          )}
        </div>
      </header>
      {exportError && <p className="error" role="alert">{exportError}</p>}

      {creating && (
        <NewOrderForm storeId={user.storeId ?? ''}
                      onCreated={(order) => { setCreating(false); setSelectedId(order.id); setReload((n) => n + 1); }}
                      onCancel={() => setCreating(false)} />
      )}

      <div className="kpis" role="group" aria-label="Orders by status">
        <Kpi label="All orders" value={counts.ALL ?? null} active={status === '' && !stuck} onClick={() => changeFilter(() => { setStatus(''); setStuck(false); })} />
        <Kpi label="Pending" icon={IconClock} value={counts.PENDING ?? null} active={status === 'PENDING'} onClick={() => changeFilter(() => setStatus('PENDING'))} />
        <Kpi label="Packed" icon={IconPackage} value={counts.PACKED ?? null} active={status === 'PACKED'} onClick={() => changeFilter(() => setStatus('PACKED'))} />
        <Kpi label="Shipped" icon={IconTruck} value={counts.SHIPPED ?? null} active={status === 'SHIPPED'} onClick={() => changeFilter(() => setStatus('SHIPPED'))} />
        <Kpi label="Stuck" icon={IconAlertTriangle} value={counts.STUCK ?? null} alert={!!counts.STUCK} active={stuck}
             hint="Still open past the stuck limit" onClick={() => changeFilter(() => setStuck(!stuck))} />
      </div>

      <div className="split">
        <div className="split-main">
          <div className="listing">
            <div className="toolbar">
              <SearchBox label="Search orders" placeholder="Order ID or customer" value={text} onChange={(v) => changeFilter(() => setText(v))} />
              <select aria-label="Order status" value={status} onChange={(e) => changeFilter(() => setStatus(e.target.value as OrderStatus | ''))}>
                <option value="">Status: any</option>
                {ORDER_STATUSES.map((s) => <option key={s} value={s}>{orderStatusLabel(s)}</option>)}
              </select>
              <label className="inline-label">From<input type="date" aria-label="Placed from" value={from} max={to || undefined} onChange={(e) => changeFilter(() => setFrom(e.target.value))} /></label>
              <label className="inline-label">To<input type="date" aria-label="Placed to" value={to} min={from || undefined} onChange={(e) => changeFilter(() => setTo(e.target.value))} /></label>
              <span className="muted small">Newest first</span>
            </div>

            {canAct && checkedList.length > 0 && (
              <div className="bulk" role="region" aria-label="Bulk actions">
                <span>{checkedList.length} selected</span>
                <span className="spacer" />
                {BULK.map((b) => {
                  const n = checkedList.filter((o) => o.status === b.from).length;
                  return n > 0 && (
                    <button key={b.to} type="button" className="secondary" disabled={bulkBusy} onClick={() => bulkMove(b.from, b.to)}>
                      {b.label} ({n})
                    </button>
                  );
                })}
                <button type="button" className="ghost" aria-label="Clear selection" onClick={() => setChecked(new Map())}><IconX size={14} aria-hidden="true" /></button>
              </div>
            )}
            {bulkMessage && <p className="notice" role="status" style={{ margin: 8 }}>{bulkMessage}</p>}

            <ListState loading={loading && !data} error={error} empty={!!data && data.items.length === 0}
                       emptyText="No orders match these filters." onClear={filtersOn ? () => changeFilter(() => { setStatus(''); setText(''); setFrom(''); setTo(''); }) : undefined} />
            {data && data.items.length > 0 && (
              <table>
                <thead>
                  <tr>
                    {canAct && <th className="check"><input type="checkbox" aria-label="Select all on this page" checked={allChecked} onChange={toggleAll} /></th>}
                    <th>Order</th>{!store && <th>Store</th>}<th>Customer</th><th>Placed</th><th>Status</th><th className="num">Total</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((o) => (
                    <tr key={o.id} className={`clickable${o.id === selectedId ? ' selected' : ''}`} onClick={() => setSelectedId(o.id)}>
                      {canAct && (
                        <td className="check" onClick={(e) => e.stopPropagation()}>
                          <input type="checkbox" aria-label={`Select ${o.id}`} checked={checked.has(o.id)} onChange={() => toggle(o)} />
                        </td>
                      )}
                      <td className="mono">{o.id}</td>
                      {!store && <td>{o.storeId}</td>}
                      <td>{o.customerName}<div className="muted small">{o.customerPhone}</div></td>
                      <td className="text-2">{dateTime(o.createdAt)}</td>
                      <td>
                        <div className="flags">
                          <OrderStatusChip status={o.status} />
                          {o.stuck && <Chip kind="warn" icon={IconClock}>Stuck</Chip>}
                          {o.reviewStatus === 'NEEDS_REVIEW' && <Chip kind="danger" icon={IconAlertTriangle}>Review</Chip>}
                        </div>
                      </td>
                      <td className="num">{money(o.totalAmount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {data && data.totalItems > 0 && <Pager page={page} size={SIZE} total={data.totalItems} onPage={setPage} />}
          </div>
        </div>
        {selectedId && (
          <OrderPanel key={selectedId} id={selectedId} canAct={canAct}
                      onChanged={() => setReload((n) => n + 1)} onClose={() => setSelectedId(null)} />
        )}
      </div>
    </section>
  );
}

function OrderPanel({ id, canAct, onChanged, onClose }: { id: string; canAct: boolean; onChanged: () => void; onClose: () => void }) {
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<OrderDetail>(`/api/orders/${id}`).then(setOrder).catch((e: Error) => setError(e.message));
  }, [id]);

  async function move(to: OrderStatus) {
    if (!order) return;
    setBusy(true);
    setError(null);
    try {
      setOrder(await api<OrderDetail>(`/api/orders/${id}/status`, { method: 'POST', body: JSON.stringify({ to, version: order.version }) }));
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const next = order ? NEXT[order.status] ?? [] : [];

  return (
    <aside className="panel side" aria-label={`Order ${id}`}>
      <div className="panel-head">
        <h2 className="mono">{id}</h2>
        <button type="button" className="secondary" onClick={onClose} aria-label="Close"><IconX size={14} aria-hidden="true" /></button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {!order ? <p className="muted">Loading…</p> : (
        <>
          <div className="flags"><OrderStatusChip status={order.status} /> {order.stuck && <Chip kind="warn" icon={IconClock}>Stuck</Chip>}</div>
          <p style={{ margin: 0 }}>{order.customerName} · {order.customerPhone}</p>
          <p className="muted" style={{ margin: 0 }}>Store {order.storeId} · placed {dateTime(order.createdAt)} by {order.placedByName ?? order.placedBy}</p>
          {order.reviewStatus === 'NEEDS_REVIEW' && <p className="notice danger">This imported order is waiting for admin review and isn't counted in revenue.</p>}
          <div className="listing">
            <table>
              <thead><tr><th>Item</th><th className="num">Qty</th><th className="num">Total</th></tr></thead>
              <tbody>
                {order.lines.map((l) => (
                  <tr key={l.sku}>
                    <td>{l.productName ?? 'Unknown product'}<div className="muted small mono">{l.sku} · {money(l.unitPrice)}</div></td>
                    <td className="num">{l.qty}</td>
                    <td className="num">{money(l.lineTotal)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot><tr><td colSpan={2}><strong>Total</strong></td><td className="num"><strong>{money(order.totalAmount)}</strong></td></tr></tfoot>
            </table>
          </div>
          {canAct && next.length > 0 && (
            <div className="form-actions">
              {next.map((n) => (
                <button key={n.to} type="button" disabled={busy} className={n.to === 'CANCELLED' ? 'danger' : ''} onClick={() => move(n.to)}>
                  {n.label}
                </button>
              ))}
            </div>
          )}
        </>
      )}
    </aside>
  );
}
