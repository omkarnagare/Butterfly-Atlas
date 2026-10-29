<script>
  import { onMount } from 'svelte';
  import { dismissTagNames, getTagNames } from './api.js';
  import TagsSheet from './TagsSheet.svelte';

  /**
   * Home's note of tag names (tag-names design §4), from GET /api/tag-names: what the V15 migration merged ("Merged
   * ‘Mud-puddling’ into ‘mud_puddling’ (12 photos).") and the built-ins it numbered apart, until Dismiss; and, while
   * any is left, the tags of more than 3 words with Tags, which opens the Tags sheet (the note is read again when it
   * closes). Nothing to say: no note. A note that can't be read, or a Dismiss that fails, says why.
   */
  const uid = $props.id();
  let note = $state(null);
  let problem = $state('');
  let dismissing = $state(false);
  let dismissError = $state('');
  let managing = $state(false);

  let changed = $derived(note && !note.dismissed && (note.merged.length > 0 || note.numbered.length > 0));
  let long = $derived(note?.long ?? []);
  const photos = (n) => (n === 1 ? '1 photo' : `${n.toLocaleString()} photos`);

  async function load() {
    try {
      note = await getTagNames();
      problem = '';
    } catch (e) {
      problem = `Could not read the tag names note: ${e.message}`;
    }
  }

  onMount(load);

  async function dismiss() {
    if (dismissing) return;
    dismissing = true;
    dismissError = '';
    try {
      note = await dismissTagNames();
    } catch (e) {
      dismissError = e.message;
    } finally {
      dismissing = false;
    }
  }

  function closeTags() {
    managing = false;
    load();
  }
</script>

{#if problem}
  <section class="stop-card" aria-labelledby="{uid}-h">
    <div class="stops">
      <h2 class="sr-only" id="{uid}-h">Tag names</h2>
      <p class="problem" role="alert">{problem}</p>
    </div>
  </section>
{:else if changed || long.length}
  <section class="stop-card" aria-labelledby="{uid}-h">
    <div class="stops">
      <h2 class="sr-only" id="{uid}-h">Tag names</h2>
      {#if changed}
        <p><span>Tag names now have one form.</span></p>
        {#each note.merged as m, i (i)}
          <p class="muted">Merged ‘{m.from}’ into ‘{m.into}’ ({photos(m.photos)}).</p>
        {/each}
        {#each note.numbered as n, i (i)}
          <p class="muted">Renamed ‘{n.from}’ to ‘{n.to}’: two built-in tags had the same name.</p>
        {/each}
      {/if}
      {#if long.length}
        <p><span>These tags have more than 3 words: {long.join(', ')}. Rename them in Tags.</span></p>
      {/if}
      {#if dismissError}<p class="problem" role="alert">{dismissError}</p>{/if}
      <p>
        {#if long.length}<button class="btn ghost" type="button" data-tags onclick={() => (managing = true)}>Tags</button>{/if}
        {#if changed}<button class="btn ghost" type="button" aria-disabled={dismissing} onclick={dismiss}>Dismiss</button>{/if}
      </p>
    </div>
  </section>
{/if}
{#if managing}<TagsSheet onClose={closeTags} />{/if}
