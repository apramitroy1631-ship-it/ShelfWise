import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import type { ProxyOptions } from 'vite';

/**
 * Forwards to a backend service as a same-origin call. The browser's Origin header is dropped: the dev server IS the
 * page's origin, so a forwarded call is never cross-origin. Keeping it made the core service's CORS check answer 403
 * whenever the page was opened at an address other than the one the backend sees (Vite's string shorthand rewrites
 * Host to localhost:8080; a tunnel such as ngrok or a LAN address changes the Origin).
 */
function backend(target: string): ProxyOptions {
  return {
    target,
    changeOrigin: false,
    // Runs before the call is forwarded; returning nothing lets it continue to the target without the header.
    bypass: (req) => {
      delete req.headers.origin;
    },
  };
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // In development, /api calls go to the core service, so the browser sees one origin (no CORS needed).
    // The bell is served by the notification service (DL-12); this more specific rule must come first.
    proxy: {
      '/api/notifications': backend('http://localhost:8081'),
      '/api': backend('http://localhost:8080'),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
