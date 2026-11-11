import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { ListState } from '../components/ui';
import { dateTime } from '../lib/format';

export type Setting = {
  key: string;
  value: unknown;
  description: string;
  rule: string;
  isPublic: boolean;
  updatedBy: string | null;
  updatedByName: string | null;
  updatedAt: string;
  version: number;
};

/**
 * Remote configuration for admins (DL-06, M27). A change applies on the server's next request and reaches the
 * apps within about a minute, with no redeploy or app-store release. Every change is audited.
 */
export function SettingsPage() {
  const [settings, setSettings] = useState<Setting[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Setting[]>('/api/admin/settings').then(setSettings).catch((e: Error) => setError(e.message));
  }, []);

  function saved(next: Setting) {
    setSettings((list) => list?.map((s) => (s.key === next.key ? next : s)) ?? null);
  }

  return (
    <section className="page">
      <header className="page-head">
        <div>
          <h1>Settings</h1>
          <p>Changes apply without a redeploy: on the server at once, in the web and mobile apps within about a minute.</p>
        </div>
      </header>
      <ListState loading={!settings && !error} error={error} empty={!!settings && settings.length === 0} emptyText="No settings." />
      {settings && (
        <div className="panel">
          <table>
            <thead><tr><th>Setting</th><th>Value</th><th>Last change</th></tr></thead>
            <tbody>{settings.map((s) => <SettingRow key={s.key} setting={s} onSaved={saved} />)}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function SettingRow({ setting, onSaved }: { setting: Setting; onSaved: (s: Setting) => void }) {
  const kind = typeof setting.value === 'boolean' ? 'flag' : setting.rule.startsWith('text') ? 'text' : 'number';
  const [draft, setDraft] = useState(setting.value === null ? '' : String(setting.value));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => { setDraft(setting.value === null ? '' : String(setting.value)); }, [setting]);

  async function save(value: unknown) {
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      onSaved(await api<Setting>(`/api/admin/settings/${encodeURIComponent(setting.key)}`, {
        method: 'PUT',
        body: JSON.stringify({ value, version: setting.version }),
      }));
      setDone(true);
    } catch (e) {
      setError((e as Error).message);             // e.g. "must be a whole number from 1 to 365", or "changed by someone else"
    } finally {
      setBusy(false);
    }
  }

  const unchanged = draft === (setting.value === null ? '' : String(setting.value));

  return (
    <tr>
      <td>
        <div className="mono">{setting.key}</div>
        <div className="muted small">{setting.description}{setting.isPublic ? ' · sent to the apps' : ''}</div>
      </td>
      <td>
        {kind === 'flag' ? (
          <label className="check">
            <input type="checkbox" aria-label={setting.key} checked={setting.value === true} disabled={busy}
                   onChange={(e) => void save(e.target.checked)} />
            {setting.value === true ? 'On' : 'Off'}
          </label>
        ) : (
          <form className="toolbar" onSubmit={(e) => { e.preventDefault(); void save(kind === 'number' ? Number(draft) : draft.trim() || null); }}>
            <input aria-label={setting.key} value={draft} onChange={(e) => setDraft(e.target.value)}
                   inputMode={kind === 'number' ? 'numeric' : undefined} placeholder={kind === 'text' ? 'Empty = hidden' : undefined}
                   style={kind === 'text' ? { minWidth: 280 } : { width: 110 }} />
            <button type="submit" disabled={busy || unchanged}>{busy ? 'Saving…' : 'Save'}</button>
          </form>
        )}
        <div className="muted small">Allowed: {setting.rule}</div>
        {error && <p className="error" role="alert">{error}</p>}
        {done && <p className="small" role="status">Saved</p>}
      </td>
      <td className="small">{setting.updatedByName ?? '—'}<div className="muted">{dateTime(setting.updatedAt)}</div></td>
    </tr>
  );
}
