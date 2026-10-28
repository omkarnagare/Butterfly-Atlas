import { discardImport, fileImport, finishImport, getImportResult, pauseImport, putImportPairs } from './api.js';
import { reducedMotion } from './motion.js';
import { showToast } from './toast.svelte.js';
import { LIBRARY_CHANGED, refreshStatus } from './status.svelte.js';

/**
 * Filing an import and finishing it (design §4.5), for screens/ImportRun.svelte: File and Pause, the result with
 * one celebration for all the new species when filing ends, Finish… and Finish, and removing a finished import.
 * `run` is the page's state, read and written through accessors; `run.act` comes from importSync.svelte.js.
 */

/**
 * Whether filing ended between the summary shown and the one answered: it was filing and is no longer, or a filing
 * began since (`filings` grew) and is over already. A small filing can start and end inside one poll interval
 * (reading gives way to it and carries on), and must still show its result.
 */
export function filingOver(before, s) {
  if (!before || before.id !== s.id || s.phase === 'filing') return false;
  return before.phase === 'filing' || (s.filings ?? 0) > (before.filings ?? 0);
}

export function importFinish(run) {
  // File sends the latest revision this page received, never reading the summary again first (that would pass over
  // another window's change unseen, A2); pressing it takes away the last import_changed note.
  const file = () => run.act(async (wanted) => {
    run.changedNote = '';
    const s = await fileImport(wanted, run.revision);
    if (!run.current(wanted)) return;
    // A small import can be filed before File answers: its result shows, as when a poll sees filing end.
    const ended = filingOver(run.summary, s);
    run.summary = s;
    run.took(s);
    run.pausing = false;
    if (ended) {
      filingEnded(s);
      return;
    }
    refreshStatus();
  });

  /** How RAW + JPEG pairs are filed (Phase 7a): saved at once; the summary says the choice back. */
  const choosePairs = (choice) => run.act(async (wanted) => {
    const s = await putImportPairs(wanted, choice);
    if (!run.current(wanted)) return;
    run.summary = s;
    run.took(s);
  });

  // Pause lasts until filing has stopped: Pause reads "Pausing…" until then.
  // Filing may have ended before the pause reached it: then its result shows, as when a poll sees it end.
  const pause = () => run.act(async (wanted) => {
    const s = await pauseImport(wanted);
    if (!run.current(wanted)) return;
    const ended = filingOver(run.summary, s);
    run.summary = s;
    run.took(s);
    if (ended) {
      filingEnded(s);
      return;
    }
    run.pausing = true;
    showToast('Filing will pause after the photos it is filing now.');
  });

  async function loadResult() {
    const wanted = run.id;
    try {
      const r = await getImportResult(wanted);
      if (!run.current(wanted)) return;
      run.result = r;
      run.resultError = '';
    } catch (e) {
      if (run.current(wanted)) run.resultError = e.message;
    }
  }

  /**
   * Filing finished: show what it did, celebrating the species its run added (`lastFiling`). The celebration
   * comes first and alone, and the result opens when it closes: two overlays opened at once would each make
   * the other inert. Under reduced motion a toast says it and the result opens at once.
   */
  async function filingEnded(s) {
    const wanted = run.id;
    run.pausing = false;
    refreshStatus();
    window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
    // A pause, or a stop for a reason in the library (its notice shows), is not the end of filing.
    if (s.paused || s.notice) return;
    await loadResult();
    if (!run.current(wanted)) return;
    if (!run.result) {
      if (run.resultError) showToast(run.resultError);
      return;
    }
    const last = run.result.lastFiling;
    if (last?.newSpecies && !reducedMotion()) {
      run.celebrating = { count: last.speciesCount, added: last.newSpecies, milestone: last.milestone };
      return;
    }
    if (last?.newSpecies) {
      const milestone = last.milestone ? ` Milestone reached: ${last.milestone.toLocaleString()} species.` : '';
      showToast(`${last.newSpecies === 1 ? '1 new species' : `${last.newSpecies.toLocaleString()} new species`} photographed!${milestone}`);
    }
    run.showingResult = true;
  }

  async function openResult() {
    await loadResult();
    if (run.result) run.showingResult = true;
    else if (run.resultError) showToast(run.resultError);
  }

  /** Finish: what still waits is marked skipped (left in the folder); then back to the imports. */
  async function finish() {
    if (run.busy) return;
    const wanted = run.id;
    run.busy = true;
    run.resultError = '';
    try {
      await finishImport(wanted, run.result.left > 0);
      if (!run.current(wanted)) return;
      run.showingResult = false;
      showToast(`The import of ${run.summary.rootName} is finished.`);
      refreshStatus();
      window.location.hash = '#/import';
    } catch (e) {
      if (run.current(wanted)) run.resultError = e.message;
    } finally {
      if (run.current(wanted)) run.busy = false;
    }
  }

  const removeFinished = () => run.act(async (wanted) => {
    await discardImport(wanted);
    if (!run.current(wanted)) return;
    showToast(`${run.summary.rootName} was removed from your imports.`);
    refreshStatus();
    window.location.hash = '#/import';
  });

  return { file, choosePairs, pause, loadResult, filingEnded, openResult, finish, removeFinished };
}
