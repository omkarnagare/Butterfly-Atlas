<script>
  import { photoCount } from '../lib/importView.js';
  import { onMount, tick } from 'svelte';
  import { applyUpdate, getCustomMatches, getUpdate, updateLater } from '../lib/api.js';
  import UpdateKinds from '../lib/UpdateKinds.svelte';
  import PausedUpdate from '../lib/PausedUpdate.svelte';
  import UpdateResult from '../lib/UpdateResult.svelte';
  import { ago } from '../lib/dates.js';
  import { LIBRARY_CHANGED, status, updateChanged } from '../lib/status.svelte.js';
  import LoadError from '../lib/LoadError.svelte';
  import { loadProblem } from '../lib/problems.js';
  import { headline, progressWords, speciesLine, updateLine } from '../lib/updates.js';
  import { DONE, PAUSED, PENDING, RUNNING, UNAVAILABLE, WAITING } from '../lib/states.js';
  import { CUSTOM_SPECIES_MERGED, PAUSED_TAXONOMY_UPDATE, PHOTOS_MOVED, STARTING, TAXONOMY_UPDATED } from '../lib/messages.js';

  /**
   * The taxonomy update (#/update, Phase 5b contract §6, design §4.8). Pending: the headline, the species line, the
   * changes per kind (lib/UpdateKinds.svelte), "Folders on disk are renamed to match. Nothing is deleted.", Later (→
   * Home, remembered for this run) and Apply now. Waiting or running: the progress, kept current by the footer's polls
   * (the status), the page free to leave. Paused: the notice and Retry. Done: what moved, the folders kept, a link to
   * sort each split and the custom-species matches (lib/UpdateResult.svelte, with the folders that couldn't be removed and
   * the photos left behind). None: up to date, with the library's version, and what the last update did (`last`, kept
   * across restarts); unavailable: its notice. The Update is read again whenever the status's state changes, and after LIBRARY_CHANGED. Later and Apply now
   * are aria-disabled while either is asked; a refusal shows above them.
   */
  let update = $state(null);
  let error = $state('');
  let problem = $state('');
  let busy = $state(false);
  let decided = $state(new Set());

  let loads = 0;
  async function load() {
    const mine = ++loads;
    try {
      const answer = await getUpdate();
      if (mine !== loads) return;
      update = answer;
      error = '';
    } catch (e) {
      if (mine === loads && update === null) error = loadProblem(e);
    }
  }

  onMount(() => {
    load();
    window.addEventListener(LIBRARY_CHANGED, load);
    return () => window.removeEventListener(LIBRARY_CHANGED, load);
  });

  // The footer polls while it runs: a change of state (running → done, paused, …) reads the Update again.
  let seenState = status.taxonomyUpdate?.state ?? null;
  $effect(() => {
    const now = status.taxonomyUpdate?.state ?? null;
    if (now === seenState) return;
    seenState = now;
    load();
  });

  // While it runs, the numbers come from the status (polled), as long as it is the same state.
  let live = $derived(update && status.taxonomyUpdate?.state === update.state ? status.taxonomyUpdate : null);
  let progress = $derived(update?.progress ? {
    ...update.progress,
    ...(live ? { done: live.done, total: live.total, step: live.step || update.progress.step, steps: live.steps || update.progress.steps, waitingFor: live.waitingFor } : {}),
  } : null);
  // What finished: this run's result, else the last one kept (`last`, contract §7), after a restart too.
  let shown = $derived(update?.result ?? update?.last ?? null);
  // A result's matches are as they were when it finished: only those still open (GET /api/custom-matches, read again
  // when the status's count changes) are offered; a list that can't be read is said, and none is offered.
  const keyOf = (m) => `${m.custom.id}|${m.official.id}`;
  let open = $state(null);
  let openFailed = $state(false);
  $effect(() => {
    const wanted = shown?.matches?.length ?? 0;
    void status.customMatches;
    if (!wanted) return undefined;
    let cancelled = false;
    getCustomMatches()
      .then((list) => {
        if (cancelled) return;
        open = new Set(list.map(keyOf));
        openFailed = false;
      })
      .catch(() => {
        if (!cancelled) openFailed = true;
      });
    return () => { cancelled = true; };
  });
  let matches = $derived((shown?.matches ?? []).filter((m) => open?.has(keyOf(m)) && !decided.has(keyOf(m))));

  async function later() {
    if (busy) return;
    busy = true;
    problem = '';
    try {
      await updateLater();
      window.location.hash = '#/';
    } catch (e) {
      problem = e.message;
    } finally {
      busy = false;
    }
  }

  async function apply() {
    if (busy) return;
    busy = true;
    problem = '';
    try {
      const answer = await applyUpdate();
      update = answer;
      updateChanged(answer);
      // Apply now is gone: focus goes to the progress's heading.
      await tick();
      document.querySelector('.upd h1')?.focus();
    } catch (e) {
      problem = e.message;
      if (e?.code === 'no_update' || e?.code === 'update_running') load();
    } finally {
      busy = false;
    }
  }

  const decide = (match) => (decided = new Set([...decided, `${match.custom.id}|${match.official.id}`]));
  const merging = () => update?.kind === 'match';
  // The files actually moved (`moved`; an older result without it: `photos`).
  const movedLine = (r) => {
    const n = r.moved ?? r.photos ?? 0;
    if (!n) return 'No photo had to move.';
    return n === 1 ? '1 photo moved to its new folder.' : `${photoCount(n)} moved to their new folders.`;
  };
