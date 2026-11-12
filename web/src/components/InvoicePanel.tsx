import { IconEye, IconFileInvoice, IconUpload } from '@tabler/icons-react';
import { useEffect, useRef, useState } from 'react';
import { api, viewFile } from '../api/client';
import type { Invoice } from '../api/types';
import { Chip } from './ui';
import { dateTime } from '../lib/format';

/** Same limit as the server (20 MB); the server is the real check, this just saves a slow upload. */
export const MAX_INVOICE_BYTES = 20 * 1024 * 1024;

function size(bytes: number) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/**
 * Supplier invoice on a purchase order (M20): attach, view and replace a PDF or image (up to 20 MB).
 * Replacing keeps the older files, listed under "Earlier versions". Whether the file is really a PDF/PNG/JPEG is
 * decided by the server from its contents, so a renamed program or a broken PDF comes back as a clear message.
 */
export function InvoicePanel({ poId, canAttach }: { poId: string; canAttach: boolean }) {
  const [invoices, setInvoices] = useState<Invoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api<Invoice[]>(`/api/purchase-orders/${poId}/invoices`).then(setInvoices).catch((e: Error) => setError(e.message));
  }, [poId]);

  async function upload(file: File) {
    setError(null);
    if (file.size > MAX_INVOICE_BYTES) {
      setError(`That file is ${size(file.size)}. Invoices can be up to 20 MB.`);
      return;
    }
    setBusy(true);
    try {
      const body = new FormData();
      body.append('file', file);
      await api<Invoice>(`/api/purchase-orders/${poId}/invoices`, { method: 'POST', body });
      setInvoices(await api<Invoice[]>(`/api/purchase-orders/${poId}/invoices`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      if (input.current) input.current.value = '';
    }
  }

  async function open(invoice: Invoice) {
    setError(null);
    try {
      await viewFile(`/api/purchase-orders/${poId}/invoices/${invoice.id}/file`);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const current = invoices?.find((i) => i.current);
  const older = invoices?.filter((i) => !i.current) ?? [];

  return (
    <div className="panel" aria-label="Supplier invoice">
      <h2>Supplier invoice</h2>
      {invoices === null && !error && <p className="muted">Loading…</p>}
      {invoices !== null && !current && <p className="muted">No invoice attached yet.</p>}
      {current && (
        <div className="invoice-row">
          <IconFileInvoice size={18} aria-hidden="true" />
          <div>
            <div>{current.fileName} <Chip kind="success" icon={IconFileInvoice}>Current</Chip></div>
            <div className="muted small">{size(current.sizeBytes)} · added by {current.uploadedByName} · {dateTime(current.uploadedAt)}</div>
          </div>
          <button type="button" className="secondary" onClick={() => open(current)}><IconEye size={15} aria-hidden="true" />View</button>
        </div>
      )}
      {error && <p className="notice danger" role="alert">{error}</p>}
      {canAttach && (
        <div className="form-actions">
          <input ref={input} type="file" accept="application/pdf,image/png,image/jpeg" hidden aria-label="Choose invoice file"
                 onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f); }} />
          <button type="button" disabled={busy || invoices === null} onClick={() => input.current?.click()}>
            <IconUpload size={15} aria-hidden="true" />{busy ? 'Uploading…' : current ? 'Replace invoice' : 'Attach invoice'}
          </button>
        </div>
      )}
      {older.length > 0 && (
        <>
          <h3 className="muted small">Earlier versions</h3>
          <ul className="history">
            {older.map((i) => (
              <li key={i.id} className="invoice-row">
                <span>{i.fileName}<div className="muted small">{size(i.sizeBytes)} · {i.uploadedByName} · {dateTime(i.uploadedAt)}</div></span>
                <button type="button" className="secondary" onClick={() => open(i)}><IconEye size={15} aria-hidden="true" />View</button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
