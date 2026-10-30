import { afterEach, expect, test, vi } from 'vitest';
import {
  ApiError, addSearchWord, createTag, deletePhotos, deleteTag, dismissTagNames, getTagNames, editTags, emptyDeleted, fetchHealth, getDeleted, getPhotos,
  getStatus, getTags, movePhotos, photoThumb, removeSearchWord, renameTag, restorePhotos, search, setCover, setPhotoTags,
  thumbUrl, uploadPhoto, PHOTO_ACCEPT, photoType, putImportPairs,
  acceptSuggestions, assignItems, discardImport, fileImport, finishImport, getFsRoots, getImport, getImportItems, getImportResult,
  getImports, importDisplayUrl, importPreviewUrl, listFolder, pauseImport, relocateImport, skipItems, startImport, tagItems,
  addPlace, deletePlace, getMyPlaces, getRecentPlaces, placeItems, placePhotos, renamePlace, searchPlaces, unplaceItems, unplacePhotos,
  filterCount, filterPhotos, filterPlaces, filterSpecies,
  cancelExport, checkExport, clearExports, deleteExport, getExport, getExports, pauseExport, resumeExport, startExport,
  addCustomSpecies, checkCustomName, deleteCustomSpecies, renameCustomSpecies,
  applyUpdate, declineCustomMatch, getCustomMatches, getSplit, getSplits, getUpdate, mergeCustomMatch, retryUpdate, sortSplit, updateLater,
  dismissPlaceUpdate, getPlaceUpdate, retrySetup, getSession, resetSessionLost, checkHeic, dismissRescanLeft, openWindow,
  cancelBackup, checkBackup, clearBackups, deleteBackup, getBackup, getBackups, pauseBackup, resumeBackup, setBackupPreference, startBackup,
  getAppearance, setAppearance,
  cancelChange, deleteOldCopy, dismissChange, dismissMovedNote, getLibraryChange, inspectChange, moveLibrary, switchLibrary, usePreviousLibrary,
} from './api.js';

afterEach(() => {
  vi.unstubAllGlobals();
  resetSessionLost();
});

const ok = (body) => vi.fn(async () => ({ ok: true, status: 200, json: async () => body }));

test('calls the relative health URL and returns parsed JSON', async () => {
  const fetchMock = ok({ status: 'ok', version: '1.2.3' });
  vi.stubGlobal('fetch', fetchMock);
  await expect(fetchHealth()).resolves.toEqual({ status: 'ok', version: '1.2.3' });
  expect(fetchMock).toHaveBeenCalledWith('./api/health', expect.objectContaining({ method: 'GET' }));
});

test('throws the server message and code when the response is not ok', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: false,
    status: 404,
    json: async () => ({ error: { code: 'not_found', message: 'No such taxon.' } }),
  })));
  const error = await fetchHealth().catch((e) => e);
  expect(error).toBeInstanceOf(ApiError);
  expect(error.code).toBe('not_found');
  expect(error.message).toBe('No such taxon.');
  expect(error.status).toBe(404);
});

test('mentions the status when the error body is not JSON', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, json: async () => { throw new SyntaxError('bad'); } })));
  await expect(fetchHealth()).rejects.toThrow(/503/);
});

test('says the service cannot be reached when fetch fails', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
  await expect(fetchHealth()).rejects.toMatchObject({
    code: 'offline',
    message: 'Butterfly Atlas could not reach its service. Close the app and open it again.',
  });
});

test('sends changes as JSON', async () => {
  const fetchMock = ok({ words: ['Kaner'] });
  vi.stubGlobal('fetch', fetchMock);
  await expect(addSearchWord('A5', 'Kaner')).resolves.toEqual(['Kaner']);
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe('./api/taxa/A5/search-words');
  expect(init.method).toBe('POST');
  expect(init.headers['Content-Type']).toBe('application/json');
  expect(init.body).toBe('{"word":"Kaner"}');
});

test('encodes ids and query values', async () => {
  const fetchMock = ok({ words: [] });
  vi.stubGlobal('fetch', fetchMock);
  await removeSearchWord('G:butterflies', 'a/b c');
  expect(fetchMock.mock.calls[0][0]).toBe('./api/taxa/G%3Abutterflies/search-words?word=a%2Fb%20c');
  expect(fetchMock.mock.calls[0][1].method).toBe('DELETE');
});

test('search sends the query and limit and returns the results', async () => {
  const fetchMock = ok({ query: 'plain ti', results: [{ id: 'A5' }] });
  vi.stubGlobal('fetch', fetchMock);
  await expect(search('plain ti')).resolves.toEqual([{ id: 'A5' }]);
  expect(fetchMock.mock.calls[0][0]).toBe('./api/search?q=plain%20ti&limit=20');
});

const photoFile = (name, type) => new File([new Uint8Array(4)], name, { type });

test('uploads a photo as its own bytes with its type, name and tags', async () => {
  const fetchMock = ok({ status: 'added', photo: { id: 12 }, otherTaxon: null, firstForSpecies: true, milestone: null });
  vi.stubGlobal('fetch', fetchMock);
  const file = photoFile('IMG 0001.JPG', 'image/jpeg');
  const progress = [];

  const result = await uploadPhoto('A5', file, { tags: ['larva', 'male'], onProgress: (p) => progress.push(p) });

  expect(result.status).toBe('added');
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe('./api/taxa/A5/photos?name=IMG%200001.JPG&tags=larva%2Cmale');
  expect(init.method).toBe('POST');
  expect(init.headers['Content-Type']).toBe('image/jpeg');
  expect(init.body).toBe(file);
  expect(progress).toEqual([0, 1]);
});

