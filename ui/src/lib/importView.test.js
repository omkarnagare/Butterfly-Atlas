import { expect, test, vi } from 'vitest';
import {
  FILTERS, applyFilter, category, editable, fileable, filterCounts, folderSections, inLibrary, keepPlace, layoutRows, measureRows, photoCount,
  fileButton, filesToFile, placeLine, sectionSuggestion, shots, speciesSections, stoppedLine, tileStatus, visibleRange,
  pairBadge, pairChoiceLabel, pairChoiceNeeded, pairChoices, pairCount, pairFiled, pairName,
} from './importView.js';

const item = (id, extra = {}) => ({
  id, path: `IMG_${id}.JPG`, folder: '', name: `IMG_${id}.JPG`, size: 1, modifiedAt: '2024-08-12T10:00:00Z',
  capturedAt: null, state: 'read', problem: null, suggestedTaxonId: null, libraryTaxonId: null, copyOf: null,
  taxonId: null, tags: [], skipped: false, outcome: null, imageId: null, error: null, v: 'v1', ...extra,
});

test('each item has one category, problems and filed first', () => {
  expect(category(item(1))).toBe('unassigned');
  expect(category(item(2, { state: 'pending', v: null }))).toBe('unassigned');
  expect(category(item(3, { suggestedTaxonId: 'A5' }))).toBe('suggested');
  expect(category(item(4, { suggestedTaxonId: 'A5', taxonId: 'A6' }))).toBe('assigned');
  expect(category(item(5, { taxonId: 'A6', skipped: true }))).toBe('skipped');
  expect(category(item(6, { taxonId: 'A6', outcome: 'filed' }))).toBe('filed');
  expect(category(item(7, { taxonId: 'A6', outcome: 'copy_of' }))).toBe('filed');
  expect(category(item(8, { taxonId: 'A6', outcome: 'failed' }))).toBe('problems');
  expect(category(item(9, { state: 'unsupported', problem: "AVIF photos aren't supported yet." }))).toBe('problems');
  expect(category(item(10, { state: 'missing', taxonId: 'A6' }))).toBe('problems');
});

test('counts every filter, and filters by category', () => {
  const items = [item(1), item(2, { suggestedTaxonId: 'A5' }), item(3, { taxonId: 'A5' }), item(4, { taxonId: 'A5', outcome: 'filed' }),
    item(5, { skipped: true }), item(6, { state: 'failed' })];
  expect(filterCounts(items)).toEqual({ all: 6, unassigned: 1, suggested: 1, assigned: 1, skipped: 1, problems: 1, filed: 1, noPlace: 4 });
  expect(FILTERS.map((f) => f.label)).toEqual(['All', 'Unassigned', 'Suggested', 'Assigned', 'Skipped', 'Problems', 'Unknown place']);
  expect(applyFilter(items, 'all')).toBe(items);
  expect(applyFilter(items, 'assigned').map((i) => i.id)).toEqual([3]);
});

test('knows what filing would take and what is already in the library', () => {
  expect(fileable(item(1, { taxonId: 'A5' }))).toBe(true);
  expect(fileable(item(2, { taxonId: 'A5', outcome: 'failed' }))).toBe(true);
  expect(fileable(item(3, { taxonId: 'A5', state: 'pending' }))).toBe(false);
  expect(fileable(item(4, { taxonId: 'A5', skipped: true }))).toBe(false);
  expect(fileable(item(5, { taxonId: 'A5', outcome: 'filed' }))).toBe(false);
  expect(fileable(item(6))).toBe(false);
  expect(inLibrary(item(7, { libraryTaxonId: 'A5' }))).toBe(true);
  expect(inLibrary(item(8, { libraryTaxonId: 'A5', skipped: true }))).toBe(false);
  expect(inLibrary(item(9, { libraryTaxonId: 'A5', outcome: 'duplicate' }))).toBe(false);
});

test('counts read with separators', () => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  expect(photoCount(1)).toBe('1 photo');
  expect(photoCount(2140)).toBe('2,140 photos');
  vi.restoreAllMocks();
});

