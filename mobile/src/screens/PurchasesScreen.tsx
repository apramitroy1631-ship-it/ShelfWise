import { useCallback, useEffect, useState } from 'react';
import { FlatList, Pressable, ScrollView, Switch, Text, View } from 'react-native';
import { api, query } from '../api/client';
import type { Invoice, Page, PoDetail, PoStatus, PoSummary, UserSummary } from '../api/types';
import { openInvoice, pickInvoice, sizeLabel, uploadInvoice } from '../invoices';
import { Button, Chip, colors, money, Sheet, State, styles } from '../ui';

const SIZE = 30;

const STATUS: Record<PoStatus, { icon: string; label: string }> = {
  DRAFT: { icon: '✎', label: 'Draft' },
  SUBMITTED: { icon: '◷', label: 'Awaiting approval' },
  APPROVED: { icon: '✓', label: 'Approved' },
  PARTIALLY_RECEIVED: { icon: '▣', label: 'Partly received' },
  RECEIVED: { icon: '✓', label: 'Received' },
  REJECTED: { icon: '✕', label: 'Rejected' },
  EXPIRED: { icon: '⏱', label: 'Expired' },
  CLOSED: { icon: '■', label: 'Closed' },
};

const IST = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });

/** Purchase orders of the manager's store (brief feature 6, mobile): list, and each PO's supplier invoice (M20). */
/** {@code startStuck}: open with "stuck only" on, as a stuck-PO notification does (D22). */
export function PurchasesScreen({ user, startStuck = false }: { user: UserSummary; startStuck?: boolean }) {
  const [stuckOnly, setStuckOnly] = useState(startStuck);
  const [items, setItems] = useState<PoSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

  const load = useCallback(async (p: number) => {
    setLoading(true);
    setError(null);
    try {
      const data = await api<Page<PoSummary>>(`/api/purchase-orders${query({ storeId: user.storeId, stuck: stuckOnly, page: p, size: SIZE })}`);
      setItems((prev) => (p === 0 ? data.items : [...prev, ...data.items]));
      setTotal(data.totalItems);
      setPage(p);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [user.storeId, stuckOnly]);

  useEffect(() => { void load(0); }, [load]);

  return (
    <View style={{ flex: 1 }}>
      <View style={[styles.row, { justifyContent: 'space-between', padding: 12 }]}>
        <Text style={styles.sub}>{total.toLocaleString('en-IN')} {stuckOnly ? 'stuck ' : ''}purchase orders in {user.storeId}</Text>
        <View style={styles.row}>
          <Text style={{ color: colors.text2 }}>Stuck only</Text>
          <Switch accessibilityLabel="Stuck purchase orders only" value={stuckOnly} onValueChange={setStuckOnly} />
        </View>
      </View>
      <FlatList
        data={items}
        keyExtractor={(po) => po.id}
        onEndReached={() => { if (!loading && items.length < total) void load(page + 1); }}
        onEndReachedThreshold={0.5}
        ListEmptyComponent={<State loading={loading} error={error} empty={loading ? null : 'No purchase orders.'} />}
        renderItem={({ item }) => (
          <Pressable accessibilityRole="button" accessibilityLabel={`${item.id}, ${STATUS[item.status]?.label ?? item.status}`} onPress={() => setOpenId(item.id)} style={styles.card}>
            <View style={[styles.row, { justifyContent: 'space-between' }]}>
              <Text style={styles.title}>{item.id}</Text>
              <Text style={styles.title}>{money(item.totalAmount)}</Text>
            </View>
            <Text style={[styles.sub, { marginTop: 4 }]}>Supplier {item.supplierId} · {IST.format(new Date(item.createdAt))}</Text>
            <View style={[styles.row, { marginTop: 4 }]}>
              <View style={[styles.chip, { backgroundColor: '#e0e7ff' }]}>
                <Text style={{ fontSize: 12, fontWeight: '600', color: colors.text }}>{STATUS[item.status]?.icon} {STATUS[item.status]?.label ?? item.status}</Text>
              </View>
              {item.stuck && <Chip kind="warn">⏱ Stuck</Chip>}
            </View>
          </Pressable>
        )}
      />
      {openId && <PoSheet id={openId} onClose={() => setOpenId(null)} />}
    </View>
  );
}

/** One purchase order: its lines and the supplier invoice (attach, view, replace). */
function PoSheet({ id, onClose }: { id: string; onClose: () => void }) {
  const [po, setPo] = useState<PoDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<PoDetail>(`/api/purchase-orders/${id}`).then(setPo).catch((e: Error) => setError(e.message));
  }, [id]);

  return (
    <Sheet title={id} onClose={onClose}>
      {!po && <State loading={!error} error={error} empty={null} />}
      {po && (
        <>
          <Text style={styles.sub}>Supplier {po.summary.supplierId} · {STATUS[po.summary.status]?.label ?? po.summary.status} · {money(po.summary.totalAmount)}</Text>
          {po.lines.map((l) => (
            <View key={l.sku} style={[styles.row, { justifyContent: 'space-between' }]}>
              <Text style={{ flex: 1, color: colors.text }}>{l.productName ?? l.sku}</Text>
              <Text style={{ color: colors.text2 }}>{l.qtyReceived}/{l.qtyOrdered}</Text>
            </View>
          ))}
          {po.summary.status !== 'DRAFT' && <InvoiceSection poId={id} />}
        </>
      )}
      <Button title="Close" kind="secondary" onPress={onClose} />
    </Sheet>
  );
}

