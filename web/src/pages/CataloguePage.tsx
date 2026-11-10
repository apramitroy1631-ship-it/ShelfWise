import { useEffect, useState, type FormEvent } from 'react';
import type { UserSummary } from '../api/auth';
import { api, query } from '../api/client';
import type { Page, Product } from '../api/types';
import { Chip, ListState, Pager } from '../components/ui';
import { money } from '../lib/format';

const SIZE = 20;

/** Catalogue (M09): everyone browses; admins create, set prices, archive and unarchive. */
export function CataloguePage({ user }: { user: UserSummary }) {
  const isAdmin = user.role === 'ADMIN';
  const [text, setText] = useState('');
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [sort, setSort] = useState('name');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Product> | null>(null);
  const [categories, setCategories] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<Product | 'new' | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    api<string[]>('/api/products/categories').then(setCategories).catch(() => setCategories([]));
  }, []);

  useEffect(() => {
    setLoading(true);
    setError(null);
    api<Page<Product>>(`/api/products${query({ q: text, category, status, sort, page, size: SIZE })}`)
      .then(setData)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [text, category, status, sort, page, reload]);

  async function changeStatus(p: Product, action: 'archive' | 'unarchive') {
    try {
      await api(`/api/products/${encodeURIComponent(p.sku)}/${action}`, { method: 'POST', body: JSON.stringify({ version: p.version }) });
      setReload((n) => n + 1);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Catalogue</h1>
          <p className="muted">{data ? `${data.totalItems.toLocaleString('en-IN')} products` : ' '}</p>
        </div>
        {isAdmin && (
          <button type="button" onClick={() => setEditing('new')}>
            New product
          </button>
        )}
      </header>

      <div className="toolbar">
        <input aria-label="Search products" placeholder="Search name or SKU" value={text} onChange={(e) => { setText(e.target.value); setPage(0); }} />
        <select aria-label="Category" value={category} onChange={(e) => { setCategory(e.target.value); setPage(0); }}>
          <option value="">All categories</option>
          {categories.map((c) => <option key={c}>{c}</option>)}
        </select>
        <select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="ACTIVE">Active</option>
          <option value="ARCHIVED">Archived</option>
          <option value="ALL">All</option>
        </select>
        <select aria-label="Sort" value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="name">Sort: name</option>
          <option value="sku">Sort: SKU</option>
          <option value="price">Sort: price</option>
          <option value="category">Sort: category</option>
        </select>
      </div>

      {editing && (
        <ProductForm
          product={editing === 'new' ? null : editing}
          categories={categories}
          onDone={() => { setEditing(null); setReload((n) => n + 1); }}
          onCancel={() => setEditing(null)}
        />
      )}

      <ListState loading={loading && !data} error={error} empty={!!data && data.items.length === 0} emptyText="No products match these filters." />
      {data && data.items.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>SKU</th><th>Name</th><th>Category</th><th className="num">Price</th><th className="num">Reorder at</th><th>Status</th>
              {isAdmin && <th />}
            </tr>
          </thead>
          <tbody>
            {data.items.map((p) => (
              <tr key={p.sku}>
                <td className="mono">{p.sku}</td>
                <td>{p.name}</td>
                <td>{p.category}</td>
                <td className="num">{p.priceInr === null ? <Chip kind="warn" icon="!">Price not set</Chip> : money(p.priceInr)}</td>
                <td className="num">{p.reorderLevel}</td>
                <td>{p.status === 'ARCHIVED' ? <Chip kind="muted" icon="▢">Archived</Chip> : <Chip kind="info" icon="●">Active</Chip>}</td>
                {isAdmin && (
                  <td className="actions">
                    <button type="button" className="secondary" onClick={() => setEditing(p)}>Edit</button>
                    {p.status === 'ACTIVE'
                      ? <button type="button" className="secondary" onClick={() => changeStatus(p, 'archive')}>Archive</button>
                      : <button type="button" className="secondary" onClick={() => changeStatus(p, 'unarchive')}>Unarchive</button>}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && <Pager page={page} size={SIZE} total={data.totalItems} onPage={setPage} />}
    </section>
  );
}

/** Create or edit a product (admins). A stale edit is refused by the server with a clear message. */
function ProductForm({ product, categories, onDone, onCancel }: { product: Product | null; categories: string[]; onDone: () => void; onCancel: () => void }) {
  const [form, setForm] = useState({
    sku: product?.sku ?? '',
    name: product?.name ?? '',
    category: product?.category ?? categories[0] ?? '',
    priceInr: product?.priceInr?.toString() ?? '',
    costPriceInr: product?.costPriceInr?.toString() ?? '',
    gstRate: product?.gstRate?.toString() ?? '18',
    supplierId: product?.supplierId ?? 'SUP01',
    reorderLevel: product?.reorderLevel?.toString() ?? '10',
  });
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    const body = {
      name: form.name, category: form.category,
      priceInr: form.priceInr === '' ? null : Number(form.priceInr),
      costPriceInr: Number(form.costPriceInr), gstRate: Number(form.gstRate),
      supplierId: form.supplierId, reorderLevel: Number(form.reorderLevel),
    };
    try {
      if (product) {
        await api(`/api/products/${encodeURIComponent(product.sku)}`, { method: 'PUT', body: JSON.stringify({ ...body, version: product.version }) });
      } else {
        await api('/api/products', { method: 'POST', body: JSON.stringify({ ...body, sku: form.sku }) });
      }
      onDone();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="panel form-grid" onSubmit={submit}>
      <h2>{product ? `Edit ${product.sku}` : 'New product'}</h2>
      {!product && <label>SKU<input required value={form.sku} onChange={set('sku')} placeholder="SW-ABC-12345" /></label>}
      <label>Name<input required value={form.name} onChange={set('name')} /></label>
      <label>Category<input required list="categories" value={form.category} onChange={set('category')} /></label>
      <datalist id="categories">{categories.map((c) => <option key={c} value={c} />)}</datalist>
      <label>Price (₹)<input type="number" min="0.01" step="0.01" required={!product || product.priceInr !== null} value={form.priceInr} onChange={set('priceInr')} /></label>
      <label>Cost (₹)<input type="number" min="0" step="0.01" required value={form.costPriceInr} onChange={set('costPriceInr')} /></label>
      <label>GST %<input type="number" min="0" step="0.01" required value={form.gstRate} onChange={set('gstRate')} /></label>
      <label>Supplier<input required value={form.supplierId} onChange={set('supplierId')} /></label>
      <label>Reorder level<input type="number" min="0" required value={form.reorderLevel} onChange={set('reorderLevel')} /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="form-actions">
        <button type="button" className="secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" disabled={saving}>{saving ? 'Saving…' : product ? 'Save changes' : 'Create product'}</button>
      </div>
    </form>
  );
}
