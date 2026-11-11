import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { SettingsPage } from './SettingsPage';

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const SETTINGS = [
  { key: 'orders.stuckAfterDays', value: 7, description: 'Open orders older than this are flagged stuck (D32)', rule: 'a whole number from 1 to 365',
    isPublic: false, updatedBy: null, updatedByName: null, updatedAt: '2026-11-10T00:00:00Z', version: 0 },
  { key: 'ui.maintenanceBanner', value: null, description: 'Text shown at the top of web and mobile; null hides it',
    rule: 'text up to 200 characters, or null to hide it', isPublic: true, updatedBy: null, updatedByName: null, updatedAt: '2026-11-10T00:00:00Z', version: 0 },
];

describe('remote config (M27)', () => {
  it('shows the maintenance banner on the login page, read from /api/config', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(url === '/api/config'
      ? json(200, { values: { 'ui.maintenanceBanner': 'Stock count tonight 9-11 pm' }, updatedAt: '2026-11-11T10:00:00Z' })
      : json(404, {}))));
    render(<App />);

    expect(await screen.findByText('Stock count tonight 9-11 pm')).toBeInTheDocument();
  });

  it('shows no banner when it is switched off', async () => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(url === '/api/config'
      ? json(200, { values: { 'ui.maintenanceBanner': null }, updatedAt: null }) : json(404, {})));
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/config'));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('an admin saves a setting with the version they saw, and sees the server rule when it is refused', async () => {
    const calls: { url: string; body?: string }[] = [];
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, body: init?.body as string | undefined });
      if (url === '/api/admin/settings') return Promise.resolve(json(200, SETTINGS));
      if (url === '/api/admin/settings/orders.stuckAfterDays') {
        const value = JSON.parse(String(init?.body)).value;
        return Promise.resolve(value === 0
          ? json(400, { detail: "'orders.stuckAfterDays' must be a whole number from 1 to 365" })
          : json(200, { ...SETTINGS[0], value, version: 1, updatedByName: 'Rohit Menon' }));
      }
      return Promise.resolve(json(404, {}));
    }));
    render(<SettingsPage />);

    const input = await screen.findByLabelText('orders.stuckAfterDays');
    await userEvent.clear(input);
    await userEvent.type(input, '0');
    await userEvent.click(screen.getAllByRole('button', { name: 'Save' })[0]);
    expect(await screen.findByRole('alert')).toHaveTextContent('must be a whole number from 1 to 365');

    await userEvent.clear(input);
    await userEvent.type(input, '10');
    await userEvent.click(screen.getAllByRole('button', { name: 'Save' })[0]);

    expect(await screen.findByText('Saved')).toBeInTheDocument();
    expect(JSON.parse(calls.filter((c) => c.url.endsWith('orders.stuckAfterDays')).at(-1)!.body!)).toEqual({ value: 10, version: 0 });
    expect(screen.getByText('Rohit Menon')).toBeInTheDocument();
  });
});
