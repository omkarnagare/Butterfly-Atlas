import { getAppearance, setAppearance } from './api.js';

/**
 * Match device, Light or Dark (Meadow design §6, contract §5; match-device design §4). `saved` is the choice the server
 * keeps ('device' | 'light' | 'dark'); with 'device' the page follows the device's setting, and changes with it. The
 * theme shown (`shown`) is put on <html> as data-theme, which theme.css keys the dusk tokens on. The choice (not the
 * theme shown) is cached in this window's storage (`atlas.theme`), so the next launch paints Light or Dark, or the
 * device's setting now, before the server answers (main.js starts this before the app mounts). A storage error is
 * ignored; the server's value wins once it arrives, and a choice made in another window comes with the status poll.
 */
export const THEME_KEY = 'atlas.theme';
export const DEVICE = 'device';
const DARK_QUERY = '(prefers-color-scheme: dark)';
const look = (value) => value === 'light' || value === 'dark';
const known = (value) => value === DEVICE || look(value);

export const theme = $state({ saved: DEVICE, shown: 'light' });

// Saves on their way: a poll answered meanwhile may still carry the choice from before.
let saving = 0;
let media = null;

const device = () => (typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light');

function show(value) {
  theme.shown = value;
  document.documentElement.dataset.theme = value;
}

function choose(value) {
  theme.saved = value;
  try {
    localStorage.setItem(THEME_KEY, value);
  } catch {
    // storage unavailable: the next launch starts from the device's setting
  }
  show(look(value) ? value : device());
}

function deviceChanged() {
  if (theme.saved === DEVICE) show(device());
}

/** The cached Light or Dark at once (else the device's setting), then the server's choice. */
export function startTheme() {
  let cached = null;
  try {
    cached = localStorage.getItem(THEME_KEY);
  } catch {
    cached = null;
  }
  show(look(cached) ? cached : device());
  media?.removeEventListener?.('change', deviceChanged);
  media = typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null;
  media?.addEventListener?.('change', deviceChanged);
  getAppearance().then((answer) => serverTheme(answer?.theme), () => {
    // the status poll brings it
  });
}

/** The server's saved choice (GET /api/appearance, or `theme` in a status answer). Anything else is Match device. */
export function serverTheme(value) {
  if (saving) return;
  choose(known(value) ? value : DEVICE);
}

/** Saves Match device, Light or Dark and shows it. A save that fails keeps the saved choice and throws the server's reason. */
export async function chooseTheme(value) {
  saving += 1;
  try {
    const answer = await setAppearance(value);
    choose(known(answer?.theme) ? answer.theme : value);
  } finally {
    saving -= 1;
  }
}

/** Test-only: Match device, nothing listened to, nothing on the page. */
export function resetTheme() {
  media?.removeEventListener?.('change', deviceChanged);
  media = null;
  saving = 0;
  theme.saved = DEVICE;
  theme.shown = 'light';
  delete document.documentElement.dataset.theme;
}
