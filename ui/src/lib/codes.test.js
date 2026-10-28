import { expect, test } from 'vitest';
import * as codes from './codes.js';

// The server's error codes (app ErrorCodesTest pins the same words on the Java side); offline and bad_response are
// the UI's own, for answers that never came or could not be read.
test('the error codes keep their wire words', () => {
  expect({ ...codes }).toEqual({
    DRIVE_FULL: 'drive_full',
    DRIVE_GONE: 'drive_gone',
    NOT_FOUND: 'not_found',
    BUSY: 'busy',
    TAXONOMY_UPDATING: 'taxonomy_updating',
    BACKUP_COPYING: 'backup_copying',
    IMPORT_FILING: 'import_filing',
    IMPORT_CHANGED: 'import_changed',
    NO_SESSION: 'no_session',
    FOLDER_MISSING: 'folder_missing',
    IMPORT_FINISHED: 'import_finished',
    NOTHING_TO_FILE: 'nothing_to_file',
    PAIR_CHOICE_NEEDED: 'pair_choice_needed',
    PAIR_CHOICE_LOCKED: 'pair_choice_locked',
    OFFLINE: 'offline',
    BAD_RESPONSE: 'bad_response',
  });
});