test('works out the type from the extension when the system gives none', async () => {
  const fetchMock = ok({ status: 'added' });
  vi.stubGlobal('fetch', fetchMock);

  await uploadPhoto('A5', photoFile('wing.TIF', ''));

  expect(fetchMock.mock.calls[0][0]).toBe('./api/taxa/A5/photos?name=wing.TIF');
  expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('image/tiff');
});

test('refuses formats the library cannot read without sending them', async () => {
  const fetchMock = ok({});
  vi.stubGlobal('fetch', fetchMock);

  await expect(uploadPhoto('A5', photoFile('notes.txt', 'text/plain'))).rejects.toMatchObject({
    message: "This file isn't a photo Butterfly Atlas can read.",
  });
  expect(fetchMock).not.toHaveBeenCalled();
});

test('AVIF and RAW formats the app does not read are refused here with their own words (review fix U8)', async () => {
  const fetchMock = ok({});
  vi.stubGlobal('fetch', fetchMock);

  await expect(uploadPhoto('A5', photoFile('wing.avif', 'image/avif'))).rejects.toMatchObject({ code: 'unsupported_photo', message: "AVIF photos aren't supported yet." });
  await expect(uploadPhoto('A5', photoFile('wing.AVIF', ''))).rejects.toMatchObject({ message: "AVIF photos aren't supported yet." });
  for (const ext of ['crw', 'mrw', 'x3f', 'erf', 'kdc', 'dcr', 'mos']) {
    await expect(uploadPhoto('A5', photoFile(`DSC_0042.${ext.toUpperCase()}`, ''))).rejects.toMatchObject({
      code: 'unsupported_photo', message: "This RAW format isn't supported yet.",
    });
  }
  expect(fetchMock).not.toHaveBeenCalled();
});

const SEQUENCE = "HEIC image sequences (several frames in one file) aren't supported. Save the frame you want as a photo and add that.";

test('HEIC photos (.heic .heif .hif, any case) are offered by the picker and sent as image/heic (Phase 7b)', async () => {
  for (const ext of ['heic', 'heif', 'hif']) {
    expect(PHOTO_ACCEPT.split(',')).toContain(`.${ext}`);
    expect(photoType(photoFile(`IMG_0042.${ext.toUpperCase()}`, ''))).toBe('image/heic');
  }
  expect(PHOTO_ACCEPT.split(',')).toEqual(expect.arrayContaining(['image/heic', 'image/heif']));
  expect(photoType(photoFile('IMG_0042.HEIF', 'image/heif'))).toBe('image/heic');
  expect(photoType(photoFile('photo', 'image/heif'))).toBe('image/heic');

  const fetchMock = ok({ status: 'added' });
  vi.stubGlobal('fetch', fetchMock);
  await uploadPhoto('A5', photoFile('IMG_0042.HEIC', 'image/heic'));
  await uploadPhoto('A5', photoFile('C0001.HIF', ''));
  expect(fetchMock.mock.calls.map(([url, init]) => [url, init.headers['Content-Type']])).toEqual([
    ['./api/taxa/A5/photos?name=IMG_0042.HEIC', 'image/heic'],
    ['./api/taxa/A5/photos?name=C0001.HIF', 'image/heic'],
  ]);
});

test('HEIC image sequences are refused here with the sequence words, by name or by type (Phase 7b audit 22)', async () => {
  const fetchMock = ok({});
  vi.stubGlobal('fetch', fetchMock);
  for (const file of [photoFile('burst.heics', ''), photoFile('burst.HEIFS', ''), photoFile('IMG_1.HEIC', 'image/heic-sequence'),
    photoFile('IMG_1.heif', 'image/heif-sequence')]) {
    await expect(uploadPhoto('A5', file)).rejects.toMatchObject({ code: 'unsupported_photo', message: SEQUENCE });
  }
  expect(fetchMock).not.toHaveBeenCalled();
});

test('RAW photos of every family are offered by the picker and sent as image/x-raw (Phase 7a)', async () => {
  const families = ['nef', 'nrw', 'dng', 'arw', 'srf', 'sr2', 'cr2', 'cr3', 'pef', 'srw', 'orf', 'rw2', 'raf', '3fr', 'iiq', 'rwl'];
  for (const ext of families) {
    expect(PHOTO_ACCEPT.split(',')).toContain(`.${ext}`);
    expect(photoType(photoFile(`DSC_0042.${ext.toUpperCase()}`, ''))).toBe('image/x-raw');
  }
  expect(PHOTO_ACCEPT.split(',')).toContain('image/x-raw');
  // A system that names the camera's own type still sends the agreed one.
  expect(photoType(photoFile('DSC_0042.NEF', 'image/x-nikon-nef'))).toBe('image/x-raw');

  const fetchMock = ok({ status: 'added' });
  vi.stubGlobal('fetch', fetchMock);
  await uploadPhoto('A5', photoFile('DSC_0042.CR3', 'image/x-canon-cr3'));
  expect(fetchMock.mock.calls[0][0]).toBe('./api/taxa/A5/photos?name=DSC_0042.CR3');
  expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('image/x-raw');
});

