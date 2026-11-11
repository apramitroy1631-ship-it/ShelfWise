import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, Modal, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { api, query } from '../api/client';
import type { OrderDetail, OrderStatus, OrderSummary, Page, StockItem, UserSummary } from '../api/types';
import { Button, Chip, colors, money, State, styles } from '../ui';

const SIZE = 30;

const STATUS: Record<OrderStatus, { icon: string; label: string }> = {
  PENDING: { icon: '◷', label: 'Pending' },
  PACKED: { icon: '▣', label: 'Packed' },
  SHIPPED: { icon: '➜', label: 'Shipped' },
  DELIVERED: { icon: '✓', label: 'Delivered' },
  CANCELLED: { icon: '✕', label: 'Cancelled' },
};

/** What each status can move to (D29: cancel only before shipping; cancelling gives the stock back). */
const NEXT: Partial<Record<OrderStatus, { to: OrderStatus; label: string }[]>> = {
  PENDING: [{ to: 'PACKED', label: 'Mark packed' }, { to: 'CANCELLED', label: 'Cancel order' }],
  PACKED: [{ to: 'SHIPPED', label: 'Mark shipped' }, { to: 'CANCELLED', label: 'Cancel order' }],
  SHIPPED: [{ to: 'DELIVERED', label: 'Mark delivered' }],
};

const IST = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });

/** Customer orders in the manager's store (brief feature 5, mobile): list, status changes, new order. */
export function OrdersScreen({ user }: { user: UserSummary }) {
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const [items, setItems] = useState<OrderSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async (p: number) => {
    setLoading(true);
    setError(null);
    try {
      const data = await api<Page<OrderSummary>>(`/api/orders${query({ storeId: user.storeId, status, page: p, size: SIZE })}`);
      setItems((prev) => (p === 0 ? data.items : [...prev, ...data.items]));
      setTotal(data.totalItems);
      setPage(p);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [user.storeId, status]);

  useEffect(() => { void load(0); }, [load]);

  return (
    <View style={{ flex: 1 }}>
      <View style={{ padding: 12, gap: 8 }}>
        <Button title="New order" onPress={() => setCreating(true)} />
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
          {(['', 'PENDING', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELLED'] as const).map((s) => (
            <Pressable key={s || 'all'} accessibilityRole="radio" accessibilityState={{ selected: status === s }} onPress={() => setStatus(s)}
                       style={{ minHeight: 36, paddingHorizontal: 12, borderRadius: 999, borderWidth: 1, justifyContent: 'center',
                                borderColor: status === s ? colors.primary : colors.border, backgroundColor: status === s ? '#e9eefc' : colors.surface }}>
              <Text style={{ color: status === s ? colors.primary : colors.text2 }}>{s ? STATUS[s].label : 'All'}</Text>
            </Pressable>
          ))}
        </ScrollView>
        <Text style={styles.sub}>{total.toLocaleString('en-IN')} orders in {user.storeId}</Text>
      </View>
      <FlatList
        data={items}
        keyExtractor={(o) => o.id}
        onEndReached={() => { if (!loading && items.length < total) void load(page + 1); }}
        onEndReachedThreshold={0.5}
        ListEmptyComponent={<State loading={loading} error={error} empty={loading ? null : 'No orders.'} />}
        renderItem={({ item }) => (
          <Pressable accessibilityRole="button" accessibilityLabel={`${item.id}, ${STATUS[item.status].label}`} onPress={() => setOpenId(item.id)} style={styles.card}>
            <View style={[styles.row, { justifyContent: 'space-between' }]}>
              <Text style={styles.title}>{item.id}</Text>
              <Text style={styles.title}>{money(item.totalAmount)}</Text>
            </View>
            <View style={[styles.row, { marginTop: 4 }]}>
              <Text style={styles.sub}>{item.customerName} · {IST.format(new Date(item.createdAt))}</Text>
            </View>
            <View style={[styles.row, { marginTop: 4 }]}>
              <StatusChip status={item.status} />
              {item.stuck && <Chip kind="warn">⏱ Stuck</Chip>}
            </View>
          </Pressable>
        )}
      />
      {openId && <OrderSheet id={openId} onClose={() => setOpenId(null)} onChanged={() => void load(0)} />}
      {creating && <NewOrderSheet user={user} onClose={() => setCreating(false)}
                                  onCreated={(o) => { setCreating(false); void load(0); setOpenId(o.id); }} />}
    </View>
  );
}

function StatusChip({ status }: { status: OrderStatus }) {
  const s = STATUS[status];
  return (
    <View style={[styles.chip, { backgroundColor: status === 'CANCELLED' ? '#f3f4f6' : status === 'DELIVERED' ? '#dcfce7' : '#e0e7ff' }]}>
      <Text style={{ fontSize: 12, fontWeight: '600', color: colors.text }}>{s.icon} {s.label}</Text>
    </View>
  );
}

function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <Modal transparent animationType="slide" onRequestClose={onClose}>
      <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(17,24,39,0.4)' }}>
        <View accessibilityViewIsModal style={{ maxHeight: '90%', backgroundColor: colors.surface, borderTopLeftRadius: 16, borderTopRightRadius: 16 }}>
          <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }} keyboardShouldPersistTaps="handled">
            <Text style={{ fontSize: 18, fontWeight: '600', color: colors.text }}>{title}</Text>
            {children}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

