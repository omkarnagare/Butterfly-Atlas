import { bareName } from './places.js';
import { NOTHING_MATCHES, NOT_PHOTOGRAPHED, PHOTOGRAPHED, UNKNOWN_PLACE } from './messages.js';
/**
 * The shared filter of the Export screen (Phase 4a contract §1, §4). One filter is
 * `{ taxa, tags, tagMode, places, status, source, stages, types }` (`types`, the file types, Phase 7a contract §4); it is parsed from and printed to the query string used in
 * the address (#/export?…) and in every /api/filter/* request. Defaults are left out when printed, keys in the
 * contract's order. A value outside its list is read as the default here, so the server is never sent one it would
 * refuse; unknown or retired ids are left for the server to drop (the count answer's `filter.query` says what it used).
 */
export const MAX_IDS = 200;

export const EMPTY = Object.freeze({ taxa: [], tags: [], tagMode: 'any', places: [], status: 'all', source: 'all', stages: null, types: [] });

/** The File type choices (Phase 7a; HEIC after RAW, Phase 7b): API value and label, in the filter panel's order. */
export const FILE_TYPES = [
  { value: 'jpeg', label: 'JPEG' }, { value: 'png', label: 'PNG' }, { value: 'tiff', label: 'TIFF' }, { value: 'webp', label: 'WebP' },
  { value: 'raw', label: 'RAW' }, { value: 'heic', label: 'HEIC' },
];
const TYPE_VALUES = FILE_TYPES.map((type) => type.value);
const TYPE_LABEL = Object.fromEntries(FILE_TYPES.map((type) => [type.value, type.label]));
/** "RAW", "WebP": how a file type is said. */
export const fileTypeLabel = (type) => TYPE_LABEL[type] ?? type;

const CHOICES = {
  tagMode: ['any', 'all'],
  status: ['all', 'photographed', 'not'],
  source: ['all', 'official', 'manual'],
  stages: ['missing'],
};

