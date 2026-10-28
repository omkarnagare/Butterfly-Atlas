/**
 * Places as the UI shows them (contract §1: Place `{id, name, path, level, mine}`; a photo's or item's place
 * `{id, path, source}`, whose name is the first part of its path).
 */

/** Where a place is: the rest of its path ("Maharashtra, India"), empty for a country. */
export const placeWhere = (place) => place.path.slice(1).join(', ');

const fold = (text) => text.trim().normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

const LEVELS = { district: 'district', state: 'state', country: 'country' };
/**
 * The word for a place's level ("district", "state", "country"); a level this page does not know is a place. Given
 * the place's `path`, a first-level place outside India is a "region", as the server labels it ("(former region)").
 */
export const levelWord = (level, path) =>
  level === 'state' && path?.length && fold(path.at(-1)) !== 'india' ? 'region' : LEVELS[level] ?? 'place';
/**
 * A place the library has: one it no longer has comes named "a place that no longer exists" with no level (Phase 5c),
 * so lines do not link to it or call it by a level.
 */
export const knownPlace = (place) => Boolean(place?.id && LEVELS[place.level]);
/**
 * A place's name without the " (former district)" an inactive place's name carries (Phase 5c contract §11), worked out
 * from its `level`, never by reading the name; without a known level the name stays as it is.
 */
export function bareName(name, level) {
  const labels = LEVELS[level] ? [` (former ${level})`, ...(level === 'state' ? [' (former region)'] : [])] : [];
  const label = labels.find((l) => name?.endsWith(l));
  return label ? name.slice(0, -label.length) : name ?? '';
}

/** Two names the server would treat as the same: case and accents aside. */
export const sameName = (a, b) => fold(a) === fold(b);

/**
 * Whether a photo is at the gallery's place filter: a place id, or "unknown" for photos without one. A photo is at a
 * place when its own place is that place or below it (its `place.ids`, the place itself up to its country, 4a contract).
 */
export const atPlace = (photo, key) => {
  if (key === 'unknown') return !photo.place;
  return photo.place?.id === key || (photo.place?.ids?.includes(key) ?? false);
};

/**
 * The species page's extra chip for a place above the photos' own places (a state, from the Export results: contract
 * §4 "in Maharashtra · 12"): `{ key, name, title, n }`, or null when `key` is one of the photos' own places, unknown,
 * or on no photo.
 */
export function widerChip(photos, key) {
  if (!key || key === 'unknown' || photos.some((photo) => photo.place?.id === key)) return null;
  const at = photos.filter((photo) => atPlace(photo, key));
  if (!at.length) return null;
  const { path, ids } = at[0].place;
  const from = ids.indexOf(key);
  return { key, name: path[from], title: path.slice(from).join(', '), n: at.length };
}

/** The name of the place `key` on a photo's place: its own, or that of the place above it that `key` is. */
export function nameAt(place, key) {
  const at = place?.ids?.indexOf(key) ?? -1;
  return at >= 0 ? place.path[at] : place?.path[0];
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/**
 * The species page's "Where photographed" line, worked out from the photos it shows: one chip per place
 * (`key` its id, `name`, `title` the full path, `n` the photos it shows: at it or below it, `former` a former place), most photos first then
 * by name, and how many have no place.
 */
export function placeChips(photos) {
  const byId = new Map();
  let unknown = 0;
  for (const photo of photos) {
    if (!photo.place) {
      unknown += 1;
      continue;
    }
    if (!byId.has(photo.place.id)) {
      byId.set(photo.place.id, { key: photo.place.id, name: photo.place.path[0], title: photo.place.path.join(', '), path: photo.place.path, n: 0, former: Boolean(photo.place.former), level: photo.place.level ?? null });
    }
  }
  // Counted as the chip filters (atPlace): a place's chip also counts the photos at places below it, which it shows.
  // One pass: each photo counts once for its own place and each place above it (its `ids`) that has a chip.
  for (const photo of photos) {
    if (!photo.place) continue;
    for (const key of new Set([photo.place.id, ...(photo.place.ids ?? [])])) {
      const chip = byId.get(key);
      if (chip) chip.n += 1;
    }
  }
  const chips = [...byId.values()].sort((a, b) => b.n - a.n || collator.compare(a.name, b.name));
  return { chips, unknown };
}
