import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { logout, onSessionChange, restoreSession } from './src/api/client';
import type { Session } from './src/api/types';
import { CatalogueScreen } from './src/screens/CatalogueScreen';
import { LoginScreen } from './src/screens/LoginScreen';
import { StockScreen } from './src/screens/StockScreen';
import { colors, State, styles } from './src/ui';

type Tab = 'stock' | 'catalogue';
const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'stock', label: 'Stock', icon: '▦' },
  { id: 'catalogue', label: 'Catalogue', icon: '☰' },
];

/**
 * ShelfWise for store managers (mobile). Sign in, then bottom tabs (at most 5, labelled, design system).
 * Orders come next. Head-office roles use the web app.
 */
export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [starting, setStarting] = useState(true);
  const [tab, setTab] = useState<Tab>('stock');

  useEffect(() => {
    onSessionChange(setSession);
    restoreSession().then(setSession).finally(() => setStarting(false));
  }, []);

  if (starting) return <View style={[styles.screen, { justifyContent: 'center' }]}><State loading error={null} empty={null} /></View>;
  if (!session) return (<><StatusBar style="light" /><LoginScreen /></>);

  const { user } = session;
  if (user.role !== 'STORE_MANAGER') {
    return (
      <View style={[styles.screen, { justifyContent: 'center', padding: 24, gap: 16 }]}>
        <Text style={{ fontSize: 16, color: colors.text }}>The mobile app is for store managers. Please use the web app, {user.name}.</Text>
        <Pressable accessibilityRole="button" onPress={logout}><Text style={{ color: colors.primary, fontSize: 16 }}>Sign out</Text></Pressable>
      </View>
    );
  }

  return (
    <View style={styles.screen}>
      <StatusBar style="light" />
      <View style={[styles.header, styles.row, { justifyContent: 'space-between' }]}>
        <View>
          <Text style={styles.headerTitle}>{TABS.find((t) => t.id === tab)!.label}</Text>
          <Text style={styles.headerSub}>Store {user.storeId} · {user.name}</Text>
        </View>
        <Pressable accessibilityRole="button" onPress={logout} style={{ minHeight: 44, justifyContent: 'center' }}>
          <Text style={{ color: '#aeb9db', fontSize: 15 }}>Sign out</Text>
        </Pressable>
      </View>
      <View style={{ flex: 1 }}>
        {tab === 'stock' && <StockScreen user={user} />}
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
