package com.butterflyatlas.app.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A library made by a newer Butterfly Atlas is refused in plain words before anything touches it (audit 5). */
class LibraryTooNewTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(Database.FILE_NAME);
    }

    private static void applied(Database db, String version) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO flyway_schema_history (installed_rank, version,"
                    + " description, type, script, checksum, installed_by, execution_time, success) VALUES"
                    + " ((SELECT MAX(installed_rank) + 1 FROM flyway_schema_history), ?, 'newer', 'SQL', ?, 0, 'x', 0, 1)")) {
                ps.setString(1, version);
                ps.setString(2, "V" + version + "__newer.sql");
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test
    void theAppKnowsTheNewestMigrationItShips() {
        assertThat(Database.knownSchemaVersion()).isEqualTo(14);
    }

    @Test
    void aLibraryAboveTheAppsVersionIsRefusedInPlainWordsAndLeftAsItWas() throws Exception {
        Database db = Database.open(file());
        applied(db, "15");
        // closed cleanly into the main file, so a byte comparison says nothing was written
        db.read(c -> {
            try (var st = c.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            return null;
        });
        String before = sha(file());

        assertThatThrownBy(() -> Database.open(file())).isInstanceOf(LibraryTooNewException.class)
                .hasMessage("This library was made by a newer Butterfly Atlas (database version 15; this one knows 14)."
                        + " Install the newer version.")
                .isInstanceOfSatisfying(LibraryTooNewException.class, e -> {
                    assertThat(e.found()).isEqualTo(15);
                    assertThat(e.known()).isEqualTo(14);
                });

        assertThat(sha(file())).isEqualTo(before);
    }

    @Test
    void aLibraryAtOrBelowTheAppsVersionOpensAsBefore() {
        Database.open(file());
        assertThat(Database.newestSchemaVersion(file())).isEqualTo(OptionalInt.of(14));

        Database.open(file()); // again: nothing to refuse
    }

    @Test
    void aFailedMigrationDoesNotCountAsAVersion() {
        Database db = Database.open(file());
        db.write(c -> {
            try (var st = c.createStatement()) {
                st.execute("UPDATE flyway_schema_history SET success = 0 WHERE version = '14'");
            }
            return null;
        });

        assertThat(Database.newestSchemaVersion(file())).isEqualTo(OptionalInt.of(13));
    }

    @Test
    void aMissingFileOrAFileWithoutHistoryIsNotATooNewLibrary() throws Exception {
        assertThat(Database.newestSchemaVersion(file())).isEmpty();
        assertThat(Files.exists(file())).as("looking never creates the file").isFalse();

        Files.write(file(), new byte[0]);
        assertThat(Database.newestSchemaVersion(file())).isEmpty();
        Database.open(file()); // an empty file is a new library
    }
}