/** decodeURIComponent, or the text as it is when it is not well encoded (a stray "%"). Shared with route.js. */
export function decode(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/** Comma-separated ids: decoded, trimmed, empty ones dropped, de-duplicated in order, at most `max`. */
function ids(raw, max = Infinity) {
  if (!raw) return [];
  const seen = [];
  for (const part of raw.split(',')) {
    // Split before decoding (an encoded comma belongs to its id); trimmed after, as the server does.
    const value = decode(part).trim();
    if (value && !seen.includes(value)) seen.push(value);
  }
  return seen.slice(0, max);
}

const choice = (key, raw) => (CHOICES[key].includes(raw) ? raw : (EMPTY[key] ?? null));

/** The filter in a query string (without the leading "?"; other keys, such as the address's `view`, are ignored). */
export function parseFilter(query) {
  // Split by hand: URLSearchParams would read "+" as a space and decode commas we split on.
  const raw = {};
  for (const pair of (query || '').replace(/^\?/, '').split('&')) {
    const at = pair.indexOf('=');
    // Keys are decoded and, of a repeated key, the first counts, as on the server.
    const key = at > 0 ? decode(pair.slice(0, at)) : '';
    if (key && !Object.hasOwn(raw, key)) raw[key] = pair.slice(at + 1);
  }
  return {
    taxa: ids(raw.taxa, MAX_IDS),
    tags: ids(raw.tags),
    tagMode: choice('tagMode', decode(raw.tagMode ?? '')),
    places: ids(raw.places, MAX_IDS),
    status: choice('status', decode(raw.status ?? '')),
    source: choice('source', decode(raw.source ?? '')),
    stages: choice('stages', decode(raw.stages ?? '')),
    // A file type outside the list is left out, so the server is never sent one it would refuse.
    types: ids(raw.types).filter((type) => TYPE_VALUES.includes(type)),
  };
}

// Percent-encoded except letters, digits and "-._~:", as the server prints `filter.query`, so one filter has one address.
const encode = (value) => encodeURIComponent(value).replace(/%3A/g, ':').replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
const list = (values) => values.map(encode).join(',');

/** The query string of a filter: defaults left out, keys in the contract's order; '' for no filter. */
export function printFilter(filter) {
  const parts = [];
  if (filter.taxa.length) parts.push(`taxa=${list(filter.taxa)}`);
  if (filter.tags.length) parts.push(`tags=${list(filter.tags)}`);
  if (filter.tagMode !== 'any') parts.push(`tagMode=${filter.tagMode}`);
  if (filter.places.length) parts.push(`places=${list(filter.places)}`);
  if (filter.status !== 'all') parts.push(`status=${filter.status}`);
  if (filter.source !== 'all') parts.push(`source=${filter.source}`);
  if (filter.stages) parts.push(`stages=${filter.stages}`);
  if (filter.types?.length) parts.push(`types=${filter.types.join(',')}`);
  return parts.join('&');
}

/**
 * Display names of chosen taxa, tags and places, remembered from the count answers (`filter.taxa`, `.tags`, `.places`)
 * and from the popovers: `{ taxa, tags, places }`, each a Map from id (tag key) to name. Returns a new object.
 */
export function mergeNames(names, { taxa = [], tags = [], places = [] } = {}) {
  const next = {
    taxa: new Map(names?.taxa ?? []),
    tags: new Map(names?.tags ?? []),
    places: new Map(names?.places ?? []),
  };
  for (const taxon of taxa) next.taxa.set(taxon.id, taxon.name);
  for (const tag of tags) next.tags.set(tag.key, tag.label);
  for (const place of places) if (place.id !== 'unknown') next.places.set(place.id, place.name);
  return next;
}

/** "a", "a or b", "a, b or c". */
function joined(words, last) {
  if (words.length < 2) return words.join('');
  return `${words.slice(0, -1).join(', ')} ${last} ${words.at(-1)}`;
}

/** Shown for a chosen taxon or place whose name has not come yet (from the count answer or the popover that chose it). */
export const UNNAMED = '…';

export const placeName = (names, placeId) => (placeId === 'unknown' ? UNKNOWN_PLACE : names?.places.get(placeId) ?? UNNAMED);
export const taxonName = (names, taxonId) => names?.taxa.get(taxonId) ?? UNNAMED;
const tagName = (names, key) => names?.tags.get(key) ?? key;

const STATUS = { photographed: PHOTOGRAPHED, not: NOT_PHOTOGRAPHED };
/** The Source choices: custom species are `manual` to the API and "Custom" to the user (Phase 5 design D3). */
export const SOURCE_OPTIONS = [{ value: 'all', label: 'All' }, { value: 'official', label: 'Official' }, { value: 'manual', label: 'Custom' }];
const SOURCE = Object.fromEntries(SOURCE_OPTIONS.map((option) => [option.value, option.label]));

/** One chip per active filter, in the filter row's order: `{ kind, label }`. */
export function chips(filter, names) {
  const out = [];
  if (filter.taxa.length) out.push({ kind: 'taxa', label: `Taxa: ${filter.taxa.map((id) => taxonName(names, id)).join(', ')}` });
  if (filter.tags.length) {
    out.push({ kind: 'tags', label: `Tags: ${joined(filter.tags.map((key) => tagName(names, key)), filter.tagMode === 'all' ? 'and' : 'or')}` });
  }
  if (filter.places.length) out.push({ kind: 'places', label: `Place: ${filter.places.map((id) => placeName(names, id)).join(', ')}` });
  if (filter.status !== 'all') out.push({ kind: 'status', label: STATUS[filter.status] });
  if (filter.source !== 'all') out.push({ kind: 'source', label: SOURCE[filter.source] });
  if (filter.types?.length) out.push({ kind: 'types', label: `File type: ${filter.types.map((type) => TYPE_LABEL[type] ?? type).join(', ')}` });
  if (filter.stages) out.push({ kind: 'stages', label: 'Missing life stages' });
  return out;
}

/** The filter with one chip's filter cleared (`kind` as in `chips`). */
export function without(filter, kind) {
  if (kind === 'taxa') return { ...filter, taxa: [] };
  if (kind === 'tags') return { ...filter, tags: [], tagMode: 'any' };
  if (kind === 'places') return { ...filter, places: [] };
  if (kind === 'status') return { ...filter, status: 'all' };
  if (kind === 'source') return { ...filter, source: 'all' };
  if (kind === 'stages') return { ...filter, stages: null };
  if (kind === 'types') return { ...filter, types: [] };
  return filter;
}

/** The presets line (contract §4): each keeps the taxa, places, source and file types; Clear clears everything. */
export const PRESETS = [
  { key: 'full', label: 'Full checklist' },
  { key: 'not', label: 'Not yet photographed' },
  { key: 'missing', label: 'Missing life stages' },
  { key: 'clear', label: 'Clear' },
];

export function applyPreset(filter, key) {
  if (key === 'clear') return { ...EMPTY };
  const kept = { ...EMPTY, taxa: filter.taxa, places: filter.places, source: filter.source, types: filter.types ?? [] };
  if (key === 'not') return { ...kept, status: 'not' };
  if (key === 'missing') return { ...kept, stages: 'missing' };
  return kept;
}

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** The unit `formatBytes` shows `bytes` in, as an index of B, KB, MB, GB, TB (1 KB = 1024 B). */
export function byteUnit(bytes) {
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  // 1023.97 KB would read "1024 KB": that is 1.0 MB.
  const shown = unit < 2 ? Math.round(value) : Number(value.toFixed(1));
  return shown >= 1024 && unit < UNITS.length - 1 ? unit + 1 : unit;
}

/** `bytes` in the unit `unit` (from `byteUnit`), without its name: B and KB whole, one decimal from MB up. */
export function bytesIn(bytes, unit) {
  const value = bytes / 1024 ** unit;
  return unit < 2 ? `${Math.round(value)}` : value.toFixed(1);
}

/** "0 B", "980 B", "2 KB", "1.4 MB", "4.2 GB": B and KB whole, one decimal from MB up (1 KB = 1024 B). */
export function formatBytes(bytes) {
  const unit = byteUnit(bytes);
  return `${bytesIn(bytes, unit)} ${UNITS[unit]}`;
}

/**
 * The count line (contract §4) from a count answer, as parts: strings, and `{ n }` for each number (so the line can
 * count them up). "3 photos of 2 species, 2 KB" (the species with matching photos: `photographedSpecies`), and with
 * status all, when the list has more species, " · 1,139 species in the list"; "No photos · 1,139 species in the list";
 * "212 species not yet photographed"; "31 species missing life stages"; "Nothing matches these filters.". Status and
 * stages are read from the filter the server used.
 */
export function countLine(count) {
  const { photos, species, bytes } = count;
  const photographed = count.photographedSpecies ?? species;
  const used = count.filter ?? EMPTY;
  if (!species) return [NOTHING_MATCHES];
  if (used.stages === 'missing') return [{ n: species }, ' species missing life stages'];
  if (used.status === 'not') return [{ n: species }, ' species not yet photographed'];
  const rest = used.status === 'all' && species > photographed ? [' · ', { n: species }, ' species in the list'] : [];
  if (!photos) return ['No photos', ...rest];
  return [{ n: photos }, photos === 1 ? ' photo of ' : ' photos of ', { n: photographed }, ` species, ${formatBytes(bytes)}`, ...rest];
}

/** The line under the count when places are chosen: "in Maharashtra", "in Maharashtra and Goa", "in 3 places", "with an unknown place". */
export function placesLine(places) {
  const named = places.filter((place) => place.id !== 'unknown');
  const unknown = named.length < places.length;
  let line = '';
  if (named.length === 1) line = `in ${named[0].name}`;
  else if (named.length === 2) line = `in ${named[0].name} and ${named[1].name}`;
  else if (named.length > 2) line = `in ${named.length} places`;
  if (!unknown) return line;
  return line ? `${line} or with an unknown place` : 'with an unknown place';
}

/**
 * The lines above the results for the places the server changed in the filter (Phase 5c `placeNotes`): "Brežice (Pečice)
 * is now part of Brežice." for each merged place (its survivor is in the filter instead), and one line for the places
 * that no longer exist, counted ("2 places in this filter no longer exist and were left out.").
 */
export function placeNoteLines(notes = []) {
  const list = notes ?? [];
  // The merged place is named as it was (its name comes labelled "… (former district)"; a merge keeps the level).
  const lines = list.filter((note) => note.kind === 'merged').map((note) => `${bareName(note.name, note.into?.level)} is now part of ${note.into?.name ?? 'another place'}.`);
  const gone = list.filter((note) => note.kind !== 'merged').length;
  if (gone === 1) lines.push('1 place in this filter no longer exists and was left out.');
  else if (gone > 1) lines.push(`${gone.toLocaleString()} places in this filter no longer exist and were left out.`);
  return lines;
}

/**
 * Where a species row of the results goes: its page with the gallery chips pre-set (design D2): `tag` only when exactly
 * one tag is chosen (and not with Missing life stages), `place` only when exactly one place is, `type` (Phase 7a) only
 * when exactly one file type is.
 */
export function speciesHref(taxonId, filter) {
  const params = [];
  if (filter.tags.length === 1 && filter.stages !== 'missing') params.push(`tag=${encodeURIComponent(filter.tags[0])}`);
  if (filter.places.length === 1) params.push(`place=${encodeURIComponent(filter.places[0])}`);
  if (filter.types?.length === 1) params.push(`type=${filter.types[0]}`);
  return `#/species/${encodeURIComponent(taxonId)}${params.length ? `?${params.join('&')}` : ''}`;
}
