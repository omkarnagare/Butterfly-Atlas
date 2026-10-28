/**
 * Bursts: shots taken within two minutes of each other, by capture time (EXIF), else the file's modified time
 * (design B5). Only a selection aid; computed here from the items, never stored.
 */
export const BURST_GAP_MS = 2 * 60 * 1000;

/** When the photo was taken, as milliseconds; 0 when neither time can be read. */
export function itemTime(item) {
  const time = Date.parse(item.capturedAt ?? item.modifiedAt ?? '');
  return Number.isNaN(time) ? 0 : time;
}

const byName = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/** Oldest first; photos taken at the same moment by name (natural number order, so IMG_9 comes before IMG_10). */
export const byTime = (a, b) => itemTime(a) - itemTime(b) || byName.compare(a.name, b.name) || a.id - b.id;

/**
 * The items sorted as `byTime` (after `first`, when given, e.g. by folder), each with its time: `[{ item, time }]`.
 * Each time is read once, not on every comparison, so ten thousand photos sort quickly.
 */
export function timeSorted(items, first = null) {
  const timed = items.map((item) => ({ item, time: itemTime(item) }));
  timed.sort((a, b) => (first ? first(a.item, b.item) : 0) || a.time - b.time || byName.compare(a.item.name, b.item.name) || a.item.id - b.item.id);
  return timed;
}

/**
 * Splits items into groups in time order. A burst is two or more shots each within `gap` of the one before;
 * single shots between bursts are kept together as one loose group, so they share rows of tiles.
 * Each group: `{ key, burst, items, start, end }` (start/end in ms; key from its first item).
 */
export function bursts(items, gap = BURST_GAP_MS) {
  const runs = [];
  for (const each of timeSorted(items)) {
    const run = runs.at(-1);
    if (run && each.time - run.at(-1).time <= gap) run.push(each);
    else runs.push([each]);
  }
  const groups = [];
  for (const run of runs) {
    const last = groups.at(-1);
    if (run.length === 1 && last && !last.burst) last.items.push(run[0]);
    else groups.push({ burst: run.length > 1, items: run });
  }
  return groups.map((group) => ({
    key: `${group.burst ? 'b' : 'l'}${group.items[0].item.id}`,
    burst: group.burst,
    items: group.items.map((each) => each.item),
    start: group.items[0].time,
    end: group.items.at(-1).time,
  }));
}

/** "12 Aug 2024, 10:22" for a burst header. `options` (tests) can pin the locale and time zone. */
export function burstTime(ms, { locale, timeZone } = {}) {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(new Date(ms));
}
