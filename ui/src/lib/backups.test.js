import { beforeEach, expect, test, vi } from 'vitest';
import { BACKUP_STOPPED, backupLine, copyingWords, holdsPhotosLine, backupPausedLine, backupPausedNotice, backupWhy, finishedLine } from './backups.js';

beforeEach(() => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});

test('the running line, with a photo count or without one for a database alone', () => {
  expect(backupLine({ done: 3210, total: 12400 })).toBe('Backing up your library — 3,210 of 12,400 photos');
  expect(backupLine({ done: 0, total: 1 })).toBe('Backing up your library — 0 of 1 photo');
  expect(backupLine({ done: 0, total: 0 })).toBe('Backing up your library…');
});

test('a stopped backup says why: closed, paused at, or paused with its notice', () => {
  expect(backupWhy({ pausedBy: 'closed', done: 5, total: 9 })).toBe('Backup stopped — the app was closed');
  expect(BACKUP_STOPPED).toBe('Backup stopped — the app was closed');
  expect(backupWhy({ pausedBy: 'user', done: 5, total: 9 })).toBe('Backup paused at 5 of 9.');
  expect(backupPausedLine({ done: 0, total: 0 })).toBe('Backup paused.');
  expect(backupPausedNotice({ pausedBy: 'user', notice: null })).toBe('You paused a backup.');
  expect(backupPausedNotice({ pausedBy: 'drive', notice: 'The drive is full.' })).toBe('The drive is full.');
  expect(backupPausedNotice({ pausedBy: 'closed', notice: null })).toMatch(/app was closed/);
});

test('the finish says how many photos were left out', () => {
  expect(finishedLine({ skipped: 0, failed: 0 })).toBe('Backup finished.');
  expect(finishedLine({})).toBe('Backup finished.');
  expect(finishedLine({ skipped: 3, failed: 1 })).toBe('Backup finished. 4 photos could not be copied.');
  expect(finishedLine({ skipped: 1, failed: 0 })).toBe('Backup finished. 1 photo could not be copied.');
});

test('the stop dialog\'s line says how much was copied and where; the kept files line is the backup\'s', async () => {
  const { stopLine, keptLine } = await import('./backups.js');
  expect(stopLine({ done: 3210, total: 12400, path: '/V/B' })).toBe('3,210 of 12,400 photos were copied to /V/B.');
  expect(stopLine({ done: 1, total: 1, path: '/V/B' })).toBe('1 of 1 photo was copied to /V/B.');
  expect(stopLine({ done: 0, total: 0, path: '/V/B' })).toBe('What was copied so far is in /V/B.');
  expect(keptLine({ cancel: 'remove', notRemoved: 120 })).toBe("120 files were kept: they had changed since the backup, or couldn't be reached.");
  expect(keptLine({ cancel: 'remove', notRemoved: 1 })).toBe("1 file was kept: it had changed since the backup, or couldn't be reached.");
  expect(keptLine({ cancel: 'keep', notRemoved: 0 })).toBe('');
  expect(keptLine({ cancel: 'remove', notRemoved: 0 })).toBe('');
});

test('the last backup in words: when, what, and how many photos could not be copied', async () => {
  const { lastLine, unreadLine, homeLine, WHAT_LABELS, whenWords } = await import('./backups.js');
  expect(whenWords(0)).toBe('today');
  expect(whenWords(1)).toBe('yesterday');
  expect(whenWords(12)).toBe('12 days ago');
  expect(WHAT_LABELS).toEqual({ database: 'Database and settings only', everything: 'Everything' });
  expect(lastLine({ lastAt: '2026-10-09T10:00:00Z', daysSince: 12, lastWhat: 'everything', lastUnread: 0 })).toBe('Last backup: 12 days ago — Everything');
  expect(lastLine({ lastAt: '2026-10-09T10:00:00Z', daysSince: 0, lastWhat: 'database', lastUnread: 4 })).toBe('Last backup: today — Database and settings only');
  expect(lastLine({ lastAt: null, daysSince: null, lastWhat: null, lastUnread: 0 })).toBe('Never backed up');
  expect(unreadLine(4)).toBe('4 photos could not be copied');
  expect(unreadLine(1)).toBe('1 photo could not be copied');
  expect(unreadLine(0)).toBe('');
  expect(homeLine({ lastAt: 'x', daysSince: 12, lastUnread: 0 })).toBe('Last backup: 12 days ago.');
  expect(homeLine({ lastAt: null, daysSince: null })).toBe('Never backed up.');
  expect(homeLine({ lastAt: 'x', daysSince: 12, lastUnread: 3 })).toBe('Last backup: 12 days ago. 3 photos could not be copied.');
});

test('a past backup in words, and a lost date reads as nothing', async () => {
  const { historyLine, PREFERENCES } = await import('./backups.js');
  expect(historyLine({ state: 'done', what: 'everything', total: 12400 })).toBe('Everything, 12,400 photos');
  expect(historyLine({ state: 'done', what: 'everything', total: 1 })).toBe('Everything, 1 photo');
  expect(historyLine({ state: 'done', what: 'database', total: 0 })).toBe('Database and settings only');
  expect(historyLine({ state: 'cancelled', what: 'everything', done: 120, total: 312, cancel: 'remove' })).toBe('Cancelled at 120 of 312 (files removed)');
  expect(historyLine({ state: 'cancelled', what: 'database', done: 0, total: 0, cancel: 'keep' })).toBe('Cancelled (files kept)');
  expect(PREFERENCES.map((p) => [p.value, p.label])).toEqual([['remind', 'Remind me'], ['less', 'Remind me less often'], ['none', 'No backup needed']]);
});

test('a backup holding the photos is worded by its buttons: Resume / Cancel… when paused by the user, Continue backup / Discard… when closing the app stopped it (A22)', () => {
  const running = { id: 2, state: 'running', pausedBy: null, holdsPhotos: true };
  const paused = { ...running, state: 'paused', pausedBy: 'you' };
  const full = { ...paused, pausedBy: 'drive', notice: 'The backup drive is full.' };
  const closed = { ...running, state: 'paused', pausedBy: 'closed' };
  expect(copyingWords(running)).toBe("A backup is copying your photos, so photos can't be changed right now. Wait for it to finish, or cancel it, change your photos, then start a new backup.");
  expect(copyingWords(paused)).toBe("A paused backup still holds your photos, so they can't be changed yet. Resume the backup and let it finish, or cancel it, change your photos, then start a new backup.");
  expect(copyingWords(full)).toBe("A paused backup still holds your photos, so they can't be changed yet. Resume the backup and let it finish, or cancel it, change your photos, then start a new backup.");
  expect(copyingWords(closed)).toBe("A paused backup still holds your photos, so they can't be changed yet. Continue the backup and let it finish, or discard it, change your photos, then start a new backup.");
  // running, its buttons are Pause / Cancel…
  expect(holdsPhotosLine(running)).toBe("While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or cancel it and start a new backup after your changes.");
  expect(holdsPhotosLine(paused)).toBe("While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or cancel it and start a new backup after your changes.");
  expect(holdsPhotosLine(full)).toBe("While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or cancel it and start a new backup after your changes.");
  expect(holdsPhotosLine(closed)).toBe("While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or discard it and start a new backup after your changes.");
});
