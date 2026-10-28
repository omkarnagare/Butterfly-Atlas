<script>
  import { onDestroy } from 'svelte';
  import Popover from './Popover.svelte';
  import { search } from './api.js';
  import { MAX_IDS, taxonName, UNNAMED } from './filter.js';
  import { famStyle } from './families.js';
  import { liveCount } from './liveCount.js';
  import { RANK_LABELS, names as titles } from './names.js';

  /**
   * The Taxa popover (contract §4): a search field (150 ms after typing pauses, /api/search) whose results show their
   * rank and family; choosing one (Enter or a click) adds it to the filter's taxa, and its name is passed on with
   * `onChange(filter, { taxa: [hit] })`. The chosen taxa are listed with remove buttons. Taxa at any rank; the whole
   * group is what no taxa means, so it is not offered.
   */
  let { filter, names, anchor = null, returnFocus, onChange, onClose } = $props();

  const uid = $props.id();
  let input = $state(null);
  let query = $state('');
  let results = $state([]);
  let searched = $state('');
  let selected = $state(0);
  let error = $state('');

  const live = liveCount((text, options) => search(text, { limit: 20, ...options }), {
    delay: 150,
    onAnswer(found, text) {
      results = found.filter((hit) => hit.rank !== 'group' && hit.rank !== 'additions');
      searched = text;
      selected = 0;
    },
    onError(e) {
      error = e.message;
    },
  });
  onDestroy(live.stop);

  function onInput(event) {
    query = event.currentTarget.value;
    error = '';
    // The results of the older words go at once, so Enter cannot add one of them.
    results = [];
    searched = '';
    selected = 0;
    const wanted = query.trim();
    if (!wanted) live.stop();
    else live.ask(wanted);
  }

  const chosen = (hit) => filter.taxa.includes(hit.id);

  function add(hit) {
    if (chosen(hit)) return;
    if (filter.taxa.length >= MAX_IDS) {
      error = 'Choose at most 200 taxa.';
      return;
    }
    error = '';
    onChange({ ...filter, taxa: [...filter.taxa, hit.id] }, { taxa: [hit] });
    input?.focus();
  }

  // The pressed Remove button leaves the list: focus moves to the next one, else the previous, else the search field.
  let chosenList = $state(null);
  let refocus = null;

  function remove(id, k) {
    refocus = k;
    onChange({ ...filter, taxa: filter.taxa.filter((each) => each !== id) }, {});
  }

  $effect(() => {
    void filter.taxa;
    if (refocus === null) return;
    const buttons = chosenList ? [...chosenList.querySelectorAll('button')] : [];
    (buttons[refocus] ?? buttons[refocus - 1] ?? input)?.focus();
    refocus = null;
  });

  function onKeydown(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      selected = Math.min(results.length - 1, selected + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      selected = Math.max(0, selected - 1);
    } else if (event.key === 'Enter' && results[selected]) {
      event.preventDefault();
      add(results[selected]);
    }
  }

  $effect(() => {
    const at = selected;
    if (results.length) document.getElementById(`${uid}-${at}`)?.scrollIntoView?.({ block: 'nearest' });
  });

  /** Under the name: the other name, and the family when the taxon is below it. */
  function sub(hit) {
    const n = titles(hit);
    return { other: n.sub, latin: n.subLatin, family: hit.rank !== 'family' ? hit.family : null };
  }
</script>

<Popover title="Taxa" {anchor} {returnFocus} {onClose}>
  <input class="field" bind:this={input} data-autofocus value={query} oninput={onInput} onkeydown={onKeydown} autocomplete="off" spellcheck="false"
         placeholder="A family, genus or species" aria-label="Find a taxon" role="combobox" aria-autocomplete="list"
         aria-expanded={results.length > 0} aria-controls="{uid}-list"
         aria-activedescendant={results.length ? `${uid}-${selected}` : undefined} />
  <div class="pick-list" id="{uid}-list" role="listbox" aria-label="Taxa found">
    {#each results as hit, k (hit.id)}
      {@const n = titles(hit)}
      {@const s = sub(hit)}
      <!-- svelte-ignore a11y_click_events_have_key_events -->
      <div class="res" id="{uid}-{k}" tabindex="-1" role="option" aria-selected={k === selected} aria-disabled={chosen(hit) ? 'true' : undefined}
           class:sel={k === selected} style={famStyle(hit.family)} onclick={() => add(hit)} onmousemove={() => (selected = k)}>
        <span class="dotf"></span>
        <span class="tx">
          <b class:bn={n.titleLatin}>{n.title}</b>
          {#if s.other || s.family}<span class="muted">{#if s.other}<i class:bn={s.latin} class:plain={!s.latin}>{s.other}</i>{/if}{#if s.family}{s.other ? ` · ${s.family}` : s.family}{/if}</span>{/if}
        </span>
        <span class="rk2">{RANK_LABELS[hit.rank] ?? hit.rank}</span>
      </div>
    {/each}
  </div>
  <p class="muted fp-status" role="status">{#if searched && query.trim() === searched}{results.length ? (results.length === 1 ? '1 result' : `${results.length} results`) : `No species or group matches “${searched}”.`}{/if}</p>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#if filter.taxa.length}
    <ul class="chosen" aria-label="Chosen taxa" bind:this={chosenList}>
      {#each filter.taxa as id, k (id)}
        {@const name = taxonName(names, id)}
        <!-- Until its name comes, the button is named by the id ("Remove …" would say nothing). -->
        <li class="chip">{name} <button type="button" aria-label="Remove {name === UNNAMED ? id : name}" onclick={() => remove(id, k)}>×</button></li>
      {/each}
    </ul>
  {/if}
</Popover>

<style>
  i.plain { font-style: normal; }
</style>
