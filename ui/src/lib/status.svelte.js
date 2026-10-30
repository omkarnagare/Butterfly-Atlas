import { checkHeic, getBackup, getExport, getStatus } from './api.js';
import { holdToasts, showToast } from './toast.svelte.js';
import { briefOf, pausedNoticeOf } from './updates.js';
import { finishedLine } from './backups.js';
import { UPDATE_HREF } from './route.js';
import { LIBRARY_PROBLEM } from './events.js';
import { LIBRARY_CHANGING } from './codes.js';
import { exactExportedLine } from './raw.js';
import { checkResult } from './heic.js';
import { CANCELLED, DECODER_CHECKING, DECODER_MISSING, DONE, PAUSED, RUNNING, WAITING } from './states.js';
import { EXPORT_FINISHED } from './messages.js';
import { serverTheme } from './theme.svelte.js';

/**
 * What the footer shows (GET /api/status): the startup photo check with its progress, photo details
 * being caught up (`processing`, null when idle; older servers leave it out), the import that is looking
 * through, reading or filing a folder (`import`, null when none), imports cut short by the app closing
 * (`stoppedImports`), the running or paused export (`export`, null when none), previews still being made, and how
 * many photos wait in Recently deleted. When a check finishes while the app is open, a toast says what it found and,
 * if anything changed, LIBRARY_CHANGED is sent on window so the open screen reloads its data in place. When a running
 * export finishes while the app is open, a toast says "Export finished."; one cancelled meanwhile from another window
 * says so instead (several-windows G2).
 *
 * Phase 5b: `taxonomyUpdate` (the update pending, waiting, running, paused or done in this run; null when none),
 * `splitsToSort` (split species with photos still to sort) and `customMatches` (open matches). When a running or
 * waiting update ends while the app is open, a toast says so ("Taxonomy update finished.", or "Custom species merged."
 * for a match) and LIBRARY_CHANGED is sent, since names, groups and files have changed.
 *
 * Phase 5c: `placeUpdate`, contract §9–§11: `{ note, formerPhotos, notChecked, pending, moved, lost, rechecking, failed }`
 * — `note` Home's place note is to show (not dismissed); `formerPhotos` live photos in former places; `notChecked` photos
 * the re-check couldn't do; `pending` photos not checked yet; `moved` photos the re-check has moved; `lost` photos it
 * left without a place since the last Dismiss; `rechecking` from the library's opening until this run's places step has
 * finished the newest list's re-check; `failed` the note's counts couldn't be read. Null only when every count is 0 and
 * every flag false.
 *
 * Phase 7b: `heic`, the computer's HEIC decoder (`{ state: 'checking' | 'available' | 'missing', decoder, system, reason }`,
 * null from an older server), and `rescanLeft`, the files the last rescan did not add grouped by reason
 * (`[{ reason, count }]`, empty when none or dismissed). When `heic.state` changes between two answers (or with Check
 * again's), HEIC_CHANGED and LIBRARY_CHANGED are sent: photos' `unreadable` and import previews follow it (audit 1).
 * `importPreviews` is the open import's `heic` summary (`{ count, unshowable, making }`, null when none), set by its
 * page so the footer counts its previews being made (audit 10).
 *
 * Several windows (contract §10 A8): `libraryVersion`, bumped by the server on every photo-changing write in any window.
 * When it moves after the first answer, LIBRARY_CHANGED is sent, so screens showing photos read them again.
 *
 * Meadow: `theme`, the saved Light or Dark (null: none), goes to lib/theme.svelte.js, so a choice made in another window
 * shows here within a poll; a status without it (an older server) leaves the theme as it is.
 */
export const LIBRARY_CHANGED = 'atlas:library-changed';
/** Sent on window by "Continue import" when that import's grid is already open (its address would not change). */
export const CONTINUE_IMPORT = 'atlas:continue-import';
/**
 * Sent on window (detail: the Export answered) after this page paused, resumed or stopped an export, so the open screen
 * reloads its exports.
 */