/** One order with its lines and the allowed next steps. A stale version gets the server's "reload" message. */
function OrderSheet({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<OrderDetail>(`/api/orders/${id}`).then(setOrder).catch((e: Error) => setError(e.message));
  }, [id]);

  async function move(to: OrderStatus) {
    if (!order) return;
    setBusy(true);
    setError(null);
    try {
      setOrder(await api<OrderDetail>(`/api/orders/${id}/status`, { method: 'POST', body: JSON.stringify({ to, version: order.version }) }));
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet title={id} onClose={onClose}>
      {!order ? <State loading={!error} error={error} empty={null} /> : (
        <>
          <View style={styles.row}><StatusChip status={order.status} />{order.stuck && <Chip kind="warn">⏱ Stuck</Chip>}</View>
          <Text style={{ color: colors.text }}>{order.customerName} · {order.customerPhone}</Text>
          {order.lines.map((l, i) => (
            <View key={i} style={[styles.row, { justifyContent: 'space-between' }]}>
              <Text style={{ flex: 1, color: colors.text }}>{l.productName ?? `Unknown product (${l.sku})`} × {l.qty}</Text>
              <Text style={{ color: colors.text }}>{money(l.lineTotal)}</Text>
            </View>
          ))}
          <Text style={[styles.title, { textAlign: 'right' }]}>Total {money(order.totalAmount)}</Text>
          {error && <Text accessibilityRole="alert" style={{ color: colors.danger }}>{error}</Text>}
          {(NEXT[order.status] ?? []).map((n) => (
            <Button key={n.to} title={n.label} kind={n.to === 'CANCELLED' ? 'secondary' : 'primary'} disabled={busy} onPress={() => void move(n.to)} />
          ))}
        </>
      )}
      <Button title="Close" kind="secondary" onPress={onClose} />
    </Sheet>
  );
}

