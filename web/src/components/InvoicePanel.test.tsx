import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Invoice } from '../api/types';
import { InvoicePanel } from './InvoicePanel';

const CURRENT: Invoice = {
  id: 'inv-2', fileName: 'scan.png', contentType: 'image/png', sizeBytes: 639, current: true,
  uploadedBy: 'U03', uploadedByName: 'Arjun Rao', uploadedAt: '2026-11-10T05:30:00Z',
};
const OLDER: Invoice = { ...CURRENT, id: 'inv-1', fileName: 'invoice_valid.pdf', contentType: 'application/pdf', sizeBytes: 408, current: false };

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** A fake core service: GET lists the invoices, POST either attaches or answers with the server's error. */
function backend(list: Invoice[], uploadReply?: Response) {
  const uploads: FormData[] = [];
  const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      uploads.push(init.body as FormData);
      return Promise.resolve(uploadReply ?? json(201, CURRENT));
    }
    return Promise.resolve(json(200, list));
  });
  vi.stubGlobal('fetch', fetchMock);
  return { uploads, fetchMock };
}

function pdf(name = 'invoice.pdf', bytes = 100) {
  return new File([new Uint8Array(bytes)], name, { type: 'application/pdf' });
}

describe('supplier invoice panel (M20)', () => {
  it('shows the current invoice and the earlier versions', async () => {
    backend([CURRENT, OLDER]);
    render(<InvoicePanel poId="PO-0312" canAttach />);
    expect(await screen.findByText('scan.png')).toBeInTheDocument();
    expect(screen.getByText('Current')).toBeInTheDocument();
    expect(screen.getByText('Earlier versions')).toBeInTheDocument();
    expect(screen.getByText('invoice_valid.pdf')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replace invoice' })).toBeEnabled();
  });

  it('offers no upload to people who may only view', async () => {
    backend([CURRENT]);
    render(<InvoicePanel poId="PO-0312" canAttach={false} />);
    await screen.findByText('scan.png');
    expect(screen.queryByRole('button', { name: /invoice$/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /View/ })).toBeInTheDocument();
  });

  it('attaches a file as multipart and refreshes the list', async () => {
    const { uploads } = backend([]);
    render(<InvoicePanel poId="PO-0312" canAttach />);
    expect(await screen.findByText('No invoice attached yet.')).toBeInTheDocument();
    await userEvent.upload(screen.getByLabelText('Choose invoice file'), pdf('inv.pdf'));
    await vi.waitFor(() => expect(uploads).toHaveLength(1));
    expect((uploads[0].get('file') as File).name).toBe('inv.pdf');
  });

  it('shows the server message when the file is refused (renamed program, broken PDF)', async () => {
    backend([], json(415, { detail: "Only PDF, PNG or JPEG files are accepted (checked by the file's contents, not its name)" }));
    render(<InvoicePanel poId="PO-0312" canAttach />);
    await screen.findByText('No invoice attached yet.');
    await userEvent.upload(screen.getByLabelText('Choose invoice file'), pdf('invoice_renamed.pdf'));
    expect(await screen.findByRole('alert')).toHaveTextContent('checked by the file');
  });

  it('refuses a file over 20 MB without uploading it', async () => {
    const { uploads } = backend([]);
    render(<InvoicePanel poId="PO-0312" canAttach />);
    await screen.findByText('No invoice attached yet.');
    await userEvent.upload(screen.getByLabelText('Choose invoice file'), pdf('big.pdf', 21 * 1024 * 1024));
    expect(await screen.findByRole('alert')).toHaveTextContent('up to 20 MB');
    expect(uploads).toHaveLength(0);
  });
});
