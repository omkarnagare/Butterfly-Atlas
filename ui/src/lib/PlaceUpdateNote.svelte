<script>
  import { onDestroy, tick, untrack } from 'svelte';
  import { dismissPlaceUpdate, getPlaceUpdate } from './api.js';
  import { refreshStatus, status } from './status.svelte.js';
  import { photoHref } from './route.js';
  import { noteViewerOpen } from './viewer.js';
  import { LIBRARY_CODES } from './events.js';
  import { knownPlace } from './places.js';
  import {
    countsLine, formerLine, inPlace, keptLine, itemLine, mineLine, moreSpecies, movedLine, movedMore, notCheckedLine, lostLine, pendingLine, stillLine,
  } from './placeUpdates.js';

  /**
   * Home's place note (Phase 5c contract §7), before the content with the taxonomy cards, while `status.placeUpdate.note`:
   * "Place names were updated with this version" with the counts sentence, the changes that touch the library, each
   * place of the user's merged into an official one, the GPS re-check's moves ("the numbers here will grow" while it
   * runs), the photos left in former places as species lines (the first 10; Show all lists the rest in place), the
   * photos the re-check couldn't do, and Dismiss. The note (GET /api/place-update) is read again whenever the status's
   * `placeUpdate` changes, one read at a time (an answer is always shown; a change meanwhile reads once more after it).
   * One that can't be read keeps the card, with "Place update details couldn't be loaded." and the reason, or, when
   * lines are already shown, "These details couldn't be refreshed." above them; both with Try again (contract §8). The
   * reason is left out when the library's drive is full or gone (the banner says so). A note the server no longer has
   * (204) says "Nothing to show any more." until the status, asked again, takes the card away.
   *
   * After Dismiss, while any photo is still in a former place (`formerPhotos`) or couldn't be re-checked (`notChecked`,
   * contract §9: photos that fail the re-check after a Dismiss are still told), a smaller card "12 photos are still in a
   * former district" / "3 photos couldn't be re-checked…" with Review, which lists the same species lines and photos;
   * Review moves focus to the first of them. Photos the re-check left without a place since the last Dismiss (`lost`,
   * contract §11) keep that card too, their lines listed on it, with its own Dismiss.
   * Dismiss is held (aria-disabled, with why) while the re-check runs. When the status says the note's counts couldn't
   * be read (`failed`), a card says so first, with Try again (the status and the note are read again).
   * Lines that Show all or Review add fade in (at once under reduced motion); every link and button is in the Tab
   * order, and a busy one stays there (aria-disabled).
   */
  const SHOWN = 10;
  const uid = $props.id();

  let brief = $derived(status.placeUpdate);
  // The status is a new object on every answer: the note is read again only when what it says changes.
  let key = $derived(brief ? JSON.stringify(brief) : '');
  let note = $state(null);
  let failed = $state(false);
  // Why the last read failed ('' when the banner already says it), and a 204 while the status still says there is a note.
  let problem = $state('');
  let gone = $state(false);
  // A read failed over lines already shown: "These details couldn't be refreshed." stays until a Try again works, two
  // reads in a row do, or one does with no further read queued.
  let stale = $state(false);
  let okInRow = 0;
  let allShown = $state(false);
  let dismissing = $state(false);
  let dismissError = $state('');
  let card = $state(null);
  let reviewing = $state(false);
  let review = $state(null);

  let full = $derived(brief?.note === true && note?.dismissed !== true);
  let former = $derived(note?.former ?? { photos: 0, species: [] });
  let species = $derived(allShown ? former.species : former.species.slice(0, SHOWN));
  // The status is live; a note read while the re-check still ran may say it runs after it ended.
  let rechecking = $derived(brief?.rechecking === true);
  // The smaller card's counts follow the status (live); its words wait for the note, or for its failure.
  let left = $derived(brief?.formerPhotos > 0 || brief?.notChecked > 0 || brief?.lost > 0);
  let still = $derived(!full && left && (note !== null || failed));
  let stillText = $derived.by(() => {
    if (!brief) return '';
    const former = brief.formerPhotos > 0 ? stillLine(brief.formerPhotos, note?.former?.species ?? []) : '';
    const unchecked = brief.notChecked > 0 ? notCheckedLine(brief.notChecked) : '';
    // Whole sentences, with Review after them.
    return former && unchecked ? `${former}. ${unchecked}` : former ? `${former}.` : unchecked;
  });
  let stillKinds = $derived([
    brief?.formerPhotos > 0 && 'Photos in former places', brief?.notChecked > 0 && 'Photos not re-checked', brief?.lost > 0 && 'Photos without a place',
  ].filter(Boolean));
  let stillLabel = $derived(stillKinds.length === 1 ? stillKinds[0] : 'Photos to review');
  // Review lists the former places and the photos not re-checked; photos without a place are listed on the card.
  let reviewable = $derived(brief?.formerPhotos > 0 || brief?.notChecked > 0);
  // What next for a photo the re-check couldn't do (contract §10): it is tried again at the next start, or, after the
  // last attempt, its place is set by hand.
  const uncheckedNext = (photo) => (photo.final ? 'Set its place by hand (Place… on the species page).' : 'Butterfly Atlas will try again when it next starts.');
  // Photos left with no place, by the place they were in (when the note names it; contract §10 `lost`).
  let lostGroups = $derived.by(() => {
    const groups = new Map();
    for (const photo of note?.lost ?? []) {
      const at = photo.place?.id ?? '';
      if (!groups.has(at)) groups.set(at, { place: photo.place ?? null, photos: [] });
      groups.get(at).photos.push(photo);
    }
    return [...groups.values()];
  });
  // Moves to no place are said by the lost lines (with why and a link), not again as moves.
  let movedGroups = $derived((note?.moved?.groups ?? []).filter((group) => group.to !== null));
  // A species line's link: the species with its place chip, or without one when the place no longer exists.
  const speciesAt = (row) => `#/species/${encodeURIComponent(row.id)}${knownPlace(row.place) ? `?place=${encodeURIComponent(row.place.id)}` : ''}`;
  let hasLines = $derived(Boolean(note?.former?.species.length || note?.notChecked?.length));

  let alive = true;
  onDestroy(() => {
    alive = false;
    current?.abort();
  });

  // One read at a time: a change of the status while one is on its way reads once more when it is back, so a slow
  // answer is never thrown away (while the re-check runs the status changes on every poll). A read gets READ_MS to
  // answer; one that doesn't is stopped and told as a failure. Try again starts a fresh read at once: each read has its
  // generation, and what an older one says later is ignored.
  const READ_MS = 20000;
  const TOO_SLOW = 'Butterfly Atlas took too long to answer. Choose Try again.';
  let reading = false;
  let readAgain = false;
  let generation = 0;
  let current = null;

  function read(byAgain = false) {
    if (reading) {
      readAgain = true;
      return;
    }
    const mine = ++generation;
    const controller = new AbortController();
    current = controller;
    reading = true;
    const ours = () => alive && mine === generation;
    let settled = false;
    const timer = setTimeout(() => {
      if (!ours()) return;
      controller.abort();
      settle(Object.assign(new Error(TOO_SLOW), { code: 'too_slow' }));
    }, READ_MS);
    getPlaceUpdate({ signal: controller.signal })
      .then((answer) => settle(null, answer), (e) => settle(e))
      .finally(() => clearTimeout(timer));

    function settle(error, answer) {
      if (settled || !ours()) return;
      settled = true;
      clearTimeout(timer);
      if (error) failedRead(error);
      else answered(answer, byAgain);
      finished();
    }
  }

  function answered(answer, byAgain) {
    if (!key) return;
    note = answer;
    failed = false;
    okInRow += 1;
    // The details are current once no further read is coming; while more are queued the alert is held (a poll that
    // fails now and then does not put it up again and again).
    if (byAgain || okInRow >= 2 || !readAgain) {
      stale = false;
      problem = '';
    }
    // Try again worked: its button is gone, so focus goes to what took its place (unless the user has moved on).
    if (refocus) {
      const at = document.activeElement;
      // Still on a Try again (data-again), or, as WebKit does not focus a clicked button, on the card itself (where Review
      // put it): the user has not moved on.
      const stayed = !at || at === document.body || at === card || at.hasAttribute?.('data-again');
      refocus = false;
      if (stayed) tick().then(() => { if (alive) focusLines(); });
    }
    // Nothing to show after all (the status was behind): it is asked again, and the card follows it.
    gone = answer === null;
    if (gone) refreshStatus();
  }

  function failedRead(e) {
    if (!key) return;
    // A Try again that failed leaves focus on its button; a later read does not take it.
    refocus = false;
    failed = true;
    okInRow = 0;
    if (note) stale = true;
    problem = LIBRARY_CODES.includes(e?.code) ? '' : e?.message ?? String(e);
  }

  function finished() {
    reading = false;
    current = null;
    const more = readAgain && key;
    readAgain = false;
    if (more) read();
  }

  $effect(() => {
    if (!key) {
      note = null;
      failed = false;
      gone = false;
      stale = false;
      return;
    }
    untrack(read);
  });

  // The status says the note's counts couldn't be read (contract §10): Try again asks the status and the note again.
  // One that changes nothing says when it tried, in a status (emptied first, so the same words are heard again).
  let statusBusy = $state(false);
  let triedNote = $state('');
  const clock = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  async function againAll() {
    if (statusBusy) return;
    statusBusy = true;
    triedNote = '';
    try {
      await refreshStatus();
    } finally {
      statusBusy = false;
    }
    if (!alive) return;
    if (key) again(!brief?.failed);
    if (brief?.failed) {
      await tick();
      if (alive) triedNote = `Tried again at ${clock(new Date())} — still couldn't be loaded.`;
    }
  }

  // Try again: a fresh read now, whatever an earlier one is doing (one stuck is stopped and ignored).
  // `andFocus`: when its answer comes, focus goes to what took the Try again's place.
  let refocus = false;
  function again(andFocus = true) {
    refocus = andFocus === true;
    current?.abort();
    current = null;
    generation += 1;
    reading = false;
    readAgain = false;
    read(true);
  }

  // The first line under Review (a species line or a photo not re-checked); without lines, or on the note, the card.
  function focusLines() {
    const first = reviewing ? card?.querySelector('.pu-new a') : null;
    (first ?? card)?.focus();
  }

  // Show all lists the rest in place; focus goes to the first line it added.
  async function showAll() {
    allShown = true;
    await tick();
    card?.querySelectorAll('.pu-species a')[SHOWN]?.focus();
  }

  // Review lists the lines under the smaller card; focus goes to the first of them (to the card while they can't be read).
  async function toggleReview() {
    reviewing = !reviewing;
    if (!reviewing) return;
    await tick();
    if (alive) focusLines();
  }

  // The card goes with its Dismiss: focus goes to the smaller card in its place, or to the page's heading (not when
  // Home was left meanwhile).
  async function dismiss() {
    // Held while the re-check runs (contract §10): its moves and failures still belong on the note.
    if (dismissing || rechecking) return;
    dismissing = true;
    dismissError = '';
    try {
      await dismissPlaceUpdate();
    } catch (e) {
      if (!alive) return;
      // The server's words (a full or missing drive says so); the card stays, with Dismiss.
      dismissError = e?.message ?? String(e);
      dismissing = false;
      return;
    }
    dismissing = false;
    // The smaller card starts folded: its Review shows the first lines again.
    allShown = false;
    // The note and the photos left without a place until now are dismissed (contract §11); the rest stays.
    const after = { ...brief, note: false, lost: 0 };
    const more = after.formerPhotos > 0 || after.notChecked > 0 || after.pending > 0 || after.failed || after.rechecking;
    status.placeUpdate = more ? after : null;
    refreshStatus();
    await tick();
    if (!alive) return;
    (review ?? document.querySelector('h1'))?.focus();
  }
