<script>
  import { untrack } from 'svelte';
  import Dialog from './Dialog.svelte';

  /**
   * Tag many photos at once. Each tag shows whether all, some or none of the chosen photos carry it;
   * pressing it puts it on all of them, pressing again takes it off all. Only changes are sent.
   */
  let { photos, tags = [], onApply, onClose } = $props();

  const stateOf = (key) => {
    const n = photos.filter((photo) => photo.tags.includes(key)).length;
    return n === 0 ? 'none' : n === photos.length ? 'all' : 'some';
  };
  const initial = untrack(() => Object.fromEntries(tags.map((tag) => [tag.key, stateOf(tag.key)])));
  let states = $state({ ...initial });
  let busy = $state(false);
  let error = $state('');

  let add = $derived(tags.map((t) => t.key).filter((key) => states[key] === 'all' && initial[key] !== 'all'));
  let remove = $derived(tags.map((t) => t.key).filter((key) => states[key] === 'none' && initial[key] !== 'none'));
  const checked = (state) => (state === 'all' ? 'true' : state === 'some' ? 'mixed' : 'false');

  async function apply() {
    if (busy) return;
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
  <div class="chips" role="group" aria-label="Tags">
    {#each tags as tag (tag.key)}
      <button class="chip" type="button" role="checkbox" aria-checked={checked(states[tag.key])}
              class:on={states[tag.key] === 'all'} class:some={states[tag.key] === 'some'}
              onclick={() => { if (!busy) states[tag.key] = states[tag.key] === 'all' ? 'none' : 'all'; }}>{tag.label}</button>
    {/each}
  </div>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}
    <button class="btn ghost" type="button" onclick={onClose}>Cancel</button>
    <button class="btn" type="button" disabled={!add.length && !remove.length} aria-disabled={busy} onclick={apply}>Apply</button>
  {/snippet}
</Dialog>
