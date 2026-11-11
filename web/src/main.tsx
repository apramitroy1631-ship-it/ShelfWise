import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, storedTheme } from './lib/theme';
import './styles.css';

// Apply a saved light/dark choice before the first paint; with none saved, the device setting is followed.
const saved = storedTheme();
if (saved) applyTheme(saved);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
