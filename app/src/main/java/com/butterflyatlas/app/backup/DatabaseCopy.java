package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.db.Database;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The database's copy for a backup (design §6): SQLite's online backup, made while the app runs (never a plain file copy of
 * a live database), into a {@code .part} the caller puts in place; and the check that it can be read back, which a backup
 * needs before it counts (audit 8): opened read-only, it passes {@code integrity_check}. {@link #sanitise} takes the
 * backup's own record out of the copy first. Publishing a library's snapshot (one-device design §5) makes the same copy.
 */
public final class DatabaseCopy {

    /** A copy that cannot be used, in plain words. */
    public static final class CopyException extends Exception {

        CopyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DatabaseCopy() {
    }

    /**
     * Makes a consistent copy of the open database at {@code target} (a file that does not exist yet), as one file: the
     * library keeps a write-ahead log, which the copy does not need (it would be two more files beside it when opened).
     */
    public static void copy(Database db, Path target) throws SQLException {
        String path = target.toAbsolutePath().toString();
        if (path.indexOf('\'') >= 0) {
            throw new SQLException("The backup's path has a quote in it: " + path);
        }
        db.read(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("backup to \"" + path + "\"");
            }
            return null;
        });
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode = DELETE");
        }
    }

    /**
     * Takes this backup's own record out of the copy before it is read back (a restored library must not show a stuck "Backup
     * stopped" card, nor resume into itself): the backup's row with its files and problems, and the last-backup settings
     * (the library the copy came from keeps them), and its sync facts but {@code dirty}, so a restored backup is a library of
     * its own. Other, finished backups stay as the history they are.
     */
    static void sanitise(Path copy, long runId) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copy.toAbsolutePath())) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM backup_file WHERE run_id = " + runId);
                st.executeUpdate("DELETE FROM backup_problem WHERE run_id = " + runId);
                st.executeUpdate("DELETE FROM backup_run WHERE id = " + runId);
                st.executeUpdate("DELETE FROM library_setting WHERE key IN ('backup.last_at', 'backup.last_what',"
                        + " 'backup.last_unread')");
                // one device at a time (its design §5): a restored backup is a library of its own, given a new ID and
                // generation 1 when it is first opened, so it never stands in for the library it was copied from
                if (hasSyncState(c)) {
                    st.executeUpdate("DELETE FROM sync_state WHERE key IN ('library_id', 'generation', 'published_at')");
                    st.executeUpdate("UPDATE sync_state SET value = '0' WHERE key = 'dirty'");
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static boolean hasSyncState(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sync_state'")) {
            return rs.next();
        }
    }

    /** Opens the copy read-only and checks it; throws when it is missing, is not a database, or fails the check. */
    public static void verify(Path copy) throws CopyException {
        if (!Files.isRegularFile(copy)) {
            throw new CopyException("The copy of the database could not be read back: it is not there.", null);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = config.createConnection("jdbc:sqlite:" + copy.toAbsolutePath());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            boolean ok = rs.next() && "ok".equals(rs.getString(1));
            if (!ok) {
                throw new CopyException("The copy of the database could not be read back: it did not pass SQLite's own check.",
                        null);
            }
        } catch (SQLException e) {
            throw new CopyException("The copy of the database could not be read back: " + e.getMessage(), e);
        }
    }
}
