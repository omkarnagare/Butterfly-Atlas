<script module>
  /** What a host says when a species was added from the picker but the move or assignment after it failed. */
  export const addedButFailed = (hit, message) => `${hit.name} was added to Custom additions. ${message}`;
</script>

<script>
  import Dialog from './Dialog.svelte';
  import AddSpeciesDialog from './AddSpeciesDialog.svelte';
  import { search } from './api.js';
  import { names } from './names.js';
  import { famStyle } from './families.js';

  /**
   * Choose a species by typing (the search API, species only): for moving photos and assigning import items. When
   * nothing matches two words (a name like "Neptis sankara"), a row offers to add them as a custom species: the Add
   * dialog opens pre-filled over the picker, and the species added is picked at once as a search hit would be, with
   * `onPick(hit, { added: true })`. Not offered when the text is the name of the species the photos are on (`exclude`),
   * which the status then says. If the pick then throws, the message says the species was added (a host that shows its
   * own error uses addedButFailed).
   */
  let { title = 'Move to another species', exclude = null, onPick, onClose } = $props();

  const uid = $props.id();
  let query = $state('');
  let results = $state([]);
  // Whether the text typed is the excluded species' (the one the photos are on) name or common name: then it exists.
  let foundExcluded = $state(false);
  const same = (a, b) => !!a && !!b && a.trim().replace(/\s+/g, ' ').toLowerCase() === b.trim().replace(/\s+/g, ' ').toLowerCase();
  let selected = $state(0);
  let status = $state('idle');
  let searched = $state('');
  let error = $state('');
  let picking = $state(false);
  let adding = $state(false);
  let input = $state(null);
  let timer;
  let controller;

  $effect(() => () => {
    clearTimeout(timer);
    controller?.abort();
  });

  function onInput(event) {
    query = event.currentTarget.value;
    results = [];
    error = '';
    status = 'idle';
    clearTimeout(timer);
    controller?.abort();
    const wanted = query.trim();
    if (!wanted) {
      results = [];
      status = 'idle';
      return;
    }
    timer = setTimeout(() => run(wanted), 120);
  }

  async function run(wanted) {
    controller = new AbortController();
    error = '';
    try {
      const found = await search(wanted, { limit: 50, signal: controller.signal });
      if (wanted !== query.trim()) return;
      results = found.filter((hit) => hit.rank === 'species' && hit.id !== exclude).slice(0, 30);
      foundExcluded = exclude !== null && found.some((hit) => hit.id === exclude && (same(hit.name, wanted) || same(hit.common, wanted)));
      selected = 0;
      searched = wanted;
      status = 'done';
    } catch (e) {
      if (e?.name === 'AbortError') return;
      results = [];
      error = e.message;
      status = 'error';
    }
  }

  // Shaped like genus and species (the server checks the rest, case included), spaces collapsed: what the add row offers.
  const BINOMIAL = /^\p{L}+ \p{L}+(-\p{L}+)*$/u;
  let spaced = $derived(searched.replace(/\s+/g, ' '));
  let addable = $derived(status === 'done' && !results.length && !foundExcluded && BINOMIAL.test(spaced) ? spaced : null);
  let count = $derived(results.length + (addable ? 1 : 0));

  $effect(() => {
    const at = selected;
    if (count) document.getElementById(`${uid}-${at}`)?.scrollIntoView?.({ block: 'nearest' });
  });

  async function pick(hit, how = null) {
    if (picking) return;
    picking = true;
    error = '';
    try {
      await (how ? onPick(hit, how) : onPick(hit));
    } catch (e) {
      error = how?.added ? addedButFailed(hit, e.message) : e.message;
    } finally {
      picking = false;
    }
  }

  // The new species, picked as the search hit it would be.
  async function added(detail) {
    adding = false;
    const { id, rank, name, common, family, source } = detail;
    await pick({ id, rank, name, common, family, source }, { added: true });
  }

  function onKeydown(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      selected = Math.min(count - 1, selected + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      selected = Math.max(0, selected - 1);
    } else if (event.key === 'Enter' && results[selected]) {
      event.preventDefault();
      pick(results[selected]);
    } else if (event.key === 'Enter' && addable) {
      event.preventDefault();
      adding = true;
    }
  }
</script>

<Dialog {title} {onClose}>
  <input class="field" bind:this={input} data-autofocus value={query} oninput={onInput} onkeydown={onKeydown} autocomplete="off" spellcheck="false"
         placeholder="Type a common or scientific name" aria-label="Species" role="combobox" aria-expanded={count > 0}
         aria-controls="{uid}-list" aria-activedescendant={count ? `${uid}-${selected}` : undefined} />
  <div class="pick-list" id="{uid}-list" role="listbox" aria-label="Species">
    {#each results as hit, k (hit.id)}
      {@const n = names(hit)}
      <!-- svelte-ignore a11y_click_events_have_key_events -->
      <div class="res" id="{uid}-{k}" tabindex="-1" role="option" aria-selected={k === selected} class:sel={k === selected} style={famStyle(hit.family)}
           onclick={() => pick(hit)} onmousemove={() => (selected = k)}>
        <span class="dotf"></span>
        <span class="tx"><b class:bn={n.titleLatin}>{n.title}</b>{#if n.sub}<span class="bn">{n.sub}</span>{/if}</span>
      </div>
    {/each}
    {#if addable}
      <!-- svelte-ignore a11y_click_events_have_key_events -->
      <div class="res sel" id="{uid}-0" tabindex="-1" role="option" aria-selected="true" onclick={() => (adding = true)}>
        <span class="tx">Add “{addable}” as a custom species</span>
      </div>
    {/if}
  </div>
  <p class:muted={status === 'done'} role="status">{#if status === 'done'}{results.length ? (results.length === 1 ? '1 species' : `${results.length} species`) : foundExcluded ? "That's the species these photos are on." : `No species matches “${searched}”.`}{/if}</p>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}<button class="btn ghost" type="button" onclick={onClose}>Cancel</button>{/snippet}
</Dialog>
{#if adding}
  <AddSpeciesDialog name={addable ?? ''} onAdded={added} onClose={() => (adding = false)} returnFocus={() => input} />
{/if}