test('photo and tag changes use the agreed methods, paths and bodies', async () => {
  const fetchMock = vi.fn(async () => ({
    ok: true, status: 200,
    json: async () => ({ photos: [], tags: [], coverId: 12, deleted: 2, removed: 3, removedFromPhotos: 4 }),
  }));
  vi.stubGlobal('fetch', fetchMock);

  await getPhotos('A5');
  await setPhotoTags(12, ['larva']);
  await editTags([12, 13], ['male'], ['female']);
  await movePhotos([12], 'A6');
  await expect(setCover('A5', 12)).resolves.toBe(12);
  await expect(deletePhotos([12, 13])).resolves.toBe(2);
  await getDeleted();
  await restorePhotos([12]);
  await expect(emptyDeleted()).resolves.toBe(3);
  await emptyDeleted([12]);
  await getTags();
  await createTag('mud-puddling');
  await renameTag(10, 'puddling');
  await expect(deleteTag(10)).resolves.toBe(4);
  await getTagNames();
  await dismissTagNames();
  await getStatus();

  const calls = fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null]);
  expect(calls).toEqual([
    ['GET', 'taxa/A5/photos', null],
    ['PATCH', 'photos/12', '{"tags":["larva"]}'],
    ['POST', 'photos/tags', '{"ids":[12,13],"add":["male"],"remove":["female"]}'],
    ['POST', 'photos/move', '{"ids":[12],"taxonId":"A6"}'],
    ['PUT', 'taxa/A5/cover', '{"photoId":12}'],
    ['POST', 'photos/delete', '{"ids":[12,13]}'],
    ['GET', 'deleted', null],
    ['POST', 'deleted/restore', '{"ids":[12]}'],
    ['POST', 'deleted/empty', '{}'],
    ['POST', 'deleted/empty', '{"ids":[12]}'],
    ['GET', 'tags', null],
    ['POST', 'tags', '{"label":"mud-puddling"}'],
    ['PATCH', 'tags/10', '{"label":"puddling"}'],
    ['DELETE', 'tags/10', null],
    ['GET', 'tag-names', null],
    ['POST', 'tag-names/dismiss', '{}'],
    ['GET', 'status', null],
  ]);
});

test('thumbnail URLs carry a version so each one can be cached for good', () => {
  expect(thumbUrl(12, 'abc')).toBe('./api/photos/12/thumb?v=abc');
  expect(photoThumb({ id: 12, v: 1791700000000 })).toBe('./api/photos/12/thumb?v=1791700000000');
});

test('refuses a photo over 300 MB without sending it', async () => {
  const fetchMock = ok({});
  vi.stubGlobal('fetch', fetchMock);
  const big = photoFile('big.tif', 'image/tiff');
  Object.defineProperty(big, 'size', { value: 300 * 1024 * 1024 + 1 });

  await expect(uploadPhoto('A5', big)).rejects.toMatchObject({ code: 'too_large', message: 'This photo is larger than 300 MB.' });
  expect(fetchMock).not.toHaveBeenCalled();
});

test('folder browser and import calls use the contract\'s paths and bodies', async () => {
  const fetchMock = vi.fn(async (url, init) => ({
    ok: true,
    status: init.method === 'DELETE' ? 204 : 200,
    json: async () => ({ roots: [], imports: [], import: { id: 3 }, continued: true, result: { filed: 1 }, version: 9, items: [], taxa: {} }),
  }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(getFsRoots()).resolves.toEqual([]);
  await listFolder('/Users/you/Pictures/Butterflies 2024');
  await expect(getImports()).resolves.toEqual([]);
  await expect(startImport('C:\\Photos')).resolves.toMatchObject({ import: { id: 3 }, continued: true });
  await getImport(3);
  await getImportItems(3);
  await getImportItems(3, 1734);
  await assignItems(3, [812, 813], 'A5');
  await assignItems(3, [812], null);
  await tagItems(3, [812], ['male'], ['female']);
  await skipItems(3, [812], true);
  await acceptSuggestions(3, [812, 813]);
  await putImportPairs(3, 'jpeg');
  await fileImport(3, 7);
  await pauseImport(3);
  await relocateImport(3, '/Volumes/Photos SSD/Butterflies 2024');
  await expect(finishImport(3, true)).resolves.toMatchObject({ result: { filed: 1 } });
  await getImportResult(3);
  await expect(discardImport(3)).resolves.toBeNull();

  const calls = fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null]);
  expect(calls).toEqual([
    ['GET', 'fs/roots', null],
    ['GET', 'fs/list?path=%2FUsers%2Fyou%2FPictures%2FButterflies%202024', null],
    ['GET', 'imports', null],
    ['POST', 'imports', '{"path":"C:\\\\Photos"}'],
    ['GET', 'imports/3', null],
    ['GET', 'imports/3/items', null],
    ['GET', 'imports/3/items?since=1734', null],
    ['POST', 'imports/3/assign', '{"itemIds":[812,813],"taxonId":"A5"}'],
    ['POST', 'imports/3/assign', '{"itemIds":[812],"taxonId":null}'],
    ['POST', 'imports/3/tags', '{"itemIds":[812],"add":["male"],"remove":["female"]}'],
    ['POST', 'imports/3/skip', '{"itemIds":[812],"skipped":true}'],
    ['POST', 'imports/3/accept-suggestions', '{"itemIds":[812,813]}'],
    ['PUT', 'imports/3/pairs', '{"choice":"jpeg"}'],
    ['POST', 'imports/3/file', '{"revision":7}'],
    ['POST', 'imports/3/pause', '{}'],
    ['POST', 'imports/3/relocate', '{"path":"/Volumes/Photos SSD/Butterflies 2024"}'],
    ['POST', 'imports/3/finish', '{"skipRest":true}'],
    ['GET', 'imports/3/result', null],
    ['DELETE', 'imports/3', null],
  ]);
});

