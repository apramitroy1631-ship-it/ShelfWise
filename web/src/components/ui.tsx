// Small shared pieces: status chips (icon + word + colour), pager, and page states.
import type { ReactNode } from 'react';
import type { OrderStatus } from '../api/types';

const ORDER_STATUS: Record<OrderStatus, { icon: string; label: string }> = {
  PENDING: { icon: '◷', label: 'Pending' },
  PACKED: { icon: '▣', label: 'Packed' },
  SHIPPED: { icon: '➜', label: 'Shipped' },
  DELIVERED: { icon: '✓', label: 'Delivered' },
  CANCELLED: { icon: '✕', label: 'Cancelled' },
};

/** Status is never colour alone: every chip has an icon and a word (design system §3). */
export function OrderStatusChip({ status }: { status: OrderStatus }) {
  const s = ORDER_STATUS[status];
  return (
    <span className={`chip chip-${status.toLowerCase()}`}>
      <span aria-hidden="true">{s.icon}</span> {s.label}
    </span>
  );
}

export function Chip({ kind, icon, children }: { kind: 'danger' | 'warn' | 'info' | 'muted'; icon: string; children: ReactNode }) {
  return (
    <span className={`chip chip-${kind}`}>
      <span aria-hidden="true">{icon}</span> {children}
    </span>
  );
}

export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  return (
    <div className="pager">
      <span>
        {first.toLocaleString('en-IN')}–{last.toLocaleString('en-IN')} of {total.toLocaleString('en-IN')}
      </span>
      <button type="button" className="secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous
      </button>
      <button type="button" className="secondary" disabled={last >= total} onClick={() => onPage(page + 1)}>
        Next
      </button>
    </div>
  );
}

/** Loading, error and empty states for lists (design system §8). */
export function ListState({ loading, error, empty, emptyText }: { loading: boolean; error: string | null; empty: boolean; emptyText: string }) {
  if (error) return <p className="state error" role="alert">{error}</p>;
  if (loading) return <p className="state">Loading…</p>;
  if (empty) return <p className="state">{emptyText}</p>;
  return null;
}
