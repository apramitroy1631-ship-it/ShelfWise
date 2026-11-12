import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as DocumentPicker from 'expo-document-picker';
import * as Sharing from 'expo-sharing';
import App from '../App';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
  deleteItemAsync: jest.fn(async () => {}),
}));
jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));
jest.mock('expo-sharing', () => ({ isAvailableAsync: jest.fn(async () => true), shareAsync: jest.fn(async () => {}) }));
const mockDownload = jest.fn(async (_url: string, _dest: unknown, _opts?: unknown) => ({ uri: 'file:///cache/invoice.pdf' }));
jest.mock('expo-file-system', () => ({
  Paths: { cache: 'file:///cache/' },
  File: class {
    static downloadFileAsync(url: string, dest: unknown, opts?: unknown) { return mockDownload(url, dest, opts); }
    constructor(..._: unknown[]) {}
  },
}));

const SESSION = {
  accessToken: 'access', refreshToken: 'refresh', expiresInSeconds: 900,
  user: { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER', storeId: 'S01' },
};
const PO = { id: 'PO-0312', storeId: 'S01', supplierId: 'SUP03', status: 'APPROVED', totalAmount: 74600, createdAt: '2026-10-28T05:30:00Z', stuck: false, version: 4 };
const DETAIL = {
  summary: PO, createdByName: 'Arjun Rao', closedReason: null,
  lines: [{ sku: 'SW-RIC-10234', productName: 'Basmati rice 5kg', qtyOrdered: 10, qtyReceived: 0, unitCost: 5000, lineTotal: 50000 }],
};
const INVOICE = {
  id: 'inv-1', fileName: 'invoice_valid.pdf', contentType: 'application/pdf', sizeBytes: 408, current: true,
  uploadedBy: 'U03', uploadedByName: 'Arjun Rao', uploadedAt: '2026-11-10T05:30:00Z',
};

function json(status: number, body?: unknown) {
  return Promise.resolve({ ok: status < 400, status, json: async () => body } as Response);
}

type Call = { path: string; init?: RequestInit };

/** A fake core service. `invoices` is what the list returns; `uploadReply` answers the upload. */
function backend(calls: Call[], state: { invoices: unknown[]; uploadReply?: () => Promise<Response> }) {
  return jest.fn((url: string, init?: RequestInit) => {
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
    calls.push({ path, init });
    if (path === '/api/auth/login') return json(200, SESSION);
    if (path === '/api/purchase-orders') return json(200, { items: [PO], page: 0, size: 30, totalItems: 1, totalPages: 1 });
    if (path === '/api/purchase-orders/PO-0312') return json(200, DETAIL);
    if (path === '/api/purchase-orders/PO-0312/invoices' && init?.method === 'POST') {
      return (state.uploadReply ?? (() => { state.invoices = [INVOICE]; return json(201, INVOICE); }))();
    }
    if (path === '/api/purchase-orders/PO-0312/invoices') return json(200, state.invoices);
    if (path === '/api/inventory') return json(200, { items: [], page: 0, size: 8, totalItems: 0, totalPages: 0 });
    return json(404, { detail: 'not mocked' });
  });
}

async function openPo(calls: Call[], state: Parameters<typeof backend>[1]) {
  globalThis.fetch = backend(calls, state) as unknown as typeof fetch;
  await render(<App />);
  await fireEvent.changeText(await screen.findByLabelText('Email'), 'arjun@test.example');
  await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
  await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
  await fireEvent.press(await screen.findByRole('tab', { name: /Purchases/ }));
  await fireEvent.press(await screen.findByRole('button', { name: /PO-0312/ }));
  await screen.findByText('Supplier invoice');
}

const pick = (asset: object | null) =>
  (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValueOnce(asset ? { canceled: false, assets: [asset] } : { canceled: true, assets: null });

describe('supplier invoices on mobile (M20)', () => {
  beforeEach(() => jest.clearAllMocks());

  it('attaches a file as multipart without a JSON content type, then shows it', async () => {
    const calls: Call[] = [];
    await openPo(calls, { invoices: [] });
    expect(await screen.findByText('No invoice attached yet.')).toBeTruthy();

    pick({ uri: 'file:///cache/inv.pdf', name: 'invoice_valid.pdf', size: 408, mimeType: 'application/pdf' });
    await fireEvent.press(screen.getByRole('button', { name: 'Attach invoice' }));

    expect(await screen.findByText('invoice_valid.pdf')).toBeTruthy();
    const upload = calls.find((c) => c.init?.method === 'POST' && c.path.endsWith('/invoices'))!;
    expect(upload.init!.body).toBeInstanceOf(FormData);
    expect(new Headers(upload.init!.headers).has('Content-Type')).toBe(false);
    expect(screen.getByRole('button', { name: 'Replace invoice' })).toBeTruthy();
  });

  it('shows the server message when a file is refused (renamed program, broken PDF)', async () => {
    const calls: Call[] = [];
    await openPo(calls, { invoices: [], uploadReply: () => json(415, { detail: "Only PDF, PNG or JPEG files are accepted (checked by the file's contents, not its name)" }) });
    await screen.findByText('No invoice attached yet.');

    pick({ uri: 'file:///cache/x.pdf', name: 'invoice_renamed.pdf', size: 2004, mimeType: 'application/pdf' });
    await fireEvent.press(screen.getByRole('button', { name: 'Attach invoice' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/checked by the file/);
  });

  it('refuses a file over 20 MB without sending it', async () => {
    const calls: Call[] = [];
    await openPo(calls, { invoices: [] });
    await screen.findByText('No invoice attached yet.');

    pick({ uri: 'file:///cache/big.pdf', name: 'invoice_too_large.pdf', size: 22 * 1024 * 1024, mimeType: 'application/pdf' });
    await fireEvent.press(screen.getByRole('button', { name: 'Attach invoice' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/up to 20 MB/);
    expect(calls.some((c) => c.init?.method === 'POST' && c.path.endsWith('/invoices'))).toBe(false);
  });

  it('does nothing when the person cancels the chooser', async () => {
    const calls: Call[] = [];
    await openPo(calls, { invoices: [] });
    await screen.findByText('No invoice attached yet.');
    pick(null);
    await fireEvent.press(screen.getByRole('button', { name: 'Attach invoice' }));
    await waitFor(() => expect(DocumentPicker.getDocumentAsync).toHaveBeenCalled());
    expect(calls.some((c) => c.init?.method === 'POST' && c.path.endsWith('/invoices'))).toBe(false);
  });

  it('opens the current invoice with the signed-in token', async () => {
    const calls: Call[] = [];
    await openPo(calls, { invoices: [INVOICE] });
    await fireEvent.press(await screen.findByRole('button', { name: 'View invoice' }));

    await waitFor(() => expect(Sharing.shareAsync).toHaveBeenCalledWith('file:///cache/invoice.pdf', expect.objectContaining({ mimeType: 'application/pdf' })));
    expect(mockDownload.mock.calls[0][0]).toMatch(/\/api\/purchase-orders\/PO-0312\/invoices\/inv-1\/file$/);
    expect(mockDownload.mock.calls[0][2]).toMatchObject({ headers: { Authorization: 'Bearer access' } });
  });
});
