<script>
  import { FILTERS, photoCount } from './importView.js';

  /**
   * The import grid's tools (design §4.3): filter chips with their counts, the two views (By folder, By
   * species), and "Skip N already in the library" while any wait (`library`), which can't be pressed while
   * `off` (a change being saved, or filing).
   */
  let { counts, filter = $bindable('all'), view = $bindable('folder'), library = 0, off = false, onSkipLibrary } = $props();

  let chips = $state(null);

  /** Focuses the active filter chip (when a change leaves no photo shown under it). */
  export function focusFilter() {
    chips?.querySelector('[aria-pressed="true"]')?.focus();
  }
</script>

<div class="tools imp-tools">
  <div class="chips" bind:this={chips} role="group" aria-label="Show photos">
    {#each FILTERS as f (f.key)}
      {@const n = counts[f.key]}
      <button type="button" class="chip" class:on={filter === f.key} aria-pressed={filter === f.key}
              aria-label="{f.label}, {photoCount(n)}" onclick={() => (filter = f.key)}>{f.label} <span class="n">{n.toLocaleString()}</span></button>
    {/each}
  </div>
  <div class="seg" role="group" aria-label="Group photos">
    <button type="button" class:on={view === 'folder'} aria-pressed={view === 'folder'} onclick={() => (view = 'folder')}>By folder</button>
    <button type="button" class:on={view === 'species'} aria-pressed={view === 'species'} onclick={() => (view = 'species')}>By species</button>
  </div>
  <div class="acts">
    {#if library}
      <button class="btn ghost" type="button" aria-disabled={off} onclick={() => { if (!off) onSkipLibrary(); }}>
        Skip {photoCount(library)} already in the library
      </button>
    {/if}
  </div>
</div>
