import {
  IconBox, IconChartBar, IconFileInvoice, IconLogout, IconMoon, IconReceipt, IconSun, IconTag, type Icon,
} from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { logout, type Role, type Session } from './api/auth';
import { onSessionChange, query, setSession as storeSession } from './api/client';
import { applyTheme, currentTheme, type Theme } from './lib/theme';
import { useCounts } from './lib/useList';
import { CataloguePage } from './pages/CataloguePage';
import { DashboardPage } from './pages/DashboardPage';
import { InventoryPage } from './pages/InventoryPage';
import { LoginPage } from './pages/LoginPage';
import { OrdersPage } from './pages/OrdersPage';
import { PurchaseOrdersPage } from './pages/PurchaseOrdersPage';

const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Admin (head office)',
  STORE_MANAGER: 'Store manager',
  FINANCE_APPROVER: 'Finance approver',
};

export const STORES = ['S01', 'S02', 'S03', 'S04', 'S05'];

type Screen = 'dashboard' | 'orders' | 'inventory' | 'catalogue' | 'purchase-orders';

/**
 * Menu by role (docs/navigation-and-workflows.md, N2). Finance gets a focused menu: purchase orders and
 * read-only inventory. Menus are a convenience; the server checks role and store on every request.
 * Suppliers and Settings join when their APIs exist.
 */
const MENU: { id: Screen; label: string; icon: Icon; roles: Role[] }[] = [
  { id: 'dashboard', label: 'Dashboard', icon: IconChartBar, roles: ['ADMIN', 'FINANCE_APPROVER'] },
  { id: 'orders', label: 'Orders', icon: IconReceipt, roles: ['ADMIN', 'STORE_MANAGER'] },
  { id: 'inventory', label: 'Inventory', icon: IconBox, roles: ['ADMIN', 'STORE_MANAGER', 'FINANCE_APPROVER'] },
  { id: 'catalogue', label: 'Catalogue', icon: IconTag, roles: ['ADMIN', 'STORE_MANAGER'] },
  { id: 'purchase-orders', label: 'Purchase orders', icon: IconFileInvoice, roles: ['ADMIN', 'STORE_MANAGER', 'FINANCE_APPROVER'] },
];

/** Where each role starts (N1). */
const LANDING: Record<Role, Screen> = { ADMIN: 'dashboard', STORE_MANAGER: 'orders', FINANCE_APPROVER: 'purchase-orders' };

/**
 * Top level of the web app: sign in, then the sidebar layout from the design system (§5).
 * The session is kept in memory only, so reloading the page signs you out (see known issues).
 */
export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [screen, setScreen] = useState<Screen>('orders');
  const [store, setStore] = useState('');      // head office: '' = all 5 stores
  const [theme, setTheme] = useState<Theme>(currentTheme);

  useEffect(() => onSessionChange(setSession), []);

  if (!session) {
    return <LoginPage onSignedIn={(s) => { storeSession(s); setScreen(LANDING[s.user.role]); setStore(''); }} />;
  }

  const { user } = session;
  const headOffice = user.role !== 'STORE_MANAGER';
  const scope = headOffice ? store : user.storeId ?? '';

  async function handleSignOut() {
    if (session) await logout(session.refreshToken);
    storeSession(null);
  }

  function toggleTheme() {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    setTheme(next);
  }

  const menu = MENU.filter((m) => m.roles.includes(user.role));

  return (
    <div className="shell">
      <nav className="sidebar" aria-label="Main">
        <div className="brand"><span className="brand-mark" aria-hidden="true">S</span>ShelfWise</div>
        <div className="store-switch">
          <span>Store</span>
          {headOffice ? (
            <select aria-label="Store" value={store} onChange={(e) => setStore(e.target.value)}>
              <option value="">All 5 stores</option>
              {STORES.map((s) => <option key={s} value={s}>Store {s}</option>)}
            </select>
          ) : (
            <span className="fixed">Store {user.storeId}</span>
          )}
        </div>
        <ul>
          {menu.map((m) => (
            <li key={m.id}>
              <button type="button" className={screen === m.id ? 'active' : ''} aria-current={screen === m.id ? 'page' : undefined} onClick={() => setScreen(m.id)}>
                <m.icon size={17} aria-hidden="true" />
                {m.label}
                {m.id === 'purchase-orders' && <WaitingCount role={user.role} store={scope} />}
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
          <button type="button" className="ghost" onClick={toggleTheme} aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
                  title={theme === 'dark' ? 'Light theme' : 'Dark theme'}>
            {theme === 'dark' ? <IconSun size={17} aria-hidden="true" /> : <IconMoon size={17} aria-hidden="true" />}
          </button>
          <button type="button" className="signout" onClick={handleSignOut}><IconLogout size={15} aria-hidden="true" />Sign out</button>
        </div>
      </nav>
      <main className="content">
        {screen === 'dashboard' && <DashboardPage user={user} store={scope} />}
        {screen === 'orders' && <OrdersPage user={user} store={scope} />}
        {screen === 'inventory' && <InventoryPage user={user} store={scope} />}
        {screen === 'catalogue' && <CataloguePage user={user} />}
        {screen === 'purchase-orders' && <PurchaseOrdersPage user={user} store={scope} />}
      </main>
    </div>
  );
}

/** The number next to "Purchase orders": POs waiting for this person (finance: to approve; managers: goods to receive). */
function WaitingCount({ role, store }: { role: Role; store: string }) {
  const status = role === 'FINANCE_APPROVER' ? 'SUBMITTED' : role === 'STORE_MANAGER' ? 'APPROVED' : 'SUBMITTED';
  const { waiting } = useCounts({ waiting: `/api/purchase-orders${query({ storeId: store, status, size: 1 })}` });
  if (!waiting) return null;
  const label = role === 'STORE_MANAGER' ? 'approved, waiting for goods' : 'waiting for approval';
  return <span className="nav-count" aria-label={`${waiting} ${label}`} title={`${waiting} ${label}`}>{waiting}</span>;
}
