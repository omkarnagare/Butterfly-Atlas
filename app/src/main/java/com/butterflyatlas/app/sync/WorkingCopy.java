package com.butterflyatlas.app.sync;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * This device's working copy of a library (one-device design §5): {@code <config folder>/libraries/<library id>/atlas.db},
 * the live database the app works on (write-ahead log, as before), with {@code claimed-at} beside it saying when this
 * device last claimed the library.
 */
public final class WorkingCopy {

    private static final Logger LOG = LoggerFactory.getLogger(WorkingCopy.class);

    /** The folder in the config folder that holds the working copies. */
    public static final String FOLDER = "libraries";
    static final String CLAIMED = "claimed-at";

    private final Path folder;

    private WorkingCopy(Path folder) {
        this.folder = folder;
    }

    /** The working copy of the library {@code libraryId} under {@code configDir} (it may not exist yet). */
    public static WorkingCopy of(Path configDir, String libraryId) {
        return new WorkingCopy(configDir.resolve(FOLDER).resolve(libraryId));
    }

    static WorkingCopy at(Path folder) {
        return new WorkingCopy(folder);
    }

    public Path folder() {
        return folder;
    }

    /** The working copy's database file. */
    public Path file() {
        return folder.resolve(Database.FILE_NAME);
    }

    public boolean exists() {
        return Files.isRegularFile(file());
    }

    /** Its sync state, read without opening it as the app does (it is never created here). */
    public SyncState state() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.READWRITE); // a write-ahead-log database may need its -shm made to be read
        try (Connection c = config.createConnection("jdbc:sqlite:" + file().toAbsolutePath())) {
            return SyncState.read(c);
        }
    }

    /**
     * Copies the snapshot {@code snapshot} here (§5 case 4), replacing any older copy: SQLite's backup from the snapshot
     * opened read-only into {@code atlas.db.part}, checked ({@code quick_check}), then renamed into place. The app switches
     * it to write-ahead logging when it opens it.
     */
    public void copyDown(Path snapshot) throws IOException, SQLException {
        Files.createDirectories(folder);
        Path part = folder.resolve(Database.FILE_NAME + Library.PART);
        Files.deleteIfExists(part);
        String target = part.toAbsolutePath().toString();
        if (target.indexOf('"') >= 0) {
            throw new SQLException("The working copy's path has a quote in it: " + target);
        }
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection c = readOnly.createConnection("jdbc:sqlite:" + snapshot.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("backup to \"" + target + "\"");
        }
        try {
            check(part);
            delete();
            Files.move(part, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** {@code quick_check} on a database file: throws when it does not answer ok. */
    static void check(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.READWRITE);
        try (Connection c = config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            String answer = rs.next() ? rs.getString(1) : null;
            if (!"ok".equals(answer)) {
                throw new SQLException("The copy of the database did not pass SQLite's own check: " + answer);
            }
        }
    }

    /** Deletes the working copy's database and its log files (not its folder). */
    public void delete() throws IOException {
        for (String name : List.of(Database.FILE_NAME, Database.FILE_NAME + "-wal", Database.FILE_NAME + "-shm",
                Database.FILE_NAME + "-journal")) {
            Files.deleteIfExists(folder.resolve(name));
        }
    }

    /** Records that this device claimed the library now (the 30-day cleanup reads it). A failure is logged only. */
    public void claimed(Instant now) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(CLAIMED), now.toString() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not record when this library was claimed in {}", folder, e);
        }
    }

    /** When this device last claimed the library; else the working copy's modified time; null when neither is known. */
    public Instant lastClaimed() {
        try {
            Path claimed = folder.resolve(CLAIMED);
            if (Files.isRegularFile(claimed)) {
                return Instant.parse(Files.readString(claimed, StandardCharsets.UTF_8).strip());
            }
            if (Files.isRegularFile(file())) {
                return Files.getLastModifiedTime(file()).toInstant();
            }
        } catch (IOException | DateTimeParseException e) {
            LOG.debug("Could not read when {} was last claimed", folder, e);
        }
        return null;
    }
}
