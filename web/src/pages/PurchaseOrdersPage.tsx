import {
  IconAlertTriangle, IconArrowLeft, IconCheck, IconClock, IconFilePencil, IconPackageImport, IconPlus, IconSend, IconUsers, IconX,
} from '@tabler/icons-react';
import { useEffect, useMemo, useState } from 'react';
import type { UserSummary } from '../api/auth';
import { api, query } from '../api/client';
import type { PoDetail, PoStatus, PoSuggestion, PoSummary } from '../api/types';
import { Chip, Kpi, ListState, PO_STATUSES, Pager, PoStatusChip, SearchBox, poStatusLabel } from '../components/ui';
import { dateTime, money } from '../lib/format';
import { useCounts, useList } from '../lib/useList';

const SIZE = 20;

type View = { kind: 'list' } | { kind: 'detail'; id: string } | { kind: 'new' };

/**
 * Purchase orders (M19): list with KPI filters, a detail page with the approval tracker (design system §6),
 * finance approve / reject, receiving in parts, closing a stuck PO, and drafting a PO from low-stock items.
 */
export function PurchaseOrdersPage({ user, store }: { user: UserSummary; store: string }) {
  const [view, setView] = useState<View>({ kind: 'list' });
  const [reload, setReload] = useState(0);

  useEffect(() => { setView({ kind: 'list' }); }, [store]);

  if (view.kind === 'detail') {
    return <PoDetailPage id={view.id} user={user} onBack={() => { setView({ kind: 'list' }); setReload((n) => n + 1); }} />;
  }
  if (view.kind === 'new') {
    return <NewDraft user={user} onCancel={() => setView({ kind: 'list' })} onCreated={(id) => setView({ kind: 'detail', id })} />;
  }
  return <PoList user={user} store={store} reload={reload} onOpen={(id) => setView({ kind: 'detail', id })} onNew={() => setView({ kind: 'new' })} />;
}

