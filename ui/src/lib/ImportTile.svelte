<script>
  import { importPreviewUrl } from './api.js';
  import { editable, pairBadge, pairFiled, placeLine, tileStatus } from './importView.js';
  import { thumbRetry } from './thumbRetry.js';
  import { CANT_SHOW } from './raw.js';
  import { heicMissing, isHeic } from './heic.js';
  import { status as appStatus } from './status.svelte.js';
  import { ITEM_READ } from './states.js';

  /**
   * One photo of an import (design §4.3): a square tile with its preview, once the photo has been read (the
   * preview is asked for only then; one still being made arrives as a placeholder and is asked for again).
   * Its look says what is known: assigned (species label), suggested (dashed outline), in the library and
   * copy badges, skipped (dimmed), filed, problem (the reason instead of a picture), not read yet (shimmer). Its place's
   * name, when it has one, is a small label at the bottom left, above the species label (contract §3); a place that became
   * former (Phase 5c) is labelled so, and its tile's name and the label's title say to choose its new place.
   * A cell of the grid (role gridcell, aria-selected; `col` is its column): the grid decides what clicks and keys
   * do; only the focused tile is in the Tab order. A photo that can no longer be changed (filed, missing,
   * unreadable) has no aria-selected: it opens, it isn't selected. A RAW photo carries a "RAW" badge; a RAW + JPEG
   * pair (lib/importView.js `shots`: the JPEG with its RAW as `pairOf`) is one tile with a "RAW + JPEG" badge (Phase 7a);
   * filed as JPEG only or RAW only, its badge says the half filed ("JPEG filed") and a line under its label the half
   * left out ("RAW not filed"), review fix U4.
   * Phase 7b: a HEIC photo carries a "HEIC" badge. A read photo with no preview to show says "Can't show this photo"
   * and asks for none: a HEIC while the computer has no HEIC decoder (it can still be assigned and filed), or one whose
   * preview failed (`previewFailed`, any format, audit 6). A RAW + HEIC pair is badged so; while the HEIC can't be shown
   * its tile shows the RAW's preview (audit 15). A preview answered as pending (a decode that timed out, retried by the
   * server when asked) keeps being asked for every 30 s; only `previewFailed` stops it (review fix U1).
   */
  let { importId, item, taxa, selected = false, tabbable = false, col = undefined, onClick, onOpen, onKey, onFocus } = $props();

  let canPick = $derived(editable(item));

  let status = $derived(tileStatus(item, taxa));
  let heicHidden = $derived(isHeic(item) && heicMissing(appStatus.heic));
  // The RAW half of a pair stands in for a HEIC this computer can't show.
  let stand = $derived(heicHidden && item.pairOf?.fileType === 'raw' && item.pairOf.state === ITEM_READ && !item.pairOf.previewFailed ? item.pairOf : null);
  let cantShow = $derived(item.state === ITEM_READ && !stand && (Boolean(item.previewFailed) || heicHidden));
  let badge = $derived(pairBadge(item));
  let described = $derived(cantShow ? `${status.text}, ${CANT_SHOW.toLowerCase()}` : status.text);
  let pairDone = $derived(pairFiled(item));
  // Only a photo that has been read has a preview (the server answers 404 for the others).
  let src = $derived(item.state !== ITEM_READ || cantShow ? null : importPreviewUrl(importId, stand ?? item));
  let ready = $state(false);
  let shownSrc = null;

  // A new preview (the photo was read again) shimmers until it arrives.
  $effect.pre(() => {
    if (src !== shownSrc) {
      shownSrc = src;
      ready = false;
    }
  });
</script>

<button type="button" class="it k-{status.kind}" class:sel={selected && canPick} class:waiting={src ? !ready : status.kind === 'waiting'}
        data-item-id={item.id} tabindex={tabbable ? 0 : -1} role="gridcell" aria-colindex={col}
        aria-selected={canPick ? selected : undefined} aria-label={described}
        onclick={(event) => onClick(item, event)} ondblclick={() => onOpen(item)} onkeydown={(event) => onKey(item, event)}
        onkeyup={(event) => { if (event.key === ' ') event.preventDefault(); }} onfocus={() => onFocus(item)}>
  {#if src}
    <img {src} alt="" loading="lazy" decoding="async" draggable="false" data-keep-asking use:thumbRetry={() => (ready = true)} />
  {:else if cantShow}
    <span class="cant" aria-hidden="true">{CANT_SHOW}</span>
  {:else if status.kind === 'problem'}
    <span class="it-why" aria-hidden="true">{status.label}</span>
  {/if}
  {#if status.label && status.kind !== 'problem' && status.kind !== 'waiting'}
    <span class="it-l" class:two={pairDone} aria-hidden="true">{status.label}{#if pairDone}<span class="it-lo">{pairDone.line}</span>{/if}</span>
  {/if}
  {#if status.kind === 'problem' && src}<span class="it-l" aria-hidden="true">{status.label}</span>{/if}
  {#if item.place}
    <span class="it-p" class:former={item.place.former && canPick} aria-hidden="true" title={item.place.former && canPick ? placeLine(item) : undefined}>{item.place.path[0]}</span>
  {/if}
  <span class="it-b" aria-hidden="true">
    {#if item.libraryTaxonId && !item.outcome}<span>In library</span>{/if}
    {#if item.copyOf != null && !item.outcome}<span>Copy</span>{/if}
    {#if pairDone}<span class="ty">{pairDone.badge}</span>{:else if badge}<span class="ty">{badge}</span>{:else if item.fileType === 'raw'}<span class="ty">RAW</span>{:else if isHeic(item)}<span class="ty">HEIC</span>{/if}
  </span>
  <span class="check" aria-hidden="true"></span>
</button>
