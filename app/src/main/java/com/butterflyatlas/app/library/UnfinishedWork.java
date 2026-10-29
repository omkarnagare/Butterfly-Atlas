package com.butterflyatlas.app.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;

/**
 * Work a library was left with where it was used before (setup design §3): imports not finished, and exports and
 * backups running or paused. Each keeps a full path of the computer it was started on, so opening the library from the
 * setup screen clears it ({@link #clear}); the setup screen says so first ({@link #count}).
 */
public final class UnfinishedWork {

    private static final Logger LOG = LoggerFactory.getLogger(UnfinishedWork.class);

    private static final String OPEN_IMPORT = "state = 'open'";
    private static final String RUNNING_OR_PAUSED = "state IN ('running', 'paused')";

    /** How many of each kind there are. */
    public record Counts(int imports, int exports, int backups) {

        public boolean none() {
            return imports == 0 && exports == 0 && backups == 0;
        }
    }

    private UnfinishedWork() {
    }

    /**
     * The unfinished work in the library file {@code database}, read without changing it (and without creating a file);
     * a table an older library does not have yet counts 0. Null when the file can't be read as a library.
     */
    static Counts count(Path database) {
        if (!Files.isRegularFile(database)) {
            return null;
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = config.createConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            return new Counts(count(c, "import_run", OPEN_IMPORT), count(c, "export_run", RUNNING_OR_PAUSED),
                    count(c, "backup_run", RUNNING_OR_PAUSED));
        } catch (SQLException e) {
            LOG.debug("Could not read the unfinished work of {}", database, e);
            return null;
        }
    }

    private static int count(Connection c, String table, String where) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    return 0;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Clears the library's unfinished work in one transaction (all or nothing): open imports are deleted as Discard
     * deletes one (photos already filed stay), running and paused exports and backups are cancelled with their files
     * kept wherever they went (nothing is removed). Returns what it cleared. The deleted imports' previews go when the
     * library opens next, as the previews of every import no longer open do (the imports' startup tidy).
     */
    static Counts clear(Library library, Clock clock) {
        String now = clock.instant().toString();
        Counts cleared = library.database().write(c -> {
            int imports;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM import_run WHERE " + OPEN_IMPORT)) {
                imports = ps.executeUpdate();
            }
            int exports;
            try (PreparedStatement ps = c.prepareStatement("UPDATE export_run SET state = 'cancelled', paused_by = NULL,"
                    + " notice = NULL, cancel_choice = 'keep', finished_at = ? WHERE " + RUNNING_OR_PAUSED)) {
                ps.setString(1, now);
                exports = ps.executeUpdate();
            }
            int backups;
            try (PreparedStatement ps = c.prepareStatement("UPDATE backup_run SET state = 'cancelled', paused_by = NULL,"
                    + " notice = NULL, cancel = 'keep', finished_at = ? WHERE " + RUNNING_OR_PAUSED)) {
                ps.setString(1, now);
                backups = ps.executeUpdate();
            }
            return new Counts(imports, exports, backups);
        });
        if (!cleared.none()) {
            LOG.info("Cleared unfinished work from where this library was used before: {} import(s), {} export(s),"
                    + " {} backup(s)", cleared.imports(), cleared.exports(), cleared.backups());
        }
        return cleared;
    }
}
