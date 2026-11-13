import {
  IconAlertTriangle, IconBox, IconChartBar, IconClipboardCheck, IconFileInvoice, IconLogout, IconMoon, IconReceipt, IconSettings, IconSun, IconTag, type Icon,
} from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { logout, type Role, type Session } from './api/auth';
import { api, onSessionChange, query, setSession as storeSession } from './api/client';
import { applyTheme, currentTheme, type Theme } from './lib/theme';
import { bannerText, useConfig } from './lib/useConfig';
import { useCounts } from './lib/useList';
import type { NotificationItem } from './api/types';
import { NotificationBell } from './components/NotificationBell';
import { CataloguePage } from './pages/CataloguePage';
import { DashboardPage } from './pages/DashboardPage';
import { InventoryPage } from './pages/InventoryPage';
import { LoginPage } from './pages/LoginPage';
import { OrdersPage } from './pages/OrdersPage';
import { PurchaseOrdersPage } from './pages/PurchaseOrdersPage';
import { ReviewsPage } from './pages/ReviewsPage';
import { SettingsPage } from './pages/SettingsPage';

const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Admin (head office)',
  STORE_MANAGER: 'Store manager',
  FINANCE_APPROVER: 'Finance approver',
};

export const STORES = ['S01', 'S02', 'S03', 'S04', 'S05'];

type Screen = 'dashboard' | 'orders' | 'inventory' | 'catalogue' | 'purchase-orders' | 'reviews' | 'settings';

/**
 * Menu by role (docs/navigation-and-workflows.md, N2). Finance gets a focused menu: purchase orders and
 * read-only inventory. Menus are a convenience; the server checks role and store on every request.
 * Suppliers joins when its API exists.
 */
const MENU: { id: Screen; label: string; icon: Icon; roles: Role[] }[] = [
  { id: 'dashboard', label: 'Dashboard', icon: IconChartBar, roles: ['ADMIN', 'FINANCE_APPROVER'] },
  { id: 'orders', label: 'Orders', icon: IconReceipt, roles: ['ADMIN', 'STORE_MANAGER'] },
  { id: 'inventory', label: 'Inventory', icon: IconBox, roles: ['ADMIN', 'STORE_MANAGER', 'FINANCE_APPROVER'] },
  { id: 'catalogue', label: 'Catalogue', icon: IconTag, roles: ['ADMIN', 'STORE_MANAGER'] },
  { id: 'purchase-orders', label: 'Purchase orders', icon: IconFileInvoice, roles: ['ADMIN', 'STORE_MANAGER', 'FINANCE_APPROVER'] },
  { id: 'reviews', label: 'Review queue', icon: IconClipboardCheck, roles: ['ADMIN'] },
  { id: 'settings', label: 'Settings', icon: IconSettings, roles: ['ADMIN'] },
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
  const [inventoryView, setInventoryView] = useState({ low: false, key: 0 });   // a stock notification opens 'low' (D7)
  const [stuckView, setStuckView] = useState({ orders: false, pos: false, key: 0 });   // a stuck notification opens 'stuck' (D32, D22)

  const config = useConfig();
  const banner = bannerText(config);

  useEffect(() => onSessionChange(setSession), []);

  if (!session) {
    return (
      <>
        {banner && <MaintenanceBanner text={banner} />}
        <LoginPage onSignedIn={(s) => { storeSession(s); setScreen(LANDING[s.user.role]); setStore(''); }} />
      </>
    );
  }

  const { user } = session;
  const headOffice = user.role !== 'STORE_MANAGER';
  const scope = headOffice ? store : user.storeId ?? '';

  async function handleSignOut() {
    if (session) await logout(session.refreshToken);
    storeSession(null);
  }

  /**
   * Opening a notification goes where you act on it: stock groups to Inventory (low), stuck orders and stuck POs
   * to their lists with "stuck" on, orders needing review to the Review queue, other PO ones to Purchase orders.
   */
  function openNotification(item: NotificationItem) {
    if (item.kind === 'OUT_OF_STOCK' || item.kind === 'RUNNING_LOW') {
      setInventoryView((v) => ({ low: true, key: v.key + 1 }));
      setScreen('inventory');
    } else if (item.kind === 'STUCK_ORDERS') {
      setStuckView((v) => ({ orders: true, pos: false, key: v.key + 1 }));
      setScreen('orders');
    } else if (item.kind === 'STUCK_POS') {
      setStuckView((v) => ({ orders: false, pos: true, key: v.key + 1 }));
      setScreen('purchase-orders');
    } else if (item.kind === 'NEEDS_REVIEW') {
      setScreen('reviews');
    } else {
      setStuckView((v) => ({ orders: false, pos: false, key: v.key + 1 }));
      setScreen('purchase-orders');
    }
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
                {m.id === 'reviews' && <ReviewCount store={scope} screen={screen} />}
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
        {banner && <MaintenanceBanner text={banner} />}
        <div className="topbar">
          <NotificationBell onOpenItem={openNotification} />
        </div>
        {screen === 'dashboard' && <DashboardPage user={user} store={scope} />}
        {screen === 'orders' && <OrdersPage key={`o${stuckView.key}`} user={user} store={scope} startStuck={stuckView.orders} />}
        {screen === 'inventory' && <InventoryPage key={inventoryView.key} user={user} store={scope} startView={inventoryView.low ? 'low' : 'all'} />}
        {screen === 'catalogue' && <CataloguePage user={user} />}
        {screen === 'purchase-orders' && <PurchaseOrdersPage key={`p${stuckView.key}`} user={user} store={scope} startStuck={stuckView.pos} />}
        {screen === 'reviews' && <ReviewsPage store={scope} />}
        {screen === 'settings' && <SettingsPage />}
      </main>
    </div>
  );
}

/** The number next to "Review queue": imported orders still waiting for an admin decision (D10). Refreshed on each screen change. */
function ReviewCount({ store, screen }: { store: string; screen: Screen }) {
  const [waiting, setWaiting] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    api<{ waiting: number }>(`/api/admin/reviews${query({ storeId: store })}`)
      .then((r) => { if (live) setWaiting(r.waiting); })
      .catch(() => undefined);
    return () => { live = false; };
  }, [store, screen]);
  if (!waiting) return null;
  return <span className="nav-count" aria-label={`${waiting} waiting for review`} title={`${waiting} waiting for review`}>{waiting}</span>;
}

/** The number next to "Purchase orders": POs waiting for this person (finance: to approve; managers: goods to receive). */
function WaitingCount({ role, store }: { role: Role; store: string }) {
  const status = role === 'FINANCE_APPROVER' ? 'SUBMITTED' : role === 'STORE_MANAGER' ? 'APPROVED' : 'SUBMITTED';
  const { waiting } = useCounts({ waiting: `/api/purchase-orders${query({ storeId: store, status, size: 1 })}` });
  if (!waiting) return null;
  const label = role === 'STORE_MANAGER' ? 'approved, waiting for goods' : 'waiting for approval';
  return <span className="nav-count" aria-label={`${waiting} ${label}`} title={`${waiting} ${label}`}>{waiting}</span>;
}

/** Remote-config banner (DL-06): an admin switches it on or off in Settings; every open screen shows it within a minute. */
function MaintenanceBanner({ text }: { text: string }) {
  return (
    <div className="maintenance-banner" role="status">
      <IconAlertTriangle size={16} aria-hidden="true" />
      <span>{text}</span>
    </div>
  );
}
