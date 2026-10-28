import { bursts, timeSorted } from './bursts.js';
import { ITEM_FAILED, ITEM_MISSING, ITEM_PENDING, ITEM_READ, ITEM_UNSUPPORTED } from './states.js';
import { UNKNOWN_PLACE } from './messages.js';

/**
 * What the import grid shows, as pure functions of the items (contract §2 Item): each item's category,
 * the filter chips and their counts, the two views (subfolder sections with bursts, or by species), the
 * rows of the virtualised grid and which of them are in view.
 */
// `pair_left` (Phase 7a): the half of a RAW + JPEG pair the user's choice left out; done with, like a filed photo.
const FILED = new Set(['filed', 'duplicate', 'also_elsewhere', 'copy_of', 'pair_left']);
const PROBLEM_STATES = new Set(['unsupported', 'failed', 'missing']);

/**
 * One category per item: 'problems' (can't be read, gone from the folder, or filing failed), 'filed'
 * (has an outcome), 'skipped', 'assigned', 'suggested', else 'unassigned'. Unlike the server's counts,
 * a photo not read yet is not set apart: it can be assigned before reading reaches it.
 */
export function category(item) {
  if (PROBLEM_STATES.has(item.state) || item.outcome === 'failed') return 'problems';
  if (FILED.has(item.outcome)) return 'filed';
  if (item.skipped) return 'skipped';
  if (item.taxonId) return 'assigned';
  if (item.suggestedTaxonId) return 'suggested';
  return 'unassigned';
}

/**
 * The photos as shots (Phase 7a contract §3): the two items of a RAW + JPEG pair (each naming the other in `pairId`;
 * Phase 7b: a RAW + HEIC pair the same way, the HEIC standing where the JPEG does)
 * become one, the JPEG, carrying its RAW as `pairOf`; when the choice filed the RAW only, the RAW stands for the shot
 * (its outcome is the shot's) and carries the JPEG. Edits sent for one id apply to both (the server mirrors them), so
 * the grid, its counts and its selection work on shots. Other items are passed through as they are.
 */
export function shots(items) {
  const byId = new Map();
  for (const item of items) byId.set(item.id, item);
  const out = [];
  for (const item of items) {
    const other = item.pairId == null ? null : byId.get(item.pairId);
    if (!other || other.pairId !== item.id) {
      out.push(item);
      continue;
    }
    const jpeg = item.fileType === 'raw' ? other : item;
    const raw = jpeg === item ? other : item;
    const lead = jpeg.outcome === 'pair_left' ? raw : jpeg;
    if (item === lead) out.push({ ...lead, pairOf: lead === jpeg ? raw : jpeg });
  }
  return out;
}

/**
 * The filter chips, in order (contract §3). Filed photos show under All only. "Unknown place" (3c-2) is not a
 * category: it shows the read photos, still to be filed, that have no place, whatever their category.
 */
export const FILTERS = [
  { key: 'all', label: 'All' },
  { key: 'unassigned', label: 'Unassigned' },
  { key: 'suggested', label: 'Suggested' },
  { key: 'assigned', label: 'Assigned' },
  { key: 'skipped', label: 'Skipped' },
  { key: 'problems', label: 'Problems' },
  { key: 'noPlace', label: UNKNOWN_PLACE },
];

export function filterCounts(items) {
  const counts = { all: 0, unassigned: 0, suggested: 0, assigned: 0, skipped: 0, problems: 0, filed: 0, noPlace: 0 };
  for (const item of items) {
    counts.all += 1;
    counts[category(item)] += 1;
    if (unknownPlace(item)) counts.noPlace += 1;
  }
  return counts;
}

export function applyFilter(items, filter) {
  if (filter === 'all') return items;
  if (filter === 'noPlace') return items.filter(unknownPlace);
  return items.filter((item) => category(item) === filter);
}

/**
 * Is the import still followed (asked about about once a second): looking through, reading or filing its folder, or
 * (Phase 7b audit 10) making HEIC previews after the decoder appeared (`heic.making`).
 */
export const followed = (summary) => summary.phase !== 'idle' || (summary.heic?.making ?? 0) > 0;

/** Would "File" take it now: read, assigned, not skipped, not filed yet (a failed filing is tried again). */
export const fileable = (item) =>
  item.state === ITEM_READ && Boolean(item.taxonId) && !item.skipped && (item.outcome == null || item.outcome === 'failed');

/** The files "File" would take from these shots with Both chosen: two for a pair still to file, one for any other. */
export const filesToFile = (list) => list.filter(fileable).reduce((n, shot) => n + (shot.pairOf && fileable(shot.pairOf) ? 2 : 1), 0);

/**
 * The file button: "File 2,140 assigned photos" (one: "File 1 assigned photo"); with pairs filed as Both, when that
 * makes more files than shots, it counts both: "File 12 photos (11 shots)" (review fix U7).
 */
