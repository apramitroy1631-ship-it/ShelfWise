// Supplier invoice files on the phone (M20): choose a file, send it, open one. The server decides whether a file is
// really a PDF/PNG/JPEG from its contents, so everything it refuses comes back as a readable message.
import * as DocumentPicker from 'expo-document-picker';
import * as Sharing from 'expo-sharing';
import { Platform } from 'react-native';
import { api, API_URL, downloadToCache, getSession } from './api/client';
import type { Invoice } from './api/types';

/** Same limit as the server (20 MB); the server is the real check, this just saves a slow upload. */
export const MAX_INVOICE_BYTES = 20 * 1024 * 1024;

export function sizeLabel(bytes: number) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

export type Picked = { uri: string; name: string; size?: number; mimeType?: string; file?: File };

/** Opens the phone's file chooser for a PDF or image; null if the person cancelled. */
export async function pickInvoice(): Promise<Picked | null> {
  const result = await DocumentPicker.getDocumentAsync({ type: ['application/pdf', 'image/png', 'image/jpeg'], copyToCacheDirectory: true });
  if (result.canceled || !result.assets[0]) return null;
  const a = result.assets[0];
  return { uri: a.uri, name: a.name, size: a.size, mimeType: a.mimeType, file: a.file };
}

/** Attach, or replace the current invoice (the server keeps the old one as an earlier version). */
export async function uploadInvoice(poId: string, picked: Picked): Promise<Invoice> {
  if (picked.size !== undefined && picked.size > MAX_INVOICE_BYTES) {
    throw new Error(`That file is ${sizeLabel(picked.size)}. Invoices can be up to 20 MB.`);
  }
  const body = new FormData();
  if (picked.file) {
    body.append('file', picked.file, picked.name);                                    // browser preview
  } else {
    // React Native's FormData takes a { uri, name, type } object for a local file.
    body.append('file', { uri: picked.uri, name: picked.name, type: picked.mimeType ?? 'application/octet-stream' } as unknown as Blob);
  }
  return api<Invoice>(`/api/purchase-orders/${poId}/invoices`, { method: 'POST', body });
}

/** Downloads the file with the person's token and hands it to the phone's viewer ("Open with…"). */
export async function openInvoice(poId: string, invoice: Invoice): Promise<void> {
  const path = `/api/purchase-orders/${poId}/invoices/${invoice.id}/file`;
  if (Platform.OS === 'web') {
    const response = await fetch(API_URL + path, { headers: { Authorization: `Bearer ${getSession()?.accessToken ?? ''}` } });
    if (!response.ok) throw new Error("Couldn't open the file.");
    window.open(URL.createObjectURL(await response.blob()), '_blank');
    return;
  }
  const uri = await downloadToCache(path, invoice.fileName);
  if (!(await Sharing.isAvailableAsync())) throw new Error("This phone can't open files from the app.");
  await Sharing.shareAsync(uri, { mimeType: invoice.contentType, dialogTitle: invoice.fileName });
}
