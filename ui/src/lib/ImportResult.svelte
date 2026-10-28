<script>
  import { pairChoiceLabel, pairName, photoCount } from './importView.js';
  import { getTaxon } from './api.js';
  import { butterflySvg } from './butterfly.js';
  import { famStyle } from './families.js';
  import { meadow } from './meadow.js';

  /**
   * What an import has done so far (contract §2 ImportResult; design §4.5): photos filed per species (new ones
   * marked), and the rest in plain words: already in the atlas, also filed elsewhere, copies, skipped, couldn't
   * be filed, problems by reason, the files a RAW + JPEG choice left out (Phase 7a), and how many still wait.
   * `kinds` (the import's `pairs.kinds`, Phase 7b) names the pairs: "left out by your RAW + HEIC choice (HEIC only)".
   * Meadow (design §5): once filing has finished (photos filed, none waiting) the result line gets a small static meadow
   * sprig with a butterfly in the colour of the most filed species' family (read with its taxon: the result names no
   * families); until that is read, or if it can't be, the butterfly is in the accent.
   */
  let { result, kinds = undefined } = $props();

  const num = (n) => n.toLocaleString();
  const SPRIG = meadow(150, 46, { seed: 9 });
  let done = $derived(result.filed > 0 && !result.left);
  let top = $derived(done ? (result.bySpecies ?? []).reduce((best, row) => (row.count > (best?.count ?? 0) ? row : best), null) : null);
  let family = $state(null);
  $effect(() => {
    const id = top?.taxonId;
    family = null;
    if (!id) return undefined;
    let gone = false;
    // Only a decoration: whatever goes wrong, the butterfly stays in the accent.
    Promise.resolve().then(() => getTaxon(id)).then((taxon) => {
      if (!gone) family = taxon?.family ?? null;
    }, () => {});
    return () => {
      gone = true;
    };
  });
  let others = $derived([
    result.duplicates && `${photoCount(result.duplicates)} already in your atlas, not filed again`,
    result.alsoElsewhere && `${photoCount(result.alsoElsewhere)} filed, also under another species`,
    result.copies && `${result.copies === 1 ? '1 copy' : `${num(result.copies)} copies`} of other photos in this folder, not filed`,
    result.skipped && `${photoCount(result.skipped)} skipped, left in the folder`,
    result.failed && `${photoCount(result.failed)} couldn't be filed`,
    ...(result.problems ?? []).map((p) => `${photoCount(p.count)}: ${p.reason}`),
    result.pairLeft && `${result.pairLeft === 1 ? '1 file' : `${num(result.pairLeft)} files`} left out by your ${pairName(kinds)} choice${result.pairChoice ? ` (${pairChoiceLabel(result.pairChoice, kinds)})` : ''}`,
  ].filter(Boolean));
</script>

<div class="ir">
  <div class="ir-line">
    <p class="ir-big"><b>{photoCount(result.filed)}</b> filed{#if result.newSpecies}, <b>{num(result.newSpecies)}</b> new species{/if}</p>
    {#if done}<span class="ir-sprig" aria-hidden="true">{@html SPRIG}<span class="ir-fly" style={famStyle(family)}>{@html butterflySvg}</span></span>{/if}
  </div>
  {#if result.bySpecies?.length}
    <ul class="ir-sp" aria-label="Filed by species">
      {#each result.bySpecies as row (row.taxonId)}
        <li>
          <span class="nm">{row.common || row.name}</span>
          {#if row.common}<i class="bn">{row.name}</i>{/if}
          <span class="n">{num(row.count)}</span>
          {#if row.isNew}<span class="badge new">New</span>{/if}
        </li>
      {/each}
    </ul>
  {/if}
  {#if others.length}
    <ul class="ir-o">{#each others as line, k (k)}<li>{line}</li>{/each}</ul>
  {/if}
  {#if result.left}<p class="muted">{photoCount(result.left)} {result.left === 1 ? 'is' : 'are'} still waiting to be filed.</p>{/if}
</div>
