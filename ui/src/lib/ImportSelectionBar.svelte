<script>
  import { reserveBelow } from './reserve.js';
  import { photoCount } from './importView.js';

  /**
   * The bar at the bottom while photos of an import are selected (design §4.3): how many, and Assign species,
   * Tags, Place… and Remove place (3c-2), Skip (or "Don't skip" when every one is skipped) and Clear, with their keys.
   * While a change is being saved, or while filing runs (`blocked` says why), the actions stay focusable but do
   * nothing (aria-disabled).
   */
  let {
    count, allSkipped = false, busy = false, blocked = '', onAssign, onTags, onPlace, onRemovePlace, onSkip, onClear,
  } = $props();

  const uid = $props.id();
  let off = $derived(busy || Boolean(blocked));
  const act = (run) => () => { if (!off) run(); };
</script>

<div class="selbar" use:reserveBelow role="group" aria-label="Selected photos">
  <span class="n">{photoCount(count)} selected</span>
  {#if blocked}<span class="muted why" id="{uid}-why">{blocked}</span>{/if}
  <button class="btn ghost" type="button" aria-disabled={off} aria-describedby={blocked ? `${uid}-why` : undefined} aria-keyshortcuts="S"
          onclick={act(onAssign)}>Assign species <kbd aria-hidden="true">S</kbd></button>
  <button class="btn ghost" type="button" aria-disabled={off} aria-describedby={blocked ? `${uid}-why` : undefined} aria-keyshortcuts="T"
          onclick={act(onTags)}>Tags <kbd aria-hidden="true">T</kbd></button>
  <button class="btn ghost" type="button" aria-disabled={off} aria-describedby={blocked ? `${uid}-why` : undefined}
          onclick={act(onPlace)}>Place…</button>
  <button class="btn ghost" type="button" aria-disabled={off} aria-describedby={blocked ? `${uid}-why` : undefined}
          onclick={act(onRemovePlace)}>Remove place</button>
  <button class="btn ghost" type="button" aria-disabled={off} aria-describedby={blocked ? `${uid}-why` : undefined} aria-keyshortcuts="X"
          onclick={act(onSkip)}>{allSkipped ? "Don't skip" : 'Skip'} <kbd aria-hidden="true">X</kbd></button>
  <button class="btn" type="button" aria-keyshortcuts="Escape" onclick={onClear}>Clear</button>
</div>
