import { parseFilter, printFilter } from './filter.js';
import { ADDITIONS_ID, GROUP_ID } from './route.js';

/*
 * An Explore page's filter (filters-in-Explore design §3): the page's own part is in its path, not in its query: a
 * group's page is the group as the first taxon, Custom additions is Source: Custom, the top page adds nothing. A filter
 * that still has the page's part stays on that page; one without it widens to the top page.
 */

/** The full filter of page `id` with the query (printed without the page's part) from its address. */
export function pageFilter(id, query) {
  const filter = parseFilter(query);
  if (id === GROUP_ID) return filter;
  if (id === ADDITIONS_ID) return { ...filter, source: 'manual' };
  return { ...filter, taxa: [id, ...filter.taxa.filter((each) => each !== id)] };
}

/** Where a filter made on page `id` is shown: `{ id, query }`, the query printed without that page's part. */
export function exploreAddress(id, filter) {
  if (id === ADDITIONS_ID && filter.source === 'manual') return { id, query: printFilter({ ...filter, source: 'all' }) };
  if (id !== GROUP_ID && id !== ADDITIONS_ID && filter.taxa.includes(id)) {
    return { id, query: printFilter({ ...filter, taxa: filter.taxa.filter((each) => each !== id) }) };
  }
  return { id: GROUP_ID, query: printFilter(filter) };
}

/** Whether an Explore route shows the filtered views (a filter beyond the page's part, another view, or a photo). */
export const startsFiltered = (route) => Boolean(route.query) || (route.view !== undefined && route.view !== 'species') || route.photoId != null;
