import { bareName, knownPlace, levelWord, placeWhere } from './places.js';

/**
 * Words for a place-data update (Phase 5c contract §7): Home's place note (GET /api/place-update, a PlaceNote) and its
 * smaller "still in a former district" card. A PlaceRef is `{ id, name, path, level, former }`; for an inactive place both
 * its `name` and `path[0]` come labelled ("Chittoor (former district)", contract §11), and its level is read from `level`.
 * Old names in change lines are shown without the label (`bareName`).
 */
const num = (n) => n.toLocaleString();
const photos = (n) => (n === 1 ? '1 photo' : `${num(n)} photos`);
const KINDS = ['merged', 'split', 'retired', 'renamed', 'moved', 'added'];
const both = (names) => (names.length < 2 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`);

/**
 * The counts sentence: "1 place merged, 2 renamed and 40 other places worldwide." (only the kinds that touch the
 * library; `elsewhere` the rest), or '' when there is nothing to count.
 */
export function countsLine(counts = {}) {
  // The first kind names what is counted: "1 place merged, 2 renamed".
  const parts = KINDS.filter((kind) => counts[kind] > 0)
    .map((kind, i) => (i === 0 ? `${num(counts[kind])} ${counts[kind] === 1 ? 'place' : 'places'} ${kind}` : `${num(counts[kind])} ${kind}`));
  const elsewhere = counts.elsewhere ?? 0;
  if (!parts.length) return elsewhere ? `${elsewhere === 1 ? '1 place' : `${num(elsewhere)} places`} changed worldwide.` : '';
  const others = elsewhere ? ` and ${num(elsewhere)} other ${elsewhere === 1 ? 'place' : 'places'} worldwide` : '';
  return `${parts.join(', ')}${others}.`;
}

/**
 * One change that touches the library, as a short line: "Brežice (Pečice) → Brežice, Slovenia · 3 photos" (a merge,
 * split or rename: the old names, bare, to the new places with where they are), "Shahdara is now in Delhi, India" (a
 * move), "Chittoor, Andhra Pradesh, India is no longer listed" (a retire), "New place: Tirupati, Andhra Pradesh, India"
 * (an add). The photos only when there are any.
 */
export function itemLine({ type, from = [], to = [], photos: n = 0 }) {
  const where = (place) => placeWhere(place);
  // The old places are named as they were: the server labels an inactive place's name ("… (former district)").
  const after = (place) => [bareName(place.name, place.level), where(place)].filter(Boolean).join(', ');
  let text;
  if (type === 'move' && to.length) text = `${to[0].name} is now in ${where(to[0]) || 'no state'}`;
  else if (type === 'retire' || !to.length) text = `${from.map(after).join('; ')} is no longer listed`;
  else if (type === 'add') text = `New place: ${to.map(after).join('; ')}`;
  else {
    const last = where(to[0]);
    // Old names are joined with " + ": a name may hold "and" ("Dadra and Nagar Haveli + Daman and Diu").
    text = `${from.map((place) => bareName(place.name, place.level)).join(' + ')} → ${both(to.map((place) => place.name))}${last ? `, ${last}` : ''}`;
  }
  return n > 0 ? `${text} · ${photos(n)}` : text;
}

/**
 * A place of the user's merged into a bundled one (design P4): "Your place Tirupati is now an official district; its 12
 * photos moved to it."; into another of the user's (`intoMine`): "Your two places named Tirupati are now one; …".
 */
export function mineLine({ name, into, photos: n = 0, intoMine = false }) {
  // Merged into another place of the user's with the same name (contract §10).
  const start = intoMine ? `Your two places named ${name} are now one`
    : knownPlace(into) ? `Your place ${name} is now an official ${levelWord(into.level, into.path)}` : `Your place ${name} is now part of ${into?.name ?? 'another place'}`;
  if (!n) return `${start}.`;
  return `${start}; ${n === 1 ? 'its photo' : `its ${num(n)} photos`} moved to it.`;
}

/** One group of the GPS re-check's moves: "8 photos moved from Chittoor to Tirupati." (a side without a place said so). */
export function movedLine({ from, to, photos: n }) {
  const name = (place) => place.path?.[0] ?? place.name;
  if (!from && to) return `${photos(n)} without a place ${n === 1 ? 'is' : 'are'} now in ${name(to)}.`;
  if (from && !to) return `${photos(n)} in ${name(from)} no longer ${n === 1 ? 'has a place' : 'have a place'}.`;
  return `${photos(n)} moved from ${name(from)} to ${name(to)}.`;
}

/** The re-check's moves the note does not list (`total` is every photo it moved to a place): "…and 30 more photos moved.", or ''. */
export function movedMore({ total = 0, groups = [] } = {}) {
  // `total` counts only moves to a place (contract: the lost lines tell the others); a group to no place is not counted.
  const more = total - groups.filter((group) => group.to !== null).reduce((sum, group) => sum + group.photos, 0);
  return more > 0 ? `…and ${more === 1 ? '1 more photo' : `${num(more)} more photos`} moved.` : '';
}

/**
 * What the former places are called, from the species lines: "a former district" (one place), "former districts"
 * (several of one level), "former places" (levels mixed, or none known yet).
 */
export function formerWhere(rows = []) {
  const places = new Map(rows.map((row) => [row.place.id, row.place.level ? levelWord(row.place.level, row.place.path) : null]));
  const words = new Set(places.values());
  const word = words.size === 1 ? [...words][0] ?? 'place' : 'place';
  return places.size === 1 ? `a former ${word}` : `former ${word}s`;
}

/** "12 of your photos are in a former district. Review them:" */
export function formerLine(n, rows) {
  return `${n === 1 ? '1 of your photos is' : `${num(n)} of your photos are`} in ${formerWhere(rows)}. Review them:`;
}

/** The smaller card after Dismiss: "12 photos are still in a former district". */
export function stillLine(n, rows) {
  return `${n === 1 ? '1 photo is' : `${num(n)} photos are`} still in ${formerWhere(rows)}`;
}

/** A species line's photos: "8 photos in Chittoor (former district)". */
export const inPlace = (row) => `${photos(row.photos)} in ${row.place.path[0]}`;

/** "…and 3 more species" past the first lines shown. */
export const moreSpecies = (n) => `…and ${num(n)} more species`;

/** "3 photos couldn't be re-checked; they keep their old place." */
export const notCheckedLine = (n) =>
  (n === 1 ? "1 photo couldn't be re-checked; it keeps its old place." : `${num(n)} photos couldn't be re-checked; they keep their old place.`);

/** Photos not checked yet with the re-check stopped for this run (contract §10): it finishes at the next start. */
export const pendingLine = (n) =>
  `${n === 1 ? "1 photo hasn't" : `${num(n)} photos haven't`} been checked yet; Butterfly Atlas will finish when it next starts.`;

/**
 * Photos the re-check left with no place (contract §10 `lost`), with the place they were in when the note names it:
 * "2 photos in Chittoor (former district) no longer have a place: their GPS position is outside every place in the new
 * list. Set their place by hand if you know it."
 */
export function lostLine(n, place) {
  // A place the library no longer has is not named ("in a place that no longer exists no longer has a place").
  const where = knownPlace(place) ? ` in ${place.path?.[0] ?? place.name}` : '';
  return n === 1
    ? `1 photo${where} no longer has a place: its GPS position is outside every place in the new list. Set its place by hand if you know it.`
    : `${num(n)} photos${where} no longer have a place: their GPS position is outside every place in the new list. Set their place by hand if you know it.`;
}

/** A place of the user's left under a former parent (contract §10 `kept`): "Your place Tirupati stays under Andhra Pradesh (former state) and keeps its 5 photos." */
export function keptLine({ name, under, photos: n = 0 }) {
  const start = `Your place ${name} stays under ${under?.path?.[0] ?? under?.name}`;
  if (!n) return `${start}.`;
  return `${start} and keeps ${n === 1 ? 'its photo' : `its ${num(n)} photos`}.`;
}
