<script>
  import { EMPTY, PRESETS, applyPreset, printFilter } from './filter.js';

  /**
   * The presets line under the filter row (contract §4, design D14): Full checklist, Not yet photographed and Missing
   * life stages set the filter in one click, keeping the taxa, places and source; Clear clears everything (unavailable
   * while there is nothing to clear).
   */
  let { filter, onChange } = $props();

  let empty = $derived(printFilter(filter) === printFilter(EMPTY));

  function choose(key) {
    if (key === 'clear' && empty) return;
    onChange(applyPreset(filter, key));
  }
</script>

<div class="presets" role="group" aria-label="Presets">
  <span class="eyebrow" aria-hidden="true">Presets</span>
  {#each PRESETS as preset (preset.key)}
    <button type="button" class="pre" aria-disabled={preset.key === 'clear' && empty ? 'true' : undefined} onclick={() => choose(preset.key)}>{preset.label}</button>
  {/each}
</div>

<style>
  .presets { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 14px; font-size: 13px; }
  .pre { color: var(--accent); font-weight: 600; padding: 2px 0; text-decoration: underline; text-decoration-color: transparent; transition: text-decoration-color .2s; }
  .pre:hover { text-decoration-color: currentColor; }
  .pre[aria-disabled='true'] { color: var(--muted); cursor: default; text-decoration-color: transparent; }
</style>
