<script>
  import { photoCount } from './importView.js';
  import { splitHref } from './route.js';
  import MatchPrompt from './MatchPrompt.svelte';

  /**
   * What a finished update or merge did (Phase 5b contract §5 Result, §7): its notes (`notes`, each as sent; none when
   * missing), the folders kept because they hold other
   * files, emptied folders that couldn't be removed, photos left behind (given up after a pause for a missing file),
   * the split species with photos to sort, and the custom-species matches (`matches`, those not decided yet on this
   * page; `onDecide(match)` when one is).
   */
  let { result, matches = [], onDecide = () => {} } = $props();

  const uid = $props.id();
</script>

{#if result.notes?.length}
  <ul class="upd-notes" aria-label="Notes">{#each result.notes as note, k (k)}<li>{note}</li>{/each}</ul>
{/if}
{#if result.keptFolders?.length}
  <section class="sec upd-kept" aria-labelledby="{uid}-kept">
    <h2 id="{uid}-kept">Folders kept</h2>
    <p class="muted">These folders still hold other files, so they were left where they are:</p>
    <ul>{#each result.keptFolders as folder (folder)}<li class="mono">{folder}</li>{/each}</ul>
  </section>
{/if}
{#if result.notRemoved?.length}
  <section class="sec upd-kept" aria-labelledby="{uid}-not-removed">
    <h2 id="{uid}-not-removed">Folders that couldn't be removed</h2>
    <p class="muted">These folders were emptied but couldn't be removed. You can delete them yourself:</p>
    <ul>{#each result.notRemoved as folder (folder)}<li class="mono">{folder}</li>{/each}</ul>
  </section>
{/if}
{#if result.leftBehind?.length}
  <section class="sec upd-kept" aria-labelledby="{uid}-left">
    <h2 id="{uid}-left">Photos left behind</h2>
    <p class="muted">{result.leftBehind.length === 1 ? 'This photo' : 'These photos'} couldn't be found in {result.leftBehind.length === 1 ? 'its' : 'their'} old or new folder, so the update went on without {result.leftBehind.length === 1 ? 'it' : 'them'}:</p>
    <ul>{#each result.leftBehind as photo (photo.id)}<li><span class="mono">{photo.fileName}</span> <span class="muted">— was in {photo.oldPath}</span></li>{/each}</ul>
  </section>
{/if}
{#if result.splits?.length}
  <section class="sec" aria-labelledby="{uid}-splits">
    <h2 id="{uid}-splits">Split species</h2>
    <p class="muted">Each of these became several species. Choose which one each photo shows.</p>
    <ul class="upd-splits">
      {#each result.splits as split (split.id)}
        <li>
          <span><i class="bn">{split.name}</i>{split.common ? ` (${split.common})` : ''} · {photoCount(split.photos)} to sort</span>
          <a class="btn ghost" href={splitHref(split.id)} aria-label="Sort photos of {split.name}">Sort photos</a>
        </li>
      {/each}
    </ul>
  </section>
{/if}
{#if matches.length}
  <section class="sec" aria-labelledby="{uid}-matches">
    <h2 id="{uid}-matches">Custom species now in the list</h2>
    {#each matches as match (`${match.custom.id}|${match.official.id}`)}<MatchPrompt {match} onDone={onDecide} />{/each}
  </section>
{/if}
