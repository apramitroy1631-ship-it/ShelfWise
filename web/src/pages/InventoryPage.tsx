import { IconAlertTriangle, IconArchive, IconRefresh, IconX } from '@tabler/icons-react';
import { useEffect, useState, type FormEvent } from 'react';
import type { UserSummary } from '../api/auth';
import { api, query } from '../api/client';
import type { Movement, StockItem } from '../api/types';
import { Chip, Kpi, ListState, Pager, SearchBox } from '../components/ui';
import { dateTime, reasonLabel } from '../lib/format';
import { useCounts, useList } from '../lib/useList';

const SIZE = 20;

type View = 'all' | 'low' | 'recount';

/** Inventory (M10): stock per store, KPI quick filters, adjustments with a reason, and the history of changes. */
export function InventoryPage({ user, store, startView = 'all' }: { user: UserSummary; store: string; startView?: View }) {
  const canAdjust = user.role !== 'FINANCE_APPROVER';
  const [text, setText] = useState('');
  const [view, setView] = useState<View>(startView);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<StockItem | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => { setPage(0); setSelected(null); }, [store]);

  const base = { storeId: store, q: text };
  const { data, error, loading } = useList<StockItem>(
    `/api/inventory${query({ ...base, lowOnly: view === 'low', recountOnly: view === 'recount', page, size: SIZE })}`, reload);
  const counts = useCounts({
    all: `/api/inventory${query({ ...base, size: 1 })}`,
    low: `/api/inventory${query({ ...base, lowOnly: true, size: 1 })}`,
    recount: `/api/inventory${query({ ...base, recountOnly: true, size: 1 })}`,
  }, reload);

  function show(next: View) {
    setView(next);
    setPage(0);
  }

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Inventory</h1>
          <p>{store ? `Store ${store}` : 'All 5 stores'}</p>
        </div>
      </header>

      <div className="kpis" role="group" aria-label="Stock views">
        <Kpi label="Items stocked" value={counts.all ?? null} active={view === 'all'} onClick={() => show('all')} />
        <Kpi label="Low stock" icon={IconAlertTriangle} value={counts.low ?? null} alert hint={view === 'low' ? 'Showing low stock' : 'Show low stock →'}
             active={view === 'low'} onClick={() => show(view === 'low' ? 'all' : 'low')} />
        <Kpi label="Needs recount" icon={IconRefresh} value={counts.recount ?? null} active={view === 'recount'} onClick={() => show(view === 'recount' ? 'all' : 'recount')} />
      </div>

      <div className="split">
        <div className="split-main">
          <div className="listing">
            <div className="toolbar">
              <SearchBox label="Search stock" placeholder="Search name or SKU" value={text} onChange={(v) => { setText(v); setPage(0); }} />
              <select aria-label="Stock view" value={view} onChange={(e) => show(e.target.value as View)}>
                <option value="all">All items</option>
                <option value="low">Low stock only</option>
                <option value="recount">Needs recount</option>
              </select>
            </div>
            <ListState loading={loading && !data} error={error} empty={!!data && data.items.length === 0} emptyText="No stock matches these filters."
                       onClear={text || view !== 'all' ? () => { setText(''); show('all'); } : undefined} />
            {data && data.items.length > 0 && (
              <table>
                <thead>
                  <tr>{!store && <th>Store</th>}<th>Product</th><th>SKU</th><th className="num">In stock</th><th className="num">Reorder at</th><th>Flags</th></tr>
                </thead>
                <tbody>
                  {data.items.map((item) => {
                    const isSelected = selected?.sku === item.sku && selected.storeId === item.storeId;
                    return (
                      <tr key={item.storeId + item.sku} className={`clickable${isSelected ? ' selected' : item.low ? ' low' : ''}`} onClick={() => setSelected(item)}>
                        {!store && <td>{item.storeId}</td>}
                        <td>{item.productName}<div className="muted small">{item.category}</div></td>
                        <td className="mono">{item.sku}</td>
                        <td className={`num${item.low ? ' neg' : ''}`}><strong>{item.quantity}</strong></td>
                        <td className="num text-2">{item.reorderLevel}</td>
                        <td>
                          <div className="flags">
                            {item.low && <Chip kind="danger" icon={IconAlertTriangle}>Low</Chip>}
                            {item.recountNeeded && <Chip kind="warn" icon={IconRefresh}>Recount</Chip>}
                            {item.productStatus === 'ARCHIVED' && <Chip kind="muted" icon={IconArchive}>Archived</Chip>}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
            {data && data.totalItems > 0 && <Pager page={page} size={SIZE} total={data.totalItems} onPage={setPage} />}
          </div>
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
    <aside className="panel side" aria-label={item.productName}>
      <div className="panel-head">
        <h2>{item.productName}</h2>
        <button type="button" className="secondary" onClick={onClose} aria-label="Close"><IconX size={14} aria-hidden="true" /></button>
      </div>
      <p className="muted mono" style={{ margin: 0 }}>{item.sku} · Store {item.storeId}</p>
      <p className={`big${item.low ? ' neg' : ''}`}>{item.quantity} <span className="muted" style={{ fontSize: 13, fontWeight: 400 }}>in stock · reorder at {item.reorderLevel}</span></p>
      {item.low && <p className="notice danger" style={{ margin: 0 }}>Below its reorder level. Draft a purchase order from Purchase orders → New draft.</p>}

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
          <label className="wide">Note<input value={note} required={type === 'OTHER'} onChange={(e) => setNote(e.target.value)} /></label>
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
              <div className="muted small">{dateTime(m.at)}{m.actorUserId ? ` · ${m.actorName ?? m.actorUserId}` : ' · System'}{m.note ? ` · ${m.note}` : ''}</div>
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}