function PoList({ user, store, reload, onOpen, onNew }: { user: UserSummary; store: string; reload: number; onOpen: (id: string) => void; onNew: () => void }) {
  const finance = user.role === 'FINANCE_APPROVER';
  const [status, setStatus] = useState<PoStatus | ''>(finance ? 'SUBMITTED' : '');
  const [text, setText] = useState('');
  const [page, setPage] = useState(0);

  useEffect(() => { setPage(0); }, [store]);

  const { data, error, loading } = useList<PoSummary>(`/api/purchase-orders${query({ storeId: store, status, q: text, page, size: SIZE })}`, reload);
  const counts = useCounts(Object.fromEntries((['SUBMITTED', 'APPROVED', 'PARTIALLY_RECEIVED', 'DRAFT'] as const)
    .map((s) => [s, `/api/purchase-orders${query({ storeId: store, status: s, q: text, size: 1 })}`])), reload);

  function filter(apply: () => void) {
    apply();
    setPage(0);
  }

  const quick = (s: PoStatus) => () => filter(() => setStatus(status === s ? '' : s));

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Purchase orders</h1>
          <p>{store ? `Store ${store}` : 'All 5 stores'} · newest first</p>
        </div>
        {user.role === 'STORE_MANAGER' && (
          <div className="page-actions"><button type="button" onClick={onNew}><IconPlus size={15} aria-hidden="true" />New draft from low stock</button></div>
        )}
      </header>

      <div className="kpis" role="group" aria-label="Purchase orders by status">
        <Kpi label="Awaiting approval" icon={IconSend} value={counts.SUBMITTED ?? null} active={status === 'SUBMITTED'} onClick={quick('SUBMITTED')} />
        <Kpi label="Approved, waiting for goods" icon={IconClock} value={counts.APPROVED ?? null} active={status === 'APPROVED'} onClick={quick('APPROVED')} />
        <Kpi label="Partly received" icon={IconPackageImport} value={counts.PARTIALLY_RECEIVED ?? null} active={status === 'PARTIALLY_RECEIVED'} onClick={quick('PARTIALLY_RECEIVED')} />
        <Kpi label="Drafts" icon={IconFilePencil} value={counts.DRAFT ?? null} active={status === 'DRAFT'} onClick={quick('DRAFT')} />
      </div>

      <div className="listing">
        <div className="toolbar">
          <SearchBox label="Search purchase orders" placeholder="PO number" value={text} onChange={(v) => filter(() => setText(v))} />
          <select aria-label="PO status" value={status} onChange={(e) => filter(() => setStatus(e.target.value as PoStatus | ''))}>
            <option value="">Status: any</option>
            {PO_STATUSES.map((s) => <option key={s} value={s}>{poStatusLabel(s)}</option>)}
          </select>
        </div>
        <ListState loading={loading && !data} error={error} empty={!!data && data.items.length === 0} emptyText="No purchase orders match these filters."
                   onClear={status || text ? () => filter(() => { setStatus(''); setText(''); }) : undefined} />
        {data && data.items.length > 0 && (
          <table>
            <thead>
              <tr><th>PO</th>{!store && <th>Store</th>}<th>Supplier</th><th>Created</th><th>Status</th><th>Approvals</th><th className="num">Total</th></tr>
            </thead>
            <tbody>
              {data.items.map((po) => (
                <tr key={po.id} className="clickable" tabIndex={0} onClick={() => onOpen(po.id)} onKeyDown={(e) => { if (e.key === 'Enter') onOpen(po.id); }}>
                  <td className="mono">{po.id}</td>
                  {!store && <td>{po.storeId}</td>}
                  <td>{po.supplierId}</td>
                  <td className="text-2">{dateTime(po.createdAt)}</td>
                  <td><Flags po={po} /></td>
                  <td className="text-2">{po.status === 'SUBMITTED' || po.requiresReapproval ? `${po.validApprovals} of ${po.approvalsNeeded}` : '—'}</td>
                  <td className="num">{money(po.totalAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && data.totalItems > 0 && <Pager page={page} size={SIZE} total={data.totalItems} onPage={setPage} />}
      </div>
    </section>
  );
}

function Flags({ po }: { po: PoSummary }) {
  return (
    <div className="flags">
      <PoStatusChip status={po.status} />
      {po.requiresReapproval && <Chip kind="warn" icon={IconAlertTriangle}>Needs re-approval</Chip>}
      {po.stuck && <Chip kind="warn" icon={IconClock}>Stuck</Chip>}
      {po.complianceFlag && <Chip kind="danger" icon={IconAlertTriangle}>Check: {po.complianceFlag.toLowerCase().replaceAll('_', ' ')}</Chip>}
    </div>
  );
}

// --- detail ------------------------------------------------------------------

function PoDetailPage({ id, user, onBack }: { id: string; user: UserSummary; onBack: () => void }) {
  const [po, setPo] = useState<PoDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<'none' | 'reject' | 'receive' | 'close'>('none');

  useEffect(() => {
    api<PoDetail>(`/api/purchase-orders/${id}`).then(setPo).catch((e: Error) => setError(e.message));
  }, [id]);

  async function act(path: string, body: Record<string, unknown>) {
    if (!po) return;
    setBusy(true);
    setError(null);
    try {
      setPo(await api<PoDetail>(`/api/purchase-orders/${id}/${path}`, { method: 'POST', body: JSON.stringify({ ...body, version: po.summary.version }) }));
      setMode('none');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!po) {
    return (
      <section className="page">
        <button type="button" className="secondary back" onClick={onBack}><IconArrowLeft size={15} aria-hidden="true" />Purchase orders</button>
        {error ? <p className="notice danger" role="alert">{error}</p> : <p className="muted">Loading…</p>}
      </section>
    );
  }

  const s = po.summary;
  const receiving = s.status === 'APPROVED' || s.status === 'PARTIALLY_RECEIVED';
  const managesStore = user.role === 'ADMIN' || (user.role === 'STORE_MANAGER' && user.storeId === s.storeId);
  const alreadyDecided = po.approvals.some((a) => a.approverId === user.id);
  const canDecide = user.role === 'FINANCE_APPROVER' && (s.status === 'SUBMITTED' || (receiving && s.requiresReapproval))
    && po.createdBy !== user.id && !alreadyDecided;
  const decideBlockedReason = user.role !== 'FINANCE_APPROVER' || !(s.status === 'SUBMITTED' || (receiving && s.requiresReapproval)) ? null
    : po.createdBy === user.id ? "You created this PO, so another finance approver must decide on it."
      : alreadyDecided ? 'You have already approved this PO; the second approval must come from a different person.' : null;
  const unitsOrdered = po.lines.reduce((n, l) => n + l.qtyOrdered, 0);
  const unitsReceived = po.lines.reduce((n, l) => n + l.qtyReceived, 0);

  return (
    <section className="page">
      <button type="button" className="secondary back" onClick={onBack}><IconArrowLeft size={15} aria-hidden="true" />Purchase orders</button>

      <div className="panel">
        <div className="po-head">
          <div>
            <div className="muted small">Purchase order</div>
            <h1 className="mono" style={{ fontSize: 18 }}>{s.id}</h1>
            <div className="text-2">Supplier {s.supplierId} · Store {s.storeId} · created {dateTime(s.createdAt)} by {po.createdByName ?? po.createdBy}</div>
          </div>
          <div className="po-amount">
            <p className="big">{money(s.totalAmount)}</p>
            <Flags po={s} />
            {s.approvalsNeeded > 1 && (s.status === 'SUBMITTED' || s.requiresReapproval) && <Chip kind="warn" icon={IconUsers}>Needs 2 approvers</Chip>}
          </div>
        </div>

        <Tracker po={po} canDecideHere={canDecide} unitsOrdered={unitsOrdered} unitsReceived={unitsReceived} />

        <div className="context">
          <span>{po.lines.length} {po.lines.length === 1 ? 'line' : 'lines'} · {unitsOrdered.toLocaleString('en-IN')} units</span>
          {receiving || s.status === 'RECEIVED' ? <span>{unitsReceived.toLocaleString('en-IN')} of {unitsOrdered.toLocaleString('en-IN')} units received</span> : null}
          {po.closedReason && <span>Closed: {po.closedReason}</span>}
        </div>

        {error && <p className="notice danger" role="alert">{error}</p>}
        {decideBlockedReason && <p className="notice">{decideBlockedReason}</p>}

        {canDecide && mode !== 'reject' && (
          <div className="decision">
            <button type="button" className="danger" disabled={busy} onClick={() => setMode('reject')}><IconX size={15} aria-hidden="true" />Reject with reason</button>
            <button type="button" disabled={busy} onClick={() => act('decision', { decision: 'APPROVED', reason: null })}><IconCheck size={15} aria-hidden="true" />Approve</button>
          </div>
        )}
        {mode === 'reject' && (
          <ReasonForm label="Why are you rejecting this PO?" submitLabel="Reject PO" busy={busy}
                      onSubmit={(reason) => act('decision', { decision: 'REJECTED', reason })} onCancel={() => setMode('none')} />
        )}

        {managesStore && mode === 'none' && (
          <div className="form-actions">
            {s.status === 'DRAFT' && <button type="button" disabled={busy} onClick={() => act('submit', {})}><IconSend size={15} aria-hidden="true" />Submit for approval</button>}
            {s.stuck && <button type="button" className="secondary" disabled={busy} onClick={() => setMode('close')}>Close stuck PO</button>}
            {receiving && !s.requiresReapproval && <button type="button" disabled={busy} onClick={() => setMode('receive')}><IconPackageImport size={15} aria-hidden="true" />Receive goods</button>}
          </div>
        )}
        {managesStore && receiving && s.requiresReapproval && <p className="notice">Finance must re-approve this PO before more goods can be received.</p>}
        {mode === 'close' && (
          <ReasonForm label="Why are you closing this PO? Goods already received stay in stock." submitLabel="Close PO" busy={busy}
                      onSubmit={(reason) => act('close', { reason })} onCancel={() => setMode('none')} />
        )}
      </div>

      {mode === 'receive'
        ? <ReceiveForm po={po} busy={busy} onSubmit={(lines) => act('receive', { lines })} onCancel={() => setMode('none')} />
        : <LinesTable po={po} />}

      {po.approvals.length > 0 && (
        <div className="panel">
          <h2>Decisions</h2>
          <ul className="history">
            {po.approvals.map((a, i) => (
              <li key={i}>
                {a.decision === 'APPROVED' ? <Chip kind="success" icon={IconCheck}>Approved</Chip> : <Chip kind="danger" icon={IconX}>Rejected</Chip>}
                {' '}by {a.approverName ?? a.approverId}{a.step ? ` (approval ${a.step})` : ''}{a.counts ? '' : ' · no longer counts'}
                <div className="muted small">{dateTime(a.decidedAt)}</div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

type Step = { label: string; who: string; state: 'done' | 'current' | 'failed' | 'todo' };

/** Submitted → Approver 1 → Approver 2 (above the limit) → Receiving (design system §6). */
function Tracker({ po, canDecideHere, unitsOrdered, unitsReceived }: { po: PoDetail; canDecideHere: boolean; unitsOrdered: number; unitsReceived: number }) {
  const s = po.summary;
  const counting = po.approvals.filter((a) => a.decision === 'APPROVED' && a.counts);
  const rejection = [...po.approvals].reverse().find((a) => a.decision === 'REJECTED');
  const deciding = s.status === 'SUBMITTED' || s.requiresReapproval;
  const approved = !deciding && ['APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED'].includes(s.status);

  const steps: Step[] = [{ label: 'Submitted', who: po.createdByName ?? po.createdBy, state: s.status === 'DRAFT' ? 'current' : 'done' }];
  for (let n = 1; n <= Math.max(1, s.approvalsNeeded); n++) {
    const a = counting.find((x) => x.step === n) ?? counting[n - 1];
    let state: Step['state'] = 'todo';
    let who = 'Finance';
    if (a) { state = 'done'; who = `${a.approverName ?? a.approverId}, ${dateTime(a.decidedAt).split(',')[0]}`; }
    else if (s.status === 'REJECTED' && n === counting.length + 1) { state = 'failed'; who = rejection ? `Rejected by ${rejection.approverName ?? rejection.approverId}` : 'Rejected'; }
    else if (approved) { state = 'done'; who = 'Approved'; }
    else if (deciding && n === counting.length + 1) { state = 'current'; who = 'Waiting'; }
    steps.push({ label: s.approvalsNeeded > 1 ? `Approver ${n}` : 'Approval', who, state });
  }
  steps.push({
    label: 'Receiving',
    who: `${unitsReceived} of ${unitsOrdered} units`,
    state: s.status === 'RECEIVED' ? 'done' : approved && s.status !== 'CLOSED' ? 'current' : 'todo',
  });

  // The step waiting on the viewer says "You" (design system §6).
  const current = steps.find((st) => st.state === 'current' && st.label.startsWith('Approv'));
  if (current && canDecideHere) current.who = 'You';

  return (
    <ol className="tracker" aria-label="Progress">
      {steps.map((st, i) => (
        <li key={st.label} className={`${st.state}${i > 0 && steps[i - 1].state === 'done' && st.state === 'done' ? ' line-done' : ''}`}
            aria-current={st.state === 'current' ? 'step' : undefined}>
          <span className="dot" aria-hidden="true">
            {st.state === 'done' ? <IconCheck size={13} stroke={3} /> : st.state === 'failed' ? <IconX size={13} stroke={3} /> : i + 1}
          </span>
          <span className="label">{st.label}</span>
          <span className="who">{st.who}</span>
        </li>
      ))}
    </ol>
  );
}

function LinesTable({ po }: { po: PoDetail }) {
  return (
    <div className="listing">
      <table>
        <thead><tr><th>Item</th><th className="num">Ordered</th><th className="num">Received</th><th className="num">Unit cost</th><th className="num">Total</th></tr></thead>
        <tbody>
          {po.lines.map((l) => (
            <tr key={l.sku}>
              <td>{l.productName ?? 'Unknown product'}<div className="muted small mono">{l.sku}</div></td>
              <td className="num">{l.qtyOrdered}</td>
              <td className={`num${l.qtyReceived < l.qtyOrdered && l.qtyReceived > 0 ? ' text-2' : ''}`}>{l.qtyReceived}</td>
              <td className="num text-2">{money(l.unitCost)}</td>
              <td className="num">{money(l.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot><tr><td colSpan={4}><strong>Total</strong></td><td className="num"><strong>{money(po.summary.totalAmount)}</strong></td></tr></tfoot>
      </table>
    </div>
  );
}

/** Goods arriving: any part of any line, never more than what is still due (the server checks too). */
function ReceiveForm({ po, busy, onSubmit, onCancel }: { po: PoDetail; busy: boolean; onSubmit: (lines: { sku: string; qty: number }[]) => void; onCancel: () => void }) {
  const [qty, setQty] = useState<Record<string, string>>(
    Object.fromEntries(po.lines.map((l) => [l.sku, String(l.qtyOrdered - l.qtyReceived)])));
  const lines = po.lines.map((l) => ({ sku: l.sku, qty: Number(qty[l.sku] || 0) })).filter((l) => l.qty > 0);
  const invalid = po.lines.some((l) => {
    const n = Number(qty[l.sku] || 0);
    return !Number.isInteger(n) || n < 0 || n > l.qtyOrdered - l.qtyReceived;
  });

  return (
    <div className="panel">
      <div className="panel-head">
        <h2>Receive goods</h2>
        <span className="muted small">Enter what actually arrived; the rest stays due.</span>
      </div>
      <div className="listing">
        <table>
          <thead><tr><th>Item</th><th className="num">Ordered</th><th className="num">Already received</th><th className="num">Arrived now</th></tr></thead>
          <tbody>
            {po.lines.map((l) => {
              const due = l.qtyOrdered - l.qtyReceived;
              return (
                <tr key={l.sku}>
                  <td>{l.productName ?? 'Unknown product'}<div className="muted small mono">{l.sku}</div></td>
                  <td className="num">{l.qtyOrdered}</td>
                  <td className="num text-2">{l.qtyReceived}</td>
                  <td className="num">
                    {due === 0 ? <span className="muted">Complete</span> : (
                      <input className="qty" type="number" min={0} max={due} aria-label={`Arrived now: ${l.productName ?? l.sku}`}
                             value={qty[l.sku]} onChange={(e) => setQty({ ...qty, [l.sku]: e.target.value })} />
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {invalid && <p className="error">Each quantity must be a whole number between 0 and what is still due.</p>}
      <div className="form-actions">
        <button type="button" className="secondary" onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="button" disabled={busy || invalid || lines.length === 0} onClick={() => onSubmit(lines)}>
          {busy ? 'Saving…' : `Receive ${lines.reduce((n, l) => n + l.qty, 0)} units`}
        </button>
      </div>
    </div>
  );
}

function ReasonForm({ label, submitLabel, busy, onSubmit, onCancel }: { label: string; submitLabel: string; busy: boolean; onSubmit: (reason: string) => void; onCancel: () => void }) {
  const [reason, setReason] = useState('');
  return (
    <form className="form-grid" onSubmit={(e) => { e.preventDefault(); if (reason.trim()) onSubmit(reason.trim()); }}>
      <label className="wide">{label}<textarea rows={2} maxLength={500} required value={reason} onChange={(e) => setReason(e.target.value)} autoFocus /></label>
      <div className="form-actions">
        <button type="button" className="secondary" onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="submit" className="danger" disabled={busy || !reason.trim()}>{submitLabel}</button>
      </div>
    </form>
  );
}

// --- new draft from low stock ---------------------------------------------------

/** Managers draft a PO from their store's low-stock items. One supplier per PO (D24); unit costs come from the catalogue. */
function NewDraft({ user, onCancel, onCreated }: { user: UserSummary; onCancel: () => void; onCreated: (id: string) => void }) {
  const [suggestions, setSuggestions] = useState<PoSuggestion[] | null>(null);
  const [supplier, setSupplier] = useState('');
  const [picked, setPicked] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api<PoSuggestion[]>(`/api/purchase-orders/suggestions${query({ storeId: user.storeId })}`)
      .then((list) => {
        setSuggestions(list);
        const first = [...new Set(list.map((x) => x.supplierId))].sort()[0] ?? '';
        setSupplier(first);
      })
      .catch((e: Error) => { setError(e.message); setSuggestions([]); });
  }, [user.storeId]);

  const suppliers = useMemo(() => {
    const bySupplier = new Map<string, number>();
    for (const x of suggestions ?? []) bySupplier.set(x.supplierId, (bySupplier.get(x.supplierId) ?? 0) + 1);
    return [...bySupplier.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [suggestions]);

  const items = (suggestions ?? []).filter((x) => x.supplierId === supplier);

  useEffect(() => {
    // Switching supplier starts with every low item ticked at its suggested quantity.
    setPicked(Object.fromEntries((suggestions ?? []).filter((x) => x.supplierId === supplier).map((x) => [x.sku, String(x.suggestedQty)])));
  }, [supplier, suggestions]);

  const lines = items.filter((x) => picked[x.sku] !== undefined && Number(picked[x.sku]) >= 1)
    .map((x) => ({ sku: x.sku, qty: Math.floor(Number(picked[x.sku])), cost: x.unitCost }));
  const total = lines.reduce((sum, l) => sum + l.qty * l.cost, 0);

  async function create() {
    setSaving(true);
    setError(null);
    try {
      const po = await api<PoDetail>('/api/purchase-orders', {
        method: 'POST',
        body: JSON.stringify({ supplierId: supplier, lines: lines.map(({ sku, qty }) => ({ sku, qty })) }),
      });
      onCreated(po.summary.id);
    } catch (e) {
      setError((e as Error).message);
      setSaving(false);
    }
  }

  return (
    <section className="page">
      <button type="button" className="secondary back" onClick={onCancel}><IconArrowLeft size={15} aria-hidden="true" />Purchase orders</button>
      <header className="page-head">
        <div>
          <h1>New draft from low stock</h1>
          <p>Store {user.storeId} · one supplier per purchase order</p>
        </div>
      </header>

      {suggestions === null ? <p className="muted">Loading low-stock items…</p> : suggestions.length === 0 ? (
        <div className="panel state"><strong>Nothing is below its reorder level in your store.</strong>{error && <span className="error">{error}</span>}</div>
      ) : (
        <>
          <div className="toolbar" style={{ padding: 0, border: 0 }}>
            <label className="inline-label">Supplier
              <select aria-label="Supplier" value={supplier} onChange={(e) => setSupplier(e.target.value)}>
                {suppliers.map(([id, n]) => <option key={id} value={id}>{id} ({n} low {n === 1 ? 'item' : 'items'})</option>)}
              </select>
            </label>
          </div>
          <div className="listing">
            <table>
              <thead><tr><th className="check" /><th>Item</th><th className="num">In stock</th><th className="num">Reorder at</th><th className="num">Order qty</th><th className="num">Unit cost</th></tr></thead>
              <tbody>
                {items.map((x) => {
                  const on = picked[x.sku] !== undefined;
                  return (
                    <tr key={x.sku} className="low">
                      <td className="check">
                        <input type="checkbox" aria-label={`Include ${x.productName}`} checked={on}
                               onChange={() => setPicked((p) => { const n = { ...p }; if (on) delete n[x.sku]; else n[x.sku] = String(x.suggestedQty); return n; })} />
                      </td>
                      <td>{x.productName}<div className="muted small mono">{x.sku}</div></td>
                      <td className="num neg"><strong>{x.quantity}</strong></td>
                      <td className="num text-2">{x.reorderLevel}</td>
                      <td className="num">
                        <input className="qty" type="number" min={1} disabled={!on} aria-label={`Order quantity: ${x.productName}`}
                               value={picked[x.sku] ?? ''} onChange={(e) => setPicked({ ...picked, [x.sku]: e.target.value })} />
                      </td>
                      <td className="num text-2">{money(x.unitCost)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {error && <p className="notice danger" role="alert">{error}</p>}
          <div className="form-actions">
            <span className="big" style={{ marginRight: 'auto' }}>Total {money(total)}</span>
            <button type="button" className="secondary" onClick={onCancel} disabled={saving}>Cancel</button>
            <button type="button" disabled={saving || lines.length === 0} onClick={create}>{saving ? 'Saving…' : `Create draft (${lines.length} ${lines.length === 1 ? 'line' : 'lines'})`}</button>
          </div>
        </>
      )}
    </section>
  );
}
