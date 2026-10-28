import { getSession } from './api.js';
import { NO_SESSION } from './events.js';
import { refreshStatus } from './status.svelte.js';

/**
 * Phase 6 contract §2, the page's side of the session:
 * - one `EventSource('/api/session/stream')`, opened at start-up before the first API call, never closed by the page and
 *   never made twice (the browser reconnects it by itself, and the server quits the app when the last window's stream
 *   has been gone for a few seconds). One the browser has given up on (CLOSED) is replaced after a pause, so a window
 *   that is still open does not let the app quit; not once the session is lost, so nothing retries then;
 * - several-windows contract §3: the stream's `event: status` (what /api/status answers has changed, e.g. in another
 *   window) asks for the status as the footer does on focus; events within 250 ms make one read;
 * - `session.lost`: an API answered `401 no_session` (api.js sent NO_SESSION), so the page shows the full-screen message;
 * - `session.info`: GET /api/session; `tabNote` and `lockNote` are the footer's standing notes.
 */
export const TAB_NOTE = 'Running in a browser tab. Closing it quits the app.';
export const LOCK_NOTE = 'This drive cannot prevent a second copy from opening this library. Open only one.';

const STREAM = '/api/session/stream';
const REOPEN_MS = 5000;
const CLOSED = 2;
const STATUS_GATHER_MS = 250;

export const session = $state({
  lost: false,
  info: null,
  tabNote: '',
  lockNote: '',
});

let stream = null;
let reopening = null;
let statusSoon = null;

function statusChanged() {
  if (statusSoon || session.lost) return;
  statusSoon = setTimeout(() => {
    statusSoon = null;
    if (!session.lost) refreshStatus();
  }, STATUS_GATHER_MS);
}

function open() {
  if (typeof EventSource !== 'function') return;
  const mine = new EventSource(STREAM);
  stream = mine;
  mine.addEventListener('status', statusChanged);
  // A drop is the browser's to reconnect; only one it has given up on is replaced.
  mine.onerror = () => {
    if (mine !== stream || mine.readyState !== CLOSED || session.lost || reopening) return;
    reopening = setTimeout(() => {
      reopening = null;
      if (!session.lost) open();
    }, REOPEN_MS);
  };
}

/**
 * A stray `?notice=` (an older app sent `already-running` after a second launch; several-windows contract §1 removed
 * that notice) is taken out of the address and ignored; the rest of the address stays.
 */
function readNotice() {
  const params = new URLSearchParams(window.location.search);
  if (!params.has('notice')) return;
  params.delete('notice');
  const search = params.toString();
  window.history.replaceState(window.history.state, '', `${window.location.pathname}${search ? `?${search}` : ''}${window.location.hash}`);
}

/** Called once from main.js before the first API call; calling it again does nothing. */
export function startSession() {
  if (stream) return;
  open();
  readNotice();
}

/** GET /api/session. A failure leaves the notes out (Settings' About says the details could not be read, with Try again). */
export async function loadSession() {
  try {
    const info = await getSession();
    session.info = info;
    session.tabNote = info?.mode === 'tab' ? TAB_NOTE : '';
    session.lockNote = info?.lockWarning === true ? LOCK_NOTE : '';
    return true;
  } catch {
    session.info = null;
    return false;
  }
}

const RETRY_MS = [1000, 2000];

/**
 * The start-up read: a transient failure must not drop the standing notes (the unlockable drive is a data-safety note)
 * for the whole load, so it asks up to three times over a few seconds, and not once the session is lost.
 */
export async function loadSessionWithRetry() {
  for (let i = 0; ; i += 1) {
    if (await loadSession() || session.lost || i >= RETRY_MS.length) return;
    await new Promise((resolve) => setTimeout(resolve, RETRY_MS[i]));
    if (session.lost) return;
  }
}

/** Test-only: back to a page that has not started a session (the real stream is never closed by the page). */
export function resetSession() {
  clearTimeout(reopening);
  reopening = null;
  clearTimeout(statusSoon);
  statusSoon = null;
  stream = null;
  session.lost = false;
  session.info = null;
  session.tabNote = '';
  session.lockNote = '';
}

if (typeof window !== 'undefined') {
  window.addEventListener(NO_SESSION, () => {
    session.lost = true;
  });
}