export const EXPORT_CHANGED = 'atlas:export-changed';
/**
 * Phase 6: sent on window (detail: the Backup answered, or null) after this page paused, resumed, stopped or started a
 * backup, and when the status sees a running one leave: the open screen reads its backups again. `status.backup` is the
 * running or paused backup (`{ id, state, done, total, pausedBy, notice, path }`), null when none; the footer shows its
 * progress, and a running one that ends while the app is open is said in a toast (with what could not be copied).
 * Several windows (A6): `status.backup.holdsPhotos`, true while a backup of Everything refuses photo changes.
 */
export const BACKUP_CHANGED = 'atlas:backup-changed';
/** Phase 7b: sent on window (detail: `{ from, to }`, the states) when the HEIC decoder's state changes. */
export const HEIC_CHANGED = 'atlas:heic-changed';
/**
 * Phase 7b audit 19: sent on window when previews still being made reach none, or the HEIC decoder's state changes, so
 * thumbnails still showing a placeholder ask once more (lib/thumbRetry.js).
 */
export const THUMBS_AGAIN = 'atlas:thumbs-again';

export const status = $state({
  rescanning: false, rescan: null, processing: null, import: null, stoppedImports: [], export: null, backup: null, previews: { pending: 0, done: 0 }, deletedCount: 0,
  taxonomyUpdate: null, splitsToSort: 0, customMatches: 0, placeUpdate: null, heic: null, rescanLeft: [], importPreviews: null,
  // Check again (review fix U3): whether one is on its way, what the last one found (`result`, its words, for `state`),
  // and whether one is still followed (asked, or answered `checking`): the bar it started from stays meanwhile.
  heicCheck: { running: false, result: '', state: null, active: false },
  // After a Retry that did not take (409 not_resumed, or the same pause back at once): `{ at: 'HH:MM', message }`.
  retryNote: null,
  // Home's "The taxonomy was updated" card was dismissed (for this run of the page).
  doneDismissed: false,
  // The kind of the last update or merge seen to finish in this page load (`{ kind }`), else null.
  finished: null,
  // Three status reads in a row have failed (and none has answered since).
  unreachable: false,
  // The library's drive is full or gone (`{ code, message }`), else null: a banner on every screen.
  library: null,
  // How many answers have been applied in this page load (one that failed, or was overtaken by a newer ask, is not).
  answers: 0,
  // Change the library folder (contract §2): the change running or waiting to be dismissed, else null; every window
  // shows its screen in place of the page. `libraryMoved` (`{ to }`): Home's one-time note after a move, else null.
  changing: null,
  libraryMoved: null,
});

let seq = 0;
/** A Retry taken by the server, until the next answer: `{ code, at }`. */
let retried = null;
let failedReads = 0;
/** What the footer and the launch line say while the status can't be read. */
export const NOT_ANSWERING = "Butterfly Atlas isn't answering. If you closed it, open it again — the update carries on where it stopped.";
/** The same while other work (a check, an import, an export) is what the footer follows. */
export const NOT_ANSWERING_PLAIN = "Butterfly Atlas isn't answering. If you closed it, open it again.";
/** The export this page is stopping (its cancel not answered yet): a poll that sees it gone first is not "Export finished.". */
let stoppingId = null;
/** The backup this page is stopping, as `stoppingId` is for an export: a poll that sees it gone first is not "Backup finished.". */
let stoppingBackupId = null;
/** That backup, when a poll saw it leave while running: finished after all if the stop then fails (backup_finished). */
let backupLeftWhileStopping = null;
/** That export (as the status had it), when a poll saw it leave while running: finished after all if the stop then fails. */
let leftWhileStopping = null;
/** When this page loaded, and the finishedAt of the last check seen (undefined until the first answer). */
let loadedAt = Date.now();
let lastFinishedAt;
/** The last answer's libraryVersion (undefined until the first answer; null from an older server). */
let libraryVersion;
/** The libraryVersion moved while an import was filing: said once, when filing has left (not on every poll). */
let movedWhileFiling = false;

