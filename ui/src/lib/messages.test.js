import { expect, test } from 'vitest';
import {
  NO_SESSION_WORDS, LIBRARY_IN_USE_WORDS, copyLeftLine, deleteOldCopyAsk, lastBackupLine, libraryMovedLine, movedCopyLine,
  movingToLine, oldCopyLeftLine, oldCopyLine, otherFilesLeftLine, tooLittleSpaceLine, usePreviousLabel,
  CLOSED_HERE, DISCARDED_PHOTOS_LEAD, NOT_SYNCED_WORDS, SYNC_BEFORE_OTHER_LINE, SYNC_SENT_LINE, agoWords, discardedWords, openAnywayWarning,
  openedElsewhereWords, otherDeviceWords, publishLine, triedAgainLine, unsentLine,
} from './messages.js';

// The server says the same words (app SessionGuard.MESSAGE, pinned in SessionApiTest); the two must not drift apart.
test('the no-session words match the server', () => {
  expect(NO_SESSION_WORDS).toBe('To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.');
});

// Change the library folder (design §3, §6, §7, §9): the sentences with the folders and sizes in them.
test('the library folder\'s sentences', () => {
  expect(movingToLine('/Volumes/B/Butterfly_Atlas', '12.4 GB', '80.1 GB'))
    .toBe('Your library will be moved to /Volumes/B/Butterfly_Atlas. It is 12.4 GB; that drive has 80.1 GB free.');
  expect(tooLittleSpaceLine('3.2 GB', '13.2 GB')).toBe('That drive has 3.2 GB free; moving your library needs about 13.2 GB.');
  // the server's words for a library another copy holds (SetupService)
  expect(LIBRARY_IN_USE_WORDS).toBe('Another copy of Butterfly Atlas is using this library.');
  expect(copyLeftLine('/Volumes/B/Butterfly_Atlas', 3))
    .toBe("Some of the copy at /Volumes/B/Butterfly_Atlas couldn't be removed (3 files). You can delete it yourself.");
  expect(copyLeftLine('/V/A', 1)).toBe("Some of the copy at /V/A couldn't be removed (1 file). You can delete it yourself.");
  expect(oldCopyLine('/Users/me/Atlas', '12.4 GB')).toBe('The old copy of your library is still at /Users/me/Atlas (12.4 GB).');
  expect(deleteOldCopyAsk('/Users/me/Atlas', '/V/A')).toBe('Delete the old copy at /Users/me/Atlas? Your library at /V/A is the one in use.');
  expect(lastBackupLine('Everything', '27 October')).toBe('Your last backup (Everything) was on 27 October.');
  expect(oldCopyLeftLine('/Users/me/Atlas', 2))
    .toBe("Some of the old copy at /Users/me/Atlas couldn't be deleted (2 files). Choose Delete old copy to try again, or delete them yourself.");
  expect(oldCopyLeftLine('/Users/me/Atlas', 1))
    .toBe("Some of the old copy at /Users/me/Atlas couldn't be deleted (1 file). Choose Delete old copy to try again, or delete it yourself.");
  expect(otherFilesLeftLine('/Users/me/Atlas', 1)).toBe("1 file that isn't part of a library was left in /Users/me/Atlas.");
  expect(otherFilesLeftLine('/Users/me/Atlas', 4)).toBe("4 files that aren't part of a library were left in /Users/me/Atlas.");
  expect(usePreviousLabel('/Users/me/Atlas')).toBe('Use the old library at /Users/me/Atlas instead');
  expect(movedCopyLine('/V/A')).toBe('The moved copy at /V/A was not deleted.');
  expect(libraryMovedLine('/V/A')).toBe('Your library was moved to /V/A.');
});

// One device at a time (one-device-library design §4–§8, §10): the sentences, with the device's name bold where the
// spec has it, and "another device" (not bold) when the name couldn't be read.
const plain = ({ lead, name, rest }) => `${lead}${name}${rest}`;

test('how long ago, from minutes', () => {
  expect(agoWords(0)).toBe('just now');
  expect(agoWords(1)).toBe('1 minute ago');
  expect(agoWords(40)).toBe('40 minutes ago');
  expect(agoWords(59)).toBe('59 minutes ago');
  expect(agoWords(60)).toBe('1 hour ago');
  expect(agoWords(150)).toBe('2 hours ago');
  expect(agoWords(1440)).toBe('1 day ago');
  expect(agoWords(3 * 1440 + 5)).toBe('3 days ago');
});

