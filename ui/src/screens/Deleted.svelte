<script>
  import { onMount, tick } from 'svelte';
  import { emptyDeleted, getDeleted, photoThumb, restorePhotos } from '../lib/api.js';
  import { CANT_SHOW, typeBadge } from '../lib/raw.js';
  import Dialog from '../lib/Dialog.svelte';
  import { LIBRARY_CHANGED, heicBarOn, refreshStatus } from '../lib/status.svelte.js';
  import HeicBar from '../lib/HeicBar.svelte';
  import { isHeic } from '../lib/heic.js';
  import LoadError from '../lib/LoadError.svelte';
  import { loadProblem } from '../lib/problems.js';
  import { showToast } from '../lib/toast.svelte.js';
  import { thumbRetry } from '../lib/thumbRetry.js';

  /** Recently deleted (#/deleted, design P7): restore photos to their species, or remove them for good. */
  let photos = $state(null);
  let error = $state('');
  let restoreError = $state(null);
  let busy = $state(false);
  let confirming = $state(null);
  let confirmError = $state('');
  let heading = $state(null);

  const speciesName = (taxon) => taxon.common || taxon.name;
  const left = (days) => (days <= 1 ? 'Last day' : `${days} days left`);
  const count = (n) => (n === 1 ? '1 photo' : `${n} photos`);

  let loads = 0;
  let list = $state(null);

  /**
   * Only the newest answer lands (reloads can overlap); a failed reload keeps the list on screen. A reload that takes
   * away the row holding focus hands focus to the heading.
   */
  async function load() {
    const mine = ++loads;
    try {
      const next = await getDeleted();
      if (mine !== loads) return;
      const hadFocus = list?.contains(document.activeElement) ?? false;
      photos = next;
      error = '';
      await tick();
      if (hadFocus && (!document.activeElement || document.activeElement === document.body)) heading?.focus();
    } catch (e) {
      if (mine === loads && photos === null) error = loadProblem(e);
    }
  }

  // The list reads again on every LIBRARY_CHANGED: the library is back after a load failed, or photos changed (in
  // another window too, several-windows audit A9).
  onMount(() => {
    load();
    window.addEventListener(LIBRARY_CHANGED, load);
    return () => window.removeEventListener(LIBRARY_CHANGED, load);
  });

  /** After a change the pressed button is usually gone, so focus goes to the heading instead of nowhere. */
  async function settle() {
    await load();
    refreshStatus();
    await tick();
    if (!document.activeElement || document.activeElement === document.body) heading?.focus();
  }

  async function restore(photo) {
    if (busy) return;
    busy = true;
    restoreError = null;
    try {
      await restorePhotos([photo.id]);
      showToast(`Restored to ${speciesName(photo.taxon)}.`);
      await settle();
    } catch (e) {
      restoreError = { id: photo.id, message: e.message };
    } finally {
      busy = false;
    }
  }

  async function purge() {
    if (busy) return;
    busy = true;
    confirmError = '';
    try {
      const removed = await emptyDeleted(confirming.ids);
      confirming = null;
      showToast(`${count(removed)} deleted for good.`);
      await settle();
    } catch (e) {
      confirmError = e.message;
    } finally {
      busy = false;
    }
  }

  function ask(next) {
    confirmError = '';
    confirming = next;
  }

  function cancel() {
    if (!busy) confirming = null;
  }
</script>

<div class="xh">
  <div>
    <div class="eyebrow">Your atlas</div>
    <h1 tabindex="-1" bind:this={heading}>Recently deleted</h1>
    <div class="cn">Deleted photos wait here for 30 days, then they are removed for good.</div>
  </div>
  {#if photos?.length}
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={() => { if (!busy) ask({ ids: photos.map((p) => p.id), count: photos.length, all: true }); }}>
      Empty Recently deleted
    </button>
  {/if}
</div>

{#if error}
  <LoadError message={error} onRetry={load} />
{:else if photos === null}
  <p class="muted loading" role="status">Opening…</p>
{:else if !photos.length}
  <div class="emptyp">
    <h2>Nothing here</h2>
    <p>Photos you delete wait here for 30 days, in case you change your mind.</p>
  </div>
{:else}
  <!-- 7b review fix U8: HEIC photos here this computer can't show are said, with How and Check again. -->
  {#if heicBarOn() && photos.some(isHeic)}<HeicBar />{/if}
  <ul class="grid del-grid" bind:this={list}>
    {#each photos as photo (photo.id)}
      <li class="spc del">
        <div class="cover">
          {#if photo.unreadable}
            <span class="cant">{CANT_SHOW}</span>
          {:else}
            <img src={photoThumb(photo)} alt="" loading="lazy" decoding="async" use:thumbRetry />
          {/if}
          {#if typeBadge(photo)}<span class="tb"><span class={typeBadge(photo).toLowerCase()}>{typeBadge(photo)}</span></span>{/if}
        </div>
        <div class="meta">
          <a class="cnm" href="#/species/{encodeURIComponent(photo.taxon.id)}">{speciesName(photo.taxon)}</a>
          {#if photo.taxon.common}<i class="bn">{photo.taxon.name}</i>{/if}
          <span class="pc">{photo.fileName} · {left(photo.daysLeft)}</span>
        </div>
        <div class="del-acts">
          <button class="btn ghost" type="button" aria-disabled={busy} aria-label="Restore {photo.fileName}" onclick={() => restore(photo)}>Restore</button>
          <button class="btn ghost" type="button" aria-disabled={busy} aria-label="Delete {photo.fileName} for good"
                  onclick={() => { if (!busy) ask({ ids: [photo.id], count: 1, name: photo.fileName }); }}>Delete for good</button>
        </div>
        {#if restoreError?.id === photo.id}<p class="problem" role="alert">{restoreError.message}</p>{/if}
      </li>
    {/each}
  </ul>
{/if}

{#if confirming}
  <Dialog title={confirming.all ? `Delete all ${count(confirming.count)} for good?` : `Delete ${confirming.name} for good?`} onClose={cancel}>
    <p>This cannot be undone: the {confirming.count === 1 ? 'file is' : 'files are'} removed from your data folder.</p>
    {#if confirmError}<p class="problem" role="alert">{confirmError}</p>{/if}
    {#snippet actions()}
      <button class="btn ghost" type="button" data-autofocus aria-disabled={busy} onclick={cancel}>Cancel</button>
      <button class="btn" type="button" aria-disabled={busy} onclick={purge}>Delete for good</button>
    {/snippet}
  </Dialog>
{/if}
