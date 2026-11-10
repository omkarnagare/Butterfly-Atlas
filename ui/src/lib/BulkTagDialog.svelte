<script>
  import { untrack } from 'svelte';
  import Dialog from './Dialog.svelte';
  import TagPicker from './TagPicker.svelte';

  /**
   * Tag many photos at once. Each tag shows whether all, some or none of the chosen photos carry it;
   * pressing it puts it on all of them, pressing again takes it off all. Only changes are sent.
   * The tags are a tristate TagPicker (behaviour tags §4): built-in tags, the user tags some or all of the photos carry,
   * and the search box. A tag created there is given to the screen (`onTagsChanged(tag)`), which keeps it and reads its tags again, then is marked "all"
   * (it starts as "none": no photo carries it).
   */
  let { photos, tags = [], onApply, onClose, onTagsChanged = async () => {} } = $props();

  const stateOf = (key) => {
    const n = photos.filter((photo) => photo.tags.includes(key)).length;
    return n === 0 ? 'none' : n === photos.length ? 'all' : 'some';
  };
  // What the photos carry as the dialog opens; a tag first seen later (created here, or found after a re-read) starts
  // from what they carry when it is first pressed (none, for a new one).
  const initial = untrack(() => Object.fromEntries(tags.map((tag) => [tag.key, stateOf(tag.key)])));
  let states = $state({ ...initial });
  let busy = $state(false);
  let creating = $state(false);
  let error = $state('');

  let add = $derived(tags.map((t) => t.key).filter((key) => states[key] === 'all' && initial[key] !== 'all'));
  let remove = $derived(tags.map((t) => t.key).filter((key) => states[key] === 'none' && initial[key] !== 'none'));
  const PICKER = { all: 'on', some: 'some', none: 'off' };

  function toggle(key) {
    if (busy) return;
    if (!(key in initial)) initial[key] = stateOf(key);
    const now = states[key] ?? initial[key];
    states[key] = now === 'all' ? 'none' : 'all';
  }

  async function apply() {
    // A tag being created is marked once it is known: Apply waits for it.
    if (busy || creating) return;
    busy = true;
    error = '';
    try {
      await onApply(add, remove);
    } catch (e) {
      error = e.message;
      busy = false;
    }
  }
</script>

<Dialog title="Tag {photos.length === 1 ? '1 photo' : `${photos.length} photos`}" {onClose}>
  <p class="muted">A dash means some of these photos have the tag. Press a tag to put it on all of them; press again to take it off.</p>
  <TagPicker {tags} tristate stateOf={(key) => PICKER[states[key] ?? 'none']} onToggle={toggle} onCreated={onTagsChanged}
             disabled={busy} bind:creating />
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}
    <button class="btn ghost" type="button" onclick={onClose}>Cancel</button>
    <button class="btn" type="button" disabled={!add.length && !remove.length} aria-disabled={busy || creating} onclick={apply}>Apply</button>
  {/snippet}
</Dialog>
