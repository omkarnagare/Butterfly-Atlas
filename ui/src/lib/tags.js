import { TAG_NAME_EMPTY, TAG_NAME_TOO_LONG, TAG_NAME_TOO_MANY_WORDS, tagNameTaken } from './messages.js';

/** Tag helpers shared by the gallery, viewer and sheets. Photos carry tag keys; labels come from GET /api/tags. */
export const ADULT_KEYS = ['upper_wing', 'under_wing', 'male', 'female', 'dsf', 'wsf'];

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
