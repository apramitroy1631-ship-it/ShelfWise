import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';

const SESSION = {
  accessToken: 'access',
  refreshToken: 'refresh',
  expiresInSeconds: 900,
  user: { id: 'U03', name: 'Arjun Rao', role: 'STORE_MANAGER', storeId: 'S01' },
};

const ORDERS_PAGE = {
  items: [
    { id: 'ORD-014954', storeId: 'S01', status: 'PACKED', customerName: 'Rahul Menon', customerPhone: '+91 94XXXXX303',
      totalAmount: 1002, createdAt: '2026-10-31T11:31:00Z', reviewStatus: null, stuck: true, version: 0 },
  ],
  page: 0, size: 20, totalItems: 1, totalPages: 1,
};

function jsonResponse(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A fake backend: answers by URL, like the real core service. */
function backend(overrides: Record<string, () => Response> = {}) {
  return vi.fn((url: string) => {
    const path = url.split('?')[0];
    if (overrides[path]) return Promise.resolve(overrides[path]());
    if (path === '/api/auth/login') return Promise.resolve(jsonResponse(200, SESSION));
    if (path === '/api/auth/logout') return Promise.resolve(jsonResponse(204));
    if (path === '/api/orders') return Promise.resolve(jsonResponse(200, ORDERS_PAGE));
    return Promise.resolve(jsonResponse(404, { detail: 'not mocked' }));
  });
}

async function signIn(email: string, password: string) {
  await userEvent.type(screen.getByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('sign-in', () => {
  it('signs in and opens the orders list for the manager’s store', async () => {
    const fetchMock = backend();
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);

    await signIn('arjun@test.example', 'secret');

    expect(await screen.findByText('Arjun Rao')).toBeInTheDocument();
    expect(screen.getByText('Store manager')).toBeInTheDocument();
    expect(await screen.findByText('ORD-014954')).toBeInTheDocument();
    expect(screen.getByText('Stuck')).toBeInTheDocument();                 // D32
    expect(screen.getByText('+91 94XXXXX303')).toBeInTheDocument();       // D16: masked in lists
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST' }));
  });

  it("shows the server's message when sign-in is rejected and clears the password", async () => {
    vi.stubGlobal('fetch', backend({ '/api/auth/login': () => jsonResponse(401, { detail: 'Invalid email or password' }) }));
    render(<App />);

    await signIn('arjun@test.example', 'wrong');

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('explains when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    render(<App />);

    await signIn('arjun@test.example', 'secret');

    expect(await screen.findByRole('alert')).toHaveTextContent("Can't reach the server");
  });

  it('disables the button while signing in, so it cannot be submitted twice', async () => {
    let finish: (r: Response) => void = () => {};
    const slowLogin = new Promise<Response>((resolve) => (finish = resolve));
    vi.stubGlobal('fetch', vi.fn((url: string) => (url === '/api/auth/login' ? slowLogin : Promise.resolve(jsonResponse(200, ORDERS_PAGE)))));
    render(<App />);

    await signIn('arjun@test.example', 'secret');

    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
    finish(jsonResponse(200, SESSION));
    expect(await screen.findByText('Arjun Rao')).toBeInTheDocument();
  });

  it('signs out, revoking the refresh token on the server', async () => {
    const fetchMock = backend();
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);
    await signIn('arjun@test.example', 'secret');
    await screen.findByText('Arjun Rao');

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ body: JSON.stringify({ refreshToken: 'refresh' }) }));
  });

  it('switches to the dark theme and remembers the choice on this device', async () => {
    vi.stubGlobal('fetch', backend());
    render(<App />);
    await signIn('arjun@test.example', 'secret');

    await userEvent.click(await screen.findByRole('button', { name: 'Switch to dark theme' }));

    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('shelfwise.theme')).toBe('dark');
    expect(screen.getByRole('button', { name: 'Switch to light theme' })).toBeInTheDocument();
    localStorage.removeItem('shelfwise.theme');
    delete document.documentElement.dataset.theme;
  });

  it('refreshes an expired access token once and retries the request', async () => {
    let ordersCalls = 0;
    const fetchMock = backend({
      '/api/orders': () => (++ordersCalls === 1 ? jsonResponse(401, { detail: 'expired' }) : jsonResponse(200, ORDERS_PAGE)),
      '/api/auth/refresh': () => jsonResponse(200, { ...SESSION, accessToken: 'access-2', refreshToken: 'refresh-2' }),
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);

    await signIn('arjun@test.example', 'secret');

    expect(await screen.findByText('ORD-014954')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/refresh', expect.anything());
  });
});