/** Test-only: back to the idle state, so tests do not depend on each other's leftovers. */
export function resetStatus() {
  seq++;
  status.rescanning = false;
  status.rescan = null;
  status.processing = null;
  status.import = null;
  status.stoppedImports = [];
  status.export = null;
  status.backup = null;
  stoppingBackupId = null;
  backupLeftWhileStopping = null;
  stoppingId = null;
  movedWhileFiling = false;
  leftWhileStopping = null;
  status.previews = { pending: 0, done: 0 };
  status.deletedCount = 0;
  status.taxonomyUpdate = null;
  status.splitsToSort = 0;
  status.customMatches = 0;
  status.placeUpdate = null;
  status.heic = null;
  status.rescanLeft = [];
  status.importPreviews = null;
  status.heicCheck = { running: false, result: '', state: null, active: false };
  status.answers = 0;
  status.library = null;
  status.retryNote = null;
  status.doneDismissed = false;
  status.finished = null;
  status.unreachable = false;
  status.changing = null;
  status.libraryMoved = null;
  holdToasts(false);
  failedReads = 0;
  retried = null;
  loadedAt = Date.now();
  lastFinishedAt = undefined;
  libraryVersion = undefined;
}
const photos = (n) => (n === 1 ? '1 photo' : `${n} photos`);

/** Plain words for a finished check, or '' when it changed nothing. */
export function rescanMessage({ added = 0, removed = 0, updated = 0 } = {}) {
  const parts = [];
  if (added) parts.push(`Found ${added === 1 ? '1 new photo' : `${added} new photos`} in your folders.`);
  if (removed) parts.push(`Removed ${photos(removed)} that ${removed === 1 ? 'was' : 'were'} deleted outside Butterfly Atlas.`);
  if (updated) parts.push(`Updated ${photos(updated)} that ${updated === 1 ? 'was' : 'were'} renamed or edited.`);
  return parts.join(' ');
}

