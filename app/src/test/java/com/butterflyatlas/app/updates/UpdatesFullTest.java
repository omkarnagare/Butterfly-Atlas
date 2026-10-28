package com.butterflyatlas.app.updates;

import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.nio.file.FileSystemException;

import static org.assertj.core.api.Assertions.assertThat;

/** What counts as a full drive (review J22): SQLite's SQLITE_FULL by its code or words, no space on macOS, Linux, Windows. */
class UpdatesFullTest {

    @Test
    void aFullDriveIsKnownByItsCodeOrItsWordsAnywhereInTheCauses() {
        assertThat(Updates.full(new RuntimeException(new SQLiteException("insert failed", SQLiteErrorCode.SQLITE_FULL))))
                .as("by the code").isTrue();
        assertThat(Updates.full(new IllegalStateException("Database write failed",
                new java.sql.SQLException("[SQLITE_FULL] Insertion failed because database is full (database or disk is full)"))))
                .isTrue();
        assertThat(Updates.full(new FileSystemException("a.jpg", null, "No space left on device"))).isTrue();
        assertThat(Updates.full(new IOException("There is not enough space on the disk"))).as("Windows").isTrue();
        assertThat(Updates.full(new RuntimeException(new SQLiteException("busy", SQLiteErrorCode.SQLITE_BUSY)))).isFalse();
        assertThat(Updates.full(new IOException("disk said no"))).isFalse();
    }

    @Test
    void aCauseChainThatLoopsEnds() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(Updates.full(a)).isFalse();
    }
}
