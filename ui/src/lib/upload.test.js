import { afterEach, expect, test, vi } from 'vitest';
import { allAdded, summarise, summaryLines, unknownPlaceLine, uploadAll } from './upload.js';
import { uploadPhoto } from './api.js';

afterEach(() => vi.unstubAllGlobals());

const jpeg = (name) => new File([new Uint8Array(3)], name, { type: 'image/jpeg' });
const added = (id, over = {}) => ({ status: 'added', photo: { id }, otherTaxon: null, firstForSpecies: false, milestone: null, ...over });
const plainTiger = { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger' };

function deferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}

test('sends at most two photos at a time and collects every answer in order', async () => {
  const waits = [deferred(), deferred(), deferred()];
  let inFlight = 0;
  let most = 0;
  const send = vi.fn(async (file) => {
    inFlight += 1;
    most = Math.max(most, inFlight);
    const answer = await waits[Number(file.name[0])].promise;
    inFlight -= 1;
    return answer;
  });

  const queue = uploadAll([jpeg('0.jpg'), jpeg('1.jpg'), jpeg('2.jpg')], send);
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  waits[1].resolve(added(11));
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(3));
  waits[0].resolve(added(10));
  waits[2].resolve(added(12));
  const items = await queue.done;

  expect(most).toBe(2);
  expect(items.map((item) => [item.file.name, item.state, item.result.photo.id])).toEqual([
    ['0.jpg', 'added', 10], ['1.jpg', 'added', 11], ['2.jpg', 'added', 12],
  ]);
});

test('a refused photo does not stop the others (through the real client and a stubbed fetch)', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url) => (url.includes('bad.jpg')
    ? { ok: false, status: 415, json: async () => ({ error: { code: 'unsupported_photo', message: "This file isn't a photo Butterfly Atlas can read." } }) }
    : { ok: true, status: 200, json: async () => added(7) })));
  const changes = [];

  const queue = uploadAll([jpeg('bad.jpg'), jpeg('good.jpg'), new File(['x'], 'IMG_2.HEICS')],
    (file, signal) => uploadPhoto('A5', file, { signal }), { onChange: (items) => changes.push(items.map((i) => i.state)) });
  const items = await queue.done;

  expect(items.map((item) => [item.state, item.message])).toEqual([
    ['refused', "This file isn't a photo Butterfly Atlas can read."],
    ['added', ''],
    ['refused', "HEIC image sequences (several frames in one file) aren't supported. Save the frame you want as a photo and add that."],
  ]);
  expect(changes[0]).toEqual(['sending', 'waiting', 'waiting']);
  expect(fetch).toHaveBeenCalledTimes(2);
});

test('cancel stops the photos still waiting and aborts the ones on their way', async () => {
  const signals = [];
  const send = vi.fn((file, signal) => new Promise((resolve, reject) => {
    signals.push(signal);
    signal.addEventListener('abort', () => reject(new DOMException('stopped', 'AbortError')));
  }));

  const queue = uploadAll([jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg')], send);
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  queue.cancel();
  const items = await queue.done;

  expect(signals.every((signal) => signal.aborted)).toBe(true);
  expect(items.map((item) => item.state)).toEqual(['cancelled', 'cancelled', 'cancelled']);
  expect(send).toHaveBeenCalledTimes(2);
});

test('summarises a batch in plain words', () => {
  const striped = { id: 'A6', name: 'Danaus genutia', common: 'Striped Tiger' };
  const items = [
    { file: jpeg('a.jpg'), state: 'added', result: added(1, { firstForSpecies: true, milestone: 10, speciesCount: 10 }) },
    { file: jpeg('b.jpg'), state: 'addedElsewhereToo', result: added(2, { status: 'addedElsewhereToo', otherTaxon: striped }) },
    { file: jpeg('c.jpg'), state: 'addedElsewhereToo', result: added(3, { status: 'addedElsewhereToo', otherTaxon: striped }) },
    { file: jpeg('d.jpg'), state: 'skipped', result: { status: 'skipped', photo: null } },
    { file: jpeg('e.jpg'), state: 'skipped', result: { status: 'skipped', photo: null } },
    { file: jpeg('f.jpg'), state: 'refused', result: null, message: 'Too big.' },
    { file: jpeg('g.jpg'), state: 'cancelled', result: null },
  ];

  const summary = summarise(items);

  expect(summary).toMatchObject({ total: 7, added: 3, addedIds: [1, 2, 3], skipped: 2, cancelled: 1, firstForSpecies: true, milestone: 10, speciesCount: 10 });
  expect(summary.refused).toEqual([{ name: 'f.jpg', message: 'Too big.' }]);
  expect(allAdded(summary)).toBe(false);
  expect(summaryLines(summary, plainTiger)).toEqual([
    '3 photos added.',
    '2 photos were already in Plain Tiger and were skipped.',
    '2 photos are also filed under Striped Tiger.',
    '1 photo was not added because you stopped.',
  ]);
});

test('uses the singular for one photo and says when none were added', () => {
  const one = summarise([{ file: jpeg('a.jpg'), state: 'added', result: added(1) }]);
  expect(allAdded(one)).toBe(true);
  expect(summaryLines(one, plainTiger)).toEqual(['1 photo added.']);

  const none = summarise([{ file: jpeg('a.jpg'), state: 'skipped', result: { status: 'skipped', photo: null } }]);
  expect(summaryLines(none, { name: 'Danaus chrysippus', common: null })).toEqual([
    'No photos were added.',
    '1 photo was already in Danaus chrysippus and was skipped.',
  ]);
});

test('a refusal that arrives after Stop keeps its reason; an aborted request is cancelled', async () => {
  let queue;
  const send = vi.fn((file, signal) => (file.name === 'bad.jpg'
    ? Promise.resolve().then(() => { queue.cancel(); throw new Error('Too big.'); })
    : new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('stopped', 'AbortError'))))));

  queue = uploadAll([jpeg('bad.jpg'), jpeg('slow.jpg')], send);
  const items = await queue.done;

  expect(items.map((i) => [i.state, i.message])).toEqual([['refused', 'Too big.'], ['cancelled', '']]);
});

