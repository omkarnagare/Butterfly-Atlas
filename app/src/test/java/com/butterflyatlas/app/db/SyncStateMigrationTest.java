package com.butterflyatlas.app.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V16 (one-device design §5): {@code sync_state} holds the library's sync facts, with {@code dirty} at 0; every write
 * transaction sets it to 1, a write of the sync state itself does not, and each write is counted and heard after its commit.
 */
class SyncStateMigrationTest {

    @TempDir
    Path dir;

    private static String dirty(Database db) {
        return db.read(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value FROM sync_state WHERE key = 'dirty'")) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Test
    void aVersion15LibraryGainsTheSyncStateWithNothingUnsent() {
        Database v15 = Database.open(dir.resolve(Database.FILE_NAME), "15");
        v15.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('backup.reminder', 'none')");
            }
            return null;
        });

        Database db = Database.open(dir.resolve(Database.FILE_NAME));

        assertThat(Database.newestSchemaVersion(dir.resolve(Database.FILE_NAME))).hasValue(16);
        List<String> rows = db.read(c -> {
            List<String> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT key, value FROM sync_state")) {
                while (rs.next()) {
                    out.add(rs.getString(1) + "=" + rs.getString(2));
                }
            }
            return out;
        });
        assertThat(rows).containsExactly("dirty=0");
        String reminder = db.read(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value FROM library_setting WHERE key = 'backup.reminder'")) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
        assertThat(reminder).isEqualTo("none");
    }

    @Test
    void everyWriteMarksTheDatabaseDirtyAndIsCountedAndHeard() {
        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        db.writeUntracked(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE sync_state SET value = '0' WHERE key = 'dirty'");
            }
            return null;
        });
        AtomicInteger heard = new AtomicInteger();
        db.onWrite(heard::incrementAndGet);
        long before = db.writes();

        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('a', 'b')");
            }
            return null;
        });

        assertThat(dirty(db)).isEqualTo("1");
        assertThat(db.writes()).isEqualTo(before + 1);
        assertThat(heard).hasValue(1);
    }

    @Test
    void aWriteOfTheSyncStateItselfLeavesItCleanAndIsNotHeard() {
        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        AtomicInteger heard = new AtomicInteger();
        db.onWrite(heard::incrementAndGet);
        long before = db.writes();

        db.writeUntracked(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE sync_state SET value = '0' WHERE key = 'dirty'");
            }
            return null;
        });

        assertThat(dirty(db)).isEqualTo("0");
        assertThat(db.writes()).isEqualTo(before);
        assertThat(heard).hasValue(0);
    }

    @Test
    void aWriteThatFailsIsNeitherMarkedNorHeard() {
        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        AtomicInteger heard = new AtomicInteger();
        db.onWrite(heard::incrementAndGet);

        assertThatThrownBy(() -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('a', 'b')");
            }
            throw new IllegalStateException("stop");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(dirty(db)).isEqualTo("0");
        assertThat(heard).hasValue(0);
    }

    @Test
    void aDatabaseOlderThanVersion16IsWrittenAsBefore() {
        Database v15 = Database.open(dir.resolve(Database.FILE_NAME), "15");

        v15.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('a', 'b')");
            }
            return null;
        });

        assertThat(v15.writes()).isEqualTo(1);
    }
}
