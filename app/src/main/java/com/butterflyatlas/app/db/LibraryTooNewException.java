package com.butterflyatlas.app.db;

/**
 * The library was made by a newer Butterfly Atlas (its newest schema version is above the one this app knows). The message
 * is for the user as it is; nothing of the library has been touched.
 */
public final class LibraryTooNewException extends RuntimeException {

    private final int found;
    private final int known;

    public LibraryTooNewException(int found, int known) {
        super("This library was made by a newer Butterfly Atlas (database version " + found + "; this one knows " + known
                + "). Install the newer version.");
        this.found = found;
        this.known = known;
    }

    /** The library's newest schema version. */
    public int found() {
        return found;
    }

    /** The newest schema version this app knows. */
    public int known() {
        return known;
    }
}
