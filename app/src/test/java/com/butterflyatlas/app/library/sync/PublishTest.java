package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.db.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publishing (one-device design §5, steps 1–6): the generation goes up in the snapshot, the working copy and the marker;
 * the snapshot is written as {@code .part} then renamed; a write during the publish keeps the working copy dirty; a marker
 * naming another device stops it; a marker that can't be read, or a folder that can't be written, is a failure that leaves
 * the library as it was. Copying a snapshot down makes a working copy with the same rows.
 */
class PublishTest {

    @TempDir
    Path dir;

    private Path library;
    private Database working;
    private static final Device ME = new Device("dev-me", "Omkar's MacBook Air");
    private static final Instant SINCE = Instant.parse("2026-11-04T10:42:00Z");
    private static final Instant NOW = Instant.parse("2026-11-04T11:05:00Z");

    @BeforeEach
    void workingCopy() throws IOException {
        library = Files.createDirectories(dir.resolve("library"));
        working = Database.open(Files.createDirectories(dir.resolve("config/libraries/lib-1")).resolve(Database.FILE_NAME));
        working.writeUntracked(c -> {
            SyncState.set(c, SyncState.LIBRARY_ID, "lib-1");
            SyncState.set(c, SyncState.GENERATION, "4");
            return null;
        });
        write("first");
    }

    private void write(String key) {
        working.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO library_setting (key, value) VALUES ('" + key + "', 'x')");
            }
            return null;
        });
    }

    private Publish.Result publish(Runnable afterCopy) throws IOException, SQLException {
        return Publish.run(working, library, ME, SINCE, NOW, null, millis -> { }, afterCopy);
    }

    private SyncState snapshot() throws SQLException {
        return SyncState.readFile(library.resolve(Database.FILE_NAME));
    }

    private Stream<String> names() throws IOException {
        return Files.list(library).map(p -> p.getFileName().toString()).sorted();
    }

    /** The SHA-256 of the library's snapshot as it is now. */
    private String sha() throws IOException {
        return Checksum.of(library.resolve(Database.FILE_NAME));
    }

    @Test
    void theGenerationGoesUpInTheSnapshotTheWorkingCopyAndTheMarker() throws Exception {
        Publish.Result result = publish(() -> { });

        assertThat(result).isEqualTo(new Publish.Published(5, false, sha()));
        assertThat(snapshot()).isEqualTo(new SyncState("lib-1", 5, NOW, false));
        assertThat(SyncState.read(working)).isEqualTo(new SyncState("lib-1", 5, NOW, false));
        assertThat(MarkerFile.read(library).marker())
                .isEqualTo(new Marker("dev-me", "Omkar's MacBook Air", Marker.OPEN, SINCE, NOW, 5, sha()));
        assertThat(names()).containsExactly(MarkerFile.NAME, Database.FILE_NAME);
    }

    @Test
    void theSnapshotIsOneFileInRollbackModeWithTheRows() throws Exception {
        Files.writeString(library.resolve(Database.FILE_NAME + "-wal"), "left by an in-place opening");
        Files.writeString(library.resolve(Database.FILE_NAME + "-shm"), "left by an in-place opening");

        publish(() -> { });

        assertThat(names()).containsExactly(MarkerFile.NAME, Database.FILE_NAME);
        assertThat(Files.readAllBytes(library.resolve(Database.FILE_NAME))[18]).isEqualTo((byte) 1); // not WAL
        WorkingCopy copy = WorkingCopy.at(dir.resolve("down"));
        copy.copyDown(library.resolve(Database.FILE_NAME));
        Database down = Database.open(copy.file());
        int rows = down.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM library_setting WHERE key = 'first'")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void aSnapshotIsWrittenAsPartThenRenamed() throws Exception {
        boolean[] there = new boolean[1];
        publish(() -> there[0] = Files.isRegularFile(library.resolve(Publish.PART))
                && !Files.exists(library.resolve(Database.FILE_NAME)));

        assertThat(there[0]).isTrue(); // the .part, and no atlas.db yet, between the copy and the rename
        assertThat(library.resolve(Publish.PART)).doesNotExist();
    }

    @Test
    void aWriteDuringThePublishKeepsTheWorkingCopyDirty() throws Exception {
        Publish.Result result = publish(() -> write("meanwhile"));

        assertThat(result).isEqualTo(new Publish.Published(5, true, sha()));
        assertThat(SyncState.read(working).dirty()).isTrue();
        assertThat(snapshot().dirty()).isFalse();
        assertThat(publish(() -> { })).isEqualTo(new Publish.Published(6, false, sha()));
    }

    @Test
    void aMarkerNamingAnotherDeviceStopsItBeforeTheRename() throws Exception {
        Marker other = new Marker("dev-pune", "DESKTOP-PUNE", Marker.OPEN, NOW, NOW, 9);
        MarkerFile.write(library, other);

        Publish.Result result = publish(() -> { });

        assertThat(result).isEqualTo(new Publish.NotOurs(other));
        assertThat(names()).containsExactly(MarkerFile.NAME);
        assertThat(SyncState.read(working)).isEqualTo(new SyncState("lib-1", 4, null, true));
        assertThat(MarkerFile.read(library).marker()).isEqualTo(other);
    }

    @Test
    void aMarkerUnreadableTwiceIsAFailureAndNothingIsPublished() throws Exception {
        Files.writeString(library.resolve(MarkerFile.NAME), "half");

        assertThatThrownBy(() -> publish(() -> { })).isInstanceOf(Publish.MarkerUnreadable.class);

        assertThat(names()).containsExactly(MarkerFile.NAME);
        assertThat(SyncState.read(working).dirty()).isTrue();
    }

    @Test
    void aFolderThatCannotBeWrittenIsAFailureAndTheWorkingCopyStaysDirty() throws Exception {
        Path gone = dir.resolve("gone");

        assertThatThrownBy(() -> Publish.run(working, gone, ME, SINCE, NOW, null, millis -> { }, () -> { }))
                .isInstanceOfAny(IOException.class, SQLException.class, RuntimeException.class);

        assertThat(SyncState.read(working)).isEqualTo(new SyncState("lib-1", 4, null, true));
    }

    @Test
    void aGivenGenerationIsPublishedAsItIs() throws Exception {
        Publish.Result result = Publish.run(working, library, ME, SINCE, NOW, 1L, millis -> { }, () -> { });

        assertThat(result).isEqualTo(new Publish.Published(1, false, sha()));
        assertThat(snapshot().generation()).isEqualTo(1);
    }

    @Test
    void copyingDownReplacesAnOlderWorkingCopy() throws Exception {
        publish(() -> { });
        WorkingCopy copy = WorkingCopy.at(dir.resolve("other"));
        Files.createDirectories(copy.folder());
        Files.writeString(copy.file(), "an older copy");
        Files.writeString(copy.folder().resolve(Database.FILE_NAME + "-wal"), "its log");

        copy.copyDown(library.resolve(Database.FILE_NAME));

        assertThat(copy.state()).isEqualTo(new SyncState("lib-1", 5, NOW, false));
        assertThat(copy.folder().resolve(Database.FILE_NAME + "-wal")).doesNotExist();
        assertThat(copy.folder().resolve(Publish.PART)).doesNotExist();
    }
}
