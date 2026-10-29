/*
 * New window opens where the window that asked for it is (filters-in-Explore design §7), without the server: the
 * asking window leaves its address in this browser's storage, and a window that starts with no address of its own
 * takes it when it is under a minute old. Storage the browser refuses passes nothing on (the asker says so).
 */
const KEY = 'atlas.newWindowAt';
const MAX_AGE = 60_000;

/** Leaves `hash` for the next new window; false when the browser would not keep it. */
export function passOn(hash, now = Date.now()) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ hash, at: now }));
    return true;
  } catch {
    return false;
  }
}

/** The window asked for did not open: nothing is left for a later one. */
export function forgetPassedOn() {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // nothing was kept
  }
}

/** The address a starting window takes over (once), or null: it has its own, none was left, or it is too old. */
export function takeOn(currentHash, now = Date.now()) {
  if (!['', '#', '#/'].includes(currentHash)) return null;
  try {
    const left = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    if (!left) return null;
    localStorage.removeItem(KEY);
    return typeof left.hash === 'string' && left.hash.startsWith('#/') && now - left.at >= 0 && now - left.at <= MAX_AGE ? left.hash : null;
  } catch {
    return null;
  }
}
