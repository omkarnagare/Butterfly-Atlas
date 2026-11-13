import { TAG_NAME_EMPTY, TAG_NAME_TOO_LONG, TAG_NAME_TOO_MANY_WORDS, tagNameTaken } from './messages.js';

/** Tag helpers shared by the gallery, viewer and sheets. Photos carry tag keys; labels come from GET /api/tags. */
/** The tags that mean "an adult" (behaviour tags §3): the same keys, in the same order, as the server's PhotoRows.ADULT_KEYS. */
export const ADULT_KEYS = ['upper_wing', 'under_wing', 'male', 'female', 'dsf', 'wsf', 'mating', 'resting', 'basking', 'nectaring',
  'puddling'];

/** Tags that change with another while tagging (2026-11-13 design, addendum §5): a mating pair is a male and a female. */
const PARTNERS = { mating: ['male', 'female'] };

/** `keys` with `key` turned the other way, and `also` with it (each added once, or all taken out). */
export function toggled(keys, key, also = []) {
  const changing = [key, ...also];
  return keys.includes(key) ? keys.filter((k) => !changing.includes(k)) : [...keys, ...changing.filter((k) => !keys.includes(k))];
}

/**
 * The other tags a press of `key` changes while tagging, given each tag's state ('on', 'some', 'off'): pressing mating
 * when it isn't on turns it on, and with it male and female (those not on already); when it is on, it goes off, and with
 * it male and female (those not off already).
 */
export function alsoChanging(key, stateOf) {
  const partners = PARTNERS[key] ?? [];
  const on = stateOf(key) !== 'on';
  return partners.filter((partner) => (on ? stateOf(partner) !== 'on' : stateOf(partner) !== 'off'));
}

/** A key → label function for the given tag list; unknown keys read as the key itself. */
export function labeller(tags) {
  const labels = new Map((tags ?? []).map((tag) => [tag.key, tag.label]));
  return (key) => labels.get(key) ?? key;
}

const TAG_NAME_MAX_LENGTH = 40;
export const TAG_NAME_MAX_WORDS = 3;
const NOT_ALPHANUMERIC = /[^\p{L}\p{N}]+/gu;
const abbreviation = (word) => {
  const letters = [...word].filter((ch) => /\p{L}/u.test(ch));
  return letters.length >= 2 && letters.every((ch) => /\p{Lu}/u.test(ch));
};

/**
 * A tag name in its one form, as the server's TagNames.form (tag-names design §2): every run of characters that are
 * not letters or digits one `_`, each word in lower case unless it is an abbreviation (2+ letters, all capitals).
 */
export function formTagName(raw) {
  return (raw ?? '').normalize('NFC').replace(NOT_ALPHANUMERIC, '_').split('_').filter(Boolean)
    .map((word) => (abbreviation(word) ? word : word.toLowerCase())).join('_');
}

/** A name as the picker's search compares it: its one form, without case. */
const searchForm = (raw) => formTagName(raw).toLowerCase();

/**
 * The tags whose name contains what is typed (behaviour tags §4.2), in the order given (tag order): without case, with
 * spaces, `_` and other marks counted as the same, leading and trailing spaces ignored (both sides take formTagName's
 * form). Nothing is typed (or only spaces or marks): no match.
 */
export function matchTags(tags, query) {
  const wanted = searchForm(query);
  if (!wanted) return [];
  return (tags ?? []).filter((tag) => searchForm(tag.label).includes(wanted));
}

/** The tag whose name is the typed name (after formTagName, without case), or null. */
export function tagNamed(tags, query) {
  const wanted = searchForm(query);
  if (!wanted) return null;
  return (tags ?? []).find((tag) => searchForm(tag.label) === wanted) ?? null;
}

/**
 * `tags` with `tag` in its place in tag order (TagService.ORDER: built-in tags first as they are, then the user's by
 * name without case), for a tag just created; the list as it is when it already has the key.
 */
export function withTag(tags, tag) {
  const list = tags ?? [];
  if (list.some((each) => each.key === tag.key)) return list;
  const name = tag.label.toLowerCase();
  const at = list.findIndex((each) => each.source !== 'builtin' && (tag.source === 'builtin' || each.label.toLowerCase() > name));
  return at === -1 ? [...list, tag] : [...list.slice(0, at), tag, ...list.slice(at)];
}

/** How many words a formed name has. */
export const tagWords = (name) => (name ? name.split('_').length : 0);

/**
 * What a new name or a rename (of the tag `exceptId`) will be saved as, and why it can't be (the server's words),
 * '' when it can.
 */
export function checkTagName(raw, tags, exceptId = null) {
  const name = formTagName(raw);
  let problem = '';
  if (!name) problem = TAG_NAME_EMPTY;
  else if (tagWords(name) > TAG_NAME_MAX_WORDS) problem = TAG_NAME_TOO_MANY_WORDS;
  else if (name.length > TAG_NAME_MAX_LENGTH) problem = TAG_NAME_TOO_LONG;
  else {
    const same = (tags ?? []).find((t) => t.id !== exceptId && t.label.toLowerCase() === name.toLowerCase());
    if (same) problem = tagNameTaken(same.label);
  }
  return { name, problem };
}

/** How many of `photos` carry each tag key. */
export function tagCounts(photos) {
  const counts = new Map();
  for (const photo of photos) for (const key of photo.tags) counts.set(key, (counts.get(key) ?? 0) + 1);
  return counts;
}