test('an import item\'s preview carries its version; there is none before it is read', () => {
  expect(importPreviewUrl(3, { id: 812, v: 'a1b2c3d4' })).toBe('./api/imports/3/items/812/preview?v=a1b2c3d4');
  expect(importPreviewUrl(3, { id: 812, v: null })).toBeNull();
  expect(importDisplayUrl(3, { id: 812, v: 'a1b2c3d4' })).toBe('./api/imports/3/items/812/display?v=a1b2c3d4');
});

test('a folder listing can be aborted when a newer one replaces it', async () => {
  const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) }));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  await listFolder('/Users/you', { signal: controller.signal });
  expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
});

test('the place routes send what the contract says and return its lists', async () => {
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false };
  const tamhini = { id: 'PU3', name: 'Tamhini', path: ['Tamhini', 'Maharashtra', 'India'], level: 'district', mine: true };
  const fetchMock = vi.fn(async (url, init) => ({
    ok: true,
    status: init.method === 'POST' && url.endsWith('/places') ? 201 : 200,
    json: async () => ({ places: [pune], photos: [{ id: 1 }], photosCleared: 4, ...(url.endsWith('PU3') || url.endsWith('/places') ? tamhini : {}), items: [], version: 9 }),
  }));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();

  expect([...await searchPlaces('pun & co', { signal: controller.signal })]).toEqual([pune]);
  await searchPlaces('mah', { level: 'state' });
  await expect(getRecentPlaces()).resolves.toEqual([pune]);
  await expect(getMyPlaces()).resolves.toEqual([pune]);
  await expect(addPlace('Tamhini', 'P20')).resolves.toMatchObject({ id: 'PU3', name: 'Tamhini' });
  await expect(renamePlace('PU3', 'Tamhini Ghat')).resolves.toMatchObject({ id: 'PU3' });
  await expect(deletePlace('PU3')).resolves.toBe(4);
  await expect(placePhotos([1, 2, 3], 'P414')).resolves.toEqual([{ id: 1 }]);
  await expect(unplacePhotos([1, 2, 3])).resolves.toEqual([{ id: 1 }]);
  await expect(placeItems(3, [812, 813], 'P414')).resolves.toMatchObject({ items: [], version: 9 });
  await expect(unplaceItems(3, [812])).resolves.toMatchObject({ items: [] });

  expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
  const calls = fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null]);
  expect(calls).toEqual([
    ['GET', 'places/search?q=pun%20%26%20co', null],
    ['GET', 'places/search?q=mah&level=state', null],
    ['GET', 'places/recent', null],
    ['GET', 'places/mine', null],
    ['POST', 'places', '{"name":"Tamhini","parentId":"P20"}'],
    ['PATCH', 'places/PU3', '{"name":"Tamhini Ghat"}'],
    ['DELETE', 'places/PU3', null],
    ['POST', 'photos/place', '{"ids":[1,2,3],"placeId":"P414"}'],
    ['POST', 'photos/place', '{"ids":[1,2,3],"remove":true}'],
    ['POST', 'imports/3/place', '{"itemIds":[812,813],"placeId":"P414"}'],
    ['POST', 'imports/3/place', '{"itemIds":[812],"remove":true}'],
  ]);
});

test('the filter routes send the filter as it is printed and return what each view needs', async () => {
  const count = { photos: 312, species: 48, photographedSpecies: 48, bytes: 4509715660, manualSpecies: false, filter: { query: 'taxa=A12' } };
  const answers = { count, species: { species: [{ id: 'A1042' }] }, photos: { photos: [{ id: 7 }], next: 'c2' }, places: { places: [{ id: 'P414' }] } };
  const fetchMock = vi.fn(async (url) => ({ ok: true, status: 200, json: async () => answers[url.match(/filter\/(\w+)/)[1]] }));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();

  await expect(filterCount('taxa=A12&places=P20', { signal: controller.signal })).resolves.toMatchObject({ photos: 312, bytes: 4509715660 });
  await expect(filterCount('')).resolves.toMatchObject({ manualSpecies: false });
  await expect(filterSpecies('taxa=A12')).resolves.toEqual([{ id: 'A1042' }]);
  await expect(filterPhotos('taxa=A12')).resolves.toEqual({ photos: [{ id: 7 }], next: 'c2' });
  await expect(filterPhotos('', { after: 'c2/x+1', limit: 50 })).resolves.toEqual({ photos: [{ id: 7 }], next: 'c2' });
  await expect(filterPlaces('places=P20,unknown')).resolves.toEqual([{ id: 'P414' }]);

  expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', '')])).toEqual([
    ['GET', 'filter/count?taxa=A12&places=P20'],
    ['GET', 'filter/count'],
    ['GET', 'filter/species?taxa=A12'],
    ['GET', 'filter/photos?taxa=A12&limit=200'],
    ['GET', 'filter/photos?after=c2%2Fx%2B1&limit=50'],
    ['GET', 'filter/places?places=P20,unknown'],
  ]);
});

