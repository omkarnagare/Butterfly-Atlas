/** Tag helpers shared by the gallery, viewer and sheets. Photos carry tag keys; labels come from GET /api/tags. */
export const ADULT_KEYS = ['upper_wing', 'under_wing', 'male', 'female', 'dsf', 'wsf'];

/** A key → label function for the given tag list; unknown keys read as their key with spaces. */
export function labeller(tags) {
  const labels = new Map((tags ?? []).map((tag) => [tag.key, tag.label]));
  return (key) => labels.get(key) ?? key.replace(/_/g, ' ');
}

/** How many of `photos` carry each tag key. */
export function tagCounts(photos) {
  const counts = new Map();
  for (const photo of photos) for (const key of photo.tags) counts.set(key, (counts.get(key) ?? 0) + 1);
  return counts;
}
