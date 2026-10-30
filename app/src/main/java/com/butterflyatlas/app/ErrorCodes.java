package com.butterflyatlas.app;

/**
 * The error codes more than one part of the app answers with, as the UI reads them ({@code ui/src/lib/codes.js}). Runs
 * keep a pause's code in the database, so the words never change.
 */
public final class ErrorCodes {

    /** A refusal of the request itself (e.g. no folder chosen). */
    public static final String INVALID = "invalid";
    public static final String NOT_FOUND = "not_found";
    /** The chosen path is a file, not a folder. */
    public static final String NOT_FOLDER = "not_folder";
    /** The chosen folder is the library's or inside it. */
    public static final String LIBRARY_FOLDER = "library_folder";
    public static final String NOT_WRITABLE = "not_writable";
    public static final String NO_SPACE = "no_space";
    /** A path would pass what Windows allows. */
    public static final String PATH_TOO_LONG = "path_too_long";
    /** An export or backup paused on files it could not write. */
    public static final String WRITE_ERRORS = "write_errors";
    /** The library's drive is full (5b contract §7). */
    public static final String DRIVE_FULL = "drive_full";
    /** The library's drive can't be found. */
    public static final String DRIVE_GONE = "drive_gone";
    /** Another copy of the app has the library open. */
    public static final String LIBRARY_IN_USE = "library_in_use";
    /** Refused while a taxonomy update runs. */
    public static final String TAXONOMY_UPDATING = "taxonomy_updating";
    /** The database stayed locked past its retries. */
    public static final String BUSY = "busy";
    /** Refused while a backup holds the photos. */
    public static final String BACKUP_COPYING = "backup_copying";
    /** Refused while an import files photos. */
    public static final String IMPORT_FILING = "import_filing";
    /** An import was changed in another window since this one read it. */
    public static final String IMPORT_CHANGED = "import_changed";
    /** The request has no session cookie (or a second launch has no key). */
    public static final String NO_SESSION = "no_session";
    /** A file Butterfly Atlas cannot read as a photo. */
    public static final String UNSUPPORTED_PHOTO = "unsupported_photo";
    /** An import's folder is gone. */
    public static final String FOLDER_MISSING = "folder_missing";
    /** The import is finished. */
    public static final String IMPORT_FINISHED = "import_finished";
    /** No photo has a species to file under. */
    public static final String NOTHING_TO_FILE = "nothing_to_file";
    /** RAW + JPEG pairs wait for a choice before filing. */
    public static final String PAIR_CHOICE_NEEDED = "pair_choice_needed";
    /** The pairs choice can no longer change. */
    public static final String PAIR_CHOICE_LOCKED = "pair_choice_locked";

    /** Refused while the library folder is being changed (change-library-folder contract §4). */
    public static final String LIBRARY_CHANGING = "library_changing";
    /** A library change can no longer be cancelled, or none runs. */
    public static final String CANNOT_CANCEL = "cannot_cancel";

    /** Running work refuses a library change; the message names what runs. */
    public static final String WORK_RUNNING = "work_running";
    /** The drive chosen has too little free space for the library. */
    public static final String NOT_ENOUGH_SPACE = "not_enough_space";
    /** A copy started on {@code -Datlas.dataDir} keeps its library folder. */
    public static final String FIXED_FOLDER = "fixed_folder";
    /** The old library is not offered (only during the first open after a change). */
    public static final String NO_PREVIOUS = "no_previous";
    /** No old copy of the library is left to delete. */
    public static final String NO_OLD_COPY = "no_old_copy";
    /** A library change's refusal: the folder already holds a library (a move). */
    public static final String EXISTING_LIBRARY = "existing_library";
    /** A library change's refusal: the folder is inside the library in use. */
    public static final String INSIDE_LIBRARY = "inside_library";
    /** A library change's refusal: the folder is the library in use. */
    public static final String CURRENT_LIBRARY = "current_library";

    private ErrorCodes() {
    }
}