test('by folder: root files first under the import\'s name, then subfolders in natural order, each in bursts', () => {
  const items = [
    item(1, { folder: 'Trip 10', capturedAt: '2024-08-12T10:00:00Z' }),
    item(2, { folder: 'Trip 9', capturedAt: '2024-08-12T10:00:00Z' }),
    item(3, { folder: '', capturedAt: '2024-08-12T09:00:00Z' }),
    item(4, { folder: 'Trip 9', capturedAt: '2024-08-12T10:01:00Z' }),
    item(5, { folder: 'Trip 9', capturedAt: '2024-08-12T08:00:00Z' }),
  ];
  const sections = folderSections(items, 'Butterflies 2024');
  expect(sections.map((s) => [s.key, s.title])).toEqual([['f:', 'Butterflies 2024'], ['f:Trip 9', 'Trip 9'], ['f:Trip 10', 'Trip 10']]);
  expect(sections[1].items.map((i) => i.id)).toEqual([5, 2, 4]);
  expect(sections[1].groups.map((g) => [g.burst, g.items.map((i) => i.id)])).toEqual([[false, [5]], [true, [2, 4]]]);
});

test('by species: assigned photos under their species in name order, the rest last', () => {
  const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }], ['A7', { name: 'Tirumala limniace', common: null }],
    ['A6', { name: 'Danaus genutia', common: 'Common Tiger' }]]);
  const items = [item(1, { taxonId: 'A5' }), item(2, { taxonId: 'A7', folder: 'b' }), item(3, { taxonId: 'A6' }), item(4),
    item(5, { taxonId: 'A5', skipped: true }), item(6, { taxonId: 'A7', folder: 'a' })];
  const sections = speciesSections(items, taxa);
  expect(sections.map((s) => [s.title, s.items.map((i) => i.id)])).toEqual([
    ['Common Tiger', [3]], ['Plain Tiger', [1]], ['Tirumala limniace', [6, 2]], ['No species yet', [4, 5]],
  ]);
  expect(sections[0].taxonId).toBe('A6');
  expect(sections[0].groups).toHaveLength(1);
});

test('a section\'s suggestion names the species only when its suggested photos share one', () => {
  expect(sectionSuggestion([item(1, { suggestedTaxonId: 'A5' }), item(2, { suggestedTaxonId: 'A5' }), item(3, { suggestedTaxonId: 'A5', taxonId: 'A6' })]))
    .toEqual({ ids: [1, 2], taxonId: 'A5' });
  expect(sectionSuggestion([item(1, { suggestedTaxonId: 'A5' }), item(2, { suggestedTaxonId: 'A6' })])).toEqual({ ids: [1, 2], taxonId: null });
  expect(sectionSuggestion([item(1)])).toEqual({ ids: [], taxonId: null });
});

test('lays sections out as header rows and rows of tiles, with stable keys', () => {
  const items = [1, 2, 3, 4, 5].map((id) => item(id, { folder: 'Trip', capturedAt: `2024-08-12T1${id}:00:00Z` }))
    .concat([item(6, { folder: 'Trip', capturedAt: '2024-08-12T15:00:30Z' })]);
  const rows = layoutRows(folderSections(items, 'Root'), 2);
  expect(rows.map((r) => [r.kind, r.kind === 'tiles' ? r.items.map((i) => i.id) : null])).toEqual([
    ['section', null], ['tiles', [1, 2]], ['tiles', [3, 4]], ['burst', null], ['tiles', [5, 6]],
  ]);
  expect(rows[1].key).toBe('f:Trip\u0000l1\u00001');
  expect(rows[3].group.items.map((i) => i.id)).toEqual([5, 6]);
  expect(new Set(rows.map((r) => r.key)).size).toBe(rows.length);
});

