<script>
  import { tick } from 'svelte';
  import { photoCount } from './importView.js';
  import { dismissTaxonomyUpdate, getCustomMatches, getSplits } from './api.js';
  import { refreshStatus, status } from './status.svelte.js';
  import { UPDATE_HREF, splitHref } from './route.js';
  import PausedUpdate from './PausedUpdate.svelte';
  import MatchPrompt from './MatchPrompt.svelte';
  import { DONE, PAUSED, PENDING, UNAVAILABLE } from './states.js';
  import { CUSTOM_SPECIES_MERGED, PAUSED_TAXONOMY_UPDATE, TAXONOMY_UPDATED } from './messages.js';

  /**
   * Home's taxonomy cards (Phase 5b contract §6), before the content as the paused export's: "A taxonomy update is
   * ready" with Review (pending, after Later or once the update screen was left); "Taxonomy updates can't be applied"
   * with the notice when the library's taxonomy is not in this copy (unavailable, contract §7), at every start while it
   * holds; "The taxonomy was updated" with See what changed for the rest of the run once one is done (Dismiss is kept
   * in the library, so the status's `dismissed` keeps it away after a page reload; one that can't be saved leaves the
   * card, saying why); "Taxonomy update paused" with its
   * notice and Retry; "N split species have photos to sort" with a link to the split (one) or a list; and each open
   * custom-species match with Merge / Not now. The splits and matches are read when their counts in the status change
   * (a failure keeps the card, with the count, "the list couldn't be loaded." and Try again).
   */
  let splits = $state([]);
  let matches = $state([]);

  let update = $derived(status.taxonomyUpdate);
  // Something finished in this run: the status says done, or a finish was seen (a merge that finished while a bundle
  // update is pending reads as pending straight away, contract J30).
  let finishedKind = $derived(status.finished?.kind ?? (update?.state === DONE ? update.kind : null));
  let splitCount = $derived(status.splitsToSort);
  let matchCount = $derived(status.customMatches);

  // A list that can't be read still has its card, with the count and Try again (contract §7): `failed` per list,
  // `tries` asks again, `loading` while asked.
  let failed = $state({ splits: false, matches: false });
  let loading = $state({ splits: false, matches: false });
  let tries = $state({ splits: 0, matches: 0 });

  function read(which, count, ask, set) {
    if (!count) {
      set([]);
      failed[which] = false;
      return undefined;
    }
    let cancelled = false;
    loading[which] = true;
    ask()
      .then((list) => {
        if (cancelled) return;
        set(list);
        failed[which] = false;
        // Try again worked: its button is gone, so focus goes to the card that now lists them.
        if (refocus[which]) {
          refocus[which] = false;
          tick().then(() => document.querySelector(`[data-card="${which}"]`)?.focus());
        }
      })
      .catch(() => {
        if (!cancelled) failed[which] = true;
      })
      .finally(() => {
        if (!cancelled) loading[which] = false;
      });
    return () => { cancelled = true; };
  }

  $effect(() => {
    tries.splits;
    return read('splits', splitCount, getSplits, (list) => (splits = list));
  });

  $effect(() => {
    tries.matches;
    return read('matches', matchCount, getCustomMatches, (list) => (matches = list));
  });

  const refocus = { splits: false, matches: false };
  const again = (which) => {
    if (loading[which]) return;
    refocus[which] = true;
    tries[which] += 1;
  };

  let dismissing = $state(false);
  let dismissError = $state('');

  // The card goes with its Dismiss button once the library has it: focus goes to the page.
  async function dismiss() {
    if (dismissing) return;
    dismissing = true;
    dismissError = '';
    try {
      await dismissTaxonomyUpdate();
    } catch (e) {
      dismissError = `Dismiss couldn't be saved: ${e?.message ?? String(e)}`;
      return;
    } finally {
      dismissing = false;
    }
    status.doneDismissed = true;
    await tick();
    document.querySelector('main')?.focus();
  }

  function decided(match) {
    matches = matches.filter((m) => m !== match);
    refreshStatus();
  }

  const key = (m) => `${m.custom.id}|${m.official.id}`;
