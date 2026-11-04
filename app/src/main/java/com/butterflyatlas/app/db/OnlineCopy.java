package com.butterflyatlas.app.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A consistent copy of an open database (SQLite's online backup, made while the app runs, never a plain file copy of a
 * live database), as one file: a backup's database and a library's published snapshot (one-device design §5) are made so.
 */
public final class OnlineCopy {

    private OnlineCopy() {
    }

    /**
     * Makes a consistent copy of the open database at {@code target} (a file that does not exist yet), as one file: the
     * library keeps a write-ahead log, which the copy does not need (it would be two more files beside it when opened).
     */
    public static void copy(Database db, Path target) throws SQLException {
        String path = target.toAbsolutePath().toString();
        if (path.indexOf('\'') >= 0) {
            throw new SQLException("The copy's path has a quote in it: " + path);
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
}