test('an unexpected reply is a refusal', async () => {
  const queue = uploadAll([jpeg('a.jpg'), jpeg('b.jpg')], vi.fn()
    .mockResolvedValueOnce(null).mockResolvedValueOnce({ status: 'weird' }));
  const items = await queue.done;

  expect(items.map((i) => [i.state, i.message])).toEqual(Array(2).fill(['refused', 'Butterfly Atlas could not add this photo.']));
});

test('counts the added photos that have an unknown place (place null), and says so', () => {
  const pune = { id: 'P414', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  const at = (id, place) => ({ file: jpeg(`${id}.jpg`), state: 'added', result: added(id, { photo: { id, place } }) });
  const summary = summarise([at(1, null), at(2, pune), at(3, null), at(4, null), { file: jpeg('s.jpg'), state: 'skipped', result: { status: 'skipped', photo: null } }]);

  expect(summary.unknownPlaceIds).toEqual([1, 3, 4]);
  expect(unknownPlaceLine(summary)).toBe('3 have an unknown place.');
  expect(unknownPlaceLine(summarise([at(1, null), at(2, pune)]))).toBe('1 has an unknown place.');
  expect(unknownPlaceLine(summarise([at(1, null)]))).toBe('It has an unknown place.');
  expect(unknownPlaceLine(summarise([at(2, pune)]))).toBe('');
  expect(summarise([{ file: jpeg('a.jpg'), state: 'added', result: added(1) }]).unknownPlaceIds).toEqual([]);
});

test('a refusal while the taxonomy updates stops the batch: the photos still waiting are refused with it, not sent', async () => {
  const wait = Object.assign(new Error('Wait for the taxonomy update to finish.'), { code: 'taxonomy_updating', status: 409 });
  const send = vi.fn().mockResolvedValueOnce(added(1)).mockRejectedValueOnce(wait).mockResolvedValue(added(9));
  const items = await uploadAll([jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg'), jpeg('d.jpg')], send, { concurrency: 1 }).done;

  expect(send).toHaveBeenCalledTimes(2);
  expect(items.map((i) => [i.state, i.message, i.code ?? null])).toEqual([
    ['added', '', null],
    ['refused', 'Wait for the taxonomy update to finish.', 'taxonomy_updating'],
    ['refused', 'Wait for the taxonomy update to finish.', 'taxonomy_updating'],
    ['refused', 'Wait for the taxonomy update to finish.', 'taxonomy_updating'],
  ]);
  expect(summarise(items).refused[2]).toEqual({ name: 'd.jpg', message: 'Wait for the taxonomy update to finish.', code: 'taxonomy_updating' });
});

test('a refusal because a backup holds the photos stops the batch as the taxonomy update does (several-windows A4)', async () => {
  const message = "A backup is copying your photos, so photos can't be changed right now. Wait for it to finish, or cancel it, change your photos, then start a new backup.";
  const held = Object.assign(new Error(message), { code: 'backup_copying', status: 409 });
  const send = vi.fn().mockRejectedValueOnce(held).mockResolvedValue(added(9));
  const items = await uploadAll([jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg')], send, { concurrency: 1 }).done;
  expect(send).toHaveBeenCalledTimes(1);
  expect(items.map((i) => [i.state, i.code ?? null])).toEqual([['refused', 'backup_copying'], ['refused', 'backup_copying'], ['refused', 'backup_copying']]);
  expect(items.every((i) => i.message === message)).toBe(true);
});
