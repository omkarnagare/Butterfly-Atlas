<script>
  import { onMount, tick, untrack } from 'svelte';
  import { PHOTO_ACCEPT, uploadPhoto } from './api.js';
  import { REFUSE_ALL, allAdded, summarise, summaryLines, unknownPlaceLine, uploadAll } from './upload.js';
  import { copyingWords } from './backups.js';
  import { isTopModal, modal } from './modal.js';
  import { famStyle } from './families.js';
  import { HEIC_CHECKING, addedLine, heicMissing } from './heic.js';
  import { status as appStatus } from './status.svelte.js';
  import { DECODER_CHECKING } from './states.js';
  import { BACKUP_COPYING } from './codes.js';

  const PREVIEWS = 30;

  /**
   * "Add photos" side sheet (prototype openAdd): drop or choose files, see previews, optionally tag the
   * batch, then each file is sent and filed as Danaus_chrysippus_NN.ext. Closes by itself when every
   * file was added; otherwise it stays open with a plain report, which also says how many added photos have
   * an unknown place. `files` are photos dropped on the page. HEIC photos added while the computer has no HEIC decoder
   * are said in the report with what to do ("3 HEIC photos were added, but this device can't show them yet. …"), and
   * the sheet then waits for Done (Phase 7b audit 20). Added while the decoder is still being checked, the report says
   * "Checking for a HEIC decoder…" and the line is decided once the check is over: shown (the sheet waits for Done), or
   * the sheet closes by itself as after any clean add (review fix U11). While a backup holds the photos
   * (`status.backup.holdsPhotos`, several-windows A5) Add sends nothing and says why, as the server would.
   */
  let { taxon, tags = [], files = [], onAdded = () => {}, onClose } = $props();

  const LABELS = {
    waiting: 'Waiting', sending: 'Adding…', added: 'Added', addedElsewhereToo: 'Added', skipped: 'Already here',
    refused: 'Not added', cancelled: 'Not added',
  };

  let root = $state(null);
  let input = $state(null);
  let chooseButton = $state(null);
  let picked = $state([]);
  let chosen = $state([]);
  let items = $state(null);
  let summary = $state(null);
  let over = $state(false);
  let stopButton = $state(null);
  let doneButton = $state(null);
  let queue = null;
  let closed = false;
  let closing = false;
  let destroyed = false;
  let depth = 0;
  let seq = 0;

  let sending = $derived(items !== null && summary === null);
  let done = $derived(items ? items.filter((item) => item.state !== 'waiting' && item.state !== 'sending').length : 0);
  let lines = $derived(summary ? summaryLines(summary, taxon) : []);
  // Refused while the taxonomy updates or a backup holds the photos: said once, not file by file.
  let waited = $derived(summary ? summary.refused.filter((r) => REFUSE_ALL.includes(r.code)) : []);
  let waitLine = $derived(waited.length ? `${waited[0].message} ${waited.length === 1 ? '1 photo was' : `${waited.length} photos were`} not added.` : '');
  let refusals = $derived(summary ? summary.refused.filter((r) => !REFUSE_ALL.includes(r.code)) : []);
  let unknown = $derived(summary ? unknownPlaceLine(summary) : '');
  let heicLine = $derived(summary && heicMissing(appStatus.heic) ? addedLine(summary.heicAdded ?? 0, appStatus.heic) : '');
  // HEIC photos added while the decoder check runs: what to say about them waits for its end.
  let heicWaiting = $derived(Boolean(summary?.heicAdded) && appStatus.heic?.state === DECODER_CHECKING);
  let settling = $state(false);
  let speciesTitle = $derived(taxon.common || taxon.name);
  let shownPicks = $derived(picked.slice(0, PREVIEWS));
  let announce = $derived(summary
    ? `Finished. ${[...lines, unknown, heicLine, waitLine].filter(Boolean).join(' ')} ${refusals.map((r) => `${r.name} — ${r.message}`).join(' ')}`.trim()
    : sending ? `${done} of ${items.length} photos done.` : '');

  const preview = (file) => (typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : '');
  const forget = (url) => {
    if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
  };

  onMount(() => {
    take(untrack(() => files));
    chooseButton?.focus();
    return () => {
      destroyed = true;
      queue?.cancel();
      for (const p of picked) forget(p.url);
    };
  });

  function take(list) {
    if (items !== null || !list?.length) return;
    const start = picked.length;
    picked = [...picked, ...[...list].map((file, i) => ({ key: ++seq, file, url: start + i < PREVIEWS ? preview(file) : '', broken: false }))];
  }

  function unpick(entry) {
    forget(entry.url);
    picked = picked.filter((p) => p !== entry);
    // The photo that moves into the first 30 gets its preview now.
    const next = picked[PREVIEWS - 1];
    if (next && !next.url) next.url = preview(next.file);
  }

  function toggleTag(key) {
    chosen = chosen.includes(key) ? chosen.filter((k) => k !== key) : [...chosen, key];
  }

  async function send() {
    const batch = picked.map((p) => p.file);
    const keys = tags.filter((t) => chosen.includes(t.key)).map((t) => t.key);
    items = batch.map((file) => ({ file, state: 'waiting', result: null, message: '' }));
    // A backup holding the photos would refuse each one: none is sent, and the refusal is the server's own.
    const held = appStatus.backup?.holdsPhotos === true
      ? Object.assign(new Error(copyingWords(appStatus.backup)), { code: BACKUP_COPYING, status: 409 }) : null;
    const sendOne = held ? () => Promise.reject(held) : (file, signal) => uploadPhoto(taxon.id, file, { tags: keys, signal });
    queue = uploadAll(batch, sendOne, {
      onChange: (next) => {
        if (!closed) items = next;
      },
    });
    await tick();
    stopButton?.focus();
    const finished = await queue.done;
    queue = null;
    if (destroyed) return;
    summary = summarise(finished);
    onAdded(summary);
    if (closing || (allAdded(summary) && !heicLine && !heicWaiting)) {
      closed = true;
      onClose();
      return;
    }
    if (allAdded(summary) && heicWaiting) settling = true;
    await tick();
    doneButton?.focus();
  }

  // A clean add that waited for the decoder check: a decoder found closes the sheet as any clean add does; none keeps
  // it open with the line.
  $effect(() => {
    if (!settling || heicWaiting) return;
    settling = false;
    if (!heicLine && !closed) {
      closed = true;
      onClose();
    }
  });

  // Closing while photos are being sent works like Stop: what was added is still reported, then the sheet goes.
  function close() {
    if (closed || closing) return;
    if (queue) {
      closing = true;
      queue.cancel();
      return;
    }
    closed = true;
    onClose();
  }

  function onKey(event) {
    if (event.key === 'Escape' && isTopModal(root)) {
      event.preventDefault();
      close();
    }
  }

  const hasFiles = (event) => event.dataTransfer?.types?.includes('Files');

  function onDrag(event) {
    if (!hasFiles(event)) return;
    event.preventDefault();
    over = true;
  }

  // dragenter/dragleave also fire for children, and WebKit's dragleave has no relatedTarget: count them.
  function onEnter(event) {
    if (!hasFiles(event)) return;
    depth += 1;
    onDrag(event);
  }

  function onLeave() {
    depth = Math.max(0, depth - 1);
    if (!depth) over = false;
  }

  function onDrop(event) {
    if (!hasFiles(event)) return;
    event.preventDefault();
    depth = 0;
    over = false;
    take(event.dataTransfer?.files);
  }
