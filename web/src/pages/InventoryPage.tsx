import { useEffect, useState, type FormEvent } from 'react';
import type { UserSummary } from '../api/auth';
import { api, query } from '../api/client';
import type { Movement, Page, StockItem } from '../api/types';
import { Chip, ListState, Pager } from '../components/ui';
import { dateTime, reasonLabel } from '../lib/format';

const SIZE = 20;
const STORES = ['S01', 'S02', 'S03', 'S04', 'S05'];

/** Inventory (M10): stock per store, adjustments with a reason, and the history of changes. */
export function InventoryPage({ user }: { user: UserSummary }) {
  const headOffice = user.role !== 'STORE_MANAGER';
  const canAdjust = user.role !== 'FINANCE_APPROVER';
  const [store, setStore] = useState(headOffice ? '' : user.storeId ?? '');
  const [text, setText] = useState('');
  const [lowOnly, setLowOnly] = useState(false);
  const [recountOnly, setRecountOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<StockItem> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<StockItem | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    setLoading(true);
    setError(null);
    api<Page<StockItem>>(`/api/inventory${query({ storeId: store, q: text, lowOnly, recountOnly, page, size: SIZE })}`)
      .then(setData)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [store, text, lowOnly, recountOnly, page, reload]);

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Inventory</h1>
          <p className="muted">{store ? `Store ${store}` : 'All 5 stores'}{data ? ` · ${data.totalItems.toLocaleString('en-IN')} items` : ''}</p>
        </div>
      </header>

      <div className="toolbar">
        <input aria-label="Search stock" placeholder="Search name or SKU" value={text} onChange={(e) => { setText(e.target.value); setPage(0); }} />
        {headOffice && (
          <select aria-label="Store" value={store} onChange={(e) => { setStore(e.target.value); setPage(0); }}>
            <option value="">All stores</option>
            {STORES.map((s) => <option key={s}>{s}</option>)}
          </select>
        )}
        <label className="check"><input type="checkbox" checked={lowOnly} onChange={(e) => { setLowOnly(e.target.checked); setPage(0); }} /> Low stock only</label>
        <label className="check"><input type="checkbox" checked={recountOnly} onChange={(e) => { setRecountOnly(e.target.checked); setPage(0); }} /> Needs recount</label>
      </div>

      <div className="split">
        <div className="split-main">
          <ListState loading={loading && !data} error={error} empty={!!data && data.items.length === 0} emptyText="No stock matches these filters." />
          {data && data.items.length > 0 && (
            <table>
              <thead>
                <tr><th>Store</th><th>SKU</th><th>Product</th><th className="num">In stock</th><th className="num">Reorder at</th><th>Flags</th></tr>
              </thead>
              <tbody>
                {data.items.map((item) => (
                  <tr key={item.storeId + item.sku}
                      className={selected?.sku === item.sku && selected.storeId === item.storeId ? 'selected' : item.low ? 'low' : ''}
                      onClick={() => setSelected(item)}>
                    <td>{item.storeId}</td>
                    <td className="mono">{item.sku}</td>
                    <td>{item.productName}</td>
                    <td className="num">{item.quantity}</td>
                    <td className="num">{item.reorderLevel}</td>
                    <td className="flags">
                      {item.low && <Chip kind="danger" icon="▼">Low</Chip>}
                      {item.recountNeeded && <Chip kind="warn" icon="↻">Recount</Chip>}
                      {item.productStatus === 'ARCHIVED' && <Chip kind="muted" icon="▢">Archived</Chip>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {data && <Pager page={page} size={SIZE} total={data.totalItems} onPage={setPage} />}
        </div>
        {selected && (
          <StockPanel key={selected.storeId + selected.sku} item={selected} canAdjust={canAdjust}
                      onChanged={(item) => { setSelected(item); setReload((n) => n + 1); }} onClose={() => setSelected(null)} />
        )}
      </div>
    </section>
  );
}

/** One item: adjust it and see every change with its reason. */
function StockPanel({ item, canAdjust, onChanged, onClose }: { item: StockItem; canAdjust: boolean; onChanged: (i: StockItem) => void; onClose: () => void }) {
  const [history, setHistory] = useState<Movement[] | null>(null);
  const [type, setType] = useState('DAMAGED');
  const [quantity, setQuantity] = useState('1');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const path = `/api/inventory/${item.storeId}/${encodeURIComponent(item.sku)}`;

  useEffect(() => {
    api<Movement[]>(`${path}/history`).then(setHistory).catch(() => setHistory([]));
  }, [path, item.version]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const result = await api<{ item: StockItem }>(`${path}/adjustments`, {
        method: 'POST',
        body: JSON.stringify({ type, quantity: Number(quantity), note: note || null }),
      });
      setNote('');
      onChanged(result.item);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <aside className="panel side">
      <div className="panel-head">
        <h2>{item.productName}</h2>
        <button type="button" className="secondary" onClick={onClose} aria-label="Close">✕</button>
      </div>
      <p className="muted mono">{item.sku} · Store {item.storeId}</p>
      <p className="big">{item.quantity} <span className="muted">in stock (reorder at {item.reorderLevel})</span></p>

      {canAdjust && (
        <form className="form-grid" onSubmit={submit}>
          <label>Adjustment
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <option value="DAMAGED">Damaged (remove)</option>
              <option value="RECOUNT">Recount (set counted number)</option>
              <option value="OTHER">Other (+ or −, note required)</option>
            </select>
          </label>
          <label>{type === 'RECOUNT' ? 'Counted on shelf' : type === 'DAMAGED' ? 'Units damaged' : 'Change (e.g. -2 or 3)'}
            <input type="number" required value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          </label>
          <label>Note<input value={note} required={type === 'OTHER'} onChange={(e) => setNote(e.target.value)} /></label>
          {error && <p className="error" role="alert">{error}</p>}
          <div className="form-actions"><button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save adjustment'}</button></div>
        </form>
      )}

      <h3>History</h3>
      {history === null ? <p className="muted">Loading…</p> : history.length === 0 ? <p className="muted">No changes recorded yet.</p> : (
        <ul className="history">
          {history.map((m) => (
            <li key={m.id}>
              <strong className={m.delta < 0 ? 'neg' : 'pos'}>{m.delta > 0 ? `+${m.delta}` : m.delta}</strong> → {m.quantityAfter}
              <span> · {reasonLabel(m.reason)}{m.referenceId ? ` ${m.referenceId}` : ''}</span>
              <div className="muted">{dateTime(m.at)}{m.actorUserId ? ` · ${m.actorUserId}` : ''}{m.note ? ` · ${m.note}` : ''}</div>
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}
