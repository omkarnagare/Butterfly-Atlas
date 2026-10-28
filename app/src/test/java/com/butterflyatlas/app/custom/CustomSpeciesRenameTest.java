package com.butterflyatlas.app.custom;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonDetail;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.butterflyatlas.app.custom.CustomSpeciesTest.rows;
import static com.butterflyatlas.app.custom.CustomSpeciesTest.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Renaming a custom species (5a contract §3): its folder and photo files follow, all or nothing. */
class CustomSpeciesRenameTest {

    private static final String SANKARA = "images/Custom_additions/Neptis_sankara_[U1]/";
    private static final String SOMA = "images/Custom_additions/Neptis_soma_[U1]/";

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private Library library;
    private PhotoStore photos;
    private CustomSpecies custom;
    private int seed;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        photos = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, id -> { });
        custom = new CustomSpecies(() -> library, new TaxonService(() -> library.database()), clock);
        custom.add("A1", "Neptis sankara", "Broad-banded Sailer");
    }

    private Photo add(String taxonId) {
        clock.advance(Duration.ofMinutes(1));
        return photos.add(taxonId, new ByteArrayInputStream(TestImages.jpeg(40, 30, ++seed)), "image/jpeg", null, List.of())
                .photo();
    }

    private Path file(String relPath) {
        return library.folder().resolve(relPath);
    }

    private List<String> images() {
        return rows(library.database(), "SELECT id, rel_path, file_name, number, deleted_at IS NOT NULL FROM image ORDER BY id");
    }

    @Test
    void aCommonNameChangeMovesNothing() throws Exception {
        Photo photo = add("U1");
        List<String> before = images();

        TaxonDetail renamed = custom.rename("U1", "Neptis sankara", "Wide-band Sailer");

        assertThat(renamed.common()).isEqualTo("Wide-band Sailer");
        assertThat(renamed.folder()).isEqualTo(SANKARA);
        assertThat(images()).isEqualTo(before);
        assertThat(file(photo.relPath())).exists();
        assertThat(rows(library.database(), "SELECT kind, text FROM taxon_fts WHERE taxon_id = 'U1' ORDER BY kind"))
                .containsExactly("common|Wide-band Sailer", "name|Neptis sankara");
        assertThat(rows(library.database(), "SELECT kind, payload_json FROM change_log WHERE kind <> 'taxon.add'"
                + " AND kind <> 'photo.add' ORDER BY id")).containsExactly("taxon.rename|{\"id\":\"U1\",\"from\":\"Neptis sankara\","
                + "\"to\":\"Neptis sankara\",\"fromCommon\":\"Broad-banded Sailer\",\"toCommon\":\"Wide-band Sailer\"}");
    }

    @Test
    void nothingChangedLogsNothing() {
        custom.rename("U1", " neptis SANKARA ", "Broad-banded  Sailer");

        assertThat(rows(library.database(), "SELECT kind FROM change_log WHERE kind = 'taxon.rename'")).isEmpty();
    }

    @Test
    void aNewNameMovesThePhotosRenamesTheirFilesAndRemovesTheEmptyFolder() throws Exception {
        Photo first = add("U1");
        Photo second = add("U1");
        Photo handNamed = add("U1");
        Photo deleted = add("U1");
        byte[] firstBytes = Files.readAllBytes(file(first.relPath()));
        // A file the user renamed by hand (its record followed it): it keeps its name.
        Files.move(file(handNamed.relPath()), file(SANKARA + "my_best.jpg"));
        sql(library.database(), "UPDATE image SET rel_path = '" + SANKARA + "my_best.jpg', file_name = 'my_best.jpg', number = NULL"
                + " WHERE id = " + handNamed.id());
        photos.delete(List.of(deleted.id()));
        String deletedFile = rows(library.database(), "SELECT deleted_rel_path FROM image WHERE id = " + deleted.id()).getFirst();

        TaxonDetail renamed = custom.rename("U1", "neptis soma", null);

        assertThat(renamed.name()).isEqualTo("Neptis soma");
        assertThat(renamed.common()).isNull();
        assertThat(renamed.folder()).isEqualTo(SOMA);
        assertThat(images()).containsExactly(
                first.id() + "|" + SOMA + "Neptis_soma_01.jpg|Neptis_soma_01.jpg|1|0",
                second.id() + "|" + SOMA + "Neptis_soma_02.jpg|Neptis_soma_02.jpg|2|0",
                handNamed.id() + "|" + SOMA + "my_best.jpg|my_best.jpg|null|0",
                deleted.id() + "|" + SANKARA + "Neptis_sankara_04.jpg|Neptis_soma_04.jpg|4|1");
        assertThat(Files.readAllBytes(file(SOMA + "Neptis_soma_01.jpg"))).isEqualTo(firstBytes);
        assertThat(file(SOMA + "Neptis_soma_02.jpg")).exists();
        assertThat(file(SOMA + "my_best.jpg")).exists();
        assertThat(file(deletedFile)).as("its file in .deleted does not move").exists();
        assertThat(file(SANKARA)).doesNotExist();
        assertThat(rows(library.database(), "SELECT name, sort_key, folder_name FROM taxon WHERE id = 'U1'"))
                .containsExactly("Neptis soma|neptis soma|Neptis_soma_[U1]");
        assertThat(rows(library.database(), "SELECT kind, text FROM taxon_fts WHERE taxon_id = 'U1'")).containsExactly("name|Neptis soma");
        assertThat(rows(library.database(), "SELECT payload_json FROM change_log WHERE kind = 'photo.move' ORDER BY id")).containsExactly(
                "{\"id\":" + first.id() + ",\"taxonId\":\"U1\",\"fromPath\":\"" + SANKARA + "Neptis_sankara_01.jpg\","
                        + "\"toPath\":\"" + SOMA + "Neptis_soma_01.jpg\"}",
                "{\"id\":" + second.id() + ",\"taxonId\":\"U1\",\"fromPath\":\"" + SANKARA + "Neptis_sankara_02.jpg\","
                        + "\"toPath\":\"" + SOMA + "Neptis_soma_02.jpg\"}",
                "{\"id\":" + handNamed.id() + ",\"taxonId\":\"U1\",\"fromPath\":\"" + SANKARA + "my_best.jpg\","
                        + "\"toPath\":\"" + SOMA + "my_best.jpg\"}");
        assertThat(rows(library.database(), "SELECT payload_json FROM change_log WHERE kind = 'taxon.rename'")).containsExactly(
                "{\"id\":\"U1\",\"from\":\"Neptis sankara\",\"to\":\"Neptis soma\",\"fromCommon\":\"Broad-banded Sailer\","
                        + "\"toCommon\":null}");

        List<Photo> restored = photos.restore(List.of(deleted.id()));
        assertThat(restored.getFirst().relPath()).as("restore puts it in the new folder").isEqualTo(SOMA + "Neptis_soma_04.jpg");
        assertThat(file(SOMA + "Neptis_soma_04.jpg")).exists();
    }

    @Test
    void theOldFolderStaysWhenTheUserLeftAFileInIt() throws Exception {
        Photo photo = add("U1");
        Files.writeString(file(SANKARA + "notes.txt"), "seen at Tamhini");

        custom.rename("U1", "Neptis soma", null);

        assertThat(file(SOMA + "Neptis_soma_01.jpg")).exists();
        assertThat(file(photo.relPath())).doesNotExist();
        assertThat(file(SANKARA + "notes.txt")).hasContent("seen at Tamhini");
    }

    @Test
    void aTakenTargetIsRefusedAndNothingMoves() throws Exception {
        Photo photo = add("U1");
        Files.createDirectories(file(SOMA));
        Files.writeString(file(SOMA + "Neptis_soma_01.jpg"), "someone else's");
        List<String> before = images();

        assertThatThrownBy(() -> custom.rename("U1", "Neptis soma", null)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("file_exists");
            assertThat(e.getMessage()).isEqualTo("A file named Neptis_soma_01.jpg is already in the species folder.");
        });

        assertThat(images()).isEqualTo(before);
        assertThat(file(photo.relPath())).exists();
        assertThat(file(SOMA + "Neptis_soma_01.jpg")).hasContent("someone else's");
        assertThat(rows(library.database(), "SELECT name FROM taxon WHERE id = 'U1'")).containsExactly("Neptis sankara");
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM change_log WHERE kind IN ('taxon.rename', 'photo.move')"))
                .containsExactly("0");
    }

    @Test
    void twoPhotosGivenNamesEqualWithoutCaseAreRefused() throws Exception {
        add("U1");
        Photo handNamed = add("U1");
        // A file named by hand exactly as the first photo will be named after the rename, but for case.
        Files.move(file(handNamed.relPath()), file(SANKARA + "NEPTIS_SOMA_01.jpg"));
        sql(library.database(), "UPDATE image SET rel_path = '" + SANKARA + "NEPTIS_SOMA_01.jpg', file_name = 'NEPTIS_SOMA_01.jpg',"
                + " number = NULL WHERE id = " + handNamed.id());

        assertThatThrownBy(() -> custom.rename("U1", "Neptis soma", null)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("file_exists"));
        assertThat(file(SANKARA + "Neptis_sankara_01.jpg")).exists();
        assertThat(file(SANKARA + "NEPTIS_SOMA_01.jpg")).exists();
    }

    @Test
    void aMoveFailingHalfWayPutsEveryFileBack() throws Exception {
        Photo first = add("U1");
        Photo second = add("U1");
        Photo third = add("U1");
        Files.delete(file(second.relPath())); // its move fails after the first photo has moved
        List<String> before = images();

        assertThatThrownBy(() -> custom.rename("U1", "Neptis soma", null)).isInstanceOf(AtlasException.class)
                .hasMessage("The file of Neptis_sankara_02.jpg can no longer be found. Restart Butterfly Atlas so it can check the"
                        + " library, then try again.");

        assertThat(images()).isEqualTo(before);
        assertThat(file(first.relPath())).exists();
        assertThat(file(third.relPath())).exists();
        assertThat(file(SOMA)).as("the new folder it made is gone").doesNotExist();
        assertThat(rows(library.database(), "SELECT name, folder_name FROM taxon WHERE id = 'U1'"))
                .containsExactly("Neptis sankara|Neptis_sankara_[U1]");
        assertThat(rows(library.database(), "SELECT kind, text FROM taxon_fts WHERE taxon_id = 'U1' ORDER BY kind"))
                .containsExactly("common|Broad-banded Sailer", "name|Neptis sankara");
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM change_log WHERE kind IN ('taxon.rename', 'photo.move')"))
                .containsExactly("0");
    }

    @Test
    void aWriteFailingAfterEveryFileMovedPutsEveryFileBack() throws Exception {
        Photo first = add("U1");
        Photo second = add("U1");
        byte[] firstBytes = Files.readAllBytes(file(first.relPath()));
        List<String> before = images();
        // The last statement of the rename's write (its change_log entry, after every move) fails.
        sql(library.database(), "CREATE TRIGGER j5a_no_rename BEFORE INSERT ON change_log WHEN NEW.kind = 'taxon.rename'"
                + " BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");

        assertThatThrownBy(() -> custom.rename("U1", "Neptis soma", null)).hasStackTraceContaining("disk I/O error");

        assertThat(images()).isEqualTo(before);
        assertThat(Files.readAllBytes(file(first.relPath()))).isEqualTo(firstBytes);
        assertThat(file(second.relPath())).exists();
        assertThat(file(SOMA)).doesNotExist();
        assertThat(rows(library.database(), "SELECT name, sort_key, folder_name FROM taxon WHERE id = 'U1'"))
                .containsExactly("Neptis sankara|neptis sankara|Neptis_sankara_[U1]");
        assertThat(rows(library.database(), "SELECT kind, text FROM taxon_fts WHERE taxon_id = 'U1' ORDER BY kind"))
                .containsExactly("common|Broad-banded Sailer", "name|Neptis sankara");
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM change_log WHERE kind IN ('taxon.rename', 'photo.move')"))
                .containsExactly("0");
    }

    @Test
    void aFailedRenameLeavesAFolderThatWasThereBefore() throws Exception {
        Photo first = add("U1");
        Photo second = add("U1");
        Files.delete(file(second.relPath()));
        Files.createDirectories(file(SOMA));

        assertThatThrownBy(() -> custom.rename("U1", "Neptis soma", null)).isInstanceOf(AtlasException.class);

        assertThat(file(first.relPath())).exists();
        assertThat(file(SOMA)).as("not made by the rename: kept").isEmptyDirectory();
    }

    @Test
    void aBlockedOrInvalidNameIsRefused() {
        custom.add("A9", "Graphium eurypylus", null);

        assertThatThrownBy(() -> custom.rename("U1", "graphium EURYPYLUS", null)).isInstanceOfSatisfying(NameBlocked.class,
                e -> assertThat(e.code()).isEqualTo("name_exists"));
        assertThatThrownBy(() -> custom.rename("U1", "Danaus genutia", null)).isInstanceOfSatisfying(NameBlocked.class,
                e -> assertThat(e.taxon().id()).isEqualTo("A6"));
        assertThatThrownBy(() -> custom.rename("U1", "Neptis", null)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("invalid"));
        assertThat(rows(library.database(), "SELECT name FROM taxon WHERE id = 'U1'")).containsExactly("Neptis sankara");
    }

    @Test
    void onlyACustomSpeciesCanBeRenamed() {
        for (String id : new String[] {"A5", "A1", "U:butterflies", "G:butterflies", "U9", null}) {
            assertThatThrownBy(() -> custom.rename(id, "Neptis soma", null)).as(id).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        }
        assertThat(rows(library.database(), "SELECT name FROM taxon WHERE id = 'A5'")).containsExactly("Danaus chrysippus");
    }
}