test('the export routes: check with the filter, start, list, one with its problems, pause, resume, cancel, delete and clear', async () => {
  const run = { id: 4, state: 'running' };
  const fetchMock = vi.fn(async (url, init) => {
    if (init.method === 'DELETE' && url.endsWith('/4')) return { ok: true, status: 204, json: async () => { throw new Error('no body'); } };
    const body = url.includes('check') ? { photos: 312, problem: null }
      : init.method === 'DELETE' ? { removed: 3 }
        : url.endsWith('exports') && init.method === 'GET' ? { current: run, past: [] }
          : url.endsWith('/4') ? { ...run, problems: [], moreProblems: 0 } : run;
    return { ok: true, status: url.endsWith('exports') && init.method === 'POST' ? 201 : 200, json: async () => body };
  });
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  const start = { filter: 'taxa=A12&places=P388', what: 'both', path: '/Volumes/Backup', layout: 'species', stripLocation: true, placeDepth: 'state' };

  await expect(checkExport('taxa=A12', 'photos', '/Volumes/My Backup', { signal: controller.signal })).resolves.toEqual({ photos: 312, problem: null });
  await expect(checkExport('', 'csv', 'C:\\Users\\you')).resolves.toMatchObject({ problem: null });
  await checkExport('types=raw', 'photos', '/Volumes/Backup', { rawWithLocation: true });
  await expect(startExport(start)).resolves.toEqual(run);
  await expect(getExports()).resolves.toEqual({ current: run, past: [] });
  await expect(getExport(4)).resolves.toEqual({ ...run, problems: [], moreProblems: 0 });
  await expect(pauseExport(4)).resolves.toEqual(run);
  await expect(resumeExport(4)).resolves.toEqual(run);
  await expect(cancelExport(4, true)).resolves.toEqual(run);
  await expect(cancelExport(4, false)).resolves.toEqual(run);
  await expect(deleteExport(4)).resolves.toBeNull();
  await expect(clearExports()).resolves.toBe(3);

  expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body])).toEqual([
    ['GET', 'exports/check?taxa=A12&what=photos&path=%2FVolumes%2FMy%20Backup', undefined],
    ['GET', 'exports/check?what=csv&path=C%3A%5CUsers%5Cyou', undefined],
    ['GET', 'exports/check?types=raw&what=photos&path=%2FVolumes%2FBackup&rawWithLocation=true', undefined],
    ['POST', 'exports', JSON.stringify(start)],
    ['GET', 'exports', undefined],
    ['GET', 'exports/4', undefined],
    ['POST', 'exports/4/pause', '{}'],
    ['POST', 'exports/4/resume', '{}'],
    ['POST', 'exports/4/cancel', '{"remove":true}'],
    ['POST', 'exports/4/cancel', '{"remove":false}'],
    ['DELETE', 'exports/4', undefined],
    ['DELETE', 'exports', undefined],
  ]);
});

test('the custom species calls go to the 5a routes', async () => {
  const sankara = { id: 'U3', rank: 'species', name: 'Neptis sankara', common: null, family: 'Nymphalidae', source: 'manual', familyId: 'A1' };
  const fetchMock = vi.fn(async (url, init) => ({
    ok: true,
    status: init.method === 'POST' ? 201 : init.method === 'DELETE' ? 204 : 200,
    json: async () => (url.includes('check') ? { ok: true, name: 'Neptis sankara' } : sankara),
  }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(addCustomSpecies('A1', 'Neptis sankara', null)).resolves.toEqual(sankara);
  await expect(renameCustomSpecies('U3', 'Neptis soma', 'Broad-banded Sailer')).resolves.toEqual(sankara);
  await expect(deleteCustomSpecies('U3')).resolves.toBeNull();
  await expect(checkCustomName('neptis  sankara')).resolves.toEqual({ ok: true, name: 'Neptis sankara' });
  await checkCustomName('Neptis soma', { id: 'U3' });

  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null])).toEqual([
    ['POST', 'custom-species', '{"familyId":"A1","name":"Neptis sankara","common":null}'],
    ['PATCH', 'custom-species/U3', '{"name":"Neptis soma","common":"Broad-banded Sailer"}'],
    ['DELETE', 'custom-species/U3', null],
    ['GET', 'custom-species/check?name=neptis%20%20sankara', null],
    ['GET', 'custom-species/check?name=Neptis%20soma&id=U3', null],
  ]);
});

test('a name check can be aborted when newer typing replaces it', async () => {
  const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ ok: true, name: 'Neptis sankara' }) }));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  await checkCustomName('Neptis sankara', { signal: controller.signal });
  expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
});

test('a refusal naming a species carries it, so the dialog can offer to open it', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: false,
    status: 409,
    json: async () => ({ error: { code: 'in_list', message: 'Neptis hylas is already in the list as Common Sailer.',
      taxon: { id: 'A88', name: 'Neptis hylas', common: 'Common Sailer' } } }),
  })));
  const error = await addCustomSpecies('A1', 'Neptis hylas', null).catch((e) => e);
  expect(error).toBeInstanceOf(ApiError);
  expect(error).toMatchObject({ code: 'in_list', status: 409, message: 'Neptis hylas is already in the list as Common Sailer.' });
  expect(error.taxon).toEqual({ id: 'A88', name: 'Neptis hylas', common: 'Common Sailer' });

  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 409, json: async () => ({ error: { code: 'has_photos', message: 'Move its 4 photos first.' } }) })));
  expect((await deleteCustomSpecies('U3').catch((e) => e)).taxon).toBeNull();
});