test('the refusal page, before and after 15 minutes', () => {
  const soon = otherDeviceWords({ name: "Omkar's MacBook Air", seen: '4 Nov at 11:05', ago: '2 minutes ago', from: '11:20', late: false });
  expect(soon.bold).toBe(true);
  expect(plain(soon)).toBe("This library is open on Omkar's MacBook Air (last seen 4 Nov at 11:05, 2 minutes ago). Quit Butterfly Atlas"
    + ' there, let your sync app finish, then choose Try again. If that device is off or asleep, you can open the library here from 11:20.');
  const late = otherDeviceWords({ name: "Omkar's MacBook Air", seen: '4 Nov at 11:05', ago: '40 minutes ago', from: '11:20', late: true });
  expect(plain(late)).toBe("This library is open on Omkar's MacBook Air (last seen 4 Nov at 11:05, 40 minutes ago). It may be off or"
    + ' asleep, or it may have closed without quitting properly. Quit Butterfly Atlas there if you can, let your sync app finish, then'
    + ' choose Try again.');
  const nameless = otherDeviceWords({ name: null, seen: '4 Nov at 11:05', ago: 'just now', from: '11:20', late: false });
  expect(nameless.bold).toBe(false);
  expect(plain(nameless)).toMatch(/^This library is open on another device \(last seen 4 Nov at 11:05, just now\)\./);
});

test('the Open here anyway warning', () => {
  expect(openAnywayWarning("Omkar's MacBook Air")).toBe("If Omkar's MacBook Air is still running Butterfly Atlas, or didn't send its last"
    + ' changes, those changes will be lost.');
  expect(openAnywayWarning(null)).toBe("If the other device is still running Butterfly Atlas, or didn't send its last changes, those changes will"
    + ' be lost.');
});

test('the discarded page', () => {
  expect(plain(discardedWords({ name: 'DESKTOP-PUNE', at: '4 Nov at 14:20', since: '4 Nov, 14:02' }))).toBe('This library was used on'
    + ' DESKTOP-PUNE (last on 4 Nov at 14:20) after this device last sent its changes. Changes made here after 4 Nov, 14:02 couldn\'t be'
    + ' sent and have been discarded.');
  expect(plain(discardedWords({ name: null, at: '4 Nov at 14:20', since: '4 Nov, 14:02' }))).toMatch(/^This library was used on another device \(/);
});

test('the opened-elsewhere page and its unsent-changes line', () => {
  const words = openedElsewhereWords({ name: 'DESKTOP-PUNE', at: '4 Nov at 14:20' });
  expect(words.bold).toBe(true);
  expect(plain(words)).toBe('Your library was opened on DESKTOP-PUNE on 4 Nov at 14:20 while Butterfly Atlas was still open here. To keep'
    + ' one version of your library, this device has stopped saving. To use the library here again, quit Butterfly Atlas on DESKTOP-PUNE'
    + ' and let your sync app finish, then quit and reopen it here.');
  expect(plain(openedElsewhereWords({ name: null, at: '4 Nov at 14:20' }))).toBe('Your library was opened on another device on 4 Nov at 14:20'
    + ' while Butterfly Atlas was still open here. To keep one version of your library, this device has stopped saving. To use the library'
    + ' here again, quit Butterfly Atlas on the other device and let your sync app finish, then quit and reopen it here.');
  expect(unsentLine('4 Nov, 14:02', 'DESKTOP-PUNE')).toBe("Changes made here since 4 Nov, 14:02 haven't reached the library yet. They will be"
    + ' sent when you reopen Butterfly Atlas here, unless changes are saved on DESKTOP-PUNE first.');
  expect(unsentLine('4 Nov, 14:02', null)).toMatch(/saved on the other device first\.$/);
});

test('the publishing bar, while it retries and after it stopped, with the reason inside the sentence', () => {
  const reason = 'The drive with your library is full. Free some space, then try again.';
  expect(publishLine({ state: 'retrying', reason })).toBe("Your changes are saved on this device but couldn't be sent to your library"
    + ' folder: the drive with your library is full. Free some space, then try again. Trying again in a minute. Don\'t open your library on'
    + ' another device until this message goes away.');
  expect(publishLine({ state: 'stopped', reason: "Butterfly Atlas can't write to your library folder." })).toBe("Your changes are saved on"
    + " this device but couldn't be sent to your library folder after 5 tries: Butterfly Atlas can't write to your library folder. Fix"
    + ' that (for example, start your sync app or free some space), then choose Try again. Don\'t open your library on another device'
    + ' until this message goes away.');
  expect(publishLine({ state: 'retrying', reason: null })).toBe("Your changes are saved on this device but couldn't be sent to your library"
    + ' folder. Trying again in a minute. Don\'t open your library on another device until this message goes away.');
});

test('the one-time notes and the setup page\'s fixed sentences', () => {
  expect(SYNC_SENT_LINE).toBe('Changes from your last session on this device were sent to the library.');
  expect(SYNC_BEFORE_OTHER_LINE).toBe('Before you open your library on another device, quit Butterfly Atlas here and let your sync app finish.');
  expect(NOT_SYNCED_WORDS).toBe("This library's database hasn't finished syncing to this device yet. Let your sync app finish, then choose"
    + ' Try again.');
  expect(DISCARDED_PHOTOS_LEAD).toBe("These photos are in your library's folder but no longer in your atlas; import them again to add them back:");
  expect(CLOSED_HERE).toBe('Butterfly Atlas has closed here. You can close this window.');
  expect(triedAgainLine('11:07')).toBe("Tried again at 11:07 — still couldn't open.");
});
