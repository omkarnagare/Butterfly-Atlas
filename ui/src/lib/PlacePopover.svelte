<script>
  import { onDestroy } from 'svelte';
  import Popover from './Popover.svelte';
  import { searchPlaces } from './api.js';
  import { MAX_IDS, placeName, UNNAMED } from './filter.js';
  import { liveCount } from './liveCount.js';
  import { placeWhere } from './places.js';
  import { PLACE_PROMPT, UNKNOWN_PLACE } from './messages.js';

  /**
   * The Place popover (contract §4): the place search of the place picker (150 ms after typing pauses, from 2 letters,
   * /api/places/search) under a first row "Unknown place"; choosing a row (Enter or a click) adds it to the filter's
   * places (`onChange(filter, { places: [place] })`). The chosen places are listed with remove buttons.
   */
  let { filter, names, anchor = null, returnFocus, onChange, onClose } = $props();

  const uid = $props.id();
  let input = $state(null);
  let query = $state('');
  let results = $state([]);
  let searched = $state('');
  let selected = $state(0);
  let error = $state('');

  let options = $derived([{ id: 'unknown' }, ...results]);

  const live = liveCount(searchPlaces, {
    delay: 150,
    onAnswer(found, text) {
      results = found;
      searched = text;
      selected = found.length ? 1 : 0;
    },
    onError(e) {
      error = e.message;
    },
  });
  onDestroy(live.stop);

  function onInput(event) {
    query = event.currentTarget.value;
    error = '';
    results = [];
    searched = '';
    selected = 0;
    const wanted = query.trim();
    if (wanted.length < 2) live.stop();
    else live.ask(wanted);
  }

  const chosen = (place) => filter.places.includes(place.id);

  function add(place) {
    if (chosen(place)) return;
    if (filter.places.length >= MAX_IDS) {
      error = 'Choose at most 200 places.';
      return;
    }
    error = '';
    onChange({ ...filter, places: [...filter.places, place.id] }, place.id === 'unknown' ? {} : { places: [place] });
    input?.focus();
  }

  // The pressed Remove button leaves the list: focus moves to the next one, else the previous, else the search field.
  let chosenList = $state(null);
  let refocus = null;

  function remove(id, k) {
    refocus = k;
    onChange({ ...filter, places: filter.places.filter((each) => each !== id) }, {});
  }

  $effect(() => {
    void filter.places;
    if (refocus === null) return;
    const buttons = chosenList ? [...chosenList.querySelectorAll('button')] : [];
    (buttons[refocus] ?? buttons[refocus - 1] ?? input)?.focus();
    refocus = null;
  });

  function onKeydown(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      selected = Math.min(options.length - 1, selected + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      selected = Math.max(0, selected - 1);
    } else if (event.key === 'Enter' && options[selected]) {
      event.preventDefault();
      add(options[selected]);
    }
  }

  $effect(() => {
    const at = selected;
    document.getElementById(`${uid}-${at}`)?.scrollIntoView?.({ block: 'nearest' });
  });
</script>

<Popover title="Place" {anchor} {returnFocus} {onClose}>
  <input class="field" bind:this={input} data-autofocus value={query} oninput={onInput} onkeydown={onKeydown} autocomplete="off" spellcheck="false"
         placeholder={PLACE_PROMPT} aria-label="Place" role="combobox" aria-autocomplete="list" aria-expanded="true"
         aria-controls="{uid}-list" aria-activedescendant="{uid}-{selected}" />
  <div class="pick-list" id="{uid}-list" role="listbox" aria-label="Places">
    {#each options as place, k (place.id)}
      <!-- svelte-ignore a11y_click_events_have_key_events -->
      <div class="res" id="{uid}-{k}" tabindex="-1" role="option" aria-selected={k === selected} aria-disabled={chosen(place) ? 'true' : undefined}
           class:sel={k === selected} onclick={() => add(place)} onmousemove={() => (selected = k)}>
        {#if place.id === 'unknown'}
          <span class="tx"><span class="pl-line"><b>{UNKNOWN_PLACE}</b></span></span>
        {:else}
          <span class="tx"><span class="pl-line"><b>{place.name}</b>{#if place.path.length > 1}<span class="muted">{` · ${placeWhere(place)}`}</span>{/if}</span></span>
          <span class="rk2">{place.level}</span>
        {/if}
      </div>
    {/each}
  </div>
  <p class="muted fp-status" role="status">{#if searched && query.trim() === searched && !results.length}No place matches “{searched}”.{/if}</p>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#if filter.places.length}
    <ul class="chosen" aria-label="Chosen places" bind:this={chosenList}>
      {#each filter.places as id, k (id)}
        {@const name = placeName(names, id)}
        <!-- Until its name comes, the button is named by the id ("Remove …" would say nothing). -->
        <li class="chip">{name} <button type="button" aria-label="Remove {name === UNNAMED ? id : name}" onclick={() => remove(id, k)}>×</button></li>
      {/each}
    </ul>
  {/if}
</Popover>
