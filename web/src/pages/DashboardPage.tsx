import { useEffect, useMemo, useState } from 'react';
import type { UserSummary } from '../api/auth';
import { api, downloadFile, query } from '../api/client';
import type { DashboardSummary, TrendPoint } from '../api/types';
import { ListState } from '../components/ui';
import { money } from '../lib/format';

const STORES = ['S01', 'S02', 'S03', 'S04', 'S05'];

/** Today in IST as yyyy-mm-dd (dates are IST days, D9). */
function istToday(): string {
  return new Date(Date.now() + 330 * 60_000).toISOString().slice(0, 10);
}

function daysBefore(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Pending', PACKED: 'Packed', SHIPPED: 'Shipped', DELIVERED: 'Delivered', CANCELLED: 'Cancelled',
};

/**
 * Dashboard (M22): revenue (delivered orders), order count by status, top products, low stock and the trend
 * by store and date range, plus the CSV export of the same orders. The store is chosen in the sidebar.
 */
export function DashboardPage({ user, store }: { user: UserSummary; store: string }) {
  const headOffice = user.role !== 'STORE_MANAGER';
  const [to, setTo] = useState(istToday);
  const [from, setFrom] = useState(() => daysBefore(istToday(), 29));
  const [data, setData] = useState<DashboardSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);

  const filters = { storeId: store, from, to };

  useEffect(() => {
    if (from && to && to < from) {
      setError("'To' is before 'From'.");
      return;
    }
    setLoading(true);
    setError(null);
    api<DashboardSummary>(`/api/dashboard${query({ storeId: store, from, to })}`)
      .then(setData)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [store, from, to]);

  async function exportCsv() {
    setExporting(true);
    try {
      await downloadFile(`/api/orders/export.csv${query(filters)}`, 'orders.csv');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setExporting(false);
    }
  }

  function preset(days: number | null) {
    const today = istToday();
    setTo(days === null ? '' : today);
    setFrom(days === null ? '' : daysBefore(today, days - 1));
  }

  const scope = headOffice ? (store ? `Store ${store}` : 'All 5 stores') : `Store ${user.storeId}`;

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Dashboard</h1>
          <p className="muted">{scope} · {from || to ? `${from || 'start'} to ${to || 'today'} (IST)` : 'All time'}</p>
        </div>
        <button type="button" className="secondary" onClick={exportCsv} disabled={exporting}>
          {exporting ? 'Preparing…' : 'Export orders (CSV)'}
        </button>
      </header>

      <div className="toolbar">
        <label className="inline">From <input type="date" aria-label="From" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="inline">To <input type="date" aria-label="To" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        <button type="button" className="secondary" onClick={() => preset(7)}>7 days</button>
        <button type="button" className="secondary" onClick={() => preset(30)}>30 days</button>
        <button type="button" className="secondary" onClick={() => preset(90)}>90 days</button>
        <button type="button" className="secondary" onClick={() => preset(null)}>All time</button>
      </div>

      <ListState loading={loading && !data} error={error} empty={false} emptyText="" />

      {data && (
        <>
          <div className="tiles">
            <Tile label="Revenue" value={money(data.revenue)} note={`${data.deliveredOrders.toLocaleString('en-IN')} delivered orders`} />
            <Tile label="Orders placed" value={data.orderCount.toLocaleString('en-IN')}
                  note={Object.entries(data.ordersByStatus).filter(([, n]) => n > 0)
                    .map(([s, n]) => `${STATUS_LABELS[s] ?? s} ${n.toLocaleString('en-IN')}`).join(' · ')} />
            <Tile label="Low stock now" value={data.lowStockCount.toLocaleString('en-IN')}
                  note={`${data.outOfStockCount.toLocaleString('en-IN')} out of stock · not date-ranged`} />
          </div>

          <div className="dash-grid">
            <div className="panel">
              <div className="panel-head">
                <h2>Revenue by {data.trendBy === 'month' ? 'month' : 'day'}</h2>
                <span className="muted small">Delivered orders, IST</span>
              </div>
              <TrendChart points={data.trend} unit={data.trendBy} />
            </div>

            {!store && headOffice && (
              <div className="panel">
                <div className="panel-head"><h2>Revenue by store</h2></div>
                <StoreBars points={data.trend} />
              </div>
            )}
          </div>

          <div className="panel">
            <div className="panel-head">
              <h2>Top products</h2>
              <span className="muted small">By revenue from delivered orders</span>
            </div>
            {data.topProducts.length === 0 ? <p className="muted">No delivered orders in this range.</p> : (
              <table>
                <thead><tr><th>#</th><th>Product</th><th>SKU</th><th className="num">Units</th><th className="num">Revenue</th></tr></thead>
                <tbody>
                  {data.topProducts.map((p, i) => (
                    <tr key={p.sku}>
                      <td>{i + 1}</td><td>{p.name}</td><td className="mono">{p.sku}</td>
                      <td className="num">{p.units.toLocaleString('en-IN')}</td><td className="num">{money(p.revenue)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {data.unknownProducts.revenue > 0 && (
              <p className="muted small">
                Unknown product codes (D12): {data.unknownProducts.units.toLocaleString('en-IN')} units, {money(data.unknownProducts.revenue)}
              </p>
            )}
          </div>
        </>
      )}
    </section>
  );
}

function Tile({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="tile">
      <div className="muted small">{label}</div>
      <div className="tile-value">{value}</div>
      <div className="muted small">{note}</div>
    </div>
  );
}

/** One series (the selected scope), so colour never has to tell stores apart. Hover or focus a bar for its value. */
function TrendChart({ points, unit }: { points: TrendPoint[]; unit: 'day' | 'month' }) {
  const series = useMemo(() => {
    const byPeriod = new Map<string, { revenue: number; orders: number }>();
    for (const p of points) {
      const cur = byPeriod.get(p.period) ?? { revenue: 0, orders: 0 };
      byPeriod.set(p.period, { revenue: cur.revenue + p.revenue, orders: cur.orders + p.orders });
    }
    return [...byPeriod.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([period, v]) => ({ period, ...v }));
  }, [points]);
  const [active, setActive] = useState<number | null>(null);

  if (series.length === 0) return <p className="muted">No orders in this range.</p>;

  const W = 640, H = 200, PAD = 28;
  const max = Math.max(...series.map((s) => s.revenue), 1);
  const slot = (W - PAD) / series.length;
  const bar = Math.max(2, Math.min(24, slot - 2));
  const label = (period: string) => (unit === 'month' ? period.slice(0, 7) : period);
  const shown = active === null ? null : series[active];

  return (
    <div>
      <p className="chart-readout small" aria-live="polite">
        {shown ? `${label(shown.period)}: ${money(shown.revenue)} · ${shown.orders} orders placed` : 'Hover a bar to see its value.'}
      </p>
      <svg viewBox={`0 0 ${W} ${H + 18}`} className="chart" role="img" aria-label={`Revenue by ${unit}, ${series.length} bars`}>
        <line x1={PAD} y1={H} x2={W} y2={H} className="chart-axis" />
        <text x={PAD} y={10} className="chart-tick">Highest: {money(max)}</text>
        {series.map((s, i) => {
          const h = (s.revenue / max) * (H - 16);
          const x = PAD + i * slot + (slot - bar) / 2;
          return (
            <g key={s.period} onMouseEnter={() => setActive(i)} onMouseLeave={() => setActive(null)}
               onFocus={() => setActive(i)} onBlur={() => setActive(null)} tabIndex={0}>
              <rect x={PAD + i * slot} y={0} width={slot} height={H} fill="transparent" />
              <rect x={x} y={H - h} width={bar} height={Math.max(h, 0)} rx={Math.min(4, bar / 2)}
                    className={active === i ? 'chart-bar active' : 'chart-bar'} />
            </g>
          );
        })}
        <text x={PAD} y={H + 14} className="chart-tick">{label(series[0].period)}</text>
        <text x={W} y={H + 14} className="chart-tick" textAnchor="end">{label(series[series.length - 1].period)}</text>
      </svg>
      <details>
        <summary className="small">Show as a table</summary>
        <table>
          <thead><tr><th>{unit === 'month' ? 'Month' : 'Day'}</th><th className="num">Revenue</th><th className="num">Orders placed</th></tr></thead>
          <tbody>{series.map((s) => <tr key={s.period}><td>{label(s.period)}</td><td className="num">{money(s.revenue)}</td><td className="num">{s.orders}</td></tr>)}</tbody>
        </table>
      </details>
    </div>
  );
}

/** Store comparison: one hue, bar length = revenue, values written out. */
function StoreBars({ points }: { points: TrendPoint[] }) {
  const totals = STORES.map((s) => ({
    store: s,
    revenue: points.filter((p) => p.storeId === s).reduce((sum, p) => sum + p.revenue, 0),
  }));
  const max = Math.max(...totals.map((t) => t.revenue), 1);
  return (
    <ul className="store-bars">
      {totals.map((t) => (
        <li key={t.store}>
          <span>{t.store}</span>
          <span className="store-bar-track"><span className="store-bar" style={{ width: `${(t.revenue / max) * 100}%` }} /></span>
          <span className="num small">{money(t.revenue)}</span>
        </li>
      ))}
    </ul>
  );
}