/** Asks the server again; pages call it after deleting, restoring, emptying or adding. Failures leave the footer as it was. */
export async function refreshStatus() {
  // The app has quit to reopen on the new library folder (contract §2 `reopening`): the window keeps that page, and
  // asks nothing more (the service is gone, or is another copy).
  if (reopening()) return;
  const mine = ++seq;
  let next;
  try {
    next = await getStatus();
  } catch {
    // Three failed reads in a row: Butterfly Atlas isn't answering (said while something works, contract §7).
    failedReads += 1;
    status.unreachable = failedReads >= 3;
    return;
  }
  failedReads = 0;
  status.unreachable = false;
  if (mine !== seq || !next) return;
  // A check can end between two answers (a short one, or a slow poll): its finishedAt gives it away.
  const finishedAt = next.rescan?.finishedAt ?? null;
  const time = finishedAt ? Date.parse(finishedAt) : NaN;
  const endedMeanwhile = lastFinishedAt === undefined
    ? time > loadedAt
    : finishedAt !== null && finishedAt !== lastFinishedAt && time > (lastFinishedAt ? Date.parse(lastFinishedAt) : -Infinity);
  lastFinishedAt = finishedAt;
  const finished = (status.rescanning && next.rescanning !== true) || (next.rescanning !== true && endedMeanwhile);
  status.rescanning = next.rescanning === true;
  status.rescan = next.rescan ?? null;
  status.processing = next.processing ?? null;
  status.import = next.import ?? null;
  status.stoppedImports = next.stoppedImports ?? [];
  const before = status.export;
  status.export = next.export ?? null;
  // A running export is no longer current: it finished (one this page stopped had already left the status; one it is
  // stopping waits for the stop's answer), or another window cancelled it (G2): read to tell which, said once read.
  let exportEnd = null;
  if (before?.state === RUNNING && status.export?.id !== before.id) {
    if (before.id !== stoppingId) exportEnd = exportEnded(before);
    else leftWhileStopping = before;
  }
  const backupBefore = status.backup;
  status.backup = next.backup ?? null;
  // A running backup is no longer current: it finished (one this page stopped, or one that paused, is said otherwise).
  if (backupBefore?.state === RUNNING && status.backup?.id !== backupBefore.id) {
    if (backupBefore.id !== stoppingBackupId) backupEnded(backupBefore.id);
    else backupLeftWhileStopping = backupBefore.id;
  }
  const pendingBefore = status.previews.pending;
  status.previews = next.previews ?? { pending: 0, done: 0 };
  status.rescanLeft = next.rescanLeft ?? [];
  const heicMoved = applyHeic(next.heic ?? null);
  if (!heicMoved && status.answers > 0 && pendingBefore > 0 && status.previews.pending === 0) window.dispatchEvent(new CustomEvent(THUMBS_AGAIN));
  status.deletedCount = next.deletedCount ?? 0;
  const updateBefore = status.taxonomyUpdate;
  status.taxonomyUpdate = next.taxonomyUpdate ?? null;
  status.splitsToSort = next.splitsToSort ?? 0;
  status.customMatches = next.customMatches ?? 0;
  status.placeUpdate = next.placeUpdate ?? null;
  if (next.theme !== undefined) serverTheme(next.theme);
  status.changing = next.changing ?? null;
  status.libraryMoved = next.libraryMoved ?? null;
  holdToasts(status.changing !== null);
  const libraryBefore = status.library;
  status.library = next.library ?? null;
  // The library's drive is back: every screen whose load failed reads again (LIBRARY_CHANGED).
  if (libraryBefore && !status.library) window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  // Photos changed (in this window or another, A8): the open screen reads them again.
  const versionBefore = libraryVersion;
  libraryVersion = Number.isInteger(next.libraryVersion) ? next.libraryVersion : null;
  const moved = versionBefore != null && libraryVersion != null && libraryVersion !== versionBefore;
  // While an import files the version moves with every photo filed: the screens read again once, when filing has left.
  if (status.import?.phase === 'filing') {
    if (moved) movedWhileFiling = true;
  } else if (moved || movedWhileFiling) {
    movedWhileFiling = false;
    window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  }
  status.answers += 1;
  // A Retry the server took but whose pause came back with the same code (whether or not an answer in between saw it
  // running) did not take either: it gives the retry note, not the pause's announcement. The Retry is followed until
  // the update pauses or ends.
  const pausedNow = status.taxonomyUpdate?.state === PAUSED ? status.taxonomyUpdate : null;
  const samePauseBack = Boolean(retried && pausedNow && pausedNow.notice?.code === retried.code);
  // The note belongs to its pause: it goes when the update runs again or pauses for another reason. The same pause
  // back is said without its notice again (the notice is beside it).
  if (samePauseBack) {
    status.retryNote = { at: retried.at, message: null, code: retried.code };
  } else if (!pausedNow || (status.retryNote && status.retryNote.code !== (pausedNow.notice?.code ?? null))) {
    status.retryNote = null;
  }
  const stillGoing = status.taxonomyUpdate?.state === RUNNING || status.taxonomyUpdate?.state === WAITING;
  if (!stillGoing) retried = null;
  // A running or waiting update that is neither any more nor paused has finished: names, groups and files changed.
  const working = (u) => u?.state === RUNNING || u?.state === WAITING;
  if (working(updateBefore) && !working(status.taxonomyUpdate) && status.taxonomyUpdate?.state !== PAUSED) {
    // A later finish in the same run shows Home's card again, even after Dismiss. Whatever the status now says (done,
    // or pending when a merge finished while a bundle update waits), the finish is kept for the card.
    status.doneDismissed = false;
    status.finished = { kind: updateBefore.kind ?? 'bundle' };
    showToast(updateBefore.kind === 'match' ? 'Custom species merged.' : 'Taxonomy update finished.',
      { link: { href: UPDATE_HREF, label: 'See what changed' } });
    window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  }
  // One that pauses is announced, with a link to where it says why (contract §7).
  if (working(updateBefore) && status.taxonomyUpdate?.state === PAUSED && !samePauseBack) {
    showToast(`The taxonomy update paused: ${pausedNoticeOf(status.taxonomyUpdate)}`, { link: { href: UPDATE_HREF, label: 'Taxonomy update' } });
  }
  const message = finished ? rescanMessage(next.rescan ?? {}) : '';
  if (message) {
    showToast(message);
    window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  }
  await exportEnd;
}