test('the taxonomy update, split and match calls go to the 5b routes', async () => {
  const update = { state: 'running', kind: 'bundle', later: false };
  const split = { taxon: { id: 'A5' }, targets: [], left: 2, total: 4, photos: [] };
  const brief = { id: 'A5', name: 'Danaus chrysippus', photos: 4, targets: [] };
  const match = { custom: { id: 'U3', name: 'Neptis sankara' }, official: { id: 'A90', name: 'Neptis sankara' }, offeredVersion: '2026-12-01' };
  const fetchMock = vi.fn(async (url, init) => {
    if (url.endsWith('later') || url.endsWith('decline')) return { ok: true, status: 204, json: async () => { throw new Error('no body'); } };
    const body = url.endsWith('splits') ? { splits: [brief] }
      : url.includes('splits/') ? split
        : url.endsWith('custom-matches') ? { matches: [match] } : update;
    return { ok: true, status: init.method === 'POST' && !url.includes('sort') ? 202 : 200, json: async () => body };
  });
  vi.stubGlobal('fetch', fetchMock);

  await expect(getUpdate()).resolves.toEqual(update);
  await expect(applyUpdate()).resolves.toEqual(update);
  await expect(retryUpdate()).resolves.toEqual(update);
  await expect(updateLater()).resolves.toBeNull();
  await expect(getSplits()).resolves.toEqual([brief]);
  await expect(getSplit('A5')).resolves.toEqual(split);
  await expect(sortSplit('A5', [1, 2], 'A90')).resolves.toEqual(split);
  await expect(getCustomMatches()).resolves.toEqual([match]);
  await expect(mergeCustomMatch('U3', 'A90')).resolves.toEqual(update);
  await expect(declineCustomMatch('U3', 'A90')).resolves.toBeNull();

  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null])).toEqual([
    ['GET', 'update', null],
    ['POST', 'update/apply', '{}'],
    ['POST', 'update/retry', '{}'],
    ['POST', 'update/later', '{}'],
    ['GET', 'splits', null],
    ['GET', 'splits/A5', null],
    ['POST', 'splits/A5/sort', '{"photoIds":[1,2],"target":"A90"}'],
    ['GET', 'custom-matches', null],
    ['POST', 'custom-matches/U3/merge', '{"officialId":"A90"}'],
    ['POST', 'custom-matches/U3/decline', '{"officialId":"A90"}'],
  ]);
});

test('a place search carries the former places that match beside the places (contract §10), [] when the answer has none', async () => {
  const chittoor = { id: 'P300', name: 'Chittoor (former district)', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], level: 'district', former: true };
  const tirupati = { id: 'P301', name: 'Tirupati', path: ['Tirupati', 'Andhra Pradesh', 'India'], level: 'district', mine: false, former: false };
  let body = { places: [tirupati], formerMatches: [{ place: chittoor, now: [tirupati] }] };
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => body })));
  const found = await searchPlaces('chit');
  expect([...found]).toEqual([tirupati]);
  expect(found.formerMatches).toEqual([{ place: chittoor, now: [tirupati] }]);
  body = { places: [tirupati] };
  expect((await searchPlaces('tiru')).formerMatches).toEqual([]);
});

test('the place update calls go to the 5c routes; no note (204) reads as null', async () => {
  const note = { dismissed: false, versions: ['2026-10-20'], counts: { merged: 1 }, items: [], mine: [], former: { photos: 0, species: [] } };
  const setup = { configured: true, suggestedFolder: '/Users/me/Butterfly_Atlas', problem: null, retry: false };
  const noBody = { ok: true, status: 204, json: async () => { throw new Error('no body'); } };
  let empty = false;
  const fetchMock = vi.fn(async (url) => {
    if (url.endsWith('dismiss') || (empty && url.endsWith('place-update'))) return noBody;
    return { ok: true, status: 200, json: async () => (url.endsWith('retry') ? setup : note) };
  });
  vi.stubGlobal('fetch', fetchMock);

  const controller = new AbortController();
  await expect(getPlaceUpdate({ signal: controller.signal })).resolves.toEqual(note);
  expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
  await expect(dismissPlaceUpdate()).resolves.toBeNull();
  await expect(retrySetup()).resolves.toEqual(setup);
  empty = true;
  await expect(getPlaceUpdate()).resolves.toBeNull();

  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null])).toEqual([
    ['GET', 'place-update', null],
    ['POST', 'place-update/dismiss', '{}'],
    ['POST', 'setup/retry', '{}'],
    ['GET', 'place-update', null],
  ]);
});

test('a setup retry refused (409 not_failed) carries the server\'s words', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: false, status: 409, json: async () => ({ error: { code: 'not_failed', message: 'Your library is already open.' } }),
  })));
  const error = await retrySetup().catch((e) => e);
  expect(error).toBeInstanceOf(ApiError);
  expect(error).toMatchObject({ code: 'not_failed', status: 409, message: 'Your library is already open.' });
});

