import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, test } from 'vitest';
import {
  EMPTY, FILE_TYPES, SOURCE_OPTIONS, applyPreset, chips, countLine, formatBytes, mergeNames, parseFilter, placeNoteLines, placesLine, printFilter, speciesHref, without,
} from './filter.js';

const full = {
  taxa: ['A12', 'A230'], tags: ['egg', 'larva'], tagMode: 'all', places: ['P20', 'unknown'], status: 'photographed', source: 'manual', stages: 'missing',
  types: [],
};

test('an empty query is the empty filter, and the empty filter prints as nothing', () => {
  expect(parseFilter('')).toEqual(EMPTY);
  expect(parseFilter(null)).toEqual(EMPTY);
  expect(printFilter(EMPTY)).toBe('');
});

test('prints every key in the contract order, leaving defaults out, and reads it back', () => {
  const query = 'taxa=A12,A230&tags=egg,larva&tagMode=all&places=P20,unknown&status=photographed&source=manual&stages=missing';
  expect(printFilter(full)).toBe(query);
  expect(parseFilter(query)).toEqual(full);
  expect(printFilter({ ...EMPTY, places: ['P20'], status: 'not' })).toBe('places=P20&status=not');
});

test('keys may come in any order; of a repeated key the first counts; unknown keys are ignored; ids are de-duplicated in order and empty ones dropped', () => {
  expect(parseFilter('status=not&zoom=3&taxa=A5,,A1,A5&places=unknown,P2,unknown&status=all')).toEqual({
    ...EMPTY, taxa: ['A5', 'A1'], places: ['unknown', 'P2'], status: 'not',
  });
});

// The cases the server's FilterTest reads too, so both sides read and print a query string alike (contract §1).
const parity = JSON.parse(readFileSync(resolve(process.cwd(), '../fixtures/filter-parity.json'), 'utf8'));

describe('reads and prints as the server does', () => {
  // Phase 7a: cases written before File type have no `types` (none chosen).
  test.each(parity.cases.map((c) => [c.name, c]))('%s', (_, { query, filter: given, printed }) => {
    const filter = { types: [], ...given };
    expect(parseFilter(query)).toEqual(filter);
    expect(printFilter(filter)).toBe(printed);
    expect(parseFilter(printed)).toEqual(filter);
  });
});

test('a value outside its list falls back to the default, so the server is never sent one', () => {
  expect(parseFilter('tagMode=most&status=maybe&source=wild&stages=some')).toEqual(EMPTY);
});

test('at most 200 taxa and 200 places are kept', () => {
  const ids = Array.from({ length: 205 }, (_, i) => `A${i + 1}`);
  const parsed = parseFilter(`taxa=${ids.join(',')}&places=${ids.map((id) => id.replace('A', 'P')).join(',')}`);
  expect(parsed.taxa).toHaveLength(200);
  expect(parsed.places).toHaveLength(200);
  expect(parsed.taxa.at(-1)).toBe('A200');
});

test('ids are encoded when printed (all but letters, digits and - . _ ~ :, as the server prints them) and decoded when read', () => {
  const filter = { ...EMPTY, taxa: ['M:7'], tags: ['café', 'a&b', "it's(1)!*"] };
  expect(printFilter(filter)).toBe('taxa=M:7&tags=caf%C3%A9,a%26b,it%27s%281%29%21%2A');
  expect(parseFilter(printFilter(filter))).toEqual(filter);
});

test('one chip per active filter, worded as the contract says', () => {
  const names = mergeNames(undefined, {
    taxa: [{ id: 'A12', name: 'Lycaenidae' }, { id: 'A230', name: 'Danaus' }],
    tags: [{ key: 'egg', label: 'egg' }],
    places: [{ id: 'P20', name: 'Maharashtra' }, { id: 'unknown' }],
  });
  expect(chips({ ...full, stages: null }, names)).toEqual([
    { kind: 'taxa', label: 'Taxa: Lycaenidae, Danaus' },
    { kind: 'tags', label: 'Tags: egg and larva' },
    { kind: 'places', label: 'Place: Maharashtra, Unknown place' },
    { kind: 'status', label: 'Photographed' },
    { kind: 'source', label: 'Custom' },
  ]);
  expect(chips({ ...EMPTY, tags: ['larva', 'pupa'], status: 'not', source: 'official' }, names).map((c) => c.label))
    .toEqual(['Tags: larva or pupa', 'Not photographed', 'Official']);
  expect(chips({ ...EMPTY, tags: ['egg', 'larva', 'upper_wing'] }, names)[0].label).toBe('Tags: egg, larva or upper_wing');
  expect(chips({ ...EMPTY, stages: 'missing' }, names)).toEqual([{ kind: 'stages', label: 'Missing life stages' }]);
  expect(chips(EMPTY, names)).toEqual([]);
});

