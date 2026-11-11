import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Page } from '../api/types';

/** Loads one page of a list API whenever the URL (or the reload counter) changes. */
export function useList<T>(url: string, reload = 0) {
  const [data, setData] = useState<Page<T> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let live = true;
    setLoading(true);
    setError(null);
    api<Page<T>>(url)
      .then((page) => { if (live) setData(page); })
      .catch((e: Error) => { if (live) setError(e.message); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [url, reload]);

  return { data, error, loading };
}

/**
 * Totals for KPI cards: asks each list URL for one row and keeps its totalItems.
 * A count that fails shows as "…" rather than breaking the page.
 */
export function useCounts(urls: Record<string, string>, reload = 0) {
  const key = JSON.stringify(urls);
  const [counts, setCounts] = useState<Record<string, number | null>>({});

  useEffect(() => {
    let live = true;
    const entries = Object.entries(JSON.parse(key) as Record<string, string>);
    setCounts(Object.fromEntries(entries.map(([name]) => [name, null])));
    for (const [name, url] of entries) {
      api<Page<unknown>>(url)
        .then((page) => { if (live) setCounts((c) => ({ ...c, [name]: page.totalItems })); })
        .catch(() => undefined);
    }
    return () => { live = false; };
  }, [key, reload]);

  return counts;
}
