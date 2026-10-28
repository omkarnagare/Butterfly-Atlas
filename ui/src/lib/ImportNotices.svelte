<script>
  import { stoppedLine } from './importView.js';

  /**
   * What an import's grid says above its photos: a folder that can't be found (Look again, Choose where it
   * moved), reading the app's closing cut short (carried on at once; only when that fails does it say it won't
   * carry on by itself, with the reason and "Continue reading"), the library's own notice that stopped filing
   * (contract §2 `notice`, shown as the server words it), what a taxonomy update changed in it (`updateNote`, Phase 5b
   * contract §7: its own note, not a filing problem) and, quietly, that the app can't be reached while
   * polling. Buttons stay focusable but do nothing while a change is being saved (aria-disabled).
   *
   * `changed`: File was refused because another window changed the import (409 `import_changed`, several-windows
   * contract §5), in the server's words, until the next File.
   */
  let {
    summary, changed = '', busy = false, continuing = false, autoFailed = '', unreachable = false, onLookAgain, onRelocate, onContinueReading,
  } = $props();

  let stopped = $derived(summary.stopped && summary.stopped.phase !== 'filing' ? summary.stopped : null);
  const act = (run) => () => { if (!busy) run(); };
</script>

{#if summary.folderMissing}
  <div class="imp-missing" role="alert">
    <p><b>Folder not found.</b> Connect the drive it is on, or choose where it moved.</p>
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={act(onLookAgain)}>Look again</button>
    <button class="btn" type="button" aria-disabled={busy} onclick={act(onRelocate)}>Choose where it moved</button>
  </div>
{:else if stopped && continuing}
  <p class="imp-stop muted">Reading {summary.rootName} again where it stopped…</p>
{:else if stopped && autoFailed}
  <div class="imp-stop imp-again">
    <p>{stoppedLine({ rootName: summary.rootName, ...stopped })}</p>
    <p class="problem" role="alert">{autoFailed}</p>
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={act(onContinueReading)}>Continue reading</button>
  </div>
{/if}
{#if changed}<p class="imp-notice imp-changed" role="alert">{changed}</p>{/if}
{#if summary.notice}<p class="imp-notice" role="alert">{summary.notice}</p>{/if}
{#if summary.updateNote}<p class="imp-update" role="note"><b>Taxonomy update:</b> {summary.updateNote}</p>{/if}
<p class="imp-poll muted" role="status">{unreachable ? "Can't reach Butterfly Atlas; trying again…" : ''}</p>