</script>

<!-- Photos dropped anywhere while the sheet is open are taken, never opened by the web view. -->
<svelte:window onkeydown={onKey} ondragover={(e) => hasFiles(e) && e.preventDefault()} ondrop={onDrop} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov sheet-bg" bind:this={root} use:modal={{ returnFocus: () => document.querySelector('[data-add-photos]') }}
     onclick={(e) => { if (e.target === e.currentTarget) close(); }}>
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="add-eyebrow add-title" style={famStyle(taxon.family)}>
    <div class="sh-h">
      <span class="eyebrow" id="add-eyebrow">Add photos to</span>
      <h2 id="add-title"><i class="bn">{taxon.name}</i></h2>
      {#if taxon.common}<span class="muted">{taxon.common}</span>{/if}
    </div>
    <div class="sh-b">
      {#if items === null}
        <div class="drop" class:over role="group" aria-label="Drop photos here"
             ondragenter={onEnter} ondragover={onDrag} ondragleave={onLeave} ondrop={(e) => { e.stopPropagation(); onDrop(e); }}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 16V4m0 0-4 4m4-4 4 4" /><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" />
          </svg>
          <b>Drop photos here</b>
          <span class="muted">JPEG, PNG, TIFF, WebP, RAW or HEIC</span>
          <button class="btn ghost" type="button" bind:this={chooseButton} onclick={() => input?.click()}>Choose photos</button>
          <input bind:this={input} type="file" accept={PHOTO_ACCEPT} multiple hidden aria-label="Choose photos"
                 onchange={(e) => { take(e.currentTarget.files); e.currentTarget.value = ''; }} />
        </div>
        <div>
          <div class="lbl2">Tag these photos <span class="muted">(optional, you can tag later)</span></div>
          <div class="chips" role="group" aria-label="Tags for these photos">
            {#each tags as tag (tag.key)}
              <button class="chip" type="button" class:on={chosen.includes(tag.key)} aria-pressed={chosen.includes(tag.key)}
                      onclick={() => toggleTag(tag.key)}>{tag.label}</button>
            {/each}
          </div>
        </div>
      {:else}
        <div class="progress">
          <progress max={items.length} value={done} aria-label="Photos added so far"></progress>
          <span class="muted">{sending ? `Adding ${Math.min(done + 1, items.length)} of ${items.length}…` : `${done} of ${items.length} done`}</span>
        </div>
      {/if}

      <div class="sr-only" role="status">{announce}</div>

      {#if summary}
        <div class="report">
          {#each lines as line (line)}<p>{line}</p>{/each}
          {#if unknown}
            <p>{unknown}</p>
          {/if}
          {#if heicLine}<p>{heicLine}</p>{:else if heicWaiting}<p>{HEIC_CHECKING}</p>{/if}
          {#if waitLine}<p>{waitLine}</p>{/if}
          {#if refusals.length}
            <ul>
              {#each refusals as refusal, k (k)}<li><span class="mono">{refusal.name}</span> — {refusal.message}</li>{/each}
            </ul>
          {/if}
        </div>
      {/if}

      {#if picked.length}
        <ul class="pend" aria-label="Photos to add">
          {#each shownPicks as entry, k (entry.key)}
            {@const state = items?.[k]?.state}
            <li class:sending={state === 'sending'} class:ok={state === 'added' || state === 'addedElsewhereToo'}
                class:bad={state === 'refused' || state === 'cancelled' || state === 'skipped'}>
              {#if entry.url && !entry.broken}
                <img src={entry.url} alt="" loading="lazy" decoding="async" onerror={() => (entry.broken = true)} />
              {:else}
                <span class="nopv">{entry.file.name}</span>
              {/if}
              {#if state}
                <span class="st">{LABELS[state]}</span>
              {:else}
                <button class="un" type="button" aria-label="Leave out {entry.file.name}" onclick={() => unpick(entry)}>×</button>
              {/if}
            </li>
          {/each}
          {#if picked.length > PREVIEWS}<li class="more">+{picked.length - PREVIEWS} more</li>{/if}
        </ul>
      {/if}

      <div class="note">Copied into <span class="mono">{taxon.folder}</span>. Your originals are left untouched.</div>
    </div>
    <div class="sh-f">
      {#if items === null}
        <button class="btn ghost" type="button" onclick={close}>Cancel</button>
        <button class="btn" type="button" disabled={!picked.length} onclick={send}>
          {picked.length ? `Add ${picked.length} ${picked.length === 1 ? 'photo' : 'photos'}` : 'Add photos'}
        </button>
      {:else if sending}
        <button class="btn ghost" type="button" bind:this={stopButton} onclick={() => queue?.cancel()}>Stop</button>
      {:else}
        <button class="btn" type="button" bind:this={doneButton} onclick={close}>Done</button>
      {/if}
    </div>
  </div>
</div>