</script>

{#if finishedKind && !status.doneDismissed && !(update?.state === DONE && update.dismissed)}
  <section class="stop-card" aria-label={finishedKind === 'match' ? CUSTOM_SPECIES_MERGED : TAXONOMY_UPDATED}>
    <div class="stops">
      <p><span><b>{finishedKind === 'match' ? 'A custom species was merged.' : 'The taxonomy was updated.'}</b></span>
        <a class="btn" href={UPDATE_HREF}>See what changed</a>
        <button class="btn ghost" type="button" aria-disabled={dismissing} onclick={dismiss}>Dismiss</button></p>
      {#if dismissError}<p class="problem" role="alert">{dismissError}</p>{/if}
    </div>
  </section>
{/if}
{#if update?.state === PENDING}
  <section class="stop-card" aria-label="Taxonomy update">
    <div class="stops">
      <p><span><b>A taxonomy update is ready.</b> Names and groups change to the newest list; your photos follow.</span>
        <a class="btn" href={UPDATE_HREF}>Review</a></p>
    </div>
  </section>
{:else if update?.state === UNAVAILABLE}
  <section class="stop-card" aria-label="Taxonomy updates can't be applied">
    <div class="stops">
      <p><b>Taxonomy updates can't be applied</b></p>
      <p><span>{update.notice?.message || "This library's species list is not in this copy of Butterfly Atlas."}</span></p>
    </div>
  </section>
{:else if update?.state === PAUSED}
  <section class="stop-card" aria-label={PAUSED_TAXONOMY_UPDATE}><PausedUpdate {update} card label="" /></section>
{/if}
{#if failed.splits && !splits.length}
  <section class="stop-card" aria-label="Split species to sort">
    <div class="stops">
      <p><span>{splitCount === 1 ? '1 split species has' : `${splitCount.toLocaleString()} split species have`} photos to sort — the list couldn't be loaded.</span>
        <button class="btn ghost" type="button" aria-disabled={loading.splits} onclick={() => again('splits')}>Try again</button></p>
    </div>
  </section>
{:else if splits.length}
  <section class="stop-card" aria-label="Split species to sort" data-card="splits" tabindex="-1">
    <div class="stops">
      {#if splits.length === 1}
        <p>
          <span>1 split species has photos to sort: <i class="bn">{splits[0].name}</i>{splits[0].common ? ` (${splits[0].common})` : ''}, {photoCount(splits[0].photos)}.</span>
          <a class="btn" href={splitHref(splits[0].id)}>Sort photos</a>
        </p>
      {:else}
        <p><span>{splits.length.toLocaleString()} split species have photos to sort.</span></p>
        <ul class="upd-splits">
          {#each splits as split (split.id)}
            <li>
              <span><i class="bn">{split.name}</i>{split.common ? ` (${split.common})` : ''} · {photoCount(split.photos)} to sort</span>
              <a class="btn ghost" href={splitHref(split.id)} aria-label="Sort photos of {split.name}">Sort photos</a>
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  </section>
{/if}
{#if failed.matches && !matches.length}
  <section class="stop-card" aria-label="Custom species now in the list">
    <div class="stops">
      <p><span>{matchCount === 1 ? '1 custom species now matches an official one' : `${matchCount.toLocaleString()} custom species now match official ones`} — the list couldn't be loaded.</span>
        <button class="btn ghost" type="button" aria-disabled={loading.matches} onclick={() => again('matches')}>Try again</button></p>
    </div>
  </section>
{:else if matches.length}
  <section class="stop-card" aria-label="Custom species now in the list" data-card="matches" tabindex="-1">
    <div class="stops">
      {#each matches as match (key(match))}<MatchPrompt {match} onDone={decided} />{/each}
    </div>
  </section>
{/if}
