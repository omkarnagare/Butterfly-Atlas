package com.butterflyatlas.app.library;

import com.butterflyatlas.app.db.Database;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Each step of a library change that touches a drive (change-library-folder spec §5, §10), so tests can fail any one of
 * them: {@link #REAL} is the app's own. Every step writes to the new library folder only; the old library is only read.
 */
public interface ChangeSteps {

    /** Creates the new library folder (and its parents), or a folder inside it. */
    void createFolder(Path folder) throws IOException;

    /** Copies one file, written as {@code <name>.part} and renamed when complete, keeping its modified time. */
    void copyFile(Path source, Path target) throws IOException;

    /** A consistent copy of the open database at {@code target} ({@code VACUUM INTO}), as one file. */
    void copyDatabase(Database database, Path target) throws IOException;

    /** {@code PRAGMA integrity_check} on the copied database, opened read-only: null when it answers ok, else why not. */
    String integrity(Path database);

    /** Deletes one file or empty folder (a copy being removed). */
    void delete(Path path) throws IOException;

    /** The steps as the app runs them. */
    ChangeSteps REAL = new ChangeSteps() {

        @Override
        public void createFolder(Path folder) throws IOException {
            Files.createDirectories(folder);
        }

        @Override
        public void copyFile(Path source, Path target) throws IOException {
            Path part = target.resolveSibling(target.getFileName() + Library.PART);
            Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public void copyDatabase(Database database, Path target) throws IOException {
            Path part = target.resolveSibling(target.getFileName() + Library.PART);
            Files.deleteIfExists(part);
            try {
                database.read(c -> {
                    try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
                        ps.setString(1, part.toAbsolutePath().toString());
                        ps.execute();
                    }
                    return null;
                });
                try (Connection c = new SQLiteConfig().createConnection("jdbc:sqlite:" + part.toAbsolutePath());
                     Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode = DELETE"); // one file: the new copy sets its own up when it opens it
                }
            } catch (RuntimeException | SQLException e) {
                throw new IOException("Could not copy the database to " + part, e);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public String integrity(Path database) {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            try (Connection c = config.createConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                String answer = rs.next() ? rs.getString(1) : null;
                return "ok".equals(answer) ? null : String.valueOf(answer);
            } catch (SQLException e) {
                return e.getMessage();
            }
        }

        @Override
        public void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    };
}
