import { useEffect, useState } from 'react';
import { AppState } from 'react-native';
import { API_URL } from './api/client';

/** Public remote settings (DL-06, M27). */
export type PublicConfig = { values: Record<string, unknown>; updatedAt: string | null };

const REFRESH_MS = 60_000;

/**
 * Reads /api/config when the app opens, every 60 seconds while it's open and whenever it comes back to the
 * foreground, so an admin's change (e.g. the maintenance banner) reaches phones within about a minute, with no
 * app-store release. If the server can't be reached, the last values are kept.
 */
export function useConfig(): PublicConfig | null {
  const [config, setConfig] = useState<PublicConfig | null>(null);

  useEffect(() => {
    let live = true;
    const load = () => {
      fetch(`${API_URL}/api/config`)
        .then((r) => (r.ok ? (r.json() as Promise<PublicConfig>) : null))
        .then((c) => { if (live && c) setConfig(c); })
        .catch(() => undefined);
    };
    load();
    const timer = setInterval(load, REFRESH_MS);
    const sub = AppState.addEventListener('change', (state) => { if (state === 'active') load(); });
    return () => {
      live = false;
      clearInterval(timer);
      sub.remove();
    };
  }, []);

  return config;
}

export function bannerText(config: PublicConfig | null): string | null {
  const text = config?.values?.['ui.maintenanceBanner'];   // tolerate an unexpected answer: no banner, never a crash
  return typeof text === 'string' && text.trim() ? text : null;
}
