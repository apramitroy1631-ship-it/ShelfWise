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

function jsonResponse(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

async function signIn(email: string, password: string) {
  await userEvent.type(screen.getByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('sign-in', () => {
  it('signs in and shows who you are', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, SESSION));
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);

    await signIn('arjun@test.example', 'secret');

    expect(await screen.findByText('Welcome, Arjun Rao')).toBeInTheDocument();
    expect(screen.getByText('Store manager · Store S01')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST' }));
  });

  it("shows the server's message when sign-in is rejected and clears the password", async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401, { detail: 'Invalid email or password' })));
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
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>((resolve) => (finish = resolve))));
    render(<App />);

    await signIn('arjun@test.example', 'secret');

    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
    finish(jsonResponse(200, SESSION));
    expect(await screen.findByText('Welcome, Arjun Rao')).toBeInTheDocument();
  });

  it('signs out, revoking the refresh token on the server', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, SESSION))
      .mockResolvedValueOnce(jsonResponse(204));
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);
    await signIn('arjun@test.example', 'secret');
    await screen.findByText('Welcome, Arjun Rao');

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/auth/logout',
      expect.objectContaining({ body: JSON.stringify({ refreshToken: 'refresh' }) }),
    );
  });
});
