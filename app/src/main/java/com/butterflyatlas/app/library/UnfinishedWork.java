package com.butterflyatlas.app.library;

import com.butterflyatlas.app.imports.ImportStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
     * kept wherever they went (nothing is removed). Then the deleted imports' previews are removed; one that can't be is
     * logged and left to the startup clean-up. Returns what it cleared.
     */
    static Counts clear(Library library, Clock clock) {
        String now = clock.instant().toString();
        List<Long> imports = new ArrayList<>();
        Counts cleared = library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM import_run WHERE " + OPEN_IMPORT);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    imports.add(rs.getLong(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM import_run WHERE " + OPEN_IMPORT)) {
                ps.executeUpdate();
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
            return new Counts(imports.size(), exports, backups);
        });
        for (long id : imports) {
            deleteTree(ImportStore.previewFolder(library.folder(), id));
        }
        if (!cleared.none()) {
            LOG.info("Cleared unfinished work from where this library was used before: {} import(s), {} export(s),"
                    + " {} backup(s)", cleared.imports(), cleared.exports(), cleared.backups());
        }
        return cleared;
    }

    private static void deleteTree(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.warn("Could not remove the previews in {}; the next start removes them", folder, e);
        }
    }
}
