// Light / dark theme (design system §9): follow the device by default, with a manual choice kept on this device.

export type Theme = 'light' | 'dark';

const KEY = 'shelfwise.theme';

/** The user's explicit choice, or null to follow the device. Storage can be blocked, so every access is guarded. */
export function storedTheme(): Theme | null {
  try {
    const value = localStorage.getItem(KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

export function deviceTheme(): Theme {
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** The theme on screen right now. */
export function currentTheme(): Theme {
  return storedTheme() ?? deviceTheme();
}

/** Applies a choice; styles.css reads data-theme on <html>. */
export function applyTheme(theme: Theme | null) {
  if (theme) document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;
  try {
    if (theme) localStorage.setItem(KEY, theme);
    else localStorage.removeItem(KEY);
  } catch {
    // Storage blocked: the choice lasts until the page is reloaded.
  }
}
