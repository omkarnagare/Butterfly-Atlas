<script>
  import { stoppedLine } from './importView.js';
  import { importHref } from './route.js';
  import { CONTINUE_IMPORT } from './status.svelte.js';
  import { IMPORTS_THAT_STOPPED } from './messages.js';

  /**
   * Imports whose reading or filing was cut short by the app closing (/api/status `stoppedImports`, contract §3):
   * nothing restarts by itself, so each says so, with "Continue import" (its grid carries on reading, or offers
   * "Continue filing"). At most three lines, then "and N more". `quiet` is the footer's small version. `label`
   * names the group; a page that names the region around it passes '' so it is announced once.
   */
  const SHOWN = 3;
  let { stops, quiet = false, label = IMPORTS_THAT_STOPPED } = $props();

  let more = $derived(stops.length - SHOWN);

  // On the import's own grid the link would not change the address: ask the grid to continue instead.
  function onContinue(event, stop) {
    if (window.location.hash !== importHref(stop.id)) return;
    event.preventDefault();
    window.dispatchEvent(new CustomEvent(CONTINUE_IMPORT, { detail: { id: stop.id } }));
  }
</script>

<div class={quiet ? 'stops quiet' : 'stops'} role={label ? 'group' : undefined} aria-label={label || undefined}>
  {#each stops.slice(0, SHOWN) as stop (stop.id)}
    <p>
      <span>{stoppedLine(stop)}</span>
      <a class={quiet ? '' : 'btn ghost'} href={importHref(stop.id)} aria-label="Continue import of {stop.rootName}"
         onclick={(event) => onContinue(event, stop)}>Continue import</a>
    </p>
  {/each}
  {#if more > 0}<p><a href="#/import">and {more.toLocaleString()} more</a></p>{/if}
</div>
