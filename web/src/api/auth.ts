// Calls to the core service's sign-in API (see the README's "Sign-in API").

export type Role = 'ADMIN' | 'STORE_MANAGER' | 'FINANCE_APPROVER';

export type UserSummary = {
  id: string;
  name: string;
  role: Role;
  storeId: string | null;
};

export type Session = {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: UserSummary;
};

/** An error the user can be shown as-is. */
export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

const NETWORK_ERROR = "Can't reach the server. Check your connection and try again.";

export async function login(email: string, password: string): Promise<Session> {
  const response = await post('/api/auth/login', { email, password });
  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }
  return (await response.json()) as Session;
}

/** Signing out never fails from the user's point of view: the local session is cleared either way. */
export async function logout(refreshToken: string): Promise<void> {
  try {
    await post('/api/auth/logout', { refreshToken });
  } catch {
    // Offline: the refresh token expires on its own.
  }
}

async function post(url: string, body: unknown): Promise<Response> {
  try {
    return await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(NETWORK_ERROR, 0);
  }
}

/** The server answers errors as problem details (RFC 9457); show their "detail" text. */
async function errorMessage(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string; title?: string };
    return problem.detail ?? problem.title ?? `Request failed (${response.status})`;
  } catch {
    return `Request failed (${response.status})`;
  }
}
