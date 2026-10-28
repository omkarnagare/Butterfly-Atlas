import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { KIND_LABELS, briefOf, headline, pausedNoticeOf, progressWords, speciesLine, splitBadge, updateLine } from './updates.js';

beforeEach(() => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => vi.restoreAllMocks());

const brief = (over = {}) => ({
  state: 'running', kind: 'bundle', later: false, done: 120, total: 340, step: 1, steps: 2, continuing: false, waitingFor: null, notice: null, ...over,
});

test('the footer\'s line: the photos moved so far, or what the update waits for', () => {
  expect(updateLine(brief())).toBe('Updating taxonomy… 120 of 340 photos');
  expect(updateLine(brief({ done: 1200, total: 34000 }))).toBe('Updating taxonomy… 1,200 of 34,000 photos');
  expect(updateLine(brief({ done: 0, total: 0 }))).toBe('Updating taxonomy…');
  expect(updateLine(brief({ state: 'waiting', waitingFor: 'import' }))).toBe('Waiting for the import to finish filing…');
  expect(updateLine(brief({ state: 'waiting', waitingFor: 'export' }))).toBe('Waiting for the export to finish copying…');
  expect(updateLine(brief({ state: 'waiting', waitingFor: 'backup' }))).toBe('Waiting for the backup to finish copying…');
  // several-windows A7: a paused backup holding the photos holds the update too, until it is continued or discarded
  expect(updateLine(brief({ state: 'waiting', waitingFor: 'backup_paused' }))).toBe('Waiting for the paused backup. Continue it and let it finish, or discard it.');
  expect(updateLine(brief({ state: 'waiting', waitingFor: 'startup' }))).toBe('Waiting for the startup checks to finish…');
  expect(updateLine(brief({ state: 'waiting', waitingFor: null }))).toBe('Waiting to update the taxonomy…');
});

test('the update screen\'s progress: the step when there are several, then the photos', () => {
  const progress = { step: 1, steps: 2, stage: 'moves', done: 120, total: 340, continuing: false, waitingFor: null };
  expect(progressWords(progress)).toBe('Step 1 of 2 · 120 of 340 photos');
  expect(progressWords({ ...progress, steps: 1 })).toBe('120 of 340 photos');
  expect(progressWords({ ...progress, stage: 'plan', done: 0, total: 0 })).toBe('Step 1 of 2 · Working out what moves…');
  expect(progressWords({ ...progress, steps: 1, stage: 'switch' })).toBe('120 of 340 photos · Updating names and groups…');
});

test('an Update answered by Apply, Retry or Merge becomes the status\'s brief', () => {
  expect(briefOf({
    state: 'running', kind: 'match', later: false, from: 'a', to: 'b', steps: ['b'], summary: null,
    progress: { step: 1, steps: 1, stage: 'plan', done: 0, total: 4, continuing: false, waitingFor: null }, notice: null, result: null,
  })).toEqual(brief({ kind: 'match', done: 0, total: 4, steps: 1 }));
  expect(briefOf({ state: 'paused', kind: 'bundle', later: false, progress: null, notice: { code: 'drive_full', message: 'Full.' } }))
    .toEqual(brief({ state: 'paused', done: 0, total: 0, step: 0, steps: 0, notice: { code: 'drive_full', message: 'Full.' } }));
});

test('a paused update without a notice still says what to do', () => {
  expect(pausedNoticeOf(brief({ state: 'paused', notice: { code: 'drive_gone', message: 'Reconnect it.' } }))).toBe('Reconnect it.');
  expect(pausedNoticeOf(brief({ state: 'paused' }))).toBe('The taxonomy update stopped. Choose Retry to try again.');
});

const kind = (k, count, photos, items = [{ rank: 'species' }]) => ({ kind: k, count, photos, items });

test('the headline counts each kind of change in the order sent, the first with its noun, and the photos it affects', () => {
  expect(headline({ photos: 340, kinds: [kind('move', 12, 200), kind('rename', 2, 140), kind('split', 1, 0)] }))
    .toBe('12 species reclassified, 2 renamed, 1 split — affects 340 of your photos.');
  expect(headline({ photos: 1, kinds: [kind('rename', 1, 1)] })).toBe('1 species renamed — affects 1 of your photos.');
  expect(headline({ photos: 0, kinds: [kind('merge', 2, 0, [{ rank: 'genus' }, { rank: 'species' }]), kind('add', 40, 0), kind('remove', 3, 0)] }))
    .toBe('2 names merged, 40 added, 3 removed — none of your photos are affected.');
  expect(headline({ photos: 5000, kinds: [kind('rename', 1, 5000, [{ rank: 'genus' }])] })).toBe('1 name renamed — affects 5,000 of your photos.');
});

test('the species line: photographed of all before and after', () => {
  expect(speciesLine({ species: { before: { got: 342, total: 1139 }, after: { got: 342, total: 1141 } } }))
    .toBe('Your atlas: 342 of 1,139 species → 342 of 1,141');
});

test('each kind has a plain label', () => {
  expect(Object.keys(KIND_LABELS)).toEqual(['rename', 'move', 'merge', 'split', 'add', 'remove']);
  expect(KIND_LABELS.move).toBe('Reclassified');
});

test('the split badge counts the photos still to sort', () => {
  expect(splitBadge(28)).toBe('Split — 28 photos to sort');
  expect(splitBadge(1)).toBe('Split — 1 photo to sort');
  expect(splitBadge(1200)).toBe('Split — 1,200 photos to sort');
});
