/** Each family's colour (docs/PLAN.md §8a), as defined in styles/theme.css. */
const KEYS = {
  Papilionidae: 'pap', Pieridae: 'pie', Nymphalidae: 'nym', Lycaenidae: 'lyc', Hesperiidae: 'hes', Riodinidae: 'rio',
};

export function famColor(family) {
  const key = KEYS[family];
  return key ? `var(--f-${key})` : 'var(--accent)';
}

/** The family's deeper shade, for bars and counts (the bright one is for the butterflies; Meadow design §2). */
export function famDeep(family) {
  const key = KEYS[family];
  return key ? `var(--f-${key}-deep)` : 'var(--accent)';
}

export const famStyle = (family) => `--fam:${famColor(family)};--fam-deep:${famDeep(family)}`;