export function fileButton(count, files, choice) {
  if (choice === 'both' && files > count) return `File ${files.toLocaleString()} photos (${count === 1 ? '1 shot' : `${count.toLocaleString()} shots`})`;
  return count === 1 ? 'File 1 assigned photo' : `File ${count.toLocaleString()} assigned photos`;
}

/**
 * Can it still be changed (assigned, tagged, skipped): not filed, still in the folder and readable. A photo
 * whose filing failed can (a change clears the failure); filed, missing and unreadable ones can't.
 */
export const editable = (item) => !FILED.has(item.outcome) && !PROBLEM_STATES.has(item.state);

/** Read, can still be changed and has no place: what the "Unknown place" chip shows. */
export const unknownPlace = (item) => item.state === ITEM_READ && editable(item) && !item.place;

/** Already in the library and still waiting: what "Skip photos already in the library" skips. */
export const inLibrary = (item) => Boolean(item.libraryTaxonId) && !item.skipped && item.outcome == null && category(item) !== 'problems';

/* ---------- RAW + JPEG and RAW + HEIC pairs (Phase 7a contract §3, Phase 7b contract §4) ---------- */

// The partner types the folder's pairs hold (`summary.pairs.kinds`, Phase 7b): ['jpeg'] (also from an older server),
// ['heic'], or both. The choice `jpeg` means the JPEG or HEIC half.
const kindsOf = (kinds) => {
  const jpeg = !kinds?.length || kinds.includes('jpeg');
  const heic = Boolean(kinds?.includes('heic'));
  return { jpeg, heic };
};

/** What the pairs are called: "RAW + JPEG", "RAW + HEIC", or with both kinds "RAW + JPEG or RAW + HEIC". */
export function pairName(kinds) {
  const { jpeg, heic } = kindsOf(kinds);
  return jpeg && heic ? 'RAW + JPEG or RAW + HEIC' : heic ? 'RAW + HEIC' : 'RAW + JPEG';
}

/** The pair choices, in the bar's order, with how they are said: Both · JPEG only (HEIC only, JPEG or HEIC only) · RAW only. */
export function pairChoices(kinds) {
  const { jpeg, heic } = kindsOf(kinds);
  const other = jpeg && heic ? 'JPEG or HEIC only' : heic ? 'HEIC only' : 'JPEG only';
  return [{ value: 'both', label: 'Both' }, { value: 'jpeg', label: other }, { value: 'raw', label: 'RAW only' }];
}
export const PAIR_CHOICES = pairChoices(['jpeg']);
export const pairChoiceLabel = (choice, kinds) => pairChoices(kinds).find((option) => option.value === choice)?.label ?? choice;

/** "1 RAW + JPEG pair", "340 RAW + JPEG pairs", "120 RAW + HEIC pairs". */
export const pairCount = (n, kinds) => (n === 1 ? `1 ${pairName(kinds)} pair` : `${n.toLocaleString()} ${pairName(kinds)} pairs`);

/** Filing waits for a choice: pairs still to file (`summary.pairs`) and none chosen. */
export const pairsWaiting = (pairs) => Boolean(pairs && pairs.toFile > 0 && !pairs.choice);

/** Why filing waits (the server's `pair_choice_needed` words): "Choose how to file the 340 RAW + JPEG pairs first." */
export const pairChoiceNeeded = (pairs) => `Choose how to file the ${pairCount(pairs.toFile, pairs.kinds)} first.`;

// A shot's non-RAW half's type, as said: 'HEIC' or 'JPEG'.
const otherHalf = (item) => ((item.fileType === 'raw' ? item.pairOf?.fileType : item.fileType) === 'heic' ? 'HEIC' : 'JPEG');

/** A pair tile's badge: "RAW + JPEG" or "RAW + HEIC" (each pair by its own halves, audit 14); '' for any other shot. */
export const pairBadge = (item) => (item.pairOf ? `RAW + ${otherHalf(item)}` : '');

/** "1 photo", "2,140 photos". */
export const photoCount = (n) => (n === 1 ? '1 photo' : `${n.toLocaleString()} photos`);

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/** A taxon's name for headers and labels: the common name, else the scientific one. */
export const taxonTitle = (taxa, taxonId) => {
  const taxon = taxa.get(taxonId);
  return taxon ? taxon.common || taxon.name : taxonId;
};

/**
 * By folder: one section per subfolder (files at the root first, under the import's name, then the others
 * in natural name order), each split into bursts. Section: `{ key, title, folder, items, groups }`.
 */