test('measures rows and finds the ones in view among ten thousand photos', () => {
  const items = Array.from({ length: 10000 }, (_, k) => item(k + 1, { folder: `F${Math.floor(k / 1000)}`, capturedAt: new Date(k * 600000).toISOString() }));
  const rows = layoutRows(folderSections(items, 'Root'), 6);
  const { tops, total } = measureRows(rows, { section: 56, burst: 36, tiles: 160 });
  expect(rows.filter((r) => r.kind === 'section')).toHaveLength(10);
  expect(total).toBe(10 * 56 + rows.filter((r) => r.kind === 'tiles').length * 160);

  const { start, end } = visibleRange(tops, total, 100000, 100800, 2);
  expect(end - start).toBeLessThanOrEqual(10);
  expect(tops[start + 2]).toBeLessThanOrEqual(100000);
  expect(tops[end - 3]).toBeLessThan(100800);
  expect(visibleRange(tops, total, -500, 300, 1)).toEqual({ start: 0, end: 4 });
  expect(visibleRange([], 0, 0, 800)).toEqual({ start: 0, end: 0 });
});

test('a tile says what is known about its photo', () => {
  const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }], ['A6', { name: 'Danaus genutia', common: null }]]);
  const says = (extra) => tileStatus(item(812, { name: 'IMG_0412.JPG', ...extra }), taxa);
  expect(says({})).toEqual({ kind: 'plain', label: '', text: 'IMG_0412.JPG' });
  expect(says({ state: 'pending', v: null })).toEqual({ kind: 'waiting', label: 'Not read yet', text: 'IMG_0412.JPG, Not read yet' });
  expect(says({ suggestedTaxonId: 'A5' }).text).toBe('IMG_0412.JPG, Suggested: Plain Tiger');
  expect(says({ suggestedTaxonId: 'A5', taxonId: 'A6' })).toMatchObject({ kind: 'assigned', label: 'Danaus genutia' });
  expect(says({ taxonId: 'A6', skipped: true })).toMatchObject({ kind: 'skipped', label: 'Skipped' });
  expect(says({ taxonId: 'A5', outcome: 'filed' })).toMatchObject({ kind: 'filed', label: 'Filed: Plain Tiger' });
  expect(says({ taxonId: 'A5', outcome: 'copy_of', copyOf: 811 }).label).toBe('Copy of another photo here: Plain Tiger');
  expect(says({ taxonId: 'A5', outcome: 'failed', error: 'The photo was locked.' })).toMatchObject({ kind: 'problem', label: 'The photo was locked.' });
  expect(says({ state: 'unsupported', problem: "AVIF photos aren't supported yet.", v: null }).label).toBe("AVIF photos aren't supported yet.");
  expect(says({ state: 'missing', taxonId: 'A5' })).toMatchObject({ kind: 'problem', label: 'Not in the folder any more' });
  expect(says({ libraryTaxonId: 'A5', suggestedTaxonId: 'A5' }).text)
    .toBe('IMG_0412.JPG, Suggested: Plain Tiger, already in your atlas as Plain Tiger');
  expect(says({ copyOf: 811 }).text).toBe('IMG_0412.JPG, a copy of another photo here');
});

test('says where a stopped import got to, and that it won\'t carry on by itself', () => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  expect(stoppedLine({ rootName: 'Butterflies 2024', phase: 'filing', done: 1200, total: 2140 }))
    .toBe("Your import of Butterflies 2024 stopped when Butterfly Atlas closed (filing 1,200 of 2,140). It won't carry on by itself.");
  expect(stoppedLine({ rootName: 'Card', phase: 'reading', done: 0, total: 0 }))
    .toBe("Your import of Card stopped when Butterfly Atlas closed (while reading). It won't carry on by itself.");
  vi.restoreAllMocks();
});

test('a photo can be changed unless it is filed, missing or unreadable; a failed filing can', () => {
  expect(editable(item(1))).toBe(true);
  expect(editable(item(2, { state: 'pending', v: null }))).toBe(true);
  expect(editable(item(3, { taxonId: 'A5', outcome: 'failed' }))).toBe(true);
  expect(editable(item(4, { taxonId: 'A5', outcome: 'filed' }))).toBe(false);
  expect(editable(item(5, { taxonId: 'A5', outcome: 'duplicate' }))).toBe(false);
  expect(editable(item(6, { state: 'missing' }))).toBe(false);
  expect(editable(item(7, { state: 'unsupported', problem: "Videos can't be imported." }))).toBe(false);
});