test('a refusal while the taxonomy is updating carries the server\'s words', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: false, status: 409, json: async () => ({ error: { code: 'taxonomy_updating', message: 'Wait for the taxonomy update to finish.' } }),
  })));
  const error = await sortSplit('A5', [1], 'A90').catch((e) => e);
  expect(error).toBeInstanceOf(ApiError);
  expect(error).toMatchObject({ code: 'taxonomy_updating', status: 409, message: 'Wait for the taxonomy update to finish.' });
});

test('a full or missing library drive (507, 503) carries the server\'s words, not a generic message', async () => {
  for (const [status, code, message] of [
    [507, 'drive_full', 'The drive with your library is full. Free some space, then try again.'],
    [503, 'drive_gone', "The drive with your library can't be found. Reconnect it, then try again."],
  ]) {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status, json: async () => ({ error: { code, message } }) })));
    const error = await getUpdate().catch((e) => e);
    expect(error).toMatchObject({ code, message, status });
  }
});

test('a request refused for the library\'s drive tells the page, so the status is read at once', async () => {
  const heard = vi.fn();
  window.addEventListener('atlas:library-problem', heard);
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 507, json: async () => ({ error: { code: 'drive_full', message: 'Full.' } }) })));
  await getUpdate().catch(() => {});
  expect(heard).toHaveBeenCalledTimes(1);
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 409, json: async () => ({ error: { code: 'taxonomy_updating', message: 'Wait.' } }) })));
  await getUpdate().catch(() => {});
  expect(heard).toHaveBeenCalledTimes(1);
  window.removeEventListener('atlas:library-problem', heard);
});

const NO_SESSION_WORDS = 'To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.';
const NO_SESSION = { error: { code: 'no_session', message: NO_SESSION_WORDS } };

test('an answer 401 no_session tells the page once and stops every later request without sending it', async () => {
  const heard = vi.fn();
  window.addEventListener('atlas:no-session', heard);
  const fetchMock = vi.fn(async () => ({ ok: false, status: 401, json: async () => NO_SESSION }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(getStatus()).rejects.toMatchObject({ code: 'no_session', status: 401, message: NO_SESSION_WORDS });
  expect(heard).toHaveBeenCalledTimes(1);

  // the later refusals, never sent, say the same (contract §1)
  await expect(getStatus()).rejects.toMatchObject({ code: 'no_session', status: 401, message: NO_SESSION_WORDS });
  await expect(getTags()).rejects.toMatchObject({ code: 'no_session' });
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(heard).toHaveBeenCalledTimes(1);
  window.removeEventListener('atlas:no-session', heard);
});

test('a 401 with another code, or a no_session code on another status, is an ordinary refusal', async () => {
  const heard = vi.fn();
  window.addEventListener('atlas:no-session', heard);
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 401, json: async () => ({ error: { code: 'other', message: 'No.' } }) })));
  await expect(getStatus()).rejects.toMatchObject({ code: 'other' });
  const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) }));
  vi.stubGlobal('fetch', fetchMock);
  await getStatus();
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(heard).not.toHaveBeenCalled();
  window.removeEventListener('atlas:no-session', heard);
});

test('reads the session: GET ./api/session', async () => {
  const fetchMock = ok({ mode: 'tab', version: '0.1.0', dataFolder: '/d', configFolder: '/c', logFolder: '/c/logs', lockWarning: false });
  vi.stubGlobal('fetch', fetchMock);
  await expect(getSession()).resolves.toMatchObject({ mode: 'tab', version: '0.1.0' });
  expect(fetchMock).toHaveBeenCalledWith('./api/session', expect.objectContaining({ method: 'GET' }));
});

test('New window: POST ./api/window with {}, 204 answers null; window_failed carries its message', async () => {
  const fetchMock = vi.fn(async () => ({ ok: true, status: 204, json: async () => { throw new Error('no body'); } }));
  vi.stubGlobal('fetch', fetchMock);
  await expect(openWindow()).resolves.toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('./api/window', expect.objectContaining({ method: 'POST', body: '{}' }));
  const message = "Butterfly Atlas couldn't open another window. Open it from its icon instead.";
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, json: async () => ({ error: { code: 'window_failed', message } }) })));
  await expect(openWindow()).rejects.toMatchObject({ code: 'window_failed', status: 500, message });
});

