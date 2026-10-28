<script>
  import { photoCount } from './importView.js';
  import { KIND_LABELS } from './updates.js';
  import { RANK_LABELS } from './names.js';
  import { SHOW_FEWER } from './messages.js';

  /**
   * The pending update's changes, one collapsible list per kind (Phase 5b contract §5 Summary, §6): its label, how many
   * and the photos they touch. A kind opens with the items that have photos (most first, each with its count; as the
   * server sends them); the rest are under "and N more" (a toggle, so focus stays on it). A kind with photos starts
   * open, the others closed.
   */
  let { kinds } = $props();

  const uid = $props.id();
  // Open or closed per kind (until toggled: open when it has photos), and whether its items without photos show.
  let open = $state({});
  let rest = $state({});
  const isOpen = (k) => open[k.kind] ?? k.photos > 0;
</script>

<div class="upd-kinds">
  {#each kinds as k (k.kind)}
    {@const withPhotos = k.items.filter((item) => item.photos > 0)}
    {@const others = k.items.filter((item) => !item.photos)}
    {@const more = others.length}
    <section class="upd-kind" aria-labelledby="{uid}-{k.kind}">
      <h2 id="{uid}-{k.kind}">
        <button type="button" class="upd-toggle" aria-expanded={isOpen(k)} aria-controls="{uid}-{k.kind}-list"
                onclick={() => (open[k.kind] = !isOpen(k))}>
          <span class="upd-chev" aria-hidden="true"></span>
          <span>{KIND_LABELS[k.kind] ?? k.kind}</span>
          <span class="n">{k.count.toLocaleString()}</span>
          {#if k.photos}<span class="muted">· {photoCount(k.photos)}</span>{/if}
        </button>
      </h2>
      <div id="{uid}-{k.kind}-list" hidden={!isOpen(k)}>
        <ul class="upd-items">
          {#each withPhotos as item, i (i)}
            <li>
              {#if item.rank !== 'species'}<span class="eyebrow">{RANK_LABELS[item.rank] ?? item.rank}</span>{/if}
              <span class="upd-detail">{item.detail}</span>
              <span class="muted">{photoCount(item.photos)}</span>
            </li>
          {/each}
          {#if rest[k.kind]}
            {#each others as item, i (i)}
              <li>
                {#if item.rank !== 'species'}<span class="eyebrow">{RANK_LABELS[item.rank] ?? item.rank}</span>{/if}
                <span class="upd-detail">{item.detail}</span>
              </li>
            {/each}
          {/if}
        </ul>
        {#if more > 0}
          <button type="button" class="upd-more" aria-expanded={rest[k.kind] === true} onclick={() => (rest[k.kind] = !rest[k.kind])}>
            {rest[k.kind] ? SHOW_FEWER : withPhotos.length ? `and ${more.toLocaleString()} more` : `Show all ${more.toLocaleString()}`}
          </button>
        {/if}
      </div>
    </section>
  {/each}
</div>