export function folderSections(items, rootName) {
  const folders = new Map();
  for (const item of items) {
    const list = folders.get(item.folder);
    if (list) list.push(item);
    else folders.set(item.folder, [item]);
  }
  const names = [...folders.keys()].sort((a, b) => (a === '' ? -1 : b === '' ? 1 : collator.compare(a, b)));
  return names.map((folder) => {
    const list = folders.get(folder);
    const groups = bursts(list);
    return { key: `f:${folder}`, title: folder || rootName, folder, items: groups.flatMap((g) => g.items), groups };
  });
}

/**
 * By species, to review before filing: one section per assigned species (skipped photos are not filed, so
 * they wait with the rest), in name order, then "No species yet". No bursts: a section is one run of tiles.
 */
export function speciesSections(items, taxa) {
  const species = new Map();
  const none = [];
  for (const item of items) {
    if (!item.taxonId || item.skipped) none.push(item);
    else if (species.has(item.taxonId)) species.get(item.taxonId).push(item);
    else species.set(item.taxonId, [item]);
  }
  const ids = [...species.keys()].sort((a, b) => collator.compare(taxonTitle(taxa, a), taxonTitle(taxa, b)));
  const section = (key, title, list, taxonId = null) => {
    const sorted = timeSorted(list, (a, b) => collator.compare(a.folder, b.folder)).map((each) => each.item);
    return { key, title, taxonId, items: sorted, groups: [{ key: `${key}:all`, burst: false, items: sorted }] };
  };
  const sections = ids.map((taxonId) => section(`s:${taxonId}`, taxonTitle(taxa, taxonId), species.get(taxonId), taxonId));
  if (none.length) sections.push(section('s:', 'No species yet', none));
  return sections;
}

/**
 * The section header's suggestion: the ids of its photos with a suggestion and no species yet, and the
 * species when they all share one ("Suggested: Plain Tiger · Accept all"; several read "12 suggested").
 */
export function sectionSuggestion(items) {
  const ids = [];
  const taxa = new Set();
  for (const item of items) {
    if (category(item) !== 'suggested') continue;
    ids.push(item.id);
    taxa.add(item.suggestedTaxonId);
  }
  return { ids, taxonId: taxa.size === 1 ? [...taxa][0] : null };
}

// Joins the parts of a row's key: NUL can't appear in a path, so no folder name can make two keys alike.
const SEP = '\u0000';

/**
 * Rows of the virtualised grid for `cols` tiles a row: a header per section, a header per burst (loose
 * shots have none), then the section's or burst's tiles in rows of `cols`. Every row has a `key` that
 * stays the same while its content does, and tile rows know their section.
 */
export function layoutRows(sections, cols) {
  const rows = [];
  for (const section of sections) {
    rows.push({ kind: 'section', key: section.key, section });
    for (const group of section.groups) {
      if (group.burst) rows.push({ kind: 'burst', key: `${section.key}${SEP}${group.key}`, section, group });
      for (let at = 0; at < group.items.length; at += cols) {
        const items = group.items.slice(at, at + cols);
        rows.push({ kind: 'tiles', key: `${section.key}${SEP}${group.key}${SEP}${items[0].id}`, section, items });
      }
    }
  }
  return rows;
}

/** Each row's top and the grid's height, for fixed heights per kind of row. */
export function measureRows(rows, heights) {
  const tops = new Array(rows.length);
  let y = 0;
  for (let i = 0; i < rows.length; i++) {
    tops[i] = y;
    y += heights[rows[i].kind];
  }
  return { tops, total: y };
}

/**
 * The rows that touch [from, to) (pixels from the grid's top), plus `overscan` rows either side:
 * `{ start, end }` with end exclusive. Binary search, so ten thousand photos cost nothing to scroll.
 */
export function visibleRange(tops, total, from, to, overscan = 2) {
  const n = tops.length;
  if (!n) return { start: 0, end: 0 };
  // Past the end (the grid got shorter under the view): its last rows.
  if (from >= total) return { start: Math.max(0, n - 1 - overscan), end: n };
  if (to <= 0) return { start: 0, end: Math.min(n, overscan) };
  let lo = 0;
  let hi = n - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (tops[mid] <= from) lo = mid;
    else hi = mid - 1;
  }
  let end = lo;
  while (end < n && tops[end] < to) end++;
  return { start: Math.max(0, lo - overscan), end: Math.min(n, end + overscan) };
}

const OUTCOMES = {
  filed: 'Filed',
  duplicate: 'Already in your atlas',
  also_elsewhere: 'Filed, also under another species',
  copy_of: 'Copy of another photo here',
};
const pairLeftWords = (kind) => `Not filed: left out by your RAW + ${kind} choice`;

/**
 * An item's place as the import screen says it: its path ("Pune, Maharashtra, India"); one that became former in a
 * place-data update (Phase 5c, its path comes labelled) and can still be changed: "Chittoor (former district) — choose
 * its new place, or file as it is." (filing with it is allowed), or for a place its GPS gave "… — when it is filed, its
 * GPS position is looked up again in the new place list." '' without a place.
 */
