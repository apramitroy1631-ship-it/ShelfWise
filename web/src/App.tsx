import { useEffect, useState } from 'react';
import { logout, type Role, type Session } from './api/auth';
import { onSessionChange, setSession as storeSession } from './api/client';
import { CataloguePage } from './pages/CataloguePage';
import { InventoryPage } from './pages/InventoryPage';
import { LoginPage } from './pages/LoginPage';
import { OrdersPage } from './pages/OrdersPage';

const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Admin (head office)',
  STORE_MANAGER: 'Store manager',
  FINANCE_APPROVER: 'Finance approver',
};

type Screen = 'orders' | 'inventory' | 'catalogue';

/**
 * Top level of the web app: sign in, then the sidebar layout from the design system.
 * The session is kept in memory only, so reloading the page signs you out (see known issues).
 */
export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [screen, setScreen] = useState<Screen>('orders');

  useEffect(() => onSessionChange(setSession), []);

  if (!session) {
    return <LoginPage onSignedIn={(s) => { storeSession(s); setScreen('orders'); }} />;
  }

  const { user } = session;

  async function handleSignOut() {
    if (session) await logout(session.refreshToken);
    storeSession(null);
  }

  const menu: { id: Screen; label: string }[] = [
    { id: 'orders', label: 'Orders' },
    { id: 'inventory', label: 'Inventory' },
    { id: 'catalogue', label: 'Catalogue' },
  ];

  return (
    <div className="shell">
      <nav className="sidebar" aria-label="Main">
        <div className="brand">ShelfWise</div>
        <div className="store-switch">{user.storeId ? `Store ${user.storeId}` : 'All 5 stores'}</div>
        <ul>
          {menu.map((m) => (
            <li key={m.id}>
              <button type="button" className={screen === m.id ? 'active' : ''} aria-current={screen === m.id ? 'page' : undefined} onClick={() => setScreen(m.id)}>
                {m.label}
              </button>
            </li>
          ))}
        </ul>
        <div className="user-block">
          <div className="avatar" aria-hidden="true">{user.name.split(' ').map((p) => p[0]).join('').slice(0, 2)}</div>
          <div>
            <div className="user-name">{user.name}</div>
            <div className="user-role">{ROLE_LABELS[user.role]}</div>
          </div>
          <button type="button" className="signout" onClick={handleSignOut}>Sign out</button>
        </div>
      </nav>
      <main className="content">
        {screen === 'orders' && <OrdersPage user={user} />}
        {screen === 'inventory' && <InventoryPage user={user} />}
        {screen === 'catalogue' && <CataloguePage user={user} />}
      </main>
    </div>
  );
}