/** Phase 7a: RAW photos (7b: and HEIC photos) went out with their location; the finished notice says how many. */
const exportFinished = (run) => showToast([EXPORT_FINISHED, exactExportedLine(run.rawCount, run.heicCount)].filter(Boolean).join(' '));

/**
 * An export the status saw leave while running, that this page did not stop: cancelled in another window (G2), or
 * finished. One that can't be read is taken as finished, as before there were several windows.
 */
async function exportEnded(before) {
  let run = null;
  try {
    run = await getExport(before.id);
  } catch {
    // finished, in the plain words below
  }
  if (run?.state === 'cancelled') showToast('The export was cancelled in another window.');
  else exportFinished(before);
}

/**
 * Takes the HEIC decoder's state (a status answer's, or Check again's). A change after the first answer tells the open
 * screens (HEIC_CHANGED, LIBRARY_CHANGED) and the placeholder thumbnails (THUMBS_AGAIN). Whether it changed.
 */
function applyHeic(heic) {
  const from = status.heic?.state ?? null;
  status.heic = heic;
  const to = heic?.state ?? null;
  // A Check again result said for another state is out of date (one on its way keeps its words until it answers).
  const check = status.heicCheck;
  if (!check.running && check.state !== null && check.state !== to) status.heicCheck = { running: false, result: '', state: null, active: false };
  if (status.answers === 0 || from === to) return false;
  window.dispatchEvent(new CustomEvent(HEIC_CHANGED, { detail: { from, to } }));
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  window.dispatchEvent(new CustomEvent(THUMBS_AGAIN));
  return true;
}

/**
 * Check again (Settings → About, the import and gallery bars): the decoder looked for afresh; its HeicStatus, taken at
 * once (the footer goes on following a check still running). Throws the ApiError when it can't be asked.
 */
export async function checkHeicAgain() {
  status.heicCheck = { running: true, result: '', state: null, active: true };
  let heic;
  try {
    heic = await checkHeic();
  } catch (e) {
    status.heicCheck = { running: false, result: '', state: null, active: false };
    throw e;
  }
  status.heicCheck = { running: false, result: checkResult(heic), state: heic?.state ?? null, active: heic?.state === DECODER_CHECKING };
  applyHeic(heic ?? null);
  return heic;
}

/**
 * Whether a "can't show HEIC photos" bar shows: while the decoder is missing, and while a Check again started from one is
 * followed (the server says `checking` meanwhile; review fix U3).
 */
export const heicBarOn = () => status.heic?.state === DECODER_MISSING || (status.heic?.state === DECODER_CHECKING && status.heicCheck.active);

/** The open import's HEIC previews (`summary.heic`, or null when it has none or is closed), for the footer. */
export function setImportPreviews(heic) {
  status.importPreviews = heic ?? null;
}

/**
 * After this page paused, resumed or stopped an export (`run`, the Export answered): the status follows the answer at
 * once (a stopped export is no longer current, so its end is not taken for one that finished), asks again, and
 * EXPORT_CHANGED tells the open screen.
 */
export function exportChanged(run) {
  const { id, state, done, total, pausedBy = null, notice = null, path, rawCount = 0, heicCount = 0 } = run;
  if (id === stoppingId) {
    stoppingId = null;
    leftWhileStopping = null;
  }
  status.export = state === RUNNING || state === PAUSED ? { id, state, done, total, pausedBy, notice, path, rawCount, heicCount } : null;
  refreshStatus();
  window.dispatchEvent(new CustomEvent(EXPORT_CHANGED, { detail: run }));
}

/** A backup the poll saw end: its toast says whether photos were left out (read from the backup; plain when it can't be). */
async function backupEnded(backupId) {
  let run = null;
  try {
    run = await getBackup(backupId);
  } catch {
    // the plain words below; Settings has the details
  }
  announceBackup(run);
}

function announceBackup(run) {
  window.dispatchEvent(new CustomEvent(BACKUP_CHANGED, { detail: run }));
  // A backup that was cancelled meanwhile (from another window) is not "finished".
  if (run?.state === CANCELLED) return;
  const left = (run?.skipped ?? 0) + (run?.failed ?? 0);
  showToast(finishedLine(run ?? {}), left > 0 ? { link: { href: '#/settings', label: 'See which' } } : {});
}