function InvoiceSection({ poId }: { poId: string }) {
  const [invoices, setInvoices] = useState<Invoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => api<Invoice[]>(`/api/purchase-orders/${poId}/invoices`).then(setInvoices), [poId]);
  useEffect(() => { refresh().catch((e: Error) => setError(e.message)); }, [refresh]);

  async function attach() {
    setError(null);
    setBusy(true);
    try {
      const picked = await pickInvoice();
      if (!picked) return;
      await uploadInvoice(poId, picked);
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function view(invoice: Invoice) {
    setError(null);
    try {
      await openInvoice(poId, invoice);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const current = invoices?.find((i) => i.current);
  const older = invoices?.filter((i) => !i.current) ?? [];

  return (
    <View style={{ gap: 8, borderTopWidth: 1, borderColor: colors.border, paddingTop: 12 }}>
      <Text style={{ fontSize: 16, fontWeight: '600', color: colors.text }}>Supplier invoice</Text>
      {invoices === null && !error && <State loading error={null} empty={null} />}
      {invoices !== null && !current && <Text style={styles.sub}>No invoice attached yet.</Text>}
      {current && (
        <View style={{ gap: 4 }}>
          <Text style={styles.title}>{current.fileName}</Text>
          <Text style={styles.sub}>{sizeLabel(current.sizeBytes)} · added by {current.uploadedByName}</Text>
          <Button title="View invoice" kind="secondary" onPress={() => view(current)} />
        </View>
      )}
      {error && <State loading={false} error={error} empty={null} />}
      <Button title={busy ? 'Uploading…' : current ? 'Replace invoice' : 'Attach invoice'} disabled={busy || invoices === null} onPress={attach} />
      {older.length > 0 && (
        <ScrollView scrollEnabled={false}>
          <Text style={[styles.sub, { marginBottom: 4 }]}>Earlier versions</Text>
          {older.map((i) => (
            <Pressable key={i.id} accessibilityRole="button" accessibilityLabel={`Open earlier version ${i.fileName}`} onPress={() => view(i)} style={{ minHeight: 44, justifyContent: 'center' }}>
              <Text style={{ color: colors.primary }}>{i.fileName} · {sizeLabel(i.sizeBytes)}</Text>
            </Pressable>
          ))}
        </ScrollView>
      )}
    </View>
  );
}
