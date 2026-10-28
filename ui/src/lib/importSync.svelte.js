import { getImport, getImportItems } from './api.js';
import { mergePage } from './importItems.js';
import { followed } from './importView.js';
import { filingOver } from './importFinish.svelte.js';
import { showProblem } from './toast.svelte.js';
import { IMPORT_OPEN } from './states.js';
import {
  FOLDER_MISSING, IMPORT_CHANGED, IMPORT_FILING, IMPORT_FINISHED, NOTHING_TO_FILE, PAIR_CHOICE_LOCKED, PAIR_CHOICE_NEEDED,
} from './codes.js';

/**
 * Keeps one import's page (screens/ImportRun.svelte) in step with the app: one change in flight at a time, answers
 * for an import no longer on screen dropped, poll answers applied in order, and a catch-up when a refusal shows the
 * page is out of date. `run` is the page's state, read and written through accessors; `run.filingEnded` and
 * `run.loadResult` come from importFinish.svelte.js.
 *
 * Several windows (contract §5, A2/A3, A20): `run.revision` is the `revision` the page's picture of the import is at
 * (what a read carried, moved on by each change's answer that follows it), sent with File; File is refused with
 * `import_changed` when another window changed the import since. Its words then stay in the import's notice area (`run.changedNote`) until the next File.
 */

/** Refusals that mean this page's picture of the import is out of date: ask for it again. */
export const RESYNC = new Set([FOLDER_MISSING, IMPORT_FINISHED, IMPORT_FILING, NOTHING_TO_FILE, PAIR_CHOICE_NEEDED, PAIR_CHOICE_LOCKED, IMPORT_CHANGED]);

export function importSync(run) {
  let edits = 0; // the user's saved changes, so a poll answer older than one of them is not merged
  // Polls and catch-ups take a ticket as they ask; an answer to an older ticket than one already applied is dropped,
  // so it can't put back items (or a version) a newer answer moved on.
  let asked = 0;
  let applied = 0;
  const ticket = () => ++asked;
  const fresh = (t) => (t > applied ? ((applied = t), true) : false);

  /**
   * A change's answer (assign, tags, place, skip, accept-suggestions, pairs, relocate, File) carries `revision` (after the
   * change) and `previousRevision` (just before it). Its revision is kept only when it follows the one held (A20):
   * otherwise another window changed the import in between, unseen here, so the held one stays and File is refused with
   * `import_changed` (the grid then reads again). A late answer to an older change fails the same test.
   */
  const took = (answered) => {
    const revision = answered?.revision;
    if (Number.isInteger(revision) && run.revision != null && answered.previousRevision === run.revision) run.revision = revision;
  };

  /**
   * A read of the import (load, poll, catch-up) keeps the revision its items answer carries, read under the same lock as
   * the items (A21): that is what the user sees. Read together with the summary, it keeps the lower of the two, so a
   * change one of them saw and the other didn't is never taken as seen. Items alone (after relocate) keep theirs.
   */
  const read = (page, s = null) => {
    const revisions = [page?.revision, s?.revision].filter(Number.isInteger);
    if (revisions.length) run.revision = Math.min(...revisions);
  };

  /** One of the user's changes was saved. */
  const edited = () => {
    edits += 1;
  };

  /** Taken as a poll asks (pollImport's `begin`), and handed back to `answer` with what it got. */
  const begin = () => ({ editsBefore: edits, t: ticket() });

  /** Applies a poll's answer (pollImport's `answer`); says whether to go on polling. */
  function answer(s, page, { editsBefore, t }) {
    if (!fresh(t)) return followed(run.summary);
    // An answer asked for before one of the user's own changes was saved may hold those photos as they were:
    // drop it (keeping the version), so the next poll asks again from the same point and gets them as they are.
    // An idle summary from a dropped answer is not applied either, or polling would end before that poll.
    const ended = filingOver(run.summary, s);
    if (edits === editsBefore) {
      run.summary = s;
      read(page, s);
      run.data = mergePage(run.data, page);
      if (ended) run.filingEnded(s);
    } else if (followed(s)) {
      run.summary = s;
      if (ended) run.filingEnded(s);
    }
    return followed(run.summary);
  }

  /** Asks for the summary and the items changed since the last answer, after a refusal showed they are out of date. */
  async function resync(wanted) {
    const { editsBefore, t } = begin();
    try {
      const [s, page] = await Promise.all([getImport(wanted), getImportItems(wanted, run.data.version)]);
      if (!run.current(wanted) || !fresh(t)) return;
      const ended = filingOver(run.summary, s);
      // As a poll's answer: one of this page's own changes saved meanwhile is newer than what this read brought, so its
      // items and revision are dropped (the change's answer already moved them on); a later poll or refusal reads again.
      // Its summary still counts while the import works or once it is finished.
      if (edits === editsBefore) {
        run.summary = s;
        run.data = mergePage(run.data, page);
        read(page, s);
      } else if (followed(s) || s.state !== IMPORT_OPEN) {
        run.summary = s;
      } else {
        return;
      }
      if (ended && s.state === IMPORT_OPEN) run.filingEnded(s);
      if (s.state !== IMPORT_OPEN) run.loadResult();
    } catch {
      // The next action or poll tries again.
    }
  }

  /** Says why an action failed, as the server words it, and catches up when the refusal shows the page is out of date. */
  function failed(e, wanted) {
    if (!run.current(wanted)) return;
    // Another window changed the import: said where it stays, above the grid, not in a passing toast as well.
    if (e.code === IMPORT_CHANGED) run.changedNote = e.message;
    else showProblem(e);
    if (RESYNC.has(e.code)) resync(wanted);
  }

  /** Runs `work` as the one action in flight for this import; its answer is dropped if another import opened meanwhile. */
  async function act(work) {
    if (run.busy) return;
    const wanted = run.id;
    run.busy = true;
    try {
      await work(wanted);
    } catch (e) {
      failed(e, wanted);
    } finally {
      if (run.current(wanted)) run.busy = false;
    }
  }

  return { ticket, fresh, took, read, edited, begin, answer, resync, failed, act };
}
