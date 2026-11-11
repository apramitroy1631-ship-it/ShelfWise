import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { NotificationFeed, NotificationItem } from '../api/types';
import { dateTime } from '../lib/format';

const REFRESH_MS = 30_000;

/**
 * The bell (D7, DL-12). Reads the notification service through /api/notifications, refreshes every 30 seconds
 * and when the window regains focus. If the notification service is down, the rest of the app keeps working and
 * the panel says so.
 */
export function NotificationBell({ onOpenItem }: { onOpenItem: (item: NotificationItem) => void }) {
  const [feed, setFeed] = useState<NotificationFeed | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      setFeed(await api<NotificationFeed>('/api/notifications'));
      setUnavailable(false);
    } catch {
      setUnavailable(true);                   // keep showing the last list we had
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), REFRESH_MS);
    const onFocus = () => void load();
    window.addEventListener('focus', onFocus);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', onFocus);
    };
  }, [load]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    const onClick = (e: MouseEvent) => box.current && !box.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onClick);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onClick);
    };
  }, [open]);

  const unread = feed?.unreadCount ?? 0;

  function markLocally(ids: number[] | 'all') {
    setFeed((f) => {
      if (!f) return f;
      const items = f.items.map((i) => (ids === 'all' || ids.includes(i.id) ? { ...i, unread: false } : i));
      return { items, unreadCount: items.filter((i) => i.unread).length };
    });
  }

  async function openItem(item: NotificationItem) {
    if (item.unread) {
      markLocally([item.id]);
      api<void>(`/api/notifications/${item.id}/read`, { method: 'POST' }).catch(() => void load());
    }
    setOpen(false);
    onOpenItem(item);
  }

  async function markAll() {
    markLocally('all');
    await api<void>('/api/notifications/read-all', { method: 'POST' }).catch(() => void load());
  }

  return (
    <div className="bell" ref={box}>
      <button
        type="button"
        className="bell-button"
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
        aria-expanded={open}
        onClick={() => { setOpen((o) => !o); if (!open) void load(); }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {unread > 0 && <span className="bell-badge" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {open && (
        <div className="bell-panel" role="dialog" aria-label="Notifications">
          <div className="panel-head">
            <strong>Notifications</strong>
            <button type="button" className="text-action" onClick={markAll} disabled={unread === 0}>Mark all as read</button>
          </div>
          {unavailable && <p className="muted small" role="status">Notifications are unavailable right now; everything else still works.</p>}
          {feed && feed.items.length === 0 && <p className="muted">You're all caught up.</p>}
          {feed && feed.items.length > 0 && (
            <ul className="bell-list">
              {feed.items.map((item) => (
                <li key={item.id}>
                  <button type="button" className={item.unread ? 'bell-item unread' : 'bell-item'} onClick={() => openItem(item)}>
                    {item.unread && <span className="dot" aria-label="Unread" />}
                    <span className="bell-text">{item.text}</span>
                    <span className="muted small">{dateTime(item.at)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
