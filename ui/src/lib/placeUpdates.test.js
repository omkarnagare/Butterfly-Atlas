import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import {
  countsLine, formerLine, keptLine, lostLine, pendingLine, formerWhere, inPlace, itemLine, mineLine, moreSpecies, movedLine, movedMore, notCheckedLine, stillLine,
} from './placeUpdates.js';

beforeEach(() => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => vi.restoreAllMocks());

const ref = (id, name, path, level = 'district', former = false) => ({ id, name, path, level, former });
const brezice = ref('P4187', 'Brežice', ['Brežice', 'Slovenia']);
// An inactive place's name comes labelled, as its path does (the server's PlaceRef).
const pecice = ref('P4245', 'Brežice (Pečice) (former district)', ['Brežice (Pečice) (former district)', 'Slovenia'], 'district', true);
const chittoor = ref('P300', 'Chittoor (former district)', ['Chittoor (former district)', 'Andhra Pradesh', 'India'], 'district', true);
const tirupati = ref('P301', 'Tirupati', ['Tirupati', 'Andhra Pradesh', 'India']);
const row = (place, photos) => ({ id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', place, photos });

test('the counts sentence: the kinds that touch the library, the first naming places, and the rest worldwide', () => {
  expect(countsLine({ merged: 1, renamed: 2, elsewhere: 40 })).toBe('1 place merged, 2 renamed and 40 other places worldwide.');
  expect(countsLine({ merged: 0, split: 3, retired: 1, moved: 1200 })).toBe('3 places split, 1 retired, 1,200 moved.');
  expect(countsLine({ added: 1, elsewhere: 1 })).toBe('1 place added and 1 other place worldwide.');
  expect(countsLine({ elsewhere: 5 })).toBe('5 places changed worldwide.');
  expect(countsLine({})).toBe('');
});

test('a change as a short line: old names to the new places and where they are, with its photos', () => {
  expect(itemLine({ type: 'merge', from: [pecice], to: [brezice], photos: 3 })).toBe('Brežice (Pečice) → Brežice, Slovenia · 3 photos');
  expect(itemLine({ type: 'merge', from: [pecice, ref('P9', 'Krsko (former district)', ['Krsko (former district)', 'Slovenia'], 'district', true)], to: [brezice], photos: 1 }))
    .toBe('Brežice (Pečice) + Krsko → Brežice, Slovenia · 1 photo');
  expect(itemLine({ type: 'split', from: [chittoor], to: [ref('P300', 'Chittoor', ['Chittoor', 'Andhra Pradesh', 'India']), tirupati], photos: 20 }))
    .toBe('Chittoor → Chittoor and Tirupati, Andhra Pradesh, India · 20 photos');
  expect(itemLine({ type: 'rename', from: [ref('P161', 'Shahdra', ['Shahdara', 'Delhi', 'India'])], to: [ref('P161', 'Shahdara', ['Shahdara', 'Delhi', 'India'])], photos: 0 }))
    .toBe('Shahdra → Shahdara, Delhi, India');
  expect(itemLine({ type: 'move', from: [ref('P161', 'Shahdara', ['Shahdara', 'Delhi', 'India'])], to: [ref('P161', 'Shahdara', ['Shahdara', 'Delhi', 'India'])], photos: 4 }))
    .toBe('Shahdara is now in Delhi, India · 4 photos');
  expect(itemLine({ type: 'retire', from: [chittoor], to: [], photos: 2 })).toBe('Chittoor, Andhra Pradesh, India is no longer listed · 2 photos');
  expect(itemLine({ type: 'add', from: [], to: [tirupati], photos: 0 })).toBe('New place: Tirupati, Andhra Pradesh, India');
});

test('a place of yours merged into an official one, with its photos', () => {
  expect(mineLine({ name: 'Tirupati', into: tirupati, photos: 12 })).toBe('Your place Tirupati is now an official district; its 12 photos moved to it.');
  expect(mineLine({ name: 'Tirupati', into: tirupati, photos: 1 })).toBe('Your place Tirupati is now an official district; its photo moved to it.');
  expect(mineLine({ name: 'Goa', into: ref('P20', 'Goa', ['Goa', 'India'], 'state'), photos: 0 })).toBe('Your place Goa is now an official state.');
});

test('the re-check\'s moves, per group, and those not listed', () => {
  expect(movedLine({ from: chittoor, to: tirupati, photos: 8 })).toBe('8 photos moved from Chittoor (former district) to Tirupati.');
  expect(movedLine({ from: null, to: tirupati, photos: 1 })).toBe('1 photo without a place is now in Tirupati.');
  expect(movedLine({ from: tirupati, to: null, photos: 2 })).toBe('2 photos in Tirupati no longer have a place.');
  expect(movedMore({ total: 40, groups: [{ photos: 8 }, { photos: 2 }] })).toBe('…and 30 more photos moved.');
  expect(movedMore({ total: 9, groups: [{ photos: 8 }] })).toBe('…and 1 more photo moved.');
  expect(movedMore({ total: 8, groups: [{ photos: 8 }] })).toBe('');
  // moves to no place are not moves (the server leaves them out of total and groups; a stray group is not counted)
  expect(movedMore({ total: 8, groups: [{ to: chittoor, photos: 8 }, { to: null, photos: 1 }] })).toBe('');
  expect(movedMore({ total: 10, groups: [{ to: chittoor, photos: 8 }, { to: null, photos: 1 }] })).toBe('…and 2 more photos moved.');
});

test('former places are named by their level: one place, several of one level, or mixed levels', () => {
  expect(formerWhere([row(chittoor, 8), row(chittoor, 2)])).toBe('a former district');
  expect(formerWhere([row(chittoor, 8), row(pecice, 2)])).toBe('former districts');
  expect(formerWhere([row(chittoor, 8), row(ref('P2', 'Old', ['Old (former state)', 'India'], 'state', true), 1)])).toBe('former places');
  expect(formerWhere([])).toBe('former places');
  const gorenjska = ref('P900', 'Gorenjska (former region)', ['Gorenjska (former region)', 'Slovenia'], 'state', true);
  expect(formerWhere([row(gorenjska, 3)])).toBe('a former region');
  expect(formerLine(12, [row(chittoor, 12)])).toBe('12 of your photos are in a former district. Review them:');
  expect(formerLine(1, [row(chittoor, 1)])).toBe('1 of your photos is in a former district. Review them:');
  expect(stillLine(12, [row(chittoor, 12)])).toBe('12 photos are still in a former district');
  expect(stillLine(1, [])).toBe('1 photo is still in former places');
});

test('a species line\'s photos, the species past the first, and the photos not re-checked', () => {
  expect(inPlace(row(chittoor, 8))).toBe('8 photos in Chittoor (former district)');
  expect(inPlace(row(chittoor, 1))).toBe('1 photo in Chittoor (former district)');
  expect(moreSpecies(1203)).toBe('…and 1,203 more species');
  expect(notCheckedLine(3)).toBe("3 photos couldn't be re-checked; they keep their old place.");
  expect(notCheckedLine(1)).toBe("1 photo couldn't be re-checked; it keeps its old place.");
});

test('photos not checked yet, once the re-check has stopped for this run', () => {
  expect(pendingLine(1200)).toBe("1,200 photos haven't been checked yet; Butterfly Atlas will finish when it next starts.");
  expect(pendingLine(1)).toBe("1 photo hasn't been checked yet; Butterfly Atlas will finish when it next starts.");
});

test('photos the re-check left with no place: where they were, why, and what to do', () => {
  expect(lostLine(2, chittoor)).toBe('2 photos in Chittoor (former district) no longer have a place: their GPS position is outside every place in the new list. Set their place by hand if you know it.');
  expect(lostLine(1, chittoor)).toBe('1 photo in Chittoor (former district) no longer has a place: its GPS position is outside every place in the new list. Set its place by hand if you know it.');
  expect(lostLine(3, null)).toBe('3 photos no longer have a place: their GPS position is outside every place in the new list. Set their place by hand if you know it.');
});

test('a place of yours left under a former parent keeps its photos', () => {
  const ap = ref('P30', 'Andhra Pradesh (former state)', ['Andhra Pradesh (former state)', 'India'], 'state', true);
  expect(keptLine({ name: 'Tirupati', under: ap, photos: 5 })).toBe('Your place Tirupati stays under Andhra Pradesh (former state) and keeps its 5 photos.');
  expect(keptLine({ name: 'Tirupati', under: ap, photos: 1 })).toBe('Your place Tirupati stays under Andhra Pradesh (former state) and keeps its photo.');
  expect(keptLine({ name: 'Tirupati', under: ap, photos: 0 })).toBe('Your place Tirupati stays under Andhra Pradesh (former state).');
});

test('two places of yours with one name, now one (contract §10 intoMine)', () => {
  const mineTirupati = { ...tirupati, id: 'PU3', mine: true };
  expect(mineLine({ name: 'Tirupati', into: mineTirupati, photos: 12, intoMine: true })).toBe('Your two places named Tirupati are now one; its 12 photos moved to it.');
  expect(mineLine({ name: 'Tirupati', into: mineTirupati, photos: 1, intoMine: true })).toBe('Your two places named Tirupati are now one; its photo moved to it.');
  expect(mineLine({ name: 'Tirupati', into: mineTirupati, photos: 0, intoMine: true })).toBe('Your two places named Tirupati are now one.');
});

test('several old places are joined with "+", since names may hold "and"; several new ones read as a list (real app)', () => {
  const dnh = ref('P60', 'Dadra and Nagar Haveli (former state)', ['Dadra and Nagar Haveli (former state)', 'India'], 'state', true);
  const daman = ref('P61', 'Daman and Diu (former state)', ['Daman and Diu (former state)', 'India'], 'state', true);
  const merged = ref('P62', 'Dadra and Nagar Haveli and Daman and Diu', ['Dadra and Nagar Haveli and Daman and Diu', 'India'], 'state');
  expect(itemLine({ type: 'merge', from: [dnh, daman], to: [merged], photos: 2 }))
    .toBe('Dadra and Nagar Haveli + Daman and Diu → Dadra and Nagar Haveli and Daman and Diu, India · 2 photos');
  const three = ['Annamayya', 'Tirupati', 'Chittoor'].map((name, i) => ref(`P30${i}`, name, [name, 'Andhra Pradesh', 'India']));
  expect(itemLine({ type: 'split', from: [chittoor], to: three, photos: 0 })).toBe('Chittoor → Annamayya, Tirupati and Chittoor, Andhra Pradesh, India');
  expect(itemLine({ type: 'split', from: [chittoor], to: three.slice(0, 2), photos: 0 })).toBe('Chittoor → Annamayya and Tirupati, Andhra Pradesh, India');
});

// A place the library no longer has (the server names it so, with no level).
const gone = { id: 'P9999', name: 'a place that no longer exists', path: ['a place that no longer exists'], level: null, former: true };

test('a place that no longer exists reads well in every line', () => {
  expect(mineLine({ name: 'Tirupati', into: gone, photos: 12 })).toBe('Your place Tirupati is now part of a place that no longer exists; its 12 photos moved to it.');
  expect(keptLine({ name: 'Tirupati', under: gone, photos: 5 })).toBe('Your place Tirupati stays under a place that no longer exists and keeps its 5 photos.');
  expect(lostLine(2, gone)).toBe('2 photos no longer have a place: their GPS position is outside every place in the new list. Set their place by hand if you know it.');
  expect(itemLine({ type: 'merge', from: [gone], to: [brezice], photos: 1 })).toBe('a place that no longer exists → Brežice, Slovenia · 1 photo');
  expect(movedLine({ from: gone, to: tirupati, photos: 3 })).toBe('3 photos moved from a place that no longer exists to Tirupati.');
  expect(inPlace(row(gone, 2))).toBe('2 photos in a place that no longer exists');
  expect(formerWhere([row(gone, 2)])).toBe('a former place');
});
