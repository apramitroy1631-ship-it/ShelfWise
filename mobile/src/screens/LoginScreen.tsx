import { useState } from 'react';
import { KeyboardAvoidingView, Platform, Text, TextInput, View } from 'react-native';
import { login } from '../api/client';
import { Button, colors, styles } from '../ui';

/** Sign-in for store managers. The server decides who may sign in; the app only shows its message. */
export function LoginScreen() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password);
    } catch (e) {
      setError((e as Error).message);
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined}
                          style={{ flex: 1, backgroundColor: colors.nav, justifyContent: 'center', padding: 16 }}>
      <View style={{ backgroundColor: colors.surface, borderRadius: 12, padding: 24, gap: 12 }}>
        <Text style={{ fontSize: 22, fontWeight: '600', color: colors.text }}>ShelfWise</Text>
        <Text style={{ color: colors.text2 }}>Sign in to manage your store's stock and orders.</Text>
        <TextInput accessibilityLabel="Email" placeholder="Email" style={styles.input} value={email} onChangeText={setEmail}
                   autoCapitalize="none" autoComplete="email" keyboardType="email-address" />
        <TextInput accessibilityLabel="Password" placeholder="Password" style={styles.input} value={password}
                   onChangeText={setPassword} secureTextEntry onSubmitEditing={submit} />
        {error && <Text accessibilityRole="alert" style={{ color: colors.danger }}>{error}</Text>}
        <Button title={busy ? 'Signing in…' : 'Sign in'} onPress={submit} disabled={busy || !email || !password} />
      </View>
    </KeyboardAvoidingView>
  );
}
