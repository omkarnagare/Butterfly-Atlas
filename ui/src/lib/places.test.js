import { expect, test } from 'vitest';
import { atPlace, bareName, levelWord, placeChips, widerChip } from './places.js';

const at = (id, path, ids) => ({ id, path, ids, source: 'gps' });
const pune = at('P414', ['Pune', 'Maharashtra', 'India'], ['P414', 'P20', 'P1']);
const satara = at('P420', ['Satara', 'Maharashtra', 'India'], ['P420', 'P20', 'P1']);
const kenya = at('P900', ['Kenya'], ['P900']);
const photos = [{ id: 3, place: pune }, { id: 2, place: satara }, { id: 1, place: null }, { id: 4, place: kenya }];

test('a photo is at a place when its own place is it or is below it (by its place ids); unknown means no place', () => {
  expect(atPlace(photos[0], 'P414')).toBe(true);
  expect(atPlace(photos[0], 'P20')).toBe(true);
  expect(atPlace(photos[0], 'P1')).toBe(true);
  expect(atPlace(photos[0], 'P420')).toBe(false);
  expect(atPlace(photos[2], 'P20')).toBe(false);
  expect(atPlace(photos[2], 'unknown')).toBe(true);
  expect(atPlace(photos[0], 'unknown')).toBe(false);
  // a place from before 4a, without ids, still matches its own id
  expect(atPlace({ place: { id: 'P414', path: ['Pune'] } }, 'P414')).toBe(true);
});

test('the extra chip for a place above the photos\' own places', () => {
  expect(widerChip(photos, 'P20')).toEqual({ key: 'P20', name: 'Maharashtra', title: 'Maharashtra, India', n: 2 });
  expect(widerChip(photos, 'P1')).toEqual({ key: 'P1', name: 'India', title: 'India', n: 2 });
  expect(widerChip(photos, 'P414')).toBeNull();
  expect(widerChip(photos, 'unknown')).toBeNull();
  expect(widerChip(photos, 'P999')).toBeNull();
  expect(widerChip(photos, null)).toBeNull();
  expect(placeChips(photos).chips.map((c) => c.key)).toEqual(['P900', 'P414', 'P420']);
});

test('a chip counts every photo it shows: one at Maharashtra and one at Pune make "Maharashtra · 2"', () => {
  const maharashtra = at('P20', ['Maharashtra', 'India'], ['P20', 'P1']);
  const { chips } = placeChips([{ id: 1, place: maharashtra }, { id: 2, place: pune }]);
  expect(chips.map((c) => [c.name, c.n])).toEqual([['Maharashtra', 2], ['Pune', 1]]);
});

test('a former place\'s name without its label, taken from its level (never read from the name); the level\'s word', () => {
  expect(bareName('Chittoor (former district)', 'district')).toBe('Chittoor');
  expect(bareName('Brežice (Pečice) (former district)', 'district')).toBe('Brežice (Pečice)');
  expect(bareName('Brežice (Pečice)', 'district')).toBe('Brežice (Pečice)');
  // without a level nothing is taken off
  expect(bareName('Chittoor (former district)', undefined)).toBe('Chittoor (former district)');
  expect(levelWord('state')).toBe('state');
  expect(levelWord('county')).toBe('place');
  const chittoor = { id: 'P300', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], level: 'district', source: 'gps', former: true };
  expect(placeChips([{ id: 1, place: chittoor }]).chips[0]).toMatchObject({ key: 'P300', former: true, level: 'district' });
});

test('outside India a first-level place is a region: its word and its former label (India keeps "state")', () => {
  expect(levelWord('state', ['Gorenjska', 'Slovenia'])).toBe('region');
  expect(levelWord('state', ['Kerala', 'India'])).toBe('state');
  expect(levelWord('district', ['Bled', 'Gorenjska', 'Slovenia'])).toBe('district');
  expect(bareName('Gorenjska (former region)', 'state')).toBe('Gorenjska');
  expect(bareName('Old (former state)', 'state')).toBe('Old');
  const gorenjska = { id: 'P900', path: ['Gorenjska (former region)', 'Slovenia'], ids: ['P900', 'P800'], level: 'state', source: 'user', former: true };
  expect(placeChips([{ id: 1, place: gorenjska }]).chips[0]).toMatchObject({ path: ['Gorenjska (former region)', 'Slovenia'] });
});

test('chip counts match "the photos the chip shows" for every chip, places nested at every depth', () => {
  const india = ['India'];
  const mh = at('P20', ['Maharashtra', 'India'], ['P20', 'P1']);
  const punePlace = at('P21', ['Pune', 'Maharashtra', 'India'], ['P21', 'P20', 'P1']);
  const mumbai = at('P22', ['Mumbai', 'Maharashtra', 'India'], ['P22', 'P20', 'P1']);
  const country = at('P1', india, ['P1']);
  const photos = [mh, punePlace, punePlace, mumbai, country, null, { id: 'P9', path: ['Odd'] }]
    .map((place, i) => ({ id: i + 1, place }));
  const { chips, unknown } = placeChips(photos);
  for (const chip of chips) expect(chip.n).toBe(photos.filter((photo) => atPlace(photo, chip.key)).length);
  expect(chips.map((c) => [c.name, c.n])).toEqual([['India', 5], ['Maharashtra', 4], ['Pune', 2], ['Mumbai', 1], ['Odd', 1]]);
  expect(unknown).toBe(1);
});