export function placeLine(item) {
  if (!item.place) return '';
  if (item.place.former && editable(item)) {
    const name = item.place.path[0];
    // A place its GPS gave is looked up again from the GPS when it is filed (contract §11); the user's own choice stays.
    if (item.place.source === 'gps') return `${name} — when it is filed, its GPS position is looked up again in the new place list.`;
    return `${name} — choose its new place, or file as it is.`;
  }
  return item.place.path.join(', ');
}

/**
 * A pair tile after filing with JPEG only or RAW only (review fix U4): its badge says the half filed ("JPEG filed",
 * "RAW filed") and a line on the tile the half left out ("RAW not filed", "JPEG not filed"); null otherwise.
 */
export function pairFiled(item) {
  if (item.pairOf?.outcome !== 'pair_left') return null;
  const other = otherHalf(item);
  const [filed, left] = item.fileType === 'raw' ? ['RAW', other] : [other, 'RAW'];
  return { badge: `${filed} filed`, line: `${left} not filed` };
}

/**
 * What a tile says about its photo: `kind` (its look: problem, filed, skipped, assigned, suggested, waiting
 * or plain), the `label` shown on it, and `text` for its accessible name, which also says when the photo
 * is already in the library or a copy of another photo in this folder, and for a RAW + JPEG (or RAW + HEIC) pair that it is one and
 * why its other half was not filed.
 */
export function tileStatus(item, taxa) {
  const name = (taxonId) => taxonTitle(taxa, taxonId);
  const notes = [];
  if (item.libraryTaxonId && item.outcome == null) notes.push(`already in your atlas as ${name(item.libraryTaxonId)}`);
  if (item.copyOf != null && item.outcome == null) notes.push('a copy of another photo here');
  let kind;
  let label;
  if (item.state === ITEM_MISSING) [kind, label] = ['problem', 'Not in the folder any more'];
  else if (item.state === ITEM_UNSUPPORTED || item.state === ITEM_FAILED) [kind, label] = ['problem', item.problem || "Butterfly Atlas couldn't read this photo."];
  else if (item.outcome === 'failed') [kind, label] = ['problem', item.error || "This photo couldn't be filed."];
  // The server words what filing did ("Already in your atlas.", "Same photo as Day 2/IMG_1.JPG."); this is a fallback.
  else if (item.outcome) {
    const said = item.outcome === 'pair_left' ? pairLeftWords(otherHalf(item)) : OUTCOMES[item.outcome];
    [kind, label] = ['filed', item.error || (item.taxonId ? `${said}: ${name(item.taxonId)}` : said)];
  }
  else if (item.skipped) [kind, label] = ['skipped', 'Skipped'];
  else if (item.taxonId) [kind, label] = ['assigned', name(item.taxonId)];
  else if (item.suggestedTaxonId) [kind, label] = ['suggested', `Suggested: ${name(item.suggestedTaxonId)}`];
  else if (item.state === ITEM_PENDING) [kind, label] = ['waiting', 'Not read yet'];
  else [kind, label] = ['plain', ''];
  const place = item.place?.former && editable(item) ? placeLine(item).replace(/\.$/, '') : item.place?.path[0];
  // A pair's other half that its choice left out says so ("The RAW of this pair was not filed: you chose JPEG only.").
  const left = item.pairOf?.outcome === 'pair_left' ? item.pairOf.error || pairLeftWords(otherHalf(item)) : '';
  const text = [item.name, pairBadge(item), label, place, ...notes, left].filter(Boolean).join(', ');
  return { kind, label, text };
}

/**
 * The line for an import whose reading or filing was cut short by the app closing (contract §3): `stop` is
 * `{ rootName, phase, done, total }`, as in /api/status `stoppedImports` (or an ImportSummary's `stopped`
 * with its `rootName`).
 */
export function stoppedLine({ rootName, phase, done, total }) {
  const what = phase === 'filing' ? 'filing' : 'reading';
  const where = total ? `${what} ${done.toLocaleString()} of ${total.toLocaleString()}` : `while ${what}`;
  return `Your import of ${rootName} stopped when Butterfly Atlas closed (${where}). It won't carry on by itself.`;
}

/**
 * The order the large view pages through: the photos shown, with `itemId` (the one on screen) kept at its place
 * in `previous` when a change has taken it out of them, so paging goes on from where it was.
 */
export function keepPlace(order, previous, itemId) {
  if (itemId == null || order.includes(itemId)) return order;
  const at = previous.indexOf(itemId);
  const kept = order.slice();
  kept.splice(at === -1 ? kept.length : Math.min(at, kept.length), 0, itemId);
  return kept;
}
