package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.db.Database;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@code sync_state} rows of a library database (one-device design §5, V16): its library ID, its generation, when a
 * working copy was last in step with the library, and whether it has been written since.
 */
public record SyncState(String libraryId, long generation, Instant publishedAt, boolean dirty) {

    static final String LIBRARY_ID = "library_id";
    static final String GENERATION = "generation";
    static final String PUBLISHED_AT = "published_at";
    static final String DIRTY = "dirty";

    /** None: a database older than V16, or a library not converted yet. */
    static final SyncState NONE = new SyncState(null, 0, null, false);

    /** Whether the library has been converted (it has an ID). */
    public boolean converted() {
        return libraryId != null;
    }

    /** The sync state as {@code c} sees it (NONE when there is no {@code sync_state} table). */
    public static SyncState read(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet table = st.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sync_state'")) {
            if (!table.next()) {
                return NONE;
            }
        }
        Map<String, String> rows = new HashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT key, value FROM sync_state")) {
            while (rs.next()) {
                rows.put(rs.getString(1), rs.getString(2));
            }
        }
        long generation;
        try {
            generation = rows.get(GENERATION) == null ? 0 : Long.parseLong(rows.get(GENERATION));
        } catch (NumberFormatException e) {
            generation = 0;
        }
        Instant published;
        try {
            published = rows.get(PUBLISHED_AT) == null ? null : Instant.parse(rows.get(PUBLISHED_AT));
        } catch (DateTimeParseException e) {
            published = null;
        }
        String id = rows.get(LIBRARY_ID);
        return new SyncState(id == null || id.isBlank() ? null : id, generation, published, "1".equals(rows.get(DIRTY)));
    }

    /** The sync state of an open database. */
    public static SyncState read(Database db) {
        return db.read(SyncState::read);
    }

    /** The sync state of a database file, opened read-only (never created); NONE when there is no such file. */
    public static SyncState readFile(Path file) throws SQLException {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = config.createConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            return read(c);
        }
    }

    /** Sets one row (inserted when missing). */
    static void set(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO sync_state (key, value) VALUES (?, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