test('a taxon or place whose name is not known yet reads "…" (never left out); a tag reads as its key', () => {
  const names = mergeNames(undefined, { places: [{ id: 'P20', name: 'Maharashtra' }] });
  expect(chips({ ...EMPTY, taxa: ['A99'], tags: ['upper_wing'], places: ['P7', 'P20'] }, names).map((c) => c.label))
    .toEqual(['Taxa: …', 'Tags: upper_wing', 'Place: …, Maharashtra']);
});

test('names are remembered across answers; a later answer renames', () => {
  const first = mergeNames(undefined, { taxa: [{ id: 'A12', name: 'Lycaenidae' }] });
  const second = mergeNames(first, { taxa: [{ id: 'A5', name: 'Danaus' }], places: [{ id: 'P20', name: 'Maharashtra' }] });
  const third = mergeNames(second, { places: [{ id: 'P20', name: 'Maharashtra State' }] });
  expect(chips({ ...EMPTY, taxa: ['A12', 'A5'], places: ['P20'] }, third).map((c) => c.label))
    .toEqual(['Taxa: Lycaenidae, Danaus', 'Place: Maharashtra State']);
  expect(first.taxa.has('A5')).toBe(false);
});

test('removing a chip clears only its filter', () => {
  expect(without(full, 'taxa')).toEqual({ ...full, taxa: [] });
  expect(without(full, 'tags')).toEqual({ ...full, tags: [], tagMode: 'any' });
  expect(without(full, 'places')).toEqual({ ...full, places: [] });
  expect(without(full, 'status')).toEqual({ ...full, status: 'all' });
  expect(without(full, 'source')).toEqual({ ...full, source: 'all' });
  expect(without(full, 'stages')).toEqual({ ...full, stages: null });
});

test('presets keep taxa and places (and the source); Clear clears everything', () => {
  const kept = { taxa: full.taxa, places: full.places, source: 'manual', types: full.types };
  expect(applyPreset(full, 'full')).toEqual({ ...EMPTY, ...kept });
  expect(applyPreset(full, 'not')).toEqual({ ...EMPTY, ...kept, status: 'not' });
  expect(applyPreset(full, 'missing')).toEqual({ ...EMPTY, ...kept, stages: 'missing' });
  expect(applyPreset(full, 'clear')).toEqual(EMPTY);
});

const answer = (photos, species, photographedSpecies, bytes, filter = {}) => ({
  photos, species, photographedSpecies, bytes, manualSpecies: false, filter: { ...EMPTY, ...filter },
});
const text = (line) => line.map((part) => (typeof part === 'string' ? part : part.n.toLocaleString())).join('');

test('the count line: photos of the photographed species and size; the rest of the list with status all; singulars', () => {
  expect(text(countLine(answer(312, 48, 48, 4509715660, { status: 'photographed' })))).toBe('312 photos of 48 species, 4.2 GB');
  expect(text(countLine(answer(1, 1, 1, 2048, { status: 'photographed' })))).toBe('1 photo of 1 species, 2 KB');
  // with status photographed the rest of the list is not counted, so no "in the list" tail even when the list is larger
  expect(text(countLine(answer(3, 1139, 2, 2048, { status: 'photographed' })))).toBe('3 photos of 2 species, 2 KB');
  expect(text(countLine(answer(3, 1139, 2, 2048)))).toBe('3 photos of 2 species, 2 KB · 1,139 species in the list');
  expect(text(countLine(answer(12400, 48, 48, 0)))).toBe('12,400 photos of 48 species, 0 B');
  expect(text(countLine(answer(0, 1139, 0, 0)))).toBe('No photos · 1,139 species in the list');
  expect(countLine(answer(312, 48, 48, 10))[0]).toEqual({ n: 312 });
});