/**
 * After this page started, paused, resumed or stopped a backup (`run`, the Backup answered): the status follows the
 * answer at once (a stopped backup is no longer current, so its end is not taken for one that finished), asks again, and
 * BACKUP_CHANGED tells the open screen. A backup already done in the answer (a database alone is quick) is said at once.
 */
export function backupChanged(run) {
  const { id, state, done, total, pausedBy = null, notice = null, path } = run;
  if (id === stoppingBackupId) {
    stoppingBackupId = null;
    backupLeftWhileStopping = null;
  }
  // Whether it holds the photos is the status's to say: kept from it for the same backup until it answers again.
  const holdsPhotos = run.holdsPhotos ?? (status.backup?.id === id ? status.backup.holdsPhotos === true : false);
  status.backup = state === RUNNING || state === PAUSED ? { id, state, done, total, pausedBy, notice, path, holdsPhotos } : null;
  refreshStatus();
  if (state === DONE) announceBackup(run);
  else window.dispatchEvent(new CustomEvent(BACKUP_CHANGED, { detail: run }));
}

/** The current backup changed in a way no answer says (a stop refused as already finished): ask again, and tell the open screen. */
export function backupGone() {
  refreshStatus();
  window.dispatchEvent(new CustomEvent(BACKUP_CHANGED, { detail: null }));
}

/**
 * This page is stopping the backup `backupId` (null: the stop failed, or none). Its end is then not said as finished,
 * unless the stop fails after a poll saw it leave while running: then it finished, and is said so now (seen later, the
 * poll says it).
 */
export function backupStopping(backupId) {
  const left = backupLeftWhileStopping;
  backupLeftWhileStopping = null;
  stoppingBackupId = backupId;
  if (backupId === null && left !== null) backupEnded(left);
}

/** The current export changed in a way no answer says (a stop refused as already finished): ask again, and tell the open screen. */
export function exportGone() {
  refreshStatus();
  window.dispatchEvent(new CustomEvent(EXPORT_CHANGED, { detail: null }));
}

/**
 * This page is stopping the export `exportId` (null: the stop failed, or none). Its end is then not said as finished,
 * unless the stop fails after a poll saw it leave while running: then it finished, and is said so now (seen later,
 * the poll says it).
 */
export function exportStopping(exportId) {
  if (exportId === null && leftWhileStopping !== null) exportFinished(leftWhileStopping);
  leftWhileStopping = null;
  stoppingId = exportId;
}

/** The change of library folder has reached `reopening`: the app is quitting, to reopen or for the user to open again. */
export const reopening = () => status.changing?.step === 'reopening';

/**
 * This page started a move or a switch of the library folder: the change (the 202 answer, `{ changing }` or the change
 * itself) shows at once, and the status is asked.
 */
export function changeStarted(answer) {
  const changing = answer && 'changing' in answer ? answer.changing : answer;
  if (changing) status.changing = changing;
  refreshStatus();
}

/** After this page applied, retried or merged (`update`, the Update answered): the status follows the answer at once and asks again. */
export function updateChanged(update) {
  status.taxonomyUpdate = briefOf(update);
  refreshStatus();
}

const clock = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
/** The Retry of a paused update whose code was `code` was taken: the next answer tells whether it paused again at once. */
export function retrySent(code) {
  retried = { code, at: clock(new Date()) };
}
/**
 * The Retry was refused (a 409: not_resumed, or a pause's own code such as drive_full) for the pause whose code is
 * `code`: its message, under the notice, until the update runs again or pauses for another reason.
 */
export function retryRefused(message, code) {
  status.retryNote = { at: clock(new Date()), message, code };
}

// A request refused because the library's drive is full or gone: read the status at once, so the banner shows. One refused
// because the library folder is changing holds toasts at once (its refusal is not shown), until the status says otherwise.
if (typeof window !== 'undefined') {
  window.addEventListener(LIBRARY_PROBLEM, (event) => {
    if (event.detail?.code === LIBRARY_CHANGING) holdToasts(true);
    refreshStatus();
  });
}