test('a filed photo shows the server\'s note as it is', () => {
  const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }]]);
  expect(tileStatus(item(1, { taxonId: 'A5', outcome: 'also_elsewhere', error: 'Also filed under Striped Tiger (Danaus genutia).' }), taxa).label)
    .toBe('Also filed under Striped Tiger (Danaus genutia).');
  expect(tileStatus(item(2, { taxonId: 'A5', outcome: 'copy_of', error: 'Same photo as Day 2/IMG_1.JPG.' }), taxa).text)
    .toBe('IMG_2.JPG, Same photo as Day 2/IMG_1.JPG.');
  expect(tileStatus(item(3, { state: 'unsupported', problem: "Videos can't be imported.", v: null }), taxa).label).toBe("Videos can't be imported.");
});

test('row keys never collide, whatever the folder names hold', () => {
  // a folder named "a|b2" must not share a key with the burst b2 of folder "a"
  const items = [
    item(2, { folder: 'a', capturedAt: '2024-08-12T10:00:00Z' }), item(3, { folder: 'a', capturedAt: '2024-08-12T10:01:00Z' }),
    item(4, { folder: 'a|b2', capturedAt: '2024-08-12T12:00:00Z' }),
  ];
  const rows = layoutRows(folderSections(items, 'Root'), 4);
  expect(new Set(rows.map((r) => r.key)).size).toBe(rows.length);
});

test('a view past the end of the grid gets its last rows, not its first', () => {
  const tops = [0, 100, 200, 300, 400];
  expect(visibleRange(tops, 500, 600, 1400, 1)).toEqual({ start: 3, end: 5 });
  expect(visibleRange(tops, 500, 500, 900, 0)).toEqual({ start: 4, end: 5 });
});

test('the large view keeps the photo on screen at its place when it leaves the photos shown', () => {
  const order = [1, 3, 4];
  expect(keepPlace(order, [1, 2, 3, 4], 3)).toBe(order);
  expect(keepPlace(order, [1, 2, 3, 4], 2)).toEqual([1, 2, 3, 4]);
  expect(keepPlace(order, [], 9)).toEqual([1, 3, 4, 9]);
  expect(keepPlace(order, [], null)).toBe(order);
});

test('Unknown place: read photos still to be filed that have no place, whatever their category', () => {
  const pune = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  const items = [item(1, { place: null }), item(2, { place: pune }), item(3, { place: null, state: 'pending', v: null }),
    item(4, { place: null, taxonId: 'A5', skipped: true }), item(5, { place: null, taxonId: 'A5', outcome: 'filed' }),
    item(6, { place: null, state: 'unsupported' }), item(7, { place: null, taxonId: 'A5', outcome: 'failed' })];
  expect(filterCounts(items).noPlace).toBe(3);
  expect(applyFilter(items, 'noPlace').map((i) => i.id)).toEqual([1, 4, 7]);
});

test('a tile names its place after its label', () => {
  const pune = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }]]);
  expect(tileStatus(item(1, { taxonId: 'A5', place: pune, libraryTaxonId: 'A5' }), taxa).text)
    .toBe('IMG_1.JPG, Plain Tiger, Pune, already in your atlas as Plain Tiger');
  expect(tileStatus(item(2, { place: pune }), taxa).text).toBe('IMG_2.JPG, Pune');
  expect(tileStatus(item(3, { place: null }), taxa).text).toBe('IMG_3.JPG');
});

