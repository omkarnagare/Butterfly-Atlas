<script>
  import HeicCheck from './HeicCheck.svelte';
  import { galleryBar, installNote } from './heic.js';
  import { status } from './status.svelte.js';

  /**
   * The bar above photos this computer can't show because it has no HEIC decoder (Phase 7b): the import grid's
   * (contract §1) and the gallery's, species page's and Export results' (audit D1). `line` says what can't be shown (by
   * default "This computer can't show HEIC photos yet.", or on a system with no usable decoder that it has none, U11);
   * How shows the system's install note under it; Check again looks for the decoder afresh (lib/HeicCheck.svelte).
   */
  let { line = undefined } = $props();

  const uid = $props.id();
  let how = $state(false);
</script>

<div class="heic-bar" role="note">
  <p>{line ?? galleryBar(status.heic)}
    <button class="linkish" type="button" aria-expanded={how} aria-controls="{uid}-how" onclick={() => (how = !how)}>How</button>
  </p>
  <div class="heic-acts"><HeicCheck /></div>
  <p class="heic-how" id="{uid}-how" hidden={!how}>{how ? installNote(status.heic) : ''}</p>
</div>
