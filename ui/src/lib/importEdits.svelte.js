import { tick } from 'svelte';
import { acceptSuggestions, assignItems, placeItems, skipItems, tagItems, unplaceItems } from './api.js';
import { mergePage } from './importItems.js';
import { RESYNC } from './importSync.svelte.js';
import { photoCount } from './importView.js';
import { showProblem, showToast } from './toast.svelte.js';
import { WAIT_FOR_SAVE } from './messages.js';

/**
 * Changes to an import's photos, for screens/ImportRun.svelte: assign a species, tags, a place (or take it off),
 * skip or file again the selected photos; accept a section's suggestions; skip what is already in the library.
 * Every change is saved at once. `run` is the page's state, read and written through accessors; `run.edited` and
 * `run.resync` come from importSync.svelte.js.
 */
export function importEdits(run) {
  /**
   * Saves a change and merges the items it changed; a failure is thrown to the caller (an open dialog shows it).
   * A focused photo the change takes out of the filter hands focus to the next one still shown (else the one before;
   * with none shown, the bar's actions give it to the active filter chip).
   */
  async function change(save, message) {
    if (run.busy) throw new Error(WAIT_FOR_SAVE);
    const wanted = run.id;
    run.busy = true;
    try {
      const page = await save();
      if (!run.current(wanted)) return;
      const before = run.order;
      run.data = mergePage(run.data, page, { advance: false });
      // A change's answer carries the import's revision too, so this page's own change doesn't refuse its next File (A3).
      run.took(page);
      const { focusId, order } = run;
      if (focusId != null && before.includes(focusId) && !order.includes(focusId)) {
        const still = new Set(order);
        const was = before.indexOf(focusId);
        run.focusId = before.slice(was + 1).find((itemId) => still.has(itemId)) ?? before.slice(0, was).findLast((itemId) => still.has(itemId)) ?? null;
      }
      run.edited();
      showToast(message);
    } catch (e) {
      if (run.current(wanted) && RESYNC.has(e.code)) run.resync(wanted);
      throw e;
    } finally {
      if (run.current(wanted)) run.busy = false;
    }
  }

  const chosenIds = () => run.chosen.map((item) => item.id);

  async function afterBar() {
    run.choosing = null;
    run.clear();
    await tick();
    if (run.focusId != null) run.grid?.focusItem(run.focusId);
    else if (!run.shown.length) run.tools?.focusFilter();
  }

  const assignTo = (hit) =>
    change(() => assignItems(run.id, chosenIds(), hit.id), `${photoCount(run.chosen.length)} assigned to ${hit.common || hit.name}.`).then(afterBar);
  const tagChosen = (add, remove) =>
    change(() => tagItems(run.id, chosenIds(), add, remove), `Tags updated on ${photoCount(run.chosen.length)}.`).then(afterBar);

  const placeChosen = (place) =>
    change(() => placeItems(run.id, chosenIds(), place.id), `${place.name} set on ${photoCount(run.chosen.length)}.`).then(afterBar);

  function unplaceChosen() {
    const n = run.chosen.length;
    change(() => unplaceItems(run.id, chosenIds()), `Place removed from ${photoCount(n)}.`).then(afterBar, (e) => showProblem(e));
  }

  function skipChosen() {
    const unskip = run.chosen.every((item) => item.skipped);
    const n = run.chosen.length;
    change(() => skipItems(run.id, chosenIds(), !unskip), unskip ? `${photoCount(n)} will be filed again.` : `${photoCount(n)} skipped. They stay in the folder.`)
      .then(afterBar, (e) => showProblem(e));
  }

  const acceptSection = (section, ids) =>
    change(() => acceptSuggestions(run.id, ids), `${ids.length === 1 ? '1 suggestion' : `${ids.length.toLocaleString()} suggestions`} accepted in ${section.title}.`)
      .catch((e) => showProblem(e));

  const skipLibrary = () =>
    change(() => skipItems(run.id, run.libraryIds, true), `${photoCount(run.libraryIds.length)} already in your atlas skipped.`).catch((e) => showProblem(e));

  /** The selection bar's actions and their keys: Skip and Remove place save at once; the others open a chooser. */
  function startAction(what) {
    if (!run.chosen.length) return;
    if (run.busy) {
      showToast(WAIT_FOR_SAVE);
      return;
    }
    if (run.blocked) {
      showToast(run.blocked);
      return;
    }
    if (what === 'skip') skipChosen();
    else if (what === 'unplace') unplaceChosen();
    else run.choosing = what;
  }

  return { assignTo, tagChosen, placeChosen, acceptSection, skipLibrary, startAction };
}
