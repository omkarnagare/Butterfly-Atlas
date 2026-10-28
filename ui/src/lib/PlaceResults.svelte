<script>
  import { placeWhere } from './places.js';
  import { UNKNOWN_PLACE } from './messages.js';

  /**
   * The Export screen's Places view (contract §4, design D7): the countries (no place chosen), the chosen place's
   * children (one chosen) or the chosen places (several), as the server answers them, each "Pune · Maharashtra, India —
   * 31 species · 140 photos", and "Unknown place" last. Choosing a row narrows the place filter to it alone
   * (`onChoose(row)`, so its name is known at once). Arrow keys, Home and End move between rows. The rows rise in with a short stagger.
   */
  let { rows, onChoose } = $props();

  let list = $state(null);
  const n = (count, one, many) => `${count.toLocaleString()} ${count === 1 ? one : many}`;

  function onKey(event, at) {
    const to = { ArrowDown: at + 1, ArrowUp: at - 1, Home: 0, End: rows.length - 1 }[event.key];
    if (to === undefined) return;
    event.preventDefault();
    list?.querySelectorAll('button')[Math.max(0, Math.min(rows.length - 1, to))]?.focus();
  }
</script>

{#if rows.length}
  <!-- role="list": WebKit drops the list role of a list styled without bullets. -->
  <ul class="fplaces stagger" role="list" aria-label="Places" bind:this={list}>
    {#each rows as row, i (row.id)}
      <li style="--i: {Math.min(i, 12)}">
        <button type="button" class="fplace" onclick={() => onChoose(row)} onkeydown={(e) => onKey(e, i)}>
          <span class="tx">
            {#if row.id === 'unknown'}<b>{UNKNOWN_PLACE}</b>{:else}<b>{row.name}</b>{#if row.path.length > 1}<span class="muted">{` · ${placeWhere(row)}`}</span>{/if}{/if}
          </span>
          <span class="ct">{` — ${n(row.species, 'species', 'species')} · ${n(row.photos, 'photo', 'photos')}`}</span>
        </button>
      </li>
    {/each}
  </ul>
{:else}
  <p class="muted">No matching photos, so no places to show.</p>
{/if}

<style>
  .fplaces { display: grid; gap: 4px; margin: 0; padding: 0; list-style: none; }
  .fplace { display: flex; align-items: baseline; gap: 6px; width: 100%; text-align: left; padding: 14px 14px; border-radius: 14px;
    border: 1px solid transparent; transition: background .2s, border-color .2s; }
  .fplace:hover, .fplace:focus-visible { background: var(--surface); border-color: var(--line); }
  .tx { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .tx b { font-weight: 650; }
  .ct { font-size: 13px; color: var(--muted); white-space: nowrap; font-variant-numeric: tabular-nums; }
</style>
