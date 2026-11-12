import { useCallback, useEffect, useState } from 'react';
import { AppState, FlatList, Modal, Pressable, Text, View } from 'react-native';
import { api, NOTIFICATIONS_URL } from './api/client';
import type { NotificationFeed, NotificationItem } from './api/types';
import { Button, colors, State, styles } from './ui';

const REFRESH_MS = 30_000;
const IST = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });

/**
 * The bell for store managers (D7, DL-12): the store's grouped notifications from the notification service.
 * Refreshes every 30 seconds and when the app comes back to the foreground. If the notification service is
 * down, the rest of the app keeps working and the list says so.
 */
export function Bell({ onOpenItem }: { onOpenItem: (item: NotificationItem) => void }) {
  const [feed, setFeed] = useState<NotificationFeed | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [open, setOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      setFeed(await api<NotificationFeed>('/api/notifications', {}, NOTIFICATIONS_URL));
      setUnavailable(false);
    } catch {
      setUnavailable(true);                                  // keep the last list
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), REFRESH_MS);
    const sub = AppState.addEventListener('change', (state) => { if (state === 'active') void load(); });
    return () => { clearInterval(timer); sub.remove(); };
  }, [load]);

  const unread = feed?.unreadCount ?? 0;

  function markLocally(ids: number[] | 'all') {
    setFeed((f) => {
      if (!f) return f;
      const items = f.items.map((i) => (ids === 'all' || ids.includes(i.id) ? { ...i, unread: false } : i));
      return { items, unreadCount: items.filter((i) => i.unread).length };
    });
  }

  function openItem(item: NotificationItem) {
    if (item.unread) {
      markLocally([item.id]);
      api(`/api/notifications/${item.id}/read`, { method: 'POST' }, NOTIFICATIONS_URL).catch(() => void load());
    }
    setOpen(false);
    onOpenItem(item);
  }

  function markAll() {
    markLocally('all');
    api('/api/notifications/read-all', { method: 'POST' }, NOTIFICATIONS_URL).catch(() => void load());
  }

  return (
    <>
      <Pressable accessibilityRole="button" accessibilityLabel={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
                 onPress={() => { setOpen(true); void load(); }}
                 style={{ minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' }}>
        <Text style={{ fontSize: 20, color: '#fff' }}>🔔</Text>
        {unread > 0 && (
          <View style={{ position: 'absolute', top: 4, right: 2, minWidth: 18, height: 18, borderRadius: 9, backgroundColor: colors.danger,
                         alignItems: 'center', justifyContent: 'center', paddingHorizontal: 4 }}>
            <Text style={{ color: '#fff', fontSize: 11, fontWeight: '700' }}>{unread > 99 ? '99+' : unread}</Text>
          </View>
        )}
      </Pressable>
      {open && (
        <Modal transparent animationType="slide" onRequestClose={() => setOpen(false)}>
          <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(17,24,39,0.4)' }}>
            <View accessibilityViewIsModal style={{ maxHeight: '85%', backgroundColor: colors.surface, borderTopLeftRadius: 16, borderTopRightRadius: 16, paddingBottom: 16 }}>
              <View style={[styles.row, { justifyContent: 'space-between', padding: 16 }]}>
                <Text style={{ fontSize: 18, fontWeight: '600', color: colors.text }}>Notifications</Text>
                <Pressable accessibilityRole="button" onPress={markAll} disabled={unread === 0} style={{ minHeight: 44, justifyContent: 'center' }}>
                  <Text style={{ color: unread === 0 ? colors.muted : colors.primary, fontWeight: '600' }}>Mark all as read</Text>
                </Pressable>
              </View>
              {unavailable && <Text accessibilityRole="alert" style={[styles.sub, { paddingHorizontal: 16 }]}>Notifications are unavailable right now; everything else still works.</Text>}
              {!feed ? <State loading={!unavailable} error={null} empty={null} /> : (
                <FlatList
                  data={feed.items}
                  keyExtractor={(i) => String(i.id)}
                  ListEmptyComponent={<State loading={false} error={null} empty="You're all caught up." />}
                  renderItem={({ item }) => (
                    <Pressable accessibilityRole="button" accessibilityLabel={item.text + (item.unread ? ', unread' : '')} onPress={() => openItem(item)}
                               style={[styles.card, item.unread && { backgroundColor: '#f7f9ff' }]}>
                      <View style={styles.row}>
                        {item.unread && <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: colors.primary }} />}
                        <Text style={[styles.title, { flex: 1, fontWeight: item.unread ? '600' : '400' }]}>{item.text}</Text>
                      </View>
                      <Text style={styles.sub}>{IST.format(new Date(item.at))}</Text>
                    </Pressable>
                  )}
                />
              )}
              <View style={{ paddingHorizontal: 16 }}><Button title="Close" kind="secondary" onPress={() => setOpen(false)} /></View>
            </View>
          </View>
        </Modal>
      )}
    </>
  );
}
