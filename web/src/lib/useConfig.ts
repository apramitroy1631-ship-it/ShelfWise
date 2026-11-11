import { useEffect, useState } from 'react';

/** Public remote settings (DL-06, M27). */
export type PublicConfig = { values: Record<string, unknown>; updatedAt: string | null };

const REFRESH_MS = 60_000;

/**
 * Reads /api/config when the app opens, every 60 seconds and when the window regains focus, so a change an admin
 * makes reaches every open screen within about a minute, with no redeploy. Works before sign-in (the banner shows
 * on the login page too). If the server can't be reached, the last values are kept.
 */
export function useConfig(): PublicConfig | null {
  const [config, setConfig] = useState<PublicConfig | null>(null);

  useEffect(() => {
    let live = true;
    const load = () => {
      fetch('/api/config')
        .then((r) => (r.ok ? (r.json() as Promise<PublicConfig>) : null))
        .then((c) => { if (live && c) setConfig(c); })
        .catch(() => undefined);
    };
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    window.addEventListener('focus', load);
    return () => {
      live = false;
      window.clearInterval(timer);
      window.removeEventListener('focus', load);
    };
  }, []);

  return config;
}

/** The maintenance banner text, or null when it's switched off. */
export function bannerText(config: PublicConfig | null): string | null {
  const text = config?.values?.['ui.maintenanceBanner'];   // tolerate an unexpected answer: no banner, never a crash
  return typeof text === 'string' && text.trim() ? text : null;
}
