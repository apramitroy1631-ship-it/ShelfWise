import { useEffect, useState } from 'react';
import type { UserSummary } from '../api/auth';
import { api, query } from '../api/client';
import type { OrderDetail, OrderStatus, OrderSummary, Page } from '../api/types';
import { Chip, ListState, OrderStatusChip, Pager } from '../components/ui';
import { dateTime, money } from '../lib/format';
import { NewOrderForm } from './NewOrderForm';

const SIZE = 20;
const STORES = ['S01', 'S02', 'S03', 'S04', 'S05'];

/** What each status can move to (D29: cancel only before shipping). */
const NEXT: Partial<Record<OrderStatus, { to: OrderStatus; label: string }[]>> = {
  PENDING: [{ to: 'PACKED', label: 'Mark packed' }, { to: 'CANCELLED', label: 'Cancel order' }],
  PACKED: [{ to: 'SHIPPED', label: 'Mark shipped' }, { to: 'CANCELLED', label: 'Cancel order' }],
  SHIPPED: [{ to: 'DELIVERED', label: 'Mark delivered' }],
};

/** Orders (M11): list, detail with status actions, and the multi-step "New order" form for store managers. */
export function OrdersPage({ user }: { user: UserSummary }) {
  const headOffice = user.role !== 'STORE_MANAGER';
  const [store, setStore] = useState('');
  const [status, setStatus] = useState('');
  const [text, setText] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<OrderSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    setLoading(true);
    setError(null);
    api<Page<OrderSummary>>(`/api/orders${query({ storeId: store, status, q: text, page, size: SIZE })}`)
      .then(setData)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [store, status, text, page, reload]);

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Orders</h1>
          <p className="muted">{headOffice ? (store ? `Store ${store}` : 'All 5 stores') : `Store ${user.storeId}`}{data ? ` · ${data.totalItems.toLocaleString('en-IN')} orders` : ''}</p>
        </div>
        {user.role === 'STORE_MANAGER' && !creating && <button type="button" onClick={() => setCreating(true)}>New order</button>}
      </header>

      {creating && (
        <NewOrderForm storeId={user.storeId ?? ''}
                      onCreated={(order) => { setCreating(false); setSelectedId(order.id); setReload((n) => n + 1); }}
                      onCancel={() => setCreating(false)} />
      )}

      <div className="toolbar">
        <input aria-label="Search orders" placeholder="Order ID or customer" value={text} onChange={(e) => { setText(e.target.value); setPage(0); }} />
        <select aria-label="Order status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="">All statuses</option>
          <option value="PENDING">Pending</option>
          <option value="PACKED">Packed</option>
          <option value="SHIPPED">Shipped</option>
          <option value="DELIVERED">Delivered</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
        {headOffice && (
          <select aria-label="Store" value={store} onChange={(e) => { setStore(e.target.value); setPage(0); }}>
            <option value="">All stores</option>
            {STORES.map((s) => <option key={s}>{s}</option>)}
          </select>
        )}
      </div>

      <div className="split">
        <div className="split-main">
          <ListState loading={loading && !data} error={error} empty={!!data && data.items.length === 0} emptyText="No orders match these filters." />
          {data && data.items.length > 0 && (
            <table>
              <thead>
                <tr><th>Order</th><th>Store</th><th>Customer</th><th>Placed</th><th>Status</th><th className="num">Total</th></tr>
              </thead>
              <tbody>
                {data.items.map((o) => (
                  <tr key={o.id} className={o.id === selectedId ? 'selected' : ''} onClick={() => setSelectedId(o.id)}>
                    <td className="mono">{o.id}</td>
                    <td>{o.storeId}</td>
                    <td>{o.customerName}<div className="muted small">{o.customerPhone}</div></td>
                    <td>{dateTime(o.createdAt)}</td>
                    <td className="flags">
                      <OrderStatusChip status={o.status} />
                      {o.stuck && <Chip kind="warn" icon="⏱">Stuck</Chip>}
                      {o.reviewStatus === 'NEEDS_REVIEW' && <Chip kind="danger" icon="!">Review</Chip>}
                    </td>
                    <td className="num">{money(o.totalAmount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {data && <Pager page={page} size={SIZE} total={data.totalItems} onPage={setPage} />}
        </div>
        {selectedId && (
          <OrderPanel key={selectedId} id={selectedId} canAct={user.role !== 'FINANCE_APPROVER'}
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

  return (
    <aside className="panel side">
      <div className="panel-head">
        <h2 className="mono">{id}</h2>
        <button type="button" className="secondary" onClick={onClose} aria-label="Close">✕</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {!order ? <p className="muted">Loading…</p> : (
        <>
          <p><OrderStatusChip status={order.status} /> {order.stuck && <Chip kind="warn" icon="⏱">Stuck</Chip>}</p>
          <p>{order.customerName} · {order.customerPhone}</p>
          <p className="muted">Store {order.storeId} · placed {dateTime(order.createdAt)} by {order.placedBy}</p>
          {order.reviewStatus === 'NEEDS_REVIEW' && <p className="error">This imported order is waiting for admin review and isn't counted in revenue.</p>}
          <table>
            <thead><tr><th>Item</th><th className="num">Qty</th><th className="num">Price</th><th className="num">Total</th></tr></thead>
            <tbody>
              {order.lines.map((l) => (
                <tr key={l.sku}>
                  <td>{l.productName ?? 'Unknown product'}<div className="muted small mono">{l.sku}</div></td>
                  <td className="num">{l.qty}</td>
                  <td className="num">{money(l.unitPrice)}</td>
                  <td className="num">{money(l.lineTotal)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot><tr><td colSpan={3}>Total</td><td className="num"><strong>{money(order.totalAmount)}</strong></td></tr></tfoot>
          </table>
          {canAct && (NEXT[order.status] ?? []).length > 0 && (
            <div className="form-actions">
              {(NEXT[order.status] ?? []).map((n, i) => (
                <button key={n.to} type="button" disabled={busy} className={n.to === 'CANCELLED' || i > 0 ? 'secondary danger' : ''} onClick={() => move(n.to)}>
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
