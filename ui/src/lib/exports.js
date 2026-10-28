/**
 * The words of an export (Phase 4b contract §5) from an Export (or the status's ExportStatus): the progress card, the
 * footer, Home, the stop dialog and Past exports. Numbers have thousands separators; sizes are 4a's (`formatBytes`).
 */
import { byteUnit, bytesIn, formatBytes } from './filter.js';
import { CANCELLED } from './states.js';
import { SPECIES_LIST_CSV } from './messages.js';

/** A count with thousands separators: "1,200". */
export const num = (n) => n.toLocaleString();
const photos = (n) => (n === 1 ? '1 photo' : `${num(n)} photos`);
const ofTotal = ({ done, total }) => `${num(done)} of ${photos(total)}`;

export const progressLine = ({ done, total }) => `Exporting photos… ${num(done)} of ${num(total)}`;
export const pausedLine = ({ done, total }) => `Export paused at ${num(done)} of ${num(total)}.`;

/** "1.6 of 4.2 GB": the bytes done in the unit the total is shown in. */
export const sizeLine = ({ bytesDone, bytesTotal }) => `${bytesIn(bytesDone, byteUnit(bytesTotal))} of ${formatBytes(bytesTotal)}`;

/** Home's paused card: "3,200 of 50,000 photos copied to <path>." */
export const copiedLine = (run) => `${ofTotal(run)} copied to ${run.path}.`;
/** The stop dialog: "3,200 of 50,000 photos were copied to <path>." */
export const stopLine = (run) => `${ofTotal(run)} ${run.total === 1 ? 'was' : 'were'} copied to ${run.path}.`;
/** Why a paused export stopped: its notice, or the user paused it. */
export const pausedNotice = (run) => run.notice || 'You paused an export.';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const pad = (n) => String(n).padStart(2, '0');

/** "13 Oct 2026, 21:40" in this computer's time ('' for a bad date). The same words whatever the system language. */
export function exportDate(iso) {
  const at = iso ? new Date(iso) : null;
  if (!at || Number.isNaN(at.getTime())) return '';
  return `${at.getDate()} ${MONTHS[at.getMonth()]} ${at.getFullYear()}, ${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

/** What a past export was: "312 photos and species list", "Species list (CSV)", "312 photos", "Cancelled at 120 of 312 (files kept)". */
export function historyLine(run) {
  if (run.state === CANCELLED) {
    return `Cancelled at ${num(run.done)} of ${num(run.total)} (${run.cancel === 'remove' ? 'files removed' : 'files kept'})`;
  }
  if (run.what === 'csv') return SPECIES_LIST_CSV;
  return run.what === 'both' ? `${photos(run.total)} and species list` : photos(run.total);
}

/** "3 skipped, 1 failed" (and "2 renamed"), leaving out kinds with none; '' when there were no problems. */
export function problemsLine({ skipped = 0, failed = 0, renamed = 0 }) {
  return [[skipped, 'skipped'], [failed, 'failed'], [renamed, 'renamed']].filter(([n]) => n > 0).map(([n, word]) => `${num(n)} ${word}`).join(', ');
}

export const PROBLEM_KINDS = { skipped: 'Skipped', failed: 'Failed', renamed: 'Renamed' };
export const moreProblemsLine = (n) => `and ${num(n)} more`;

/**
 * Files Remove kept (they changed since the export, or could not be reached): "120 files were kept: they had changed
 * since the export, or couldn't be reached."; '' when none.
 */
export function notRemovedLine({ cancel, notRemoved = 0 }) {
  if (cancel !== 'remove' || notRemoved <= 0) return '';
  return notRemoved === 1
    ? "1 file was kept: it had changed since the export, or couldn't be reached."
    : `${num(notRemoved)} files were kept: they had changed since the export, or couldn't be reached.`;
}

/**
 * The sheet's space line, "4.2 GB needed, 310.0 GB free": the photos' size (the server's margin on top stays its own;
 * too little space comes as the check's problem); '' for a species list alone (`neededBytes` 0: no space line). Free
 * space the server could not read (it sends Long.MAX_VALUE) is left out: "4.2 GB needed".
 */
export function spaceLine(check) {
  if (!check?.neededBytes) return '';
  const needed = `${formatBytes(check.bytes)} needed`;
  return check.freeBytes >= Number.MAX_SAFE_INTEGER ? needed : `${needed}, ${formatBytes(check.freeBytes)} free`;
}
