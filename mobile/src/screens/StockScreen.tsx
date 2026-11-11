import { useCallback, useEffect, useState } from 'react';
import { FlatList, Modal, Pressable, Switch, Text, TextInput, View } from 'react-native';
import { api, query } from '../api/client';
import type { Page, StockItem, UserSummary } from '../api/types';
import { Button, Chip, colors, State, styles } from '../ui';

const SIZE = 30;

/**
 * Stock in the manager's own store (brief feature 4, mobile): search, "low only", and adjustments with a reason.
 * The server checks the store and keeps stock at 0 or above even when two people change it at once.
 */
export function StockScreen({ user }: { user: UserSummary }) {
  const [text, setText] = useState('');
  const [lowOnly, setLowOnly] = useState(false);
  const [items, setItems] = useState<StockItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<StockItem | null>(null);

  const load = useCallback(async (p: number) => {
    setLoading(true);
    setError(null);
    try {
      const data = await api<Page<StockItem>>(`/api/inventory${query({ storeId: user.storeId, q: text, lowOnly, page: p, size: SIZE })}`);
      setItems((prev) => (p === 0 ? data.items : [...prev, ...data.items]));
      setTotal(data.totalItems);
      setPage(p);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [user.storeId, text, lowOnly]);

  useEffect(() => {
    const t = setTimeout(() => void load(0), 300);
    return () => clearTimeout(t);
  }, [load]);

  function saved(item: StockItem) {
    setItems((prev) => prev.map((i) => (i.sku === item.sku ? item : i)));
    setEditing(null);
  }

  return (
    <View style={{ flex: 1 }}>
      <View style={{ padding: 12, gap: 8 }}>
        <TextInput accessibilityLabel="Search stock" placeholder="Search name or SKU" style={styles.input} value={text} onChangeText={setText} autoCapitalize="none" />
        <View style={[styles.row, { justifyContent: 'space-between' }]}>
          <Text style={styles.sub}>{total.toLocaleString('en-IN')} items in {user.storeId}</Text>
          <View style={styles.row}>
            <Text style={{ color: colors.text2 }}>Low stock only</Text>
            <Switch accessibilityLabel="Low stock only" value={lowOnly} onValueChange={setLowOnly} />
          </View>
        </View>
      </View>
      <FlatList
        data={items}
        keyExtractor={(i) => i.sku}
        onEndReached={() => { if (!loading && items.length < total) void load(page + 1); }}
        onEndReachedThreshold={0.5}
        ListEmptyComponent={<State loading={loading} error={error} empty={loading ? null : 'Nothing matches.'} />}
        renderItem={({ item }) => (
          <Pressable accessibilityRole="button" accessibilityLabel={`${item.productName}, ${item.quantity} in stock`}
                     onPress={() => setEditing(item)} style={styles.card}>
            <View style={[styles.row, { justifyContent: 'space-between' }]}>
              <Text style={[styles.title, { flex: 1 }]}>{item.productName}</Text>
              <Text style={{ fontSize: 18, fontWeight: '600', color: item.low ? colors.danger : colors.text }}>{item.quantity}</Text>
            </View>
            <View style={[styles.row, { marginTop: 4 }]}>
              <Text style={styles.sub}>{item.sku} · reorder at {item.reorderLevel}</Text>
              {item.quantity === 0 ? <Chip kind="danger">✕ Out</Chip> : item.low && <Chip kind="danger">▼ Low</Chip>}
              {item.recountNeeded && <Chip kind="warn">↻ Recount</Chip>}
            </View>
          </Pressable>
        )}
      />
      {editing && <AdjustSheet item={editing} onClose={() => setEditing(null)} onSaved={saved} />}
    </View>
  );
}

const TYPES = [
  { id: 'DAMAGED', label: 'Damaged', hint: 'How many to write off' },
  { id: 'RECOUNT', label: 'Recount', hint: 'How many are on the shelf' },
  { id: 'OTHER', label: 'Other', hint: 'Change, e.g. -2 or 5 (note required)' },
] as const;

/** Adjust one item: the reason is required (brief: "adjustments with reason"). */
function AdjustSheet({ item, onClose, onSaved }: { item: StockItem; onClose: () => void; onSaved: (i: StockItem) => void }) {
  const [type, setType] = useState<(typeof TYPES)[number]['id']>('DAMAGED');
  const [qty, setQty] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const hint = TYPES.find((t) => t.id === type)!.hint;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const result = await api<{ item: StockItem }>(`/api/inventory/${item.storeId}/${encodeURIComponent(item.sku)}/adjustments`, {
        method: 'POST',
        body: JSON.stringify({ type, quantity: Number(qty), note: note.trim() || null }),
      });
      onSaved(result.item);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal transparent animationType="slide" onRequestClose={onClose}>
      <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(17,24,39,0.4)' }}>
        <View accessibilityViewIsModal style={{ backgroundColor: colors.surface, padding: 16, gap: 12, borderTopLeftRadius: 16, borderTopRightRadius: 16 }}>
          <Text style={{ fontSize: 18, fontWeight: '600', color: colors.text }}>{item.productName}</Text>
          <Text style={styles.sub}>{item.quantity} in stock now</Text>
          <View style={styles.row}>
            {TYPES.map((t) => (
              <Pressable key={t.id} accessibilityRole="radio" accessibilityState={{ selected: type === t.id }} onPress={() => setType(t.id)}
                         style={{ flex: 1, minHeight: 44, borderRadius: 8, borderWidth: 1, alignItems: 'center', justifyContent: 'center',
                                  borderColor: type === t.id ? colors.primary : colors.border, backgroundColor: type === t.id ? '#e9eefc' : colors.surface }}>
                <Text style={{ color: type === t.id ? colors.primary : colors.text2, fontWeight: '500' }}>{t.label}</Text>
              </Pressable>
            ))}
          </View>
          <TextInput accessibilityLabel="Quantity" placeholder={hint} style={styles.input} value={qty} onChangeText={setQty}
                     keyboardType={type === 'OTHER' ? 'numbers-and-punctuation' : 'number-pad'} />
          <TextInput accessibilityLabel="Note" placeholder={type === 'OTHER' ? 'Note (required)' : 'Note (optional)'} style={styles.input} value={note} onChangeText={setNote} />
          {error && <Text accessibilityRole="alert" style={{ color: colors.danger }}>{error}</Text>}
          <Button title={busy ? 'Saving…' : 'Save'} onPress={save} disabled={busy || qty.trim() === '' || Number.isNaN(Number(qty))} />
          <Button title="Cancel" kind="secondary" onPress={onClose} />
        </View>
      </View>
    </Modal>
  );
}
