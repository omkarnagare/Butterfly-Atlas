package com.butterflyatlas.app;

/**
 * The permissions of what only this user may read: the config folder, the logs, the pointer file and the launch files
 * (design §3). They apply where the file system has POSIX permissions; elsewhere the user's own folder already is.
 */
public final class OwnerOnly {

    /** The file attribute view that has POSIX permissions. */
    public static final String POSIX = "posix";
    /** A folder only its owner can open. */
    public static final String FOLDER = "rwx------";
    /** A file only its owner can read and write. */
    public static final String FILE = "rw-------";

    private OwnerOnly() {
    }
}
