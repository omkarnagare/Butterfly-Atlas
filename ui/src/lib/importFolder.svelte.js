import { tick } from 'svelte';
import { getImportItems, relocateImport, startImport } from './api.js';
import { emptyItems, mergePage } from './importItems.js';
import { RESYNC } from './importSync.svelte.js';
import { showProblem, showToast } from './toast.svelte.js';
import { refreshStatus } from './status.svelte.js';
import { WAIT_FOR_SAVE } from './messages.js';

/**
 * An import's folder, for screens/ImportRun.svelte: reading cut short carries on, a missing folder is looked for
 * again, and a folder that moved is pointed to where it is now. `run` is the page's state, read and written through
 * accessors; `run.act`, `run.resync`, `run.ticket` and `run.fresh` come from importSync.svelte.js.
 */
export function importFolder(run) {
  /**
   * Reading the app's closing cut short carries on by itself when the import is opened (POST /api/imports with
   * its path, which looks through the folder again). Only if that fails does the page say it won't carry on by
   * itself, with the reason and "Continue reading".
   */
  async function carryOn() {
    if (run.busy) return;
    const wanted = run.id;
    run.busy = true;
    run.continuing = true;
    run.autoFailed = '';
    try {
      const { import: s } = await startImport(run.summary.rootPath);
      if (!run.current(wanted)) return;
      run.summary = s;
      run.took(s);
      showToast(`Reading ${s.rootName} again where it stopped.`);
      refreshStatus();
    } catch (e) {
      if (run.current(wanted)) run.autoFailed = e.message;
    } finally {
      if (run.current(wanted)) {
        run.busy = false;
        run.continuing = false;
      }
    }
  }

  /** The folder was not found: look for it again, and say whether it is there now. */
  const lookAgain = () => run.act(async (wanted) => {
    const { import: s } = await startImport(run.summary.rootPath);
    if (!run.current(wanted)) return;
    run.summary = s;
    run.took(s);
    refreshStatus();
    if (s.folderMissing) {
      showToast(`${s.rootName} still can't be found. Connect the drive it is on, or choose where it moved.`);
    } else {
      showToast(`Found ${s.rootName}. Looking through it again.`);
      await tick();
      run.heading?.focus();
    }
  });

  /**
   * The folder moved: point the import at its new place, then load every item again (their paths are matched
   * anew). A refusal is thrown to the browser, which shows it; once moved, a failure to load the items is only a toast.
   */
  async function relocate(listing) {
    if (run.busy) throw new Error(WAIT_FOR_SAVE);
    const wanted = run.id;
    run.busy = true;
    try {
      const s = await relocateImport(wanted, listing.path);
      if (!run.current(wanted)) return;
      run.summary = s;
      run.took(s);
      run.foundFocus = true;
      run.relocating = false;
      showToast(`Found ${s.rootName} at ${s.rootPath}.`);
      refreshStatus();
      try {
        const t = run.ticket();
        const page = await getImportItems(wanted);
        if (run.current(wanted) && run.fresh(t)) {
          run.data = mergePage(emptyItems(), page);
          run.read(page); // the revision the items were read at (A21)
        }
      } catch (e) {
        if (run.current(wanted)) showProblem(e);
      }
    } catch (e) {
      if (run.current(wanted) && RESYNC.has(e.code)) run.resync(wanted);
      throw e;
    } finally {
      if (run.current(wanted)) run.busy = false;
    }
  }

  return { carryOn, lookAgain, relocate };
}
