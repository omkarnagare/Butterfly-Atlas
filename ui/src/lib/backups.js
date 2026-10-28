/**
 * The words of a backup (Phase 6 contract §3–§5) from a Backup (or the status's Backups.Status): the footer, Home, the
 * card, the stop dialog, Settings. Numbers have thousands separators; sizes and dates are the exports' (lib/exports.js).
 */
import { num } from './exports.js';
import { CANCELLED, CLOSED } from './states.js';
import { BACKUP_FINISHED, DATABASE_ONLY } from './messages.js';

/**
 * The one-time preference question was closed without saving in this page load (Phase 6 contract §3: asked again on the
 * next start, not each time Home is opened). Module state, so Home opening again does not ask.
 */
let askClosed = false;
export const askWasClosed = () => askClosed;
export const noteAskClosed = () => { askClosed = true; };
/** Test-only: a page load that has not been asked yet. */
export const resetAsk = () => { askClosed = false; };

const photos = (n) => (n === 1 ? '1 photo' : `${num(n)} photos`);

/** "Backing up your library — 3,210 of 12,400 photos"; a database alone has no photo count: "Backing up your library…". */
export const backupLine = ({ done, total }) => (total > 0
  ? `Backing up your library — ${num(done)} of ${photos(total)}`
  : 'Backing up your library…');

/**
 * Several windows (contract §10 A6, §11 A22): a backup of Everything holds the photos until its photos are all copied
 * (`status.backup.holdsPhotos`); photo changes are refused meanwhile (409 `backup_copying`). The words follow the
 * backup's own buttons: running (Pause / Cancel…), paused by the user or by a full or missing drive (Resume / Cancel…),
 * or stopped by the app's closing (`pausedBy: 'closed'`: Continue backup / Discard…). The add sheet says them itself,
 * sending nothing.
 */
export const COPYING_RUNNING = "A backup is copying your photos, so photos can't be changed right now. Wait for it to finish, or cancel it, change your photos, then start a new backup.";
export const COPYING_PAUSED = "A paused backup still holds your photos, so they can't be changed yet. Resume the backup and let it finish, or cancel it, change your photos, then start a new backup.";
export const COPYING_STOPPED = "A paused backup still holds your photos, so they can't be changed yet. Continue the backup and let it finish, or discard it, change your photos, then start a new backup.";
const stoppedByClosing = (backup) => backup?.state === 'paused' && backup.pausedBy === 'closed';
export const copyingWords = (backup) => {
  if (backup?.state !== 'paused') return COPYING_RUNNING;
  return stoppedByClosing(backup) ? COPYING_STOPPED : COPYING_PAUSED;
};
/** The standing line of Home's backup card and the footer while the backup holds the photos (A6, A22), not muted. */
export const HOLDS_PHOTOS = "While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or cancel it and start a new backup after your changes.";
export const HOLDS_PHOTOS_STOPPED = "While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or discard it and start a new backup after your changes.";
export const holdsPhotosLine = (backup) => (stoppedByClosing(backup) ? HOLDS_PHOTOS_STOPPED : HOLDS_PHOTOS);

/** The footer's line for a backup the app's closing stopped. */
export const BACKUP_STOPPED = 'Backup stopped — the app was closed';

/** "Backup paused at 3,210 of 12,400." (a database alone has no count). */
export const backupPausedLine = ({ done, total }) => (total > 0 ? `Backup paused at ${num(done)} of ${num(total)}.` : 'Backup paused.');

/** What the footer and the card say of a backup that is not running: the stop by closing, else "paused at". */
export const backupWhy = (run) => (run.pausedBy === CLOSED ? BACKUP_STOPPED : backupPausedLine(run));

/** Why a paused backup stopped: the app's closing, its notice, or the user paused it. */
export const backupPausedNotice = (run) => (run.pausedBy === CLOSED
  ? 'The app was closed while this backup was running. It was not carried on by itself.'
  : run.notice || 'You paused a backup.');

/** "Backup finished." with how many photos were left out, when the Backup says any. */
export function finishedLine({ skipped = 0, failed = 0 } = {}) {
  const left = skipped + failed;
  return left > 0 ? `Backup finished. ${left === 1 ? '1 photo' : `${num(left)} photos`} could not be copied.` : BACKUP_FINISHED;
}

/** The stop dialog: "3,210 of 12,400 photos were copied to <path>."; a database alone has no count. */
export function stopLine({ done, total, path }) {
  return total > 0 ? `${num(done)} of ${photos(total)} ${total === 1 ? 'was' : 'were'} copied to ${path}.` : `What was copied so far is in ${path}.`;
}

/**
 * Files Remove kept (they changed since the backup, or could not be reached): "120 files were kept: they had changed
 * since the backup, or couldn't be reached."; '' when none.
 */
export function keptLine({ cancel, notRemoved = 0 }) {
  if (cancel !== 'remove' || notRemoved <= 0) return '';
  return notRemoved === 1
    ? "1 file was kept: it had changed since the backup, or couldn't be reached."
    : `${num(notRemoved)} files were kept: they had changed since the backup, or couldn't be reached.`;
}

export const WHAT_LABELS = { database: DATABASE_ONLY, everything: 'Everything' };

/** The reminder choices (Settings and the first-run dialog) with what each does. */
export const PREFERENCES = [
  { value: 'remind', label: 'Remind me', hint: 'A note on Home when your last backup is 7 or more days old, or there is none.' },
  { value: 'less', label: 'Remind me less often', hint: 'The same note, from 30 days.' },
  { value: 'none', label: 'No backup needed', hint: 'No note on Home. You can still back up from Settings.' },
];

/** "today", "yesterday" or "12 days ago", from the server's whole days since the last backup. */
export const whenWords = (days) => (days <= 0 ? 'today' : days === 1 ? 'yesterday' : `${num(days)} days ago`);

/** "4 photos could not be copied"; '' for none. */
export const unreadLine = (n) => (n > 0 ? `${photos(n)} could not be copied` : '');

/** Settings: "Last backup: 12 days ago — Everything", or "Never backed up". */
export function lastLine({ lastAt, daysSince, lastWhat }) {
  if (!lastAt) return 'Never backed up';
  return `Last backup: ${whenWords(daysSince ?? 0)}${WHAT_LABELS[lastWhat] ? ` — ${WHAT_LABELS[lastWhat]}` : ''}`;
}

/** Home's quiet line: "Last backup: 12 days ago." (with what was left out when anything was) or "Never backed up.". */
export function homeLine({ lastAt, daysSince, lastUnread = 0 }) {
  if (!lastAt) return 'Never backed up.';
  const left = unreadLine(lastUnread);
  return `Last backup: ${whenWords(daysSince ?? 0)}.${left ? ` ${left}.` : ''}`;
}

/** What a past backup was: "Everything, 12,400 photos", "Database and settings only", "Cancelled at 120 of 312 (files kept)". */
export function historyLine(run) {
  if (run.state === CANCELLED) {
    const files = run.cancel === 'remove' ? 'files removed' : 'files kept';
    return run.total > 0 ? `Cancelled at ${num(run.done)} of ${num(run.total)} (${files})` : `Cancelled (${files})`;
  }
  const what = WHAT_LABELS[run.what] ?? run.what;
  return run.what === 'everything' && run.total > 0 ? `${what}, ${photos(run.total)}` : what;
}
