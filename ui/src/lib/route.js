import { FILE_TYPES, decode, parseFilter, printFilter } from './filter.js';

/**
 * Hash routes: #/ home, #/setup, #/explore[/<id>], #/species/<id>[/photo/<photoId>][?tag=<key>][&place=<id|unknown>][&type=<file type>], #/deleted,
 * #/import (the imports), #/import/<id>[/photo/<itemId>] (one import's grid and its large view),
 * #/export[?<filter>][&view=photos|places] (the Export screen) and #/export/photo/<id>[?<filter>] (a photo of its results),
 * #/settings (Settings: backup and About, Phase 6), #/update (the taxonomy update) and #/split/<id>[/photo/<photoId>] (sorting a split species' photos, and one of them large).
 * Plain <a href> links drive them. The photo viewer has its own route so Back closes it; `tag`, `place` and `type` are the
 * gallery filters it pages through, and on a species page's own address (from the Export results) the chips it opens with.
 */
export const GROUP_ID = 'G:butterflies';
const POSITIVE = /^[1-9]\d*$/;
const VIEWS = ['species', 'photos', 'places'];

export function parseRoute(hash) {
  const [path, query = ''] = (hash || '').replace(/^#/, '').split('?');
  const parts = path.replace(/^\/+/, '').split('/');
  const screen = parts[0];
  const id = parts[1] ? decode(parts[1]) : null;
  if (screen === 'setup') return { screen: 'setup' };
  if (screen === 'explore') return { screen: 'explore', id: id ?? GROUP_ID };
  if (screen === 'species' && id) {
    const photoId = parts[2] === 'photo' && POSITIVE.test(parts[3] ?? '') ? Number(parts[3]) : null;
    const params = new URLSearchParams(query);
    const tag = params.get('tag');
    const place = params.get('place');
    // A file type (Phase 7a) outside the list is ignored.
    const type = FILE_TYPES.some((each) => each.value === params.get('type')) ? params.get('type') : null;
    const chips = { ...(tag ? { tag } : {}), ...(place ? { place } : {}), ...(type ? { type } : {}) };
    return photoId ? { screen: 'species', id, photoId, ...chips } : { screen: 'species', id, ...chips };
  }
  if (screen === 'deleted') return { screen: 'deleted' };
  if (screen === 'update') return { screen: 'update' };
  if (screen === 'settings') return { screen: 'settings' };
  if (screen === 'split' && id) {
    const photoId = parts[2] === 'photo' && POSITIVE.test(parts[3] ?? '') ? Number(parts[3]) : null;
    return photoId ? { screen: 'split', id, photoId } : { screen: 'split', id };
  }
  if (screen === 'export') {
    // The filter as printed (so two addresses for one filter are one route), and the view; Species is the default.
    const filter = printFilter(parseFilter(query));
    const view = /(?:^|&)view=(\w+)/.exec(query)?.[1];
    const photoId = parts[1] === 'photo' && POSITIVE.test(parts[2] ?? '') ? Number(parts[2]) : null;
    if (photoId) return { screen: 'export', query: filter, view: 'photos', photoId };
    return { screen: 'export', query: filter, view: VIEWS.includes(view) ? view : 'species' };
  }
  if (screen === 'import') {
    if (!POSITIVE.test(parts[1] ?? '')) return { screen: 'imports' };
    const itemId = parts[2] === 'photo' && POSITIVE.test(parts[3] ?? '') ? Number(parts[3]) : null;
    return itemId ? { screen: 'import', id: Number(parts[1]), itemId } : { screen: 'import', id: Number(parts[1]) };
  }
  return { screen: 'home' };
}

export function hrefFor(taxon) {
  if (taxon.rank === 'species') return `#/species/${encodeURIComponent(taxon.id)}`;
  if (taxon.rank === 'group') return '#/explore';
  return `#/explore/${encodeURIComponent(taxon.id)}`;
}

/**
 * The viewer's address; `tag` (a gallery filter other than all) limits paging to photos with that tag, `place`
 * (a place id, or "unknown") to photos at that place, and `type` (a file type, Phase 7a) to photos of that type.
 */
export function photoHref(taxonId, photoId, tag = null, place = null, type = null) {
  const params = [];
  if (tag && tag !== 'all') params.push(`tag=${encodeURIComponent(tag)}`);
  if (place) params.push(`place=${encodeURIComponent(place)}`);
  if (type) params.push(`type=${encodeURIComponent(type)}`);
  return `#/species/${encodeURIComponent(taxonId)}/photo/${photoId}${params.length ? `?${params.join('&')}` : ''}`;
}

export const importHref = (importId) => `#/import/${importId}`;

/** The Export screen's address for a printed filter (lib/filter.js) and a view ('species' is left out). */
export function exportHref(query, view = 'species') {
  const params = [query, view !== 'species' ? `view=${view}` : ''].filter(Boolean);
  return `#/export${params.length ? `?${params.join('&')}` : ''}`;
}

/** A photo of the results in the viewer, over the Photos view; Back closes it. */
export const exportPhotoHref = (photoId, query) => `#/export/photo/${photoId}${query ? `?${query}` : ''}`;
/** The import's large view of one photo; it has its own address so Back closes it. */
export const importPhotoHref = (importId, itemId) => `#/import/${importId}/photo/${itemId}`;

/** The taxonomy update screen (Phase 5b). */
export const UPDATE_HREF = '#/update';
/** Sorting a split species' photos to its new species. */
export const splitHref = (taxonId) => `#/split/${encodeURIComponent(taxonId)}`;
/** One photo still to sort, large, over the split screen; Back closes it. */
export const splitPhotoHref = (taxonId, photoId) => `${splitHref(taxonId)}/photo/${photoId}`;
