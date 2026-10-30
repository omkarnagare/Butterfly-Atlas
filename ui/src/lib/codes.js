/** The server's error codes the UI reads (app ErrorCodes.java has the same words), and the UI's own two below. */
export const DRIVE_FULL = 'drive_full';
export const DRIVE_GONE = 'drive_gone';
export const NOT_FOUND = 'not_found';
export const BUSY = 'busy';
export const TAXONOMY_UPDATING = 'taxonomy_updating';
export const BACKUP_COPYING = 'backup_copying';
export const IMPORT_FILING = 'import_filing';
export const IMPORT_CHANGED = 'import_changed';
export const NO_SESSION = 'no_session';
export const FOLDER_MISSING = 'folder_missing';
export const IMPORT_FINISHED = 'import_finished';
export const NOTHING_TO_FILE = 'nothing_to_file';
export const PAIR_CHOICE_NEEDED = 'pair_choice_needed';
export const PAIR_CHOICE_LOCKED = 'pair_choice_locked';
/**
 * Change the library folder (contract §4–§5): every request but the change's own is refused while one runs or waits to
 * be dismissed; the move's and switch's refusals; Cancel or Dismiss at the wrong time; the setup screen's "Use the old
 * library instead" when it isn't offered; Delete old copy with none; a copy started on a fixed folder.
 */
export const LIBRARY_CHANGING = 'library_changing';
export const WORK_RUNNING = 'work_running';
export const NOT_ENOUGH_SPACE = 'not_enough_space';
export const CANNOT_CANCEL = 'cannot_cancel';
export const NO_PREVIOUS = 'no_previous';
export const NO_OLD_COPY = 'no_old_copy';
export const FIXED_FOLDER = 'fixed_folder';
/** Another copy of the app holds that library (setup's code; also a `refusal` of the change's folder check). */
export const LIBRARY_IN_USE = 'library_in_use';
/** The change's folder check (`refusal`): a library already there (move), inside the library, the library itself. */
export const EXISTING_LIBRARY = 'existing_library';
export const INSIDE_LIBRARY = 'inside_library';
export const CURRENT_LIBRARY = 'current_library';
/** The service could not be reached. */
export const OFFLINE = 'offline';
/** The service's answer could not be read. */
export const BAD_RESPONSE = 'bad_response';
