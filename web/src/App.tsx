import { useState } from 'react';
import { logout, type Role, type Session } from './api/auth';
import { LoginPage } from './pages/LoginPage';

const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Admin (head office)',
  STORE_MANAGER: 'Store manager',
  FINANCE_APPROVER: 'Finance approver',
};

/**
 * Top level of the web app. For now: sign in, see who you are, sign out.
 * The session is kept in memory only, so reloading the page signs you out (see known issues).
 */
export function App() {
  const [session, setSession] = useState<Session | null>(null);

  if (!session) {
    return <LoginPage onSignedIn={setSession} />;
  }

  const { user } = session;

  async function handleSignOut() {
    if (session) {
      await logout(session.refreshToken);
    }
    setSession(null);
  }

  return (
    <main className="home">
      <header className="topbar">
        <strong>ShelfWise</strong>
        <button type="button" className="secondary" onClick={handleSignOut}>
          Sign out
        </button>
      </header>
      <section>
        <h1>Welcome, {user.name}</h1>
        <p>
          {ROLE_LABELS[user.role]}
          {user.storeId ? ` · Store ${user.storeId}` : ' · All stores'}
        </p>
      </section>
    </main>
  );
}