test('the backup routes: list, preference, check, start, one with its problems, pause, resume, cancel, delete and clear', async () => {
  const run = { id: 2, state: 'running' };
  const fetchMock = vi.fn(async (url, init) => {
    if (init.method === 'DELETE' && url.endsWith('/2')) return { ok: true, status: 204, json: async () => { throw new Error('no body'); } };
    const body = url.includes('check') ? { photos: 0, problem: null }
      : init.method === 'DELETE' ? { removed: 3 }
        : url.endsWith('backup') && init.method === 'GET' ? { asked: true, preference: 'remind', current: run, past: [] }
          : url.endsWith('preference') ? { asked: true, preference: 'none' }
            : url.endsWith('/2') ? { ...run, problems: [], moreProblems: 0 } : run;
    return { ok: true, status: url.endsWith('backup') && init.method === 'POST' ? 201 : 200, json: async () => body };
  });
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();

  await expect(getBackups()).resolves.toMatchObject({ preference: 'remind', current: run });
  await expect(setBackupPreference('none')).resolves.toMatchObject({ preference: 'none' });
  await expect(checkBackup('everything', '/Volumes/My Backup', { signal: controller.signal })).resolves.toEqual({ photos: 0, problem: null });
  await expect(startBackup({ what: 'database', path: '/Volumes/B' })).resolves.toEqual(run);
  await expect(getBackup(2)).resolves.toEqual({ ...run, problems: [], moreProblems: 0 });
  await expect(pauseBackup(2)).resolves.toEqual(run);
  await expect(resumeBackup(2)).resolves.toEqual(run);
  await expect(cancelBackup(2, true)).resolves.toEqual(run);
  await expect(cancelBackup(2, false)).resolves.toEqual(run);
  await expect(deleteBackup(2)).resolves.toBeNull();
  await expect(clearBackups()).resolves.toBe(3);

  expect(fetchMock.mock.calls[2][1].signal).toBe(controller.signal);
  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body])).toEqual([
    ['GET', 'backup', undefined],
    ['PUT', 'backup/preference', '{"preference":"none"}'],
    ['GET', 'backup/check?what=everything&path=%2FVolumes%2FMy%20Backup', undefined],
    ['POST', 'backup', '{"what":"database","path":"/Volumes/B"}'],
    ['GET', 'backup/2', undefined],
    ['POST', 'backup/2/pause', '{}'],
    ['POST', 'backup/2/resume', '{}'],
    ['POST', 'backup/2/cancel', '{"remove":true}'],
    ['POST', 'backup/2/cancel', '{"remove":false}'],
    ['DELETE', 'backup/2', undefined],
    ['DELETE', 'backup', undefined],
  ]);
});

test('Check again asks for a fresh HEIC decoder check, and the rescan note is dismissed (Phase 7b)', async () => {
  const heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  const fetchMock = vi.fn(async (url) => (url.endsWith('dismiss')
    ? { ok: true, status: 204, json: async () => { throw new Error('no body'); } }
    : { ok: true, status: 200, json: async () => heic }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(checkHeic()).resolves.toEqual(heic);
  await expect(dismissRescanLeft()).resolves.toBeNull();

  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null])).toEqual([
    ['POST', 'heic/check', '{}'],
    ['POST', 'rescan-left/dismiss', '{}'],
  ]);
});

test('the appearance is read and saved (Meadow contract §2–§3)', async () => {
  const fetchMock = vi.fn(async (url, init) => ({ ok: true, status: 200, json: async () => ({ theme: init.method === 'PUT' ? 'dark' : 'device' }) }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(getAppearance()).resolves.toEqual({ theme: 'device' });
  await expect(setAppearance('dark')).resolves.toEqual({ theme: 'dark' });

  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body])).toEqual([
    ['GET', 'appearance', undefined],
    ['PUT', 'appearance', '{"theme":"dark"}'],
  ]);
});

test("an appearance that cannot be saved fails with the server's reason", async () => {
  const message = "Couldn't save the appearance: Permission denied";
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, json: async () => ({ error: { code: 'save_failed', message } }) })));
  await expect(setAppearance('light')).rejects.toMatchObject({ code: 'save_failed', status: 500, message });
});

// Change the library folder (contract §3): every route, its method, address and body.
test('the library folder\'s routes', async () => {
  const fetchMock = vi.fn(async (url, init) => (init.method === 'POST' && /dismiss$/.test(url)
    ? { ok: true, status: 204, json: async () => null }
    : { ok: true, status: /move|switch/.test(url) ? 202 : 200, json: async () => ({ answer: url }) }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(getLibraryChange()).resolves.toEqual({ answer: './api/library/change' });
  await inspectChange('/Volumes/B 2/Atlas', 'move');
  await inspectChange('/Volumes/B', 'switch');
  await moveLibrary('/Volumes/B');
  await switchLibrary('/Volumes/C');
  await cancelChange();
  await expect(dismissChange()).resolves.toBeNull();
  await deleteOldCopy();
  await expect(dismissMovedNote()).resolves.toBeNull();
  await usePreviousLibrary();

  expect(fetchMock.mock.calls.map(([url, init]) => [init.method, url.replace('./api/', ''), init.body ?? null])).toEqual([
    ['GET', 'library/change', null],
    ['GET', 'library/change/inspect?path=%2FVolumes%2FB%202%2FAtlas&for=move', null],
    ['GET', 'library/change/inspect?path=%2FVolumes%2FB&for=switch', null],
    ['POST', 'library/change/move', '{"path":"/Volumes/B"}'],
    ['POST', 'library/change/switch', '{"path":"/Volumes/C"}'],
    ['POST', 'library/change/cancel', '{}'],
    ['POST', 'library/change/dismiss', '{}'],
    ['DELETE', 'library/old-copy', null],
    ['POST', 'library/moved-note/dismiss', '{}'],
    ['POST', 'setup/previous', '{}'],
  ]);
});

test('a request refused because the library folder is changing tells the page, so the change screen shows at once', async () => {
  const heard = vi.fn();
  window.addEventListener('atlas:library-problem', heard);
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: false, status: 409, json: async () => ({ error: { code: 'library_changing', message: 'Butterfly Atlas is changing your library folder.' } }),
  })));
  await expect(getUpdate()).rejects.toMatchObject({ code: 'library_changing', status: 409 });
  expect(heard).toHaveBeenCalledTimes(1);
  expect(heard.mock.calls[0][0].detail.code).toBe('library_changing');
  window.removeEventListener('atlas:library-problem', heard);
});