test('the count line: not photographed, missing life stages and nothing are as before', () => {
  expect(text(countLine(answer(0, 212, 0, 0, { status: 'not' })))).toBe('212 species not yet photographed');
  expect(text(countLine(answer(90, 31, 31, 10, { stages: 'missing' })))).toBe('31 species missing life stages');
  expect(text(countLine(answer(0, 0, 0, 0)))).toBe('Nothing matches these filters.');
  expect(text(countLine(answer(0, 0, 0, 0, { status: 'not' })))).toBe('Nothing matches these filters.');
});

test('sizes: B and KB whole, one decimal from MB up', () => {
  expect(formatBytes(0)).toBe('0 B');
  expect(formatBytes(1023)).toBe('1023 B');
  expect(formatBytes(1536)).toBe('2 KB');
  expect(formatBytes(1024 * 1024 - 1)).toBe('1.0 MB');
  expect(formatBytes(5.25 * 1024 * 1024)).toBe('5.3 MB');
  expect(formatBytes(4509715660)).toBe('4.2 GB');
  expect(formatBytes(3 * 1024 ** 4)).toBe('3.0 TB');
  expect(formatBytes(2048 * 1024 ** 4)).toBe('2048.0 TB');
});

test('the places line under the count', () => {
  const p = (id, name) => ({ id, name, path: [name], level: 'state', mine: false });
  expect(placesLine([])).toBe('');
  expect(placesLine([p('P20', 'Maharashtra')])).toBe('in Maharashtra');
  expect(placesLine([p('P20', 'Maharashtra'), p('P21', 'Goa')])).toBe('in Maharashtra and Goa');
  expect(placesLine([p('P20', 'Maharashtra'), p('P21', 'Goa'), p('P1', 'Kenya')])).toBe('in 3 places');
  expect(placesLine([{ id: 'unknown' }])).toBe('with an unknown place');
  expect(placesLine([p('P20', 'Maharashtra'), { id: 'unknown' }])).toBe('in Maharashtra or with an unknown place');
  expect(placesLine([p('P20', 'Maharashtra'), p('P21', 'Goa'), p('P1', 'Kenya'), { id: 'unknown' }])).toBe('in 3 places or with an unknown place');
});

test('a species row links with its tag chip only for exactly one tag (not with missing life stages) and its place chip for exactly one place', () => {
  expect(speciesHref('A1042', EMPTY)).toBe('#/species/A1042');
  expect(speciesHref('A1042', { ...EMPTY, tags: ['larva'], places: ['P20'] })).toBe('#/species/A1042?tag=larva&place=P20');
  expect(speciesHref('A1042', { ...EMPTY, tags: ['larva', 'pupa'], places: ['unknown'] })).toBe('#/species/A1042?place=unknown');
  expect(speciesHref('A1042', { ...EMPTY, tags: ['larva'], stages: 'missing', places: ['P20', 'P21'] })).toBe('#/species/A1042');
  expect(speciesHref('A:1', { ...EMPTY, tags: ['a&b'] })).toBe('#/species/A%3A1?tag=a%26b');
});

test('each id is trimmed after decoding, as the server reads it; an encoded comma stays inside its id', () => {
  expect(parseFilter('tags=%20larva%20,pupa%09,%20').tags).toEqual(['larva', 'pupa']);
  expect(parseFilter('tags=a%2Cb,c').tags).toEqual(['a,b', 'c']);
});

test('the Source choices read All, Official and Custom (the API value stays manual)', () => {
  expect(SOURCE_OPTIONS).toEqual([{ value: 'all', label: 'All' }, { value: 'official', label: 'Official' }, { value: 'manual', label: 'Custom' }]);
});

