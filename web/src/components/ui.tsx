// Shared pieces from the design system: status chips (icon + word + colour), KPI cards, search box, pager and list states.
import {
  IconAlertTriangle, IconChevronLeft, IconChevronRight, IconCircleCheck, IconCircleX, IconClock, IconFilePencil,
  IconPackage, IconPackageImport, IconSearch, IconSend, IconTruck, IconX, type Icon,
} from '@tabler/icons-react';
import type { ReactNode } from 'react';
import type { OrderStatus, PoStatus } from '../api/types';

type ChipKind = 'pending' | 'packed' | 'shipped' | 'delivered' | 'cancelled' | 'danger' | 'warn' | 'info' | 'muted' | 'success';

/** Status is never colour alone: every chip has an icon and a word (design system §3). */
export function Chip({ kind, icon: I, children }: { kind: ChipKind; icon: Icon; children: ReactNode }) {
  return (
    <span className={`chip chip-${kind}`}>
      <I size={12} stroke={2} aria-hidden="true" /> {children}
    </span>
  );
}

const ORDER_STATUS: Record<OrderStatus, { icon: Icon; label: string; kind: ChipKind }> = {
  PENDING: { icon: IconClock, label: 'Pending', kind: 'pending' },
  PACKED: { icon: IconPackage, label: 'Packed', kind: 'packed' },
  SHIPPED: { icon: IconTruck, label: 'Shipped', kind: 'shipped' },
  DELIVERED: { icon: IconCircleCheck, label: 'Delivered', kind: 'delivered' },
  CANCELLED: { icon: IconCircleX, label: 'Cancelled', kind: 'cancelled' },
};

export const ORDER_STATUSES = Object.keys(ORDER_STATUS) as OrderStatus[];

export function orderStatusLabel(status: OrderStatus) {
  return ORDER_STATUS[status].label;
}

export function OrderStatusChip({ status }: { status: OrderStatus }) {
  const s = ORDER_STATUS[status];
  return <Chip kind={s.kind} icon={s.icon}>{s.label}</Chip>;
}

/** PO states reuse the order families: draft grey, waiting amber, approved green, rejected red, part-received teal. */
const PO_STATUS: Record<PoStatus, { icon: Icon; label: string; kind: ChipKind }> = {
  DRAFT: { icon: IconFilePencil, label: 'Draft', kind: 'muted' },
  SUBMITTED: { icon: IconSend, label: 'Awaiting approval', kind: 'pending' },
  APPROVED: { icon: IconCircleCheck, label: 'Approved', kind: 'delivered' },
  REJECTED: { icon: IconX, label: 'Rejected', kind: 'danger' },
  PARTIALLY_RECEIVED: { icon: IconPackageImport, label: 'Partly received', kind: 'shipped' },
  RECEIVED: { icon: IconCircleCheck, label: 'Received', kind: 'delivered' },
  CLOSED: { icon: IconCircleX, label: 'Closed', kind: 'cancelled' },
};

export const PO_STATUSES = Object.keys(PO_STATUS) as PoStatus[];

export function poStatusLabel(status: PoStatus) {
  return PO_STATUS[status]?.label ?? status;
}

export function PoStatusChip({ status }: { status: PoStatus }) {
  const s = PO_STATUS[status] ?? { icon: IconAlertTriangle, label: status, kind: 'muted' as ChipKind };
  return <Chip kind={s.kind} icon={s.icon}>{s.label}</Chip>;
}

/**
 * A KPI card. With onClick it doubles as a quick filter (pressed = filter on).
 * Counts come from the list APIs' totals, so they always match what the filter shows.
 */
export function Kpi({ label, value, icon: I, hint, alert, active, onClick }: {
  label: string; value: number | null; icon?: Icon; hint?: string; alert?: boolean; active?: boolean; onClick?: () => void;
}) {
  const body = (
    <>
      <span className="kpi-label">{I && <I size={14} aria-hidden="true" />}{label}</span>
      <span className="kpi-value">{value === null ? '…' : value.toLocaleString('en-IN')}</span>
      {hint && <span className="kpi-hint">{hint}</span>}
    </>
  );
  const className = `kpi${alert ? ' alert' : ''}${active ? ' active' : ''}`;
  const name = `${label}: ${value === null ? 'loading' : value.toLocaleString('en-IN')}`;
  return onClick
    ? <button type="button" className={className} aria-pressed={active} aria-label={name} onClick={onClick}>{body}</button>
    : <div className={className}>{body}</div>;
}

export function SearchBox({ label, placeholder, value, onChange }: { label: string; placeholder: string; value: string; onChange: (v: string) => void }) {
  return (
    <span className="search">
      <IconSearch size={15} className="icon" aria-hidden="true" />
      <input type="search" aria-label={label} placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} />
    </span>
  );
}

/** "1–20 of 1,284" plus numbered pages (first, last and the ones around the current page). */
export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  const shown = [...new Set([0, page - 1, page, page + 1, pages - 1])].filter((p) => p >= 0 && p < pages).sort((a, b) => a - b);
  return (
    <nav className="pager" aria-label="Pages">
      <span className="count">{first.toLocaleString('en-IN')}–{last.toLocaleString('en-IN')} of {total.toLocaleString('en-IN')}</span>
      <button type="button" className="secondary" aria-label="Previous page" disabled={page === 0} onClick={() => onPage(page - 1)}>
        <IconChevronLeft size={14} aria-hidden="true" />
      </button>
      {shown.map((p, i) => (
        <span key={p} className="inline">
          {i > 0 && p - shown[i - 1] > 1 && <span className="gap" aria-hidden="true">…</span>}
          <button type="button" className="secondary" aria-current={p === page ? 'page' : undefined} onClick={() => onPage(p)}>
            {(p + 1).toLocaleString('en-IN')}
          </button>
        </span>
      ))}
      <button type="button" className="secondary" aria-label="Next page" disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>
        <IconChevronRight size={14} aria-hidden="true" />
      </button>
    </nav>
  );
}

/** Loading (skeleton rows), error and empty states for lists (design system §8). */
export function ListState({ loading, error, empty, emptyText, onClear }: {
  loading: boolean; error: string | null; empty: boolean; emptyText: string; onClear?: () => void;
}) {
  if (error) return <p className="state error" role="alert">{error}</p>;
  if (loading) return <div className="skeleton" aria-label="Loading" role="status">{[0, 1, 2, 3, 4].map((i) => <div key={i} />)}</div>;
  if (empty) {
    return (
      <div className="state">
        <strong>{emptyText}</strong>
        {onClear && <button type="button" className="secondary" onClick={onClear}>Clear filters</button>}
      </div>
    );
  }
  return null;
}