test('a place that became former (Phase 5c): its line says to choose its new place or file as it is, until the photo is filed', () => {
  const chittoor = { id: 'P300', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], source: 'user', former: true };
  const pune = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps', former: false };
  const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }]]);
  expect(placeLine(item(1, { place: chittoor }))).toBe('Chittoor (former district) — choose its new place, or file as it is.');
  // a place its GPS gave: filing looks its GPS position up again (contract §11)
  expect(placeLine(item(5, { place: { ...chittoor, source: 'gps' } })))
    .toBe('Chittoor (former district) — when it is filed, its GPS position is looked up again in the new place list.');
  expect(placeLine(item(2, { place: pune }))).toBe('Pune, Maharashtra, India');
  expect(placeLine(item(3, { place: null }))).toBe('');
  expect(placeLine(item(4, { place: chittoor, taxonId: 'A5', outcome: 'filed' }))).toBe('Chittoor (former district), Andhra Pradesh, India');
  expect(tileStatus(item(1, { place: chittoor, taxonId: 'A5' }), taxa).text)
    .toBe('IMG_1.JPG, Plain Tiger, Chittoor (former district) — choose its new place, or file as it is');
});

/* ---------- RAW + JPEG pairs (Phase 7a) ---------- */

const jpg = (id, pairId, extra = {}) => item(id, { name: `DSC_${id}.JPG`, fileType: 'jpeg', pairId, ...extra });
const nef = (id, pairId, extra = {}) => item(id, { name: `DSC_${id}.NEF`, fileType: 'raw', pairId, rawPreview: { width: 6000, height: 4000, orientation: 1 }, ...extra });

test('a pair is one shot: the JPEG stands for it, knowing its RAW; everything else stays as it is', () => {
  const list = [nef(1, 2), jpg(2, 1), jpg(3, null), nef(4, null)];
  const out = shots(list);
  expect(out.map((each) => each.id)).toEqual([2, 3, 4]);
  expect(out[0].pairOf).toBe(list[0]);
  expect(out[1]).toBe(list[2]);
  expect(out[2]).toBe(list[3]);
  // counts are per shot
  expect(filterCounts(out).all).toBe(3);
});

test('a half whose partner is not listed, or does not point back, shows on its own', () => {
  expect(shots([nef(1, 2)]).map((each) => each.id)).toEqual([1]);
  expect(shots([nef(1, 2), jpg(2, 5)]).map((each) => each.id)).toEqual([1, 2]);
});

test('with RAW only filed, the RAW stands for the shot, and the JPEG left out says why', () => {
  const left = "The JPEG of this pair was not filed: you chose RAW only.";
  const list = [nef(1, 2, { taxonId: 'A5', outcome: 'filed' }), jpg(2, 1, { taxonId: 'A5', outcome: 'pair_left', error: left })];
  const [shot] = shots(list);
  expect(shot.id).toBe(1);
  expect(shot.pairOf.id).toBe(2);
  expect(category(shot)).toBe('filed');
  expect(tileStatus(shot, new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }]])).text).toBe(`DSC_1.NEF, RAW + JPEG, Filed: Plain Tiger, ${left}`);
});

test('a half left out by the pair choice is done with: shown as the server words it, not a problem, not editable', () => {
  const left = item(1, { taxonId: 'A5', outcome: 'pair_left', error: 'The RAW of this pair was not filed: you chose JPEG only.' });
  expect(category(left)).toBe('filed');
  expect(editable(left)).toBe(false);
  expect(fileable(left)).toBe(false);
  expect(tileStatus(left, new Map()).label).toBe('The RAW of this pair was not filed: you chose JPEG only.');
});

test('the files filing takes: two for a pair to file, one for any other shot (review fix U7)', () => {
  const list = shots([
    nef(1, 2, { taxonId: 'A5' }), jpg(2, 1, { taxonId: 'A5' }),
    jpg(3, null, { taxonId: 'A5' }),
    nef(4, 5, { taxonId: 'A5', outcome: 'filed' }), jpg(5, 4, { taxonId: 'A5', outcome: 'filed' }),
    jpg(6, null),
  ]);
  expect(filesToFile(list)).toBe(3);
});

