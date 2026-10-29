<script>
  import { onMount, tick } from 'svelte';
  import { createTag, deleteTag, getTags, renameTag } from './api.js';
  import Dialog from './Dialog.svelte';
  import { isTopModal, modal } from './modal.js';
  import { showToast } from './toast.svelte.js';
  import { TAG_NAME_MAX_WORDS, checkTagName, tagWords } from './tags.js';
  import { OVER_3_WORDS, tagSavedAs } from './messages.js';

  /**
   * Manage tags (design P6): built-in tags can be renamed, not deleted; the user's own tags can be added,
   * renamed and deleted. Deleting says how many photos carry the tag first; the photos stay. A refused delete (an
   * import filing with the tag, several-windows A17) says why on the tag's row, and focus goes back to its Delete.
   * The add and rename fields keep what is typed; under them, the name it will be saved as ("Saved as: mud_puddling")
   * when it differs, or why it can't be (the server's words), with Add / Save disabled (tag-names design §5). A tag
   * of more than 3 words (one from before the limit) is marked on its row.
   */
  let { onChanged = () => {}, onClose } = $props();

  const uid = $props.id();
  let root = $state(null);
  let tags = $state(null);
  let draft = $state('');
  let renaming = $state(null);
  let newLabel = $state('');
  let confirming = $state(null);
  let error = $state('');
  let errorAt = $state('');
  let busy = $state(false);
  let addInput = $state(null);

  let builtin = $derived((tags ?? []).filter((t) => t.source === 'builtin'));
  let mine = $derived((tags ?? []).filter((t) => t.source === 'user'));
  const photos = (n) => (n === 1 ? '1 photo' : `${n} photos`);
  // What a field's name will be saved as, or why not; null while the field is empty.
  let addCheck = $derived(draft.trim() ? checkTagName(draft, tags) : null);
  let renameCheck = $derived(renaming !== null && newLabel.trim() ? checkTagName(newLabel, tags, renaming) : null);
  const hint = (check, typed) => check?.problem || (check && check.name !== typed.trim() ? tagSavedAs(check.name) : '');
  let addHint = $derived(hint(addCheck, draft));
  let renameHint = $derived(hint(renameCheck, newLabel));

  onMount(async () => {
    addInput?.focus();
    try {
      tags = await getTags();
    } catch (e) {
      error = e.message;
      errorAt = '';
    }
  });

  async function change(work, message, at = 'add') {
    if (busy) return false;
    busy = true;
    error = '';
    errorAt = '';
    try {
      await work();
    } catch (e) {
      error = e.message;
      errorAt = at;
      busy = false;
      return false;
    }
    try {
      tags = await getTags();
    } catch {
      // The change went through; the list stays as it was until the next load.
    }
    busy = false;
    onChanged();
    if (message) showToast(message);
    return true;
  }

  async function add(event) {
    event.preventDefault();
    const label = draft.trim();
    if (!label || addCheck?.problem) return;
    if (await change(() => createTag(label))) {
      draft = '';
      addInput?.focus();
    }
  }

  async function startRename(tag) {
    renaming = tag.id;
    newLabel = tag.label;
    await tick();
    document.getElementById(`${uid}-rename`)?.select();
  }

  async function stopRename(tag) {
    renaming = null;
    // A rename's refusal goes with the field (it would otherwise stay on the row as the row's own).
    if (errorAt === tag.id) error = '';
    await tick();
    document.querySelector(`[data-rename="${tag.id}"]`)?.focus();
  }

  async function saveRename(tag) {
    const label = newLabel.trim();
    if (renameCheck?.problem) return;
    if (!label || renameCheck?.name === tag.label) {
      stopRename(tag);
      return;
    }
    if (await change(() => renameTag(tag.id, label), null, tag.id)) stopRename(tag);
  }

  function renameKey(event, tag) {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      stopRename(tag);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      saveRename(tag);
    }
  }

  async function confirmDelete() {
    if (busy) return;
    const tag = confirming;
    let removed = 0;
    const ok = await change(async () => {
      removed = await deleteTag(tag.id);
    }, null, `del:${tag.id}`); // its own key: a delete's refusal never shows on the rename field
    confirming = null;
    if (ok) addInput?.focus();
    else {
      await tick();
      document.querySelector(`[data-delete="${tag.id}"]`)?.focus();
    }
    if (ok) showToast(removed ? `“${tag.label}” deleted and taken off ${photos(removed)}.` : `“${tag.label}” deleted.`);
  }

  function onKey(event) {
    if (event.key === 'Escape' && isTopModal(root)) {
      event.preventDefault();
      onClose();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

{#snippet row(tag)}
  <li class="tag-row">
    {#if renaming === tag.id}
      <input class="field" id="{uid}-rename" bind:value={newLabel} maxlength="60" aria-label="New name for {tag.label}"
             aria-invalid={(error && errorAt === tag.id) || renameCheck?.problem ? 'true' : undefined}
             aria-describedby={error && errorAt === tag.id ? `${uid}-err` : renameHint ? `${uid}-rename-hint` : undefined}
             oninput={() => (error = '')} onkeydown={(e) => renameKey(e, tag)} />
      <button class="btn" type="button" disabled={!!renameCheck?.problem} aria-disabled={busy} onclick={() => saveRename(tag)}>Save</button>
      <button class="btn ghost" type="button" onclick={() => stopRename(tag)}>Cancel</button>
      {#if renameHint && !(error && errorAt === tag.id)}
        <p class={renameCheck?.problem ? 'problem' : 'muted'} id="{uid}-rename-hint" aria-live="polite" style="flex-basis: 100%">{renameHint}</p>
      {/if}
      {#if error && errorAt === tag.id}<p class="problem" id="{uid}-err" role="alert" style="flex-basis: 100%">{error}</p>{/if}
    {:else}
      <span class="tag-name">{tag.label}</span>
      {#if tagWords(tag.label) > TAG_NAME_MAX_WORDS}<span class="tag-long">{OVER_3_WORDS}</span>{/if}
      <span class="tag-n">{photos(tag.photoCount)}</span>
      <button class="btn ghost" type="button" aria-label="Rename {tag.label}" data-rename={tag.id} onclick={() => startRename(tag)}>Rename</button>
      {#if tag.source === 'builtin'}
        <button class="btn ghost" type="button" disabled aria-label="Delete {tag.label}" aria-describedby="{uid}-builtin">Delete</button>
      {:else}
        <button class="btn ghost" type="button" aria-label="Delete {tag.label}" data-delete={tag.id}
                aria-describedby={error && errorAt === `del:${tag.id}` ? `${uid}-err` : undefined} onclick={() => (confirming = tag)}>Delete</button>
      {/if}
      {#if error && errorAt === `del:${tag.id}`}<p class="problem" id="{uid}-err" role="alert" style="flex-basis: 100%">{error}</p>{/if}
    {/if}
  </li>
{/snippet}

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov sheet-bg" bind:this={root} use:modal={{ returnFocus: () => document.querySelector('[data-tags]') }}
     onclick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="{uid}-title">
    <div class="sh-h">
      <span class="eyebrow">Your atlas</span>
      <h2 id="{uid}-title">Tags</h2>
      <span class="muted">Tags work on every species. A photo can have several.</span>
    </div>
    <div class="sh-b">
      <form class="tag-add" onsubmit={add}>
        <input class="field" bind:this={addInput} bind:value={draft} maxlength="60" aria-label="New tag"
               aria-invalid={(error && errorAt === 'add') || addCheck?.problem ? 'true' : undefined}
               aria-describedby={error && errorAt === 'add' ? `${uid}-err` : addHint ? `${uid}-add-hint` : undefined}
               placeholder="For example “mud_puddling”" oninput={() => (error = '')} />
        <button class="btn" type="submit" disabled={!draft.trim() || !!addCheck?.problem} aria-disabled={busy}>Add</button>
      </form>
      {#if addHint && !(error && errorAt === 'add')}
        <p class={addCheck?.problem ? 'problem' : 'muted'} id="{uid}-add-hint" aria-live="polite">{addHint}</p>
      {/if}
      {#if error && (errorAt === 'add' || errorAt === '')}<p class="problem" id="{uid}-err" role="alert">{error}</p>{/if}
      {#if tags === null && !error}
        <p class="muted" role="status">Loading tags…</p>
      {:else if tags}
        <section>
          <h3 class="lbl2">Your tags</h3>
          {#if mine.length}
            <ul class="tag-list">{#each mine as tag (tag.id)}{@render row(tag)}{/each}</ul>
          {:else}
            <p class="muted">No tags of your own yet.</p>
          {/if}
        </section>
        <section>
          <h3 class="lbl2">Built-in</h3>
          <p class="muted" id="{uid}-builtin">Built-in tags can be renamed but not deleted.</p>
          <ul class="tag-list">{#each builtin as tag (tag.id)}{@render row(tag)}{/each}</ul>
        </section>
      {/if}
    </div>
    <div class="sh-f"><button class="btn" type="button" onclick={onClose}>Done</button></div>
  </div>
  {#if confirming}
    <Dialog title="Delete “{confirming.label}”?" onClose={() => (confirming = null)}>
      <p>{confirming.photoCount
        ? `It is on ${photos(confirming.photoCount)}. The photos stay; only the tag is taken off.`
        : 'No photos carry it.'}</p>
      {#snippet actions()}
        <button class="btn ghost" type="button" data-autofocus onclick={() => (confirming = null)}>Cancel</button>
        <button class="btn" type="button" aria-disabled={busy} onclick={confirmDelete}>Delete tag</button>
      {/snippet}
    </Dialog>
  {/if}
</div>
