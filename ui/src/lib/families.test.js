import { expect, test } from 'vitest';
import { famColor, famDeep, famStyle } from './families.js';

test('each family has its own colour', () => {
  expect(famColor('Nymphalidae')).toBe('var(--f-nym)');
  expect(famColor('Riodinidae')).toBe('var(--f-rio)');
  expect(famStyle('Papilionidae')).toBe('--fam:var(--f-pap);--fam-deep:var(--f-pap-deep)');
});

test('bars and counts take the deeper shade of the family colour', () => {
  expect(famDeep('Pieridae')).toBe('var(--f-pie-deep)');
  expect(famDeep(null)).toBe('var(--accent)');
});

test('anything else uses the accent colour', () => {
  expect(famColor(null)).toBe('var(--accent)');
  expect(famColor('Unknownidae')).toBe('var(--accent)');
  expect(famStyle(null)).toBe('--fam:var(--accent);--fam-deep:var(--accent)');
});
