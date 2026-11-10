// Display helpers, following docs/design-system.md (Indian money grouping, IST dates).

const rupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 0 });

/** ₹1,28,400 (Indian grouping). */
export function money(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : rupees.format(value);
}

const istDateTime = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** Times are stored as exact moments and always shown in IST (D9). */
export function dateTime(iso: string | null | undefined): string {
  return iso ? istDateTime.format(new Date(iso)) : '—';
}

const REASONS: Record<string, string> = {
  ORDER_CREATED: 'Order created',
  ORDER_CANCELLED: 'Order cancelled',
  PO_RECEIPT: 'Goods received',
  ADJUSTMENT_DAMAGED: 'Damaged',
  ADJUSTMENT_RECOUNT: 'Recount',
  ADJUSTMENT_OTHER: 'Other',
  IMPORT_CORRECTION: 'Import correction',
};

export function reasonLabel(code: string): string {
  return REASONS[code] ?? code;
}
