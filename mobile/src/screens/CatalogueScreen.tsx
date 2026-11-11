import { useCallback, useEffect, useState } from 'react';
import { FlatList, Text, TextInput, View } from 'react-native';
import { api, query } from '../api/client';
import type { Page, Product } from '../api/types';
import { Chip, money, State, styles } from '../ui';

const SIZE = 30;

/** Catalogue (brief feature 3, mobile): search and scroll; more products load as you reach the end. */
export function CatalogueScreen() {
  const [text, setText] = useState('');
  const [items, setItems] = useState<Product[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (p: number, q: string) => {
    setLoading(true);
    setError(null);
    try {
      const data = await api<Page<Product>>(`/api/products${query({ q, page: p, size: SIZE })}`);
      setItems((prev) => (p === 0 ? data.items : [...prev, ...data.items]));
      setTotal(data.totalItems);
      setPage(p);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const t = setTimeout(() => void load(0, text), 300);   // wait until typing pauses
    return () => clearTimeout(t);
  }, [text, load]);

  return (
    <View style={{ flex: 1 }}>
      <View style={{ padding: 12 }}>
        <TextInput accessibilityLabel="Search products" placeholder="Search name or SKU" style={styles.input} value={text} onChangeText={setText} autoCapitalize="none" />
        <Text style={[styles.sub, { marginTop: 6 }]}>{total.toLocaleString('en-IN')} products</Text>
      </View>
      <FlatList
        data={items}
        keyExtractor={(p) => p.sku}
        onEndReached={() => { if (!loading && items.length < total) void load(page + 1, text); }}
        onEndReachedThreshold={0.5}
        ListEmptyComponent={<State loading={loading} error={error} empty={loading ? null : 'No products match.'} />}
        ListFooterComponent={items.length > 0 ? <State loading={loading} error={error} empty={null} /> : undefined}
        renderItem={({ item }) => (
          <View style={styles.card}>
            <Text style={styles.title}>{item.name}</Text>
            <View style={[styles.row, { marginTop: 4 }]}>
              <Text style={styles.sub}>{item.sku} · {item.category} · {money(item.priceInr)}</Text>
              {item.status === 'ARCHIVED' && <Chip kind="warn">▣ Archived</Chip>}
              {item.status === 'ACTIVE' && item.priceInr === null && <Chip kind="danger">! No price</Chip>}
            </View>
          </View>
        )}
      />
    </View>
  );
}
