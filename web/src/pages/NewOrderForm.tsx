import { useEffect, useMemo, useState } from 'react';
import { api, query } from '../api/client';
import type { OrderDetail, Page, StockItem } from '../api/types';
import { money } from '../lib/format';

type Line = { sku: string; name: string; price: number; available: number; qty: number };

/**
 * The multi-step order form (brief feature 5): 1 customer, 2 items, 3 review.
 * One idempotency key per attempt, so a double click or a retry creates one order (D8).
 * Only sellable products (active, priced, in stock) can be picked; the server checks again (D30).
 */
export function NewOrderForm({ storeId, onCreated, onCancel }: { storeId: string; onCreated: (o: OrderDetail) => void; onCancel: () => void }) {
  const [step, setStep] = useState(1);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('+91 ');
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<StockItem[]>([]);
  const [lines, setLines] = useState<Line[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const idempotencyKey = useMemo(() => crypto.randomUUID(), []);
  const phoneValid = /^\+91 [6-9]\d{9}$/.test(phone);
  const total = lines.reduce((sum, l) => sum + l.price * l.qty, 0);

  useEffect(() => {
    if (step !== 2 || search.trim().length < 2) {
      setResults([]);
      return;
    }
    const timer = setTimeout(() => {
      api<Page<StockItem>>(`/api/inventory${query({ storeId, q: search, size: 8 })}`)
        .then((p) => setResults(p.items.filter((i) => i.productStatus === 'ACTIVE' && i.priceInr !== null && i.quantity > 0)))
        .catch(() => setResults([]));
    }, 250);
    return () => clearTimeout(timer);
  }, [search, step, storeId]);

  function add(item: StockItem) {
    setLines((current) => current.some((l) => l.sku === item.sku)
      ? current.map((l) => (l.sku === item.sku ? { ...l, qty: Math.min(l.qty + 1, l.available) } : l))   // D14: same product, one line
      : [...current, { sku: item.sku, name: item.productName, price: item.priceInr ?? 0, available: item.quantity, qty: 1 }]);
    setSearch('');
  }

  async function submit() {
    setSaving(true);
    setError(null);
    try {
      const order = await api<OrderDetail>('/api/orders', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ customerName: name.trim(), customerPhone: phone, lines: lines.map((l) => ({ sku: l.sku, qty: l.qty })) }),
      });
      onCreated(order);
    } catch (e) {
      setError((e as Error).message);
      setSaving(false);
    }
  }

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>New order · step {step} of 3</h2>
        <button type="button" className="secondary" onClick={onCancel}>Cancel</button>
      </div>
      <ol className="steps">
        <li className={step >= 1 ? 'done' : ''}>Customer</li>
        <li className={step >= 2 ? 'done' : ''}>Items</li>
        <li className={step >= 3 ? 'done' : ''}>Review</li>
      </ol>

      {step === 1 && (
        <div className="form-grid">
          <label>Customer name<input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></label>
          <label>Mobile number<input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+91 9876543210" /></label>
          {phone.length > 4 && !phoneValid && <p className="error">Enter an Indian mobile number like +91 9876543210.</p>}
          <div className="form-actions"><button type="button" disabled={!name.trim() || !phoneValid} onClick={() => setStep(2)}>Next: items</button></div>
        </div>
      )}

      {step === 2 && (
        <div>
          <input aria-label="Find product" placeholder="Search products in stock (2+ letters)" value={search} onChange={(e) => setSearch(e.target.value)} autoFocus />
          {results.length > 0 && (
            <ul className="picker">
              {results.map((r) => (
                <li key={r.sku}>
                  <button type="button" className="link" onClick={() => add(r)}>
                    {r.productName} <span className="muted mono">{r.sku}</span> · {money(r.priceInr)} · {r.quantity} in stock
                  </button>
                </li>
              ))}
            </ul>
          )}
          <table>
            <thead><tr><th>Item</th><th className="num">Qty</th><th className="num">Price</th><th /></tr></thead>
            <tbody>
              {lines.length === 0 && <tr><td colSpan={4} className="muted">No items yet. Search above to add one.</td></tr>}
              {lines.map((l) => (
                <tr key={l.sku}>
                  <td>{l.name}<div className="muted small mono">{l.sku}</div></td>
                  <td className="num">
                    <input aria-label={`Quantity of ${l.name}`} type="number" min={1} max={l.available} value={l.qty} className="qty"
                           onChange={(e) => setLines(lines.map((x) => (x.sku === l.sku ? { ...x, qty: Math.max(1, Math.min(Number(e.target.value), x.available)) } : x)))} />
                  </td>
                  <td className="num">{money(l.price * l.qty)}</td>
                  <td><button type="button" className="secondary" onClick={() => setLines(lines.filter((x) => x.sku !== l.sku))}>Remove</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="form-actions">
            <button type="button" className="secondary" onClick={() => setStep(1)}>Back</button>
            <button type="button" disabled={lines.length === 0} onClick={() => setStep(3)}>Next: review</button>
          </div>
        </div>
      )}

      {step === 3 && (
        <div>
          <p><strong>{name}</strong> · {phone} · Store {storeId}</p>
          <ul>{lines.map((l) => <li key={l.sku}>{l.qty} × {l.name} — {money(l.price * l.qty)}</li>)}</ul>
          <p className="big">Total {money(total)}</p>
          {error && <p className="error" role="alert">{error}</p>}
          <div className="form-actions">
            <button type="button" className="secondary" onClick={() => setStep(2)} disabled={saving}>Back</button>
            <button type="button" onClick={submit} disabled={saving}>{saving ? 'Placing order…' : 'Place order'}</button>
          </div>
        </div>
      )}
    </div>
  );
}
