import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { logout, onSessionChange, restoreSession } from './src/api/client';
import type { NotificationItem, Session } from './src/api/types';
import { Bell } from './src/Bell';
import { CatalogueScreen } from './src/screens/CatalogueScreen';
import { LoginScreen } from './src/screens/LoginScreen';
import { OrdersScreen } from './src/screens/OrdersScreen';
import { StockScreen } from './src/screens/StockScreen';
import { colors, State, styles } from './src/ui';
import { bannerText, useConfig } from './src/useConfig';

type Tab = 'orders' | 'stock' | 'catalogue';
const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'orders', label: 'Orders', icon: '▤' },
  { id: 'stock', label: 'Stock', icon: '▦' },
  { id: 'catalogue', label: 'Catalogue', icon: '☰' },
];

/**
 * ShelfWise for store managers (mobile). Sign in, then bottom tabs (at most 5, labelled, design system).
 * Head-office roles use the web app.
 */
export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [starting, setStarting] = useState(true);
  const [tab, setTab] = useState<Tab>('stock');
  const banner = bannerText(useConfig());
  const [stockView, setStockView] = useState({ low: false, key: 0 });   // a stock notification opens 'low stock only' (D7)

  useEffect(() => {
    onSessionChange(setSession);
    restoreSession().then(setSession).finally(() => setStarting(false));
  }, []);

  if (starting) return <View style={[styles.screen, { justifyContent: 'center' }]}><State loading error={null} empty={null} /></View>;
  if (!session) return (<><StatusBar style="light" />{banner && <Banner text={banner} top />}<LoginScreen /></>);

  const { user } = session;
  if (user.role !== 'STORE_MANAGER') {
    return (
      <View style={[styles.screen, { justifyContent: 'center', padding: 24, gap: 16 }]}>
        <Text style={{ fontSize: 16, color: colors.text }}>The mobile app is for store managers. Please use the web app, {user.name}.</Text>
        <Pressable accessibilityRole="button" onPress={logout}><Text style={{ color: colors.primary, fontSize: 16 }}>Sign out</Text></Pressable>
      </View>
    );
  }

  /** A stock notification opens Stock with "low stock only" on; a PO decision just gets marked read (no PO screen on mobile). */
  function openNotification(item: NotificationItem) {
    if (item.kind === 'OUT_OF_STOCK' || item.kind === 'RUNNING_LOW') {
      setStockView((v) => ({ low: true, key: v.key + 1 }));
      setTab('stock');
    }
  }

  return (
    <View style={styles.screen}>
      <StatusBar style="light" />
      <View style={[styles.header, styles.row, { justifyContent: 'space-between' }]}>
        <View>
          <Text style={styles.headerTitle}>{TABS.find((t) => t.id === tab)!.label}</Text>
          <Text style={styles.headerSub}>Store {user.storeId} · {user.name}</Text>
        </View>
        <View style={styles.row}>
          <Bell onOpenItem={openNotification} />
          <Pressable accessibilityRole="button" onPress={logout} style={{ minHeight: 44, justifyContent: 'center' }}>
            <Text style={{ color: '#aeb9db', fontSize: 15 }}>Sign out</Text>
          </Pressable>
        </View>
      </View>
      {banner && <Banner text={banner} />}
      <View style={{ flex: 1 }}>
        {tab === 'orders' && <OrdersScreen user={user} />}
        {tab === 'stock' && <StockScreen key={stockView.key} user={user} startLowOnly={stockView.low} />}
        {tab === 'catalogue' && <CatalogueScreen />}
      </View>
      <View accessibilityRole="tablist" style={{ flexDirection: 'row', backgroundColor: colors.surface, borderTopWidth: 1, borderColor: colors.border, paddingBottom: 12 }}>
        {TABS.map((t) => (
          <Pressable key={t.id} accessibilityRole="tab" accessibilityState={{ selected: tab === t.id }} onPress={() => setTab(t.id)}
                     style={{ flex: 1, minHeight: 56, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ fontSize: 18, color: tab === t.id ? colors.primary : colors.muted }}>{t.icon}</Text>
            <Text style={{ fontSize: 12, color: tab === t.id ? colors.primary : colors.muted, fontWeight: tab === t.id ? '600' : '400' }}>{t.label}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

/** Remote-config banner (DL-06): switched on or off by an admin; reaches the phone within a minute. Icon + words, not colour alone. */
function Banner({ text, top }: { text: string; top?: boolean }) {
  return (
    <View accessibilityRole="alert" style={{ flexDirection: 'row', gap: 8, alignItems: 'center', backgroundColor: colors.warnBg,
                                             paddingHorizontal: 16, paddingVertical: 10, paddingTop: top ? 48 : 10 }}>
      <Text style={{ color: colors.warn, fontSize: 16 }}>⚠</Text>
      <Text style={{ color: colors.warn, fontWeight: '600', flex: 1 }}>{text}</Text>
    </View>
  );
}
