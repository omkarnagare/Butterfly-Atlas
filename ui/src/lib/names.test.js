import { expect, test } from 'vitest';
import { RANK_LABELS, names } from './names.js';

test('a species is titled by its common name with the scientific name beneath', () => {
  expect(names({ rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger' }))
    .toEqual({ title: 'Plain Tiger', titleLatin: false, sub: 'Danaus chrysippus', subLatin: true });
});

test('a species without a common name is titled by its scientific name', () => {
  expect(names({ rank: 'species', name: 'Papilio demoleus', common: null }))
    .toEqual({ title: 'Papilio demoleus', titleLatin: true, sub: null, subLatin: false });
});

test('a genus keeps its italic scientific name as the title', () => {
  expect(names({ rank: 'genus', name: 'Papilio', common: 'Swallowtails' }))
    .toEqual({ title: 'Papilio', titleLatin: true, sub: 'Swallowtails', subLatin: false });
});

test('higher ranks use the upright scientific name', () => {
  expect(names({ rank: 'family', name: 'Nymphalidae', common: 'Brush-footed Butterflies' }))
    .toEqual({ title: 'Nymphalidae', titleLatin: false, sub: 'Brush-footed Butterflies', subLatin: false });
  expect(names({ rank: 'tribe', name: 'Danaini', common: null }).sub).toBeNull();
});

test('the system branch of custom species is labelled Custom additions', () => {
  expect(RANK_LABELS.additions).toBe('Custom additions');
});
