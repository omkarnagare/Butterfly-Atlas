import { expect, test } from 'vitest';
import {
  NO_SESSION_WORDS, LIBRARY_IN_USE_WORDS, copyLeftLine, deleteOldCopyAsk, lastBackupLine, libraryMovedLine, movedCopyLine,
  movingToLine, oldCopyLeftLine, oldCopyLine, otherFilesLeftLine, tooLittleSpaceLine, usePreviousLabel,
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
