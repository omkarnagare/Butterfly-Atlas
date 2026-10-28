<script>
  import { tick } from 'svelte';
  import Popover from './Popover.svelte';
  import Dialog from './Dialog.svelte';
  import AddSpeciesDialog from './AddSpeciesDialog.svelte';
  import { deleteCustomSpecies, getDeleted } from './api.js';

  /**
   * Edit… on a custom species' page (Phase 5a contract §4): a menu of Rename… (the Add dialog in rename mode;
   * `onRenamed(detail)` after) and Delete… (a confirmation; `onDeleted()` after). Delete… is aria-disabled, with the
   * reason, while the species has photos: `photoCount` live ones plus its photos in Recently deleted (read each time the
   * menu opens; until that answers it reads "Checking…"). If they change meanwhile, the server's refusal shows in the
   * confirmation.
   */
  let { taxon, photoCount: live, onRenamed, onDeleted } = $props();

  const uid = $props.id();
  let button = $state(null);
  let menu = $state(false);
  let open = $state(null);
  // How many of its photos are in Recently deleted; null until the menu's read has answered.
  let deleted = $state(null);
  let busy = $state(false);
  let error = $state('');
  let label = $derived(taxon.common || taxon.name);

  /** "its photo", "its 4 photos" — the server's words for its refusal. */
  const its = (photos) => (photos === 1 ? 'its photo' : `its ${photos} photos`);

  /** Why Delete… can't be used now, or '' when it can, in the server's words. */
  let blocker = $derived.by(() => {
    if (deleted === null) return 'Checking…';
    if (live && deleted) return `Move ${its(live)} and empty or restore ${deleted} in Recently deleted first.`;
    if (live) return `Move ${its(live)} first.`;
    if (deleted) return `Empty or restore ${its(deleted)} in Recently deleted first.`;
    return '';
  });

  // Counts the menu's openings, so an answer to an earlier opening is dropped.
  let reads = 0;

  async function openMenu() {
    menu = true;
    deleted = null;
    const read = ++reads;
    let count;
    try {
      const binned = await getDeleted();
      count = binned.filter((photo) => photo.taxonId === taxon.id).length;
    } catch {
      count = 0; // unknown: the server still refuses a delete while any photo is on it
    }
    if (read === reads) deleted = count;
  }

  async function choose(what) {
    if (what === 'delete' && blocker) return;
    menu = false;
    error = '';
    await tick();
    open = what;
  }

  function onMenuKey(event) {
    const all = [...event.currentTarget.querySelectorAll('[role="menuitem"]')];
    const at = all.indexOf(document.activeElement);
    const to = { ArrowDown: (at + 1) % all.length, ArrowUp: (at - 1 + all.length) % all.length, Home: 0, End: all.length - 1 }[event.key];
    if (to === undefined) return;
    event.preventDefault();
    all[to]?.focus();
  }

  async function renamed(detail) {
    open = null;
    await onRenamed(detail);
  }

  async function remove() {
    if (busy) return;
    busy = true;
    error = '';
    try {
      await deleteCustomSpecies(taxon.id);
    } catch (e) {
      error = e.message;
      busy = false;
      return;
    }
    busy = false;
    open = null;
    await onDeleted();
  }

  function closeDelete() {
    if (!busy) open = null;
  }
</script>

<button class="btn ghost ed-btn" type="button" bind:this={button} aria-haspopup="menu" aria-expanded={menu}
        aria-controls={menu ? `${uid}-menu` : undefined} onclick={openMenu}>Edit…</button>
{#if menu}
  <Popover title="Edit" role="menu" anchor={button} onClose={() => (menu = false)}>
    <!-- svelte-ignore a11y_interactive_supports_focus -->
    <div class="ed-menu" id="{uid}-menu" role="menu" aria-label="Edit {label}" tabindex="-1" onkeydown={onMenuKey}>
      <button type="button" role="menuitem" onclick={() => choose('rename')}>Rename…</button>
      <button type="button" role="menuitem" aria-disabled={blocker ? 'true' : undefined} aria-describedby={blocker ? `${uid}-why` : undefined}
              onclick={() => choose('delete')}>Delete…</button>
      {#if blocker}<span class="muted ed-why" id="{uid}-why">{blocker}</span>{/if}
    </div>
  </Popover>
{/if}
{#if open === 'rename'}
  <AddSpeciesDialog {taxon} onAdded={renamed} onClose={() => (open = null)} returnFocus={() => button} />
{:else if open === 'delete'}
  <Dialog title="Delete {label}?" onClose={closeDelete} returnFocus={() => button}>
    <p>It has no photos. Its search words are removed with it.</p>
    {#if error}<p class="problem" role="alert">{error}</p>{/if}
    {#snippet actions()}
      <button class="btn ghost" type="button" aria-disabled={busy} data-autofocus onclick={closeDelete}>Cancel</button>
      <button class="btn" type="button" aria-disabled={busy} onclick={remove}>{busy ? 'Deleting…' : 'Delete'}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .ed-btn { padding: 4px 12px; font-size: 13px; border-radius: 99px; }
  .ed-menu { display: grid; gap: 2px; outline: none; }
  .ed-menu button { text-align: left; padding: 9px 10px; border-radius: 10px; font-weight: 600; }
  .ed-menu button:hover, .ed-menu button:focus-visible { background: var(--surface-2); }
  .ed-menu button[aria-disabled='true'] { opacity: .45; cursor: default; }
  .ed-why { padding: 0 10px 6px; font-size: 13px; }
</style>