test('the file button counts photos, and files and shots when pairs are filed as Both (review fix U7)', () => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  expect(fileButton(2140, 2140, null)).toBe('File 2,140 assigned photos');
  expect(fileButton(1, 1, null)).toBe('File 1 assigned photo');
  expect(fileButton(11, 12, 'both')).toBe('File 12 photos (11 shots)');
  expect(fileButton(1, 2, 'both')).toBe('File 2 photos (1 shot)');
  expect(fileButton(1340, 2680, 'both')).toBe('File 2,680 photos (1,340 shots)');
  // JPEG only or RAW only: one file a shot
  expect(fileButton(11, 12, 'jpeg')).toBe('File 11 assigned photos');
  // no pair among them: as before
  expect(fileButton(11, 11, 'both')).toBe('File 11 assigned photos');
  vi.restoreAllMocks();
});

/* ---------- RAW + HEIC pairs (Phase 7b contract §1, §4; audit 14) ---------- */

const heic = (id, pairId, extra = {}) => item(id, { name: `IMG_${id}.HEIC`, fileType: 'heic', pairId, ...extra });

test('pair words follow the kinds the folder holds', () => {
  expect(pairName(['jpeg'])).toBe('RAW + JPEG');
  expect(pairName(undefined)).toBe('RAW + JPEG');
  expect(pairName(['heic'])).toBe('RAW + HEIC');
  expect(pairName(['jpeg', 'heic'])).toBe('RAW + JPEG or RAW + HEIC');
  expect(pairCount(120, ['heic'])).toBe('120 RAW + HEIC pairs');
  expect(pairCount(1, ['heic'])).toBe('1 RAW + HEIC pair');
  expect(pairCount(340, ['jpeg', 'heic'])).toBe('340 RAW + JPEG or RAW + HEIC pairs');
  expect(pairChoices(['heic']).map((c) => c.label)).toEqual(['Both', 'HEIC only', 'RAW only']);
  expect(pairChoices(['jpeg', 'heic']).map((c) => c.label)).toEqual(['Both', 'JPEG or HEIC only', 'RAW only']);
  expect(pairChoices(['jpeg']).map((c) => c.label)).toEqual(['Both', 'JPEG only', 'RAW only']);
  expect(pairChoiceLabel('jpeg', ['heic'])).toBe('HEIC only');
  expect(pairChoiceNeeded({ toFile: 120, kinds: ['heic'] })).toBe('Choose how to file the 120 RAW + HEIC pairs first.');
  expect(pairChoiceNeeded({ toFile: 340, kinds: ['jpeg', 'heic'] })).toBe('Choose how to file the 340 RAW + JPEG or RAW + HEIC pairs first.');
});

test('a RAW + HEIC pair is one shot, its tile named by its own halves', () => {
  const [shot] = shots([nef(1, 2), heic(2, 1)]);
  expect(shot.id).toBe(2);
  expect(pairBadge(shot)).toBe('RAW + HEIC');
  expect(tileStatus(shot, new Map()).text).toBe('IMG_2.HEIC, RAW + HEIC');
  expect(pairBadge(shots([nef(1, 2), jpg(2, 1)])[0])).toBe('RAW + JPEG');
  expect(pairBadge(item(5))).toBe('');
});

test('a RAW + HEIC pair filed as HEIC only or RAW only says so with HEIC words (audit 14)', () => {
  const heicOnly = shots([nef(1, 2, { taxonId: 'A5', outcome: 'pair_left' }), heic(2, 1, { taxonId: 'A5', outcome: 'filed' })])[0];
  expect(pairFiled(heicOnly)).toEqual({ badge: 'HEIC filed', line: 'RAW not filed' });
  const rawOnly = shots([nef(1, 2, { taxonId: 'A5', outcome: 'filed' }), heic(2, 1, { taxonId: 'A5', outcome: 'pair_left' })])[0];
  expect(pairFiled(rawOnly)).toEqual({ badge: 'RAW filed', line: 'HEIC not filed' });
  // Without the server's words, the reason names the pair's own kind.
  expect(tileStatus(rawOnly, new Map()).text).toBe('DSC_1.NEF, RAW + HEIC, Filed: A5, Not filed: left out by your RAW + HEIC choice');
});
