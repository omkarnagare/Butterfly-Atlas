<script>
  import { countUp } from './countUp.js';
  import { countLine, placesLine } from './filter.js';

  /**
   * The Export screen's count line (contract §4): "3 photos of 2 species, 2 KB · 1,139 species in the list", and under it the places
   * ("in Maharashtra"). `count` is the newest count answer (null before the first); its numbers count up (at once under
   * reduced motion). The animated line is hidden from screen readers, which hear the whole line once from a status.
   * `pending` (a newer filter is being counted) dims it; `error` shows whenever set, and while it is set the previous numbers
   * stay dimmed (they are not current). One status outside the {#if} carries "Counting…", then the line, so the first count
   * is announced (a status swapped in with its text already in it may not be).
   */
  let { count = null, pending = false, error = '' } = $props();

  const format = (n) => n.toLocaleString();
  let parts = $derived(count ? countLine(count) : []);
  let where = $derived(count ? placesLine(count.filter?.places ?? []) : '');
  let text = $derived(parts.map((part) => (typeof part === 'string' ? part : format(part.n))).join(''));
  let said = $derived(error ? '' : count ? (where ? `${text}, ${where}` : text) : 'Counting…');
  let dim = $derived(pending || Boolean(error && count));
</script>

<div class="cl" class:pending={dim} aria-busy={pending ? 'true' : undefined}>
  {#if count}
    <p class="cl-n" aria-hidden="true">{#each parts as part, i (i)}{#if typeof part === 'string'}{part}{:else}<b use:countUp={{ to: part.n, format }}>{format(part.n)}</b>{/if}{/each}</p>
    {#if where}<p class="cl-where muted" aria-hidden="true">{where}</p>{/if}
  {:else if !error}
    <p class="cl-n muted" aria-hidden="true">Counting…</p>
  {/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  <p class="sr-only" role="status">{said}</p>
</div>

<style>
  .cl { display: grid; gap: 2px; min-height: 52px; }
  .cl-n, .cl-where { transition: opacity .2s; }
  /* Only the numbers dim: an error under them stays at full strength. */
  .cl.pending .cl-n, .cl.pending .cl-where { opacity: .6; }
  .cl-n { margin: 0; font: 650 clamp(18px, 2.6vw, 24px)/1.25 var(--display); }
  .cl-n b { font-variant-numeric: tabular-nums; font-weight: inherit; }
  .cl-where { margin: 0; font-size: 14px; }
</style>
