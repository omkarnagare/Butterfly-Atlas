import { getAppearance, setAppearance } from './api.js';

/**
 * Light or Dark (Meadow design §6, contract §5). `saved` is the choice the server keeps ('light' | 'dark'), null while
 * none is saved: the page then follows the computer's setting, and changes with it. The theme shown (`shown`) is put on
 * <html> as data-theme, which theme.css keys the dusk tokens on, and cached in this window's storage (`atlas.theme`), so
 * the next launch paints it before the server answers (main.js starts this before the app mounts). A storage error is
 * ignored; the server's value wins once it arrives, and a choice made in another window comes with the status poll.
 */
export const THEME_KEY = 'atlas.theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';
const known = (value) => value === 'light' || value === 'dark';

export const theme = $state({ saved: null, shown: 'light' });

// Saves on their way: a poll answered meanwhile may still carry the choice from before.
let saving = 0;
let media = null;

const computer = () => (typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light');

function show(value) {
  theme.shown = value;
  document.documentElement.dataset.theme = value;
  try {
    localStorage.setItem(THEME_KEY, value);
  } catch {
    // storage unavailable: the next launch starts from the computer's setting
  }
}

const follow = () => show(theme.saved ?? computer());

function computerChanged() {
  if (theme.saved === null) follow();
}

/** The cached theme at once (else the computer's), then the server's choice. */
export function startTheme() {
  let cached = null;
  try {
    cached = localStorage.getItem(THEME_KEY);
  } catch {
    cached = null;
  }
  show(known(cached) ? cached : computer());
  media?.removeEventListener?.('change', computerChanged);
  media = typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null;
  media?.addEventListener?.('change', computerChanged);
  getAppearance().then((answer) => serverTheme(answer?.theme), () => {
    // the status poll brings it
  });
}

/** The server's saved choice (GET /api/appearance, or `theme` in a status answer). Anything else is no choice. */
export function serverTheme(value) {
  if (saving) return;
  theme.saved = known(value) ? value : null;
  follow();
}

/** Saves Light or Dark and shows it. A save that fails keeps the saved choice and throws the server's reason. */
export async function chooseTheme(value) {
  saving += 1;
  try {
    const answer = await setAppearance(value);
    theme.saved = known(answer?.theme) ? answer.theme : value;
    follow();
  } finally {
    saving -= 1;
  }
}

/** Test-only: no choice, nothing listened to, nothing on the page. */
export function resetTheme() {
  media?.removeEventListener?.('change', computerChanged);
  media = null;
  saving = 0;
  theme.saved = null;
  theme.shown = 'light';
  delete document.documentElement.dataset.theme;
}
