package com.butterflyatlas.app.backup;

import java.util.function.Consumer;

/** Test access to the backup's hooks from other packages. */
public final class TestBackups {

    private TestBackups() {
    }

    /** Runs once the database's copy is in place, before it is read back, with it. */
    public static void afterDatabase(Backups backups, Consumer<java.nio.file.Path> hook) {
        backups.afterDatabase(hook);
    }

    /** Runs before each photo is copied, with its id. */
    public static void beforePhoto(Backups backups, Consumer<Long> hook) {
        backups.beforePhoto(hook);
    }
}