test('the place notes of a filter: each merged place, and the places gone counted in one line', () => {
  const brezice = { id: 'P4187', name: 'Brežice', path: ['Brežice', 'Slovenia'], level: 'district', former: false };
  expect(placeNoteLines(undefined)).toEqual([]);
  expect(placeNoteLines([])).toEqual([]);
  expect(placeNoteLines([{ id: 'P4245', kind: 'merged', name: 'Brežice (Pečice)', into: brezice }]))
    .toEqual(['Brežice (Pečice) is now part of Brežice.']);
  // the merged place's name comes labelled; the line names it as it was
  expect(placeNoteLines([{ id: 'P4245', kind: 'merged', name: 'Brežice (Pečice) (former district)', into: brezice }]))
    .toEqual(['Brežice (Pečice) is now part of Brežice.']);
  expect(placeNoteLines([{ id: 'P404', kind: 'gone', name: null, into: null }]))
    .toEqual(['1 place in this filter no longer exists and was left out.']);
  expect(placeNoteLines([
    { id: 'P404', kind: 'gone', name: null, into: null }, { id: 'P4245', kind: 'merged', name: 'Brežice (Pečice)', into: brezice },
    { id: 'P405', kind: 'gone', name: null, into: null },
  ])).toEqual(['Brežice (Pečice) is now part of Brežice.', '2 places in this filter no longer exist and were left out.']);
});

/* ---------- File type (Phase 7a contract §4) ---------- */

test('File type is read and printed after stages, as the given types without repeats; unknown ones are left out', () => {
  expect(EMPTY.types).toEqual([]);
  expect(FILE_TYPES.map((t) => [t.value, t.label])).toEqual([['jpeg', 'JPEG'], ['png', 'PNG'], ['tiff', 'TIFF'], ['webp', 'WebP'], ['raw', 'RAW'], ['heic', 'HEIC']]);
  const filter = { ...EMPTY, source: 'official', stages: 'missing', types: ['raw', 'jpeg'] };
  expect(printFilter(filter)).toBe('source=official&stages=missing&types=raw,jpeg');
  expect(parseFilter('types=raw,jpeg&stages=missing&source=official')).toEqual(filter);
  expect(parseFilter('types=raw,avif,,RAW,raw,png').types).toEqual(['raw', 'png']);
  // Phase 7b: HEIC (types=heic).
  expect(parseFilter('types=heic,raw,heic').types).toEqual(['heic', 'raw']);
  expect(printFilter({ ...EMPTY, types: ['heic'] })).toBe('types=heic');
  expect(chips({ ...EMPTY, types: ['raw', 'heic'] }, undefined)).toEqual([{ kind: 'types', label: 'File type: RAW, HEIC' }]);
  expect(parseFilter('types=').types).toEqual([]);
});

test('File type has a chip after Source ("File type: JPEG, RAW"), and removing it clears it', () => {
  const filter = { ...EMPTY, source: 'official', types: ['jpeg', 'raw'] };
  expect(chips(filter, undefined)).toEqual([{ kind: 'source', label: 'Official' }, { kind: 'types', label: 'File type: JPEG, RAW' }]);
  expect(chips({ ...EMPTY, types: ['raw'], stages: 'missing' }, undefined).map((c) => c.kind)).toEqual(['types', 'stages']);
  expect(without(filter, 'types')).toEqual({ ...filter, types: [] });
});

test('presets keep the file types, as they keep the source; Clear clears them', () => {
  const filter = { ...EMPTY, types: ['raw'], status: 'photographed' };
  expect(applyPreset(filter, 'not')).toEqual({ ...EMPTY, types: ['raw'], status: 'not' });
  expect(applyPreset(filter, 'clear')).toEqual(EMPTY);
});

test('a species row carries one chosen file type to the species page, as it carries one tag and one place', () => {
  expect(speciesHref('A5', { ...EMPTY, types: ['raw'] })).toBe('#/species/A5?type=raw');
  expect(speciesHref('A5', { ...EMPTY, tags: ['larva'], places: ['P20'], types: ['raw'] })).toBe('#/species/A5?tag=larva&place=P20&type=raw');
  expect(speciesHref('A5', { ...EMPTY, types: ['raw', 'jpeg'] })).toBe('#/species/A5');
});
