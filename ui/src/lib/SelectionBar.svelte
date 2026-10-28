<script>
  import { reserveBelow } from './reserve.js';
  /** The bar at the bottom while photos are being selected: how many, and what to do with them. */
  let { count, total, busy = false, onAll, onTag, onMove, onPlace, onRemovePlace, onDelete, onCancel } = $props();
</script>

<div class="selbar" use:reserveBelow role="group" aria-label="Selected photos">
  <span class="n" aria-live="polite">{count === 1 ? '1 photo selected' : `${count} photos selected`}</span>
  {#if count < total}<button class="btn ghost" type="button" onclick={onAll}>Select all</button>{/if}
  <button class="btn ghost" type="button" disabled={!count} aria-disabled={busy} onclick={() => { if (!busy) onTag(); }}>Tag</button>
  <button class="btn ghost" type="button" disabled={!count} aria-disabled={busy} onclick={() => { if (!busy) onMove(); }}>Move</button>
  <button class="btn ghost" type="button" disabled={!count} aria-disabled={busy} onclick={() => { if (!busy) onPlace(); }}>Place…</button>
  <button class="btn ghost" type="button" disabled={!count} aria-disabled={busy} onclick={() => { if (!busy) onRemovePlace(); }}>Remove place</button>
  <button class="btn ghost" type="button" disabled={!count} aria-disabled={busy} onclick={() => { if (!busy) onDelete(); }}>Delete</button>
  <button class="btn" type="button" data-cancel onclick={onCancel}>Cancel</button>
</div>
