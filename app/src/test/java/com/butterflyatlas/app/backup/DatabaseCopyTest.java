package com.butterflyatlas.app.backup;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The database copy (design §6): SQLite's online backup while the library is in use, read back before it counts. */
class DatabaseCopyTest {

    @TempDir
    Path home;

    private long count(Path file, String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void copiesALiveDatabaseConsistentlyIntoAFileInAFolderWithSpaces() throws Exception {
        Library library = TestBundles.fixtureLibrary(home.resolve("My Atlas"));
        Database db = library.database();
        for (int i = 0; i < 5; i++) {
            TestBundles.addImage(db, "A5");
        }
        Path folder = Files.createDirectories(home.resolve("a backup folder"));
        Path part = folder.resolve("atlas.db.part");

        DatabaseCopy.copy(db, part);

        assertThat(count(part, "image")).isEqualTo(5);
        assertThat(count(part, "taxon")).isEqualTo(count(db.file(), "taxon"));
        assertThat(Files.size(part)).isPositive();
    }

    @Test
    void aGoodCopyIsVerifiedWithoutLeavingAnythingBesideIt() throws Exception {
        Library library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        TestBundles.addImage(library.database(), "A5");
        Path folder = Files.createDirectories(home.resolve("backup"));
        Path part = folder.resolve("atlas.db.part");
        DatabaseCopy.copy(library.database(), part);
        Path copy = folder.resolve("atlas.db");
        Files.move(part, copy);

        DatabaseCopy.verify(copy);

        try (var names = Files.list(folder)) {
            assertThat(names.map(p -> p.getFileName().toString()).toList()).containsExactly("atlas.db");
        }
    }

    @Test
    void aCopyThatIsDamagedIsRefusedInPlainWords() throws Exception {
        Library library = TestBundles.fixtureLibrary(home.resolve("Atlas"));
        for (int i = 0; i < 50; i++) {
            TestBundles.addImage(library.database(), "A5");
        }
        Path folder = Files.createDirectories(home.resolve("backup"));
        Path copy = folder.resolve("atlas.db");
        DatabaseCopy.copy(library.database(), copy);
        try (RandomAccessFile file = new RandomAccessFile(copy.toFile(), "rw")) {
            long size = Files.size(copy);
            file.seek(size / 4);
            byte[] garbage = new byte[(int) (size / 2)]; // the middle of the file
            java.util.Arrays.fill(garbage, (byte) 0x5a);
            file.write(garbage);
        }

        assertThatThrownBy(() -> DatabaseCopy.verify(copy)).isInstanceOf(DatabaseCopy.CopyException.class)
                .hasMessageContaining("could not be read back");
    }

    @Test
    void aFileThatIsNotADatabaseIsRefusedToo() throws Exception {
        Path copy = Files.writeString(home.resolve("atlas.db"), "just text, not a database ".repeat(200));

        assertThatThrownBy(() -> DatabaseCopy.verify(copy)).isInstanceOf(DatabaseCopy.CopyException.class);
    }

    @Test
    void aMissingCopyIsRefused() {
        assertThatThrownBy(() -> DatabaseCopy.verify(home.resolve("nothing.db")))
                .isInstanceOf(DatabaseCopy.CopyException.class);
        assertThat(List.of(home.toFile().list())).doesNotContain("nothing.db"); // verifying never makes a file
    }
}