</script>

{#snippet lastShown()}
  {#if update.last}
    <section class="sec" aria-labelledby="upd-last">
      <h2 id="upd-last">What the last {update.last.from && update.last.to && update.last.from !== update.last.to ? `update (${update.last.from} → ${update.last.to})` : 'change'} did</h2>
      <p class="muted">{update.last.finishedAt ? `Finished ${ago(update.last.finishedAt)}. ` : ''}{movedLine(update.last)}</p>
    </section>
    <UpdateResult result={update.last} {matches} onDecide={decide} />
    {#if update.last.matches?.length && openFailed}<p class="problem">The custom-species matches couldn't be checked. Home shows those still open.</p>{/if}
  {:else if update.lastProblem}
    <!-- what the last update did couldn't be read: said where it would be (contract §7) -->
    <p class="problem upd-why">{update.lastProblem}</p>
  {/if}
{/snippet}

{#if error}
  <LoadError message={error} onRetry={load} />
{:else if update === null}
  <p class="muted loading" role="status">Opening…</p>
{:else}
  <div class="upd">
    {#if update.state === PENDING}
      <!-- a pending update whose summary could not be worked out is still offered, with a plain headline -->
      <div class="xh">
        <div>
          <div class="eyebrow">Taxonomy update · {update.from} → {update.to}</div>
          <h1 tabindex="-1">{update.summary ? headline(update.summary) : 'A taxonomy update is ready.'}</h1>
          {#if update.summary}<div class="cn">{speciesLine(update.summary)}</div>{:else if update.summaryProblem}<p class="problem upd-why">{update.summaryProblem}</p>{/if}
        </div>
      </div>
      {#if update.summary}<UpdateKinds kinds={update.summary.kinds} />{/if}
      <p class="muted">Folders on disk are renamed to match. Nothing is deleted.</p>
      {#if problem}<p class="problem" role="alert">{problem}</p>{/if}
      <div class="upd-acts">
        <button class="btn ghost" type="button" aria-disabled={busy} onclick={later}>Later</button>
        <button class="btn" type="button" aria-disabled={busy} onclick={apply}>{busy ? 'Applying…' : 'Apply now'}</button>
      </div>
      <!-- a merge that finished meanwhile reads as pending (contract J30): what it did shows below the offer -->
      {@render lastShown()}
    {:else if update.state === WAITING || update.state === RUNNING}
      <div class="xh">
        <div>
          <div class="eyebrow">Taxonomy update{merging() ? '' : ` · ${update.from} → ${update.to}`}</div>
          <h1 tabindex="-1">{merging() ? 'Merging the custom species' : 'Updating the taxonomy'}</h1>
          <div class="cn">You can leave this page: the update carries on, and the footer shows how far it has got.</div>
        </div>
      </div>
      <div class="progress upd-progress">
        {#if update.state === WAITING}
          <p>{updateLine({ state: WAITING, waitingFor: progress?.waitingFor ?? null })}</p>
          <progress aria-label={PHOTOS_MOVED}></progress>
        {:else}
          <p>{progress ? progressWords(progress) : STARTING}</p>
          {#if progress?.total}
            <progress max={progress.total} value={progress.done} aria-label={PHOTOS_MOVED}></progress>
          {:else}
            <progress aria-label={PHOTOS_MOVED}></progress>
          {/if}
        {/if}
      </div>
    {:else if update.state === PAUSED}
      <div class="xh">
        <div>
          <div class="eyebrow">Taxonomy update{merging() ? '' : ` · ${update.from} → ${update.to}`}</div>
          <h1 tabindex="-1">{merging() ? 'The merge is paused' : 'The update is paused'}</h1>
          {#if progress?.total}<div class="cn">{progressWords(progress)} so far.</div>{/if}
        </div>
      </div>
      <section class="stop-card" aria-label={PAUSED_TAXONOMY_UPDATE}>
        <PausedUpdate update={{ state: PAUSED, notice: update.notice }} card label="" />
      </section>
    {:else if update.state === DONE}
      <div class="xh">
        <div>
          <div class="eyebrow">Taxonomy update{merging() || !shown?.from ? '' : ` · ${shown.from} → ${shown.to}`}</div>
          <h1 tabindex="-1">{merging() ? CUSTOM_SPECIES_MERGED : TAXONOMY_UPDATED}</h1>
          {#if shown}<div class="cn">{movedLine(shown)}</div>{/if}
        </div>
      </div>
      {#if !update.result && update.resultProblem}<p class="problem" role="alert">{update.resultProblem}</p>{/if}
      {#if shown}<UpdateResult result={shown} {matches} onDecide={decide} />{/if}
      {#if shown?.matches?.length && openFailed}<p class="problem">The custom-species matches couldn't be checked. Home shows those still open.</p>{/if}
    {:else if update.state === UNAVAILABLE}
      <div class="xh"><div><div class="eyebrow">Taxonomy</div><h1 tabindex="-1">Updates can't be applied</h1></div></div>
      <p class="problem">{update.notice?.message ?? "This library's taxonomy is not in this copy of Butterfly Atlas, so updates can't be applied."}</p>
    {:else}
      <div class="xh">
        <div>
          <div class="eyebrow">Taxonomy</div>
          <h1 tabindex="-1">Your taxonomy is up to date.</h1>
          {#if update.from}<div class="cn">It follows the {update.from} list.</div>{/if}
        </div>
      </div>
      {@render lastShown()}
    {/if}
  </div>
{/if}
