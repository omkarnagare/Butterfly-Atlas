import { UPDATE_HREF } from './route.js';
import { BACKUP_COPYING, BUSY, IMPORT_CHANGED, IMPORT_FILING, TAXONOMY_UPDATING } from './codes.js';

/**
 * One short message at a time at the bottom of the screen (prototype .toast). It may carry a link (`{ href, label }`),
 * and then stays 8 s unless `ms` says otherwise; plain, 2.6 s.
 */
export const SHORT_MS = 2600;
export const LINK_MS = 8000;
export const toast = $state({ message: '', seq: 0, link: null, ms: SHORT_MS });

export function showToast(message, { link: wanted = null, ms } = {}) {
  // No link to the page already shown (it would lead nowhere).
  const link = wanted && typeof window !== 'undefined' && window.location.hash === wanted.href ? null : wanted;
  ms ??= link ? LINK_MS : SHORT_MS;
  toast.message = message;
  toast.link = link;
  toast.ms = ms;
  toast.seq += 1;
}

/** Hides the toast at once (its link was followed). */
export function hideToast() {
  toast.seq += 1;
  toast.message = '';
  toast.link = null;
  toast.ms = 0;
}

/** Several windows (audit A1): refusals whose words are long (90–140 characters) stay as long as one with a link. */
const LONG = new Set([BACKUP_COPYING, IMPORT_FILING, IMPORT_CHANGED, BUSY]);

/**
 * A refusal in a toast: one because the taxonomy is updating (409 `taxonomy_updating`, contract §7) stays 8 s and
 * links to the update screen, where it says why; one because a backup holds the photos (409 `backup_copying`) links to
 * Home, where its card is; the other long ones of several windows stay 8 s; any other is the message as it is.
 */
export function showProblem(e) {
  if (e?.code === TAXONOMY_UPDATING) showToast(e.message, { link: { href: UPDATE_HREF, label: 'Taxonomy update' } });
  else if (e?.code === BACKUP_COPYING) showToast(e.message, { link: { href: '#/', label: 'Backup' }, ms: LINK_MS });
  else if (LONG.has(e?.code)) showToast(e.message, { ms: LINK_MS });
  else showToast(e?.message ?? String(e));
}