</script>

{#snippet speciesLines()}
  <ul class="pu-list pu-species">
    {#each species as row, i (`${row.id}|${row.place.id}`)}
      <li class:pu-new={allShown && i >= SHOWN}><a href={speciesAt(row)}><i class="bn">{row.name}</i> — {inPlace(row)}</a></li>
    {/each}
  </ul>
  {#if !allShown && former.species.length > SHOWN}
    <p><span>{moreSpecies(former.species.length - SHOWN)}</span>
      <button class="btn ghost" type="button" onclick={showAll}>Show all</button></p>
  {/if}
  <p><span>Open each species, select the photos and choose Place… to give them a current one.</span></p>
{/snippet}

{#snippet uncheckedLinks()}
  <ul class="pu-list">
    {#each note.notChecked as photo (photo.photoId)}
      <li><a href={photoHref(photo.taxonId, photo.photoId)} onclick={noteViewerOpen}>{photo.fileName}</a> <span class="muted">· <i class="bn">{photo.name}</i></span>{#if photo.reason || photo.final !== undefined}{` — ${[photo.reason, uncheckedNext(photo)].filter(Boolean).join(' ')}`}{/if}</li>
    {/each}
  </ul>
{/snippet}

{#snippet lostLines()}
  {#each lostGroups as group (group.place?.id ?? '')}
    <p><span>{lostLine(group.photos.length, group.place)}</span></p>
    <ul class="pu-list">
      {#each group.photos as photo (photo.photoId)}
        <li><a href={photoHref(photo.taxonId, photo.photoId)} onclick={noteViewerOpen}>{photo.fileName}</a> <span class="muted">· <i class="bn">{photo.name}</i></span></li>
      {/each}
    </ul>
  {/each}
{/snippet}

{#snippet dismissButton()}
  {#if dismissError}<p class="problem" role="alert">{dismissError}</p>{/if}
  <p><button class="btn ghost" type="button" aria-disabled={dismissing || rechecking} aria-describedby={rechecking ? `${uid}-held` : undefined}
             onclick={dismiss}>Dismiss</button>
    {#if rechecking}<span id="{uid}-held">You can dismiss this once photo places are checked.</span>{/if}</p>
{/snippet}

{#snippet notLoaded(words)}
  <p class="problem" role="alert">{words}{problem ? ` ${problem}` : ''}</p>
  <p><button class="btn ghost" type="button" data-again onclick={() => again()}>Try again</button></p>
{/snippet}

{#if brief?.failed}
  <section class="stop-card pu" aria-label="Place update">
    <div class="stops">
      <p class="problem" role="alert">Place update details couldn't be loaded.</p>
      <p><button class="btn ghost" type="button" data-again aria-disabled={statusBusy} onclick={againAll}>Try again</button></p>
      <p role="status">{triedNote}</p>
    </div>
  </section>
{/if}
{#if full}
  <section class="stop-card pu" aria-label="Place names updated" tabindex="-1" bind:this={card}>
    <div class="stops">
      <p><span><b>Place names were updated with this version.</b>{note ? ` ${countsLine(note.counts)}` : ''}</span></p>
      {#if failed && !note}
        <!-- while the status says the counts couldn't be read, the card above says so (once) -->
        {#if !brief.failed}{@render notLoaded("Place update details couldn't be loaded.")}{/if}
      {:else if gone}
        <p><span>Nothing to show any more.</span></p>
      {:else if !note}
        <p role="status"><span>Loading the details…</span></p>
      {:else}
        {#if stale && !brief.failed}{@render notLoaded("These details couldn't be refreshed.")}{/if}
        {#if note.items.length}
          <ul class="pu-list">{#each note.items as item, i (i)}<li>{itemLine(item)}</li>{/each}</ul>
        {/if}
        {#each note.mine as mine, i (i)}<p><span>{mineLine(mine)}</span></p>{/each}
        {#each note.kept ?? [] as kept, i (i)}<p><span>{keptLine(kept)}</span></p>{/each}
        {#if movedGroups.length}
          <ul class="pu-list">
            {#each movedGroups as group, i (i)}<li>{movedLine(group)}</li>{/each}
            {#if movedMore(note.moved)}<li>{movedMore(note.moved)}</li>{/if}
          </ul>
        {/if}
        {#if rechecking}<p><span class="muted">Checking photo places… — the numbers here will grow.</span></p>
        {:else if brief.pending > 0}<p><span>{pendingLine(brief.pending)}</span></p>{/if}
        {#if former.photos > 0}
          <p><span>{formerLine(former.photos, former.species)}</span></p>
          {@render speciesLines()}
        {/if}
        {@render lostLines()}
        {#if note.notChecked?.length}
          <p><span>{notCheckedLine(note.notChecked.length)}</span></p>
          {@render uncheckedLinks()}
        {/if}
      {/if}
      {@render dismissButton()}
    </div>
  </section>
{:else if still}
  <section class="stop-card pu" aria-label={stillLabel} tabindex="-1" bind:this={card}>
    <div class="stops">
      {#if stale && note && !brief.failed}{@render notLoaded("These details couldn't be refreshed.")}{/if}
      {#if reviewable}
        <p><span>{stillText}</span>
          <button class="btn ghost" type="button" bind:this={review} aria-expanded={reviewing} onclick={toggleReview}>Review</button></p>
      {/if}
      {#if brief.lost > 0}
        {#if lostGroups.length}{@render lostLines()}{:else}<p><span>{lostLine(brief.lost, null)}</span></p>{/if}
      {/if}
      {#if reviewing && reviewable}
        {#if hasLines}
          <div class="stops pu-new">
            {#if note.former?.species.length}{@render speciesLines()}{/if}
            {#if note.notChecked?.length}{@render uncheckedLinks()}{/if}
          </div>
        {:else if failed && !brief.failed}{@render notLoaded("Place update details couldn't be loaded.")}{/if}
      {/if}
      {#if brief.lost > 0}{@render dismissButton()}{/if}
    </div>
  </section>
{/if}
