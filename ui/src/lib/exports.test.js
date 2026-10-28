import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import {
  copiedLine, exportDate, historyLine, moreProblemsLine, notRemovedLine, pausedLine, pausedNotice, PROBLEM_KINDS, problemsLine,
  progressLine, sizeLine, spaceLine, stopLine,
} from './exports.js';

beforeEach(() => {
  // pin the number format, so the tests do not depend on the machine's locale
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => vi.restoreAllMocks());

const run = (over = {}) => ({
  id: 4, state: 'running', what: 'both', layout: 'species', stripLocation: true, placeDepth: 'state', filter: '',
  path: '/Volumes/Backup/Butterfly_Atlas_export_2026-10-13', csv: 'species-list.csv',
  species: 48, total: 50000, done: 3200, copied: 3197, skipped: 2, failed: 1, renamed: 0,
  bytesTotal: 4509715660, bytesDone: 1712345678, pausedBy: null, notice: null, cancel: null, notRemoved: 0,
  startedAt: '2026-10-13T16:10:04Z', finishedAt: null, ...over,
});

test('the progress and paused lines count the photos done of the total', () => {
  expect(progressLine(run())).toBe('Exporting photos… 3,200 of 50,000');
  expect(pausedLine(run({ state: 'paused' }))).toBe('Export paused at 3,200 of 50,000.');
  expect(progressLine(run({ done: 0, total: 1 }))).toBe('Exporting photos… 0 of 1');
});

test('the size line gives the bytes done in the unit of the total, as 4a sizes them', () => {
  expect(sizeLine(run())).toBe('1.6 of 4.2 GB');
  expect(sizeLine(run({ bytesDone: 0 }))).toBe('0.0 of 4.2 GB');
  expect(sizeLine(run({ bytesDone: 4509715660 }))).toBe('4.2 of 4.2 GB');
  expect(sizeLine(run({ bytesDone: 512, bytesTotal: 2048 }))).toBe('1 of 2 KB');
  expect(sizeLine(run({ bytesDone: 300, bytesTotal: 900 }))).toBe('300 of 900 B');
});

test('Home, the footer and the stop dialog say how far it got and where', () => {
  expect(copiedLine(run())).toBe('3,200 of 50,000 photos copied to /Volumes/Backup/Butterfly_Atlas_export_2026-10-13.');
  expect(stopLine(run())).toBe('3,200 of 50,000 photos were copied to /Volumes/Backup/Butterfly_Atlas_export_2026-10-13.');
  expect(stopLine(run({ done: 1, total: 1 }))).toBe('1 of 1 photo was copied to /Volumes/Backup/Butterfly_Atlas_export_2026-10-13.');
  expect(copiedLine(run({ done: 0, total: 1 }))).toBe('0 of 1 photo copied to /Volumes/Backup/Butterfly_Atlas_export_2026-10-13.');
  expect(pausedNotice(run({ state: 'paused', pausedBy: 'you' }))).toBe('You paused an export.');
  expect(pausedNotice(run({ state: 'paused', pausedBy: 'closing', notice: 'This export stopped when Butterfly Atlas closed.' })))
    .toBe('This export stopped when Butterfly Atlas closed.');
});

test('dates read "13 Oct 2026, 21:40" in this computer\'s time, 24-hour, whatever the language; a bad date reads as nothing', () => {
  expect(exportDate(new Date(2026, 9, 13, 21, 40, 5).toISOString())).toBe('13 Oct 2026, 21:40');
  expect(exportDate(new Date(2026, 8, 3, 7, 5).toISOString())).toBe('3 Sep 2026, 07:05');
  expect(exportDate(new Date(2027, 0, 1, 0, 0).toISOString())).toBe('1 Jan 2027, 00:00');
  expect(exportDate('not a date')).toBe('');
  expect(exportDate(null)).toBe('');
});

test('a past export\'s line: what was exported, or where it was cancelled and what became of its files', () => {
  expect(historyLine(run({ state: 'done', total: 312, done: 312 }))).toBe('312 photos and species list');
  expect(historyLine(run({ state: 'done', what: 'photos', csv: null, total: 1, done: 1 }))).toBe('1 photo');
  expect(historyLine(run({ state: 'done', what: 'photos', csv: null, total: 1200, done: 1200 }))).toBe('1,200 photos');
  expect(historyLine(run({ state: 'done', what: 'csv', total: 0, done: 0 }))).toBe('Species list (CSV)');
  expect(historyLine(run({ state: 'cancelled', cancel: 'keep', total: 312, done: 120 }))).toBe('Cancelled at 120 of 312 (files kept)');
  expect(historyLine(run({ state: 'cancelled', cancel: 'remove', total: 312, done: 120 }))).toBe('Cancelled at 120 of 312 (files removed)');
});

test('the problems line counts skipped, failed and renamed photos, leaving out the ones with none', () => {
  expect(problemsLine(run({ skipped: 3, failed: 1 }))).toBe('3 skipped, 1 failed');
  expect(problemsLine(run({ skipped: 0, failed: 2 }))).toBe('2 failed');
  expect(problemsLine(run({ skipped: 1200, failed: 0, renamed: 2 }))).toBe('1,200 skipped, 2 renamed');
  expect(problemsLine(run({ skipped: 0, failed: 0, renamed: 0 }))).toBe('');
  expect(moreProblemsLine(1500)).toBe('and 1,500 more');
  expect(PROBLEM_KINDS).toEqual({ skipped: 'Skipped', failed: 'Failed', renamed: 'Renamed' });
});

test('files Remove kept (changed since the export, or out of reach) are counted; none, or files kept, say nothing', () => {
  expect(notRemovedLine(run({ state: 'cancelled', cancel: 'remove', notRemoved: 120 })))
    .toBe("120 files were kept: they had changed since the export, or couldn't be reached.");
  expect(notRemovedLine(run({ state: 'cancelled', cancel: 'remove', notRemoved: 1 })))
    .toBe("1 file was kept: it had changed since the export, or couldn't be reached.");
  expect(notRemovedLine(run({ state: 'cancelled', cancel: 'remove', notRemoved: 0 }))).toBe('');
  expect(notRemovedLine(run({ state: 'cancelled', cancel: 'keep', notRemoved: 0 }))).toBe('');
});

test('the space line gives the photos\' size (the server keeps its margin to itself) and what is free; a species list alone needs none', () => {
  expect(spaceLine({ bytes: 4509715660, neededBytes: 4719430860, freeBytes: 332859965440 })).toBe('4.2 GB needed, 310.0 GB free');
  expect(spaceLine({ bytes: 11264, neededBytes: 209715200, freeBytes: 197999999999 })).toBe('11 KB needed, 184.4 GB free');
  expect(spaceLine({ bytes: 0, neededBytes: 0, freeBytes: 332859965440 })).toBe('');
  expect(spaceLine(null)).toBe('');
});

test('the space line leaves out what is free when the server could not read it (it sends the largest long)', () => {
  expect(spaceLine({ bytes: 4509715660, neededBytes: 4719430860, freeBytes: 9223372036854775807 })).toBe('4.2 GB needed');
  expect(spaceLine({ bytes: 4509715660, neededBytes: 4719430860, freeBytes: Number.MAX_SAFE_INTEGER })).toBe('4.2 GB needed');
});
