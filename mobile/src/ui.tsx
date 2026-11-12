// Shared look for the mobile screens, using the design-system v3 colours (docs/design-system.md).
import type { ReactNode } from 'react';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

export const colors = {
  nav: '#0e1a3a',
  canvas: '#f6f5f1',
  surface: '#ffffff',
  border: '#e7e5df',
  text: '#111827',
  text2: '#4b5563',
  muted: '#6b7280',
  primary: '#2f55d4',
  danger: '#b91c1c',
  dangerBg: '#fee2e2',
  warnBg: '#fef3c7',
  warn: '#92400e',
};

const rupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 0 });

/** ₹1,28,400 (Indian grouping). */
export function money(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : rupees.format(value);
}

/** Primary action: at least 48 dp tall (touch-target minimum, design system). */
export function Button({ title, onPress, disabled, kind = 'primary' }: { title: string; onPress: () => void; disabled?: boolean; kind?: 'primary' | 'secondary' }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ disabled: !!disabled }} onPress={onPress} disabled={disabled}
               style={[styles.button, kind === 'secondary' && styles.secondary, disabled && styles.disabled]}>
      <Text style={[styles.buttonText, kind === 'secondary' && styles.secondaryText]}>{title}</Text>
    </Pressable>
  );
}

/** Status is never colour alone: icon + word (design system §3). */
export function Chip({ kind, children }: { kind: 'danger' | 'warn'; children: ReactNode }) {
  return (
    <View style={[styles.chip, { backgroundColor: kind === 'danger' ? colors.dangerBg : colors.warnBg }]}>
      <Text style={{ color: kind === 'danger' ? colors.danger : colors.warn, fontSize: 12, fontWeight: '600' }}>{children}</Text>
    </View>
  );
}

export function State({ loading, error, empty }: { loading: boolean; error: string | null; empty: string | null }) {
  if (error) return <Text accessibilityRole="alert" style={styles.error}>{error}</Text>;
  if (loading) return <ActivityIndicator style={{ margin: 24 }} color={colors.primary} />;
  if (empty) return <Text style={styles.empty}>{empty}</Text>;
  return null;
}

/** A bottom sheet for details and forms. */
export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
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

export const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.canvas },
  header: { backgroundColor: colors.nav, paddingHorizontal: 16, paddingTop: 48, paddingBottom: 14 },
  headerTitle: { color: '#fff', fontSize: 20, fontWeight: '600' },
  headerSub: { color: '#aeb9db', fontSize: 13, marginTop: 2 },
  input: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: 8, paddingHorizontal: 12, minHeight: 48, fontSize: 16, color: colors.text },
  card: { backgroundColor: colors.surface, borderBottomWidth: 1, borderColor: colors.border, paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 16, color: colors.text, fontWeight: '500' },
  sub: { fontSize: 13, color: colors.muted, marginTop: 2 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  button: { backgroundColor: colors.primary, minHeight: 48, borderRadius: 8, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  secondary: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.border },
  secondaryText: { color: colors.primary },
  disabled: { opacity: 0.55 },
  chip: { borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2, alignSelf: 'flex-start' },
  error: { color: colors.danger, margin: 16, fontSize: 15 },
  empty: { color: colors.muted, margin: 24, textAlign: 'center' },
});
