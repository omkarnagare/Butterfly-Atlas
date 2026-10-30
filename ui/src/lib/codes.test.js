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
    LIBRARY_CHANGING: 'library_changing',
    WORK_RUNNING: 'work_running',
    NOT_ENOUGH_SPACE: 'not_enough_space',
    CANNOT_CANCEL: 'cannot_cancel',
    NO_PREVIOUS: 'no_previous',
    NO_OLD_COPY: 'no_old_copy',
    FIXED_FOLDER: 'fixed_folder',
    LIBRARY_IN_USE: 'library_in_use',
    EXISTING_LIBRARY: 'existing_library',
    INSIDE_LIBRARY: 'inside_library',
    CURRENT_LIBRARY: 'current_library',
    OFFLINE: 'offline',
    BAD_RESPONSE: 'bad_response',
  });
});