/** A unique id per order attempt: a double tap or a retry with the same key creates ONE order (D8). */
function newKey() {
  return `m-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

type Line = { item: StockItem; qty: number };

/**
 * New order: customer, then products from the store's own stock (only sellable items with stock), then confirm.
 * The server checks everything again (D30) and takes the stock in the same step, so two phones can't sell the last unit twice.
 */
function NewOrderSheet({ user, onClose, onCreated }: { user: UserSummary; onClose: () => void; onCreated: (o: OrderDetail) => void }) {
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('+91 ');
  const [search, setSearch] = useState('');
  const [found, setFound] = useState<StockItem[]>([]);
  const [lines, setLines] = useState<Line[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const key = useRef(newKey());

  useEffect(() => {
    if (search.trim().length < 2) { setFound([]); return; }
    const t = setTimeout(() => {
      api<Page<StockItem>>(`/api/inventory${query({ storeId: user.storeId, q: search, size: 8 })}`)
        .then((p) => setFound(p.items.filter((i) => i.productStatus === 'ACTIVE' && i.priceInr !== null && i.quantity > 0)))
        .catch(() => setFound([]));
    }, 300);
    return () => clearTimeout(t);
  }, [search, user.storeId]);

  const total = useMemo(() => lines.reduce((sum, l) => sum + (l.item.priceInr ?? 0) * l.qty, 0), [lines]);
  const phoneOk = /^\+91 [6-9]\d{9}$/.test(phone);

  function add(item: StockItem) {
    setLines((prev) => (prev.some((l) => l.item.sku === item.sku)
      ? prev.map((l) => (l.item.sku === item.sku ? { ...l, qty: Math.min(l.qty + 1, item.quantity) } : l))
      : [...prev, { item, qty: 1 }]));
    setSearch('');
    setFound([]);
  }

  function setQty(sku: string, qty: number) {
    setLines((prev) => prev.flatMap((l) => (l.item.sku !== sku ? [l] : qty <= 0 ? [] : [{ ...l, qty: Math.min(qty, l.item.quantity) }])));
  }

  async function place() {
    setBusy(true);
    setError(null);
    try {
      const order = await api<OrderDetail>('/api/orders', {
        method: 'POST',
        headers: { 'Idempotency-Key': key.current },
        body: JSON.stringify({ customerName: name.trim(), customerPhone: phone, lines: lines.map((l) => ({ sku: l.item.sku, qty: l.qty })) }),
      });
      onCreated(order);
    } catch (e) {
      setError((e as Error).message);       // e.g. "Only 2 of 'Salt' left in stock"; the same key is kept for a retry
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet title="New order" onClose={onClose}>
      <TextInput accessibilityLabel="Customer name" placeholder="Customer name" style={styles.input} value={name} onChangeText={setName} />
      <TextInput accessibilityLabel="Customer phone" placeholder="+91 9876543210" style={styles.input} value={phone} onChangeText={setPhone} keyboardType="phone-pad" />
      {phone.length > 4 && !phoneOk && <Text style={styles.sub}>Use +91 and a 10-digit mobile number.</Text>}
      <TextInput accessibilityLabel="Add product" placeholder="Add product: name or SKU" style={styles.input} value={search} onChangeText={setSearch} autoCapitalize="none" />
      {found.map((i) => (
        <Pressable key={i.sku} accessibilityRole="button" accessibilityLabel={`Add ${i.productName}`} onPress={() => add(i)}
                   style={{ paddingVertical: 10, borderBottomWidth: 1, borderColor: colors.border }}>
          <Text style={{ color: colors.text }}>{i.productName}</Text>
          <Text style={styles.sub}>{money(i.priceInr)} · {i.quantity} in stock</Text>
        </Pressable>
      ))}
      {lines.map((l) => (
        <View key={l.item.sku} style={[styles.row, { justifyContent: 'space-between' }]}>
          <Text style={{ flex: 1, color: colors.text }}>{l.item.productName}</Text>
          <Pressable accessibilityRole="button" accessibilityLabel={`One less ${l.item.productName}`} onPress={() => setQty(l.item.sku, l.qty - 1)} style={stepper}><Text>−</Text></Pressable>
          <Text accessibilityLabel={`${l.item.productName} quantity`} style={{ minWidth: 24, textAlign: 'center', color: colors.text }}>{l.qty}</Text>
          <Pressable accessibilityRole="button" accessibilityLabel={`One more ${l.item.productName}`} onPress={() => setQty(l.item.sku, l.qty + 1)} style={stepper}><Text>+</Text></Pressable>
        </View>
      ))}
      <Text style={[styles.title, { textAlign: 'right' }]}>Total {money(total)}</Text>
      {error && <Text accessibilityRole="alert" style={{ color: colors.danger }}>{error}</Text>}
      <Button title={busy ? 'Placing…' : 'Place order'} onPress={place} disabled={busy || !name.trim() || !phoneOk || lines.length === 0} />
      <Button title="Cancel" kind="secondary" onPress={onClose} />
    </Sheet>
  );
}

const stepper = { width: 44, height: 44, borderRadius: 8, borderWidth: 1, borderColor: colors.border, alignItems: 'center' as const, justifyContent: 'center' as const };
