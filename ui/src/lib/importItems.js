/**
 * The import's items as the grid keeps them: a Map by id, the taxa they name, and the version of the last
 * full or polled answer (GET …/items?since=<version> asks only for what changed after it). Answers to
 * changes (assign, tags, skip, accept) carry only the items they changed; they are merged in but do not move
 * the version on, because reading may have changed other items in between: the next poll still asks from
 * the last polled version, and getting an item twice is harmless.
 */
export const emptyItems = () => ({ items: new Map(), taxa: new Map(), version: null });

export function mergePage(state, page, { advance = true } = {}) {
  // An answer with nothing new keeps the same Maps (and the same state when the version stays too), so the
  // grid's sections, which follow the items, are not worked out again on every poll.
  const changed = page.items ?? [];
  const named = Object.entries(page.taxa ?? {});
  const version = advance && page.version != null ? page.version : state.version;
  if (!changed.length && !named.length && version === state.version) return state;
  let { items, taxa } = state;
  if (changed.length) {
    items = new Map(items);
    for (const item of changed) items.set(item.id, item);
  }
  if (named.length) {
    taxa = new Map(taxa);
    for (const [taxonId, taxon] of named) taxa.set(taxonId, taxon);
  }
  return { items, taxa, version };
}
