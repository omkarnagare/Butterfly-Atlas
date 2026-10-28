package com.butterflyatlas.app.custom;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoStore;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.taxa.SearchWordService;
import com.butterflyatlas.app.taxa.TaxonService;
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

/** Deleting a custom species (5a contract §3): only once it has no photos at all. */
class CustomSpeciesDeleteTest {

    private static final String SANKARA = "images/Custom_additions/Neptis_sankara_[U1]/";

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

    private static String hasPhotos(Runnable delete) {
        try {
            delete.run();
        } catch (AtlasException e) {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("has_photos");
            return e.getMessage();
        }
        throw new AssertionError("not refused");
    }

    @Test
    void itIsRefusedWhileItHasPhotosLiveOrInRecentlyDeleted() {
        List<Photo> four = List.of(add("U1"), add("U1"), add("U1"), add("U1"));
        assertThat(hasPhotos(() -> custom.delete("U1"))).isEqualTo("Move its 4 photos first.");

        photos.delete(List.of(four.get(0).id()));
        assertThat(hasPhotos(() -> custom.delete("U1"))).isEqualTo("Move its 3 photos and empty or restore 1 in Recently deleted first.");

        photos.move(List.of(four.get(1).id(), four.get(2).id()), "A5");
        assertThat(hasPhotos(() -> custom.delete("U1"))).isEqualTo("Move its photo and empty or restore 1 in Recently deleted first.");

        photos.move(List.of(four.get(3).id()), "A5");
        assertThat(hasPhotos(() -> custom.delete("U1"))).isEqualTo("Empty or restore its photo in Recently deleted first.");

        assertThat(rows(library.database(), "SELECT COUNT(*) FROM taxon WHERE id = 'U1'")).containsExactly("1");
        photos.purge(List.of());
        custom.delete("U1");
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM taxon WHERE id = 'U1'")).containsExactly("0");
    }

    @Test
    void itGoesWithItsWordsSearchRowsAndEmptyFolderAndIsLogged() {
        new SearchWordService(() -> library.database(), clock).add("U1", "sailer of Tamhini");
        Photo photo = add("U1");
        photos.move(List.of(photo.id()), "A5");
        assertThat(library.folder().resolve(SANKARA)).isDirectory();

        custom.delete("U1");

        assertThat(library.folder().resolve(SANKARA)).doesNotExist();
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM taxon_fts WHERE taxon_id = 'U1'")).containsExactly("0");
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM taxon_synonym WHERE taxon_id = 'U1'")).containsExactly("0");
        assertThat(new SearchService(() -> library.database()).search("neptis", 20)).isEmpty();
        assertThat(rows(library.database(), "SELECT payload_json FROM change_log WHERE kind = 'taxon.delete'")).containsExactly(
                "{\"id\":\"U1\",\"name\":\"Neptis sankara\",\"common\":\"Broad-banded Sailer\",\"familyId\":\"A1\","
                        + "\"words\":[\"sailer of Tamhini\"]}");
        assertThat(custom.add("A1", "Neptis sankara", null).id()).as("its id is never given again").isEqualTo("U2");
    }

    @Test
    void aFolderStillHoldingAFileStays() throws Exception {
        Files.createDirectories(library.folder().resolve(SANKARA));
        Files.writeString(library.folder().resolve(SANKARA + "notes.txt"), "mine");

        custom.delete("U1");

        assertThat(library.folder().resolve(SANKARA + "notes.txt")).hasContent("mine");
    }

    @Test
    void importItemsThatNamedItForgetIt() {
        sql(library.database(), "INSERT INTO import_run (id, root_path, state, created_at, updated_at, next_seq) VALUES"
                + " (1, '/photos/a', 'open', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z', 5),"
                + " (2, '/photos/b', 'finished', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z', 9),"
                + " (3, '/photos/c', 'open', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z', 7)");
        sql(library.database(), "INSERT INTO import_item (id, run_id, rel_path, file_size, modified_at, read_state, taxon_id,"
                + " suggested_taxon_id, library_taxon_id, seq) VALUES"
                + " (1, 1, 'a.jpg', 1, 't', 'read', 'U1', NULL, NULL, 1),"
                + " (2, 1, 'b.jpg', 1, 't', 'read', 'A5', 'U1', NULL, 2),"
                + " (3, 1, 'c.jpg', 1, 't', 'read', 'A5', 'A5', NULL, 3),"
                + " (4, 2, 'd.jpg', 1, 't', 'read', NULL, NULL, 'U1', 4),"
                + " (5, 3, 'e.jpg', 1, 't', 'read', 'A6', NULL, NULL, 6)");

        custom.delete("U1");

        assertThat(rows(library.database(), "SELECT id, taxon_id, suggested_taxon_id, library_taxon_id, seq FROM import_item ORDER BY id"))
                .containsExactly("1|null|null|null|5", "2|A5|null|null|5", "3|A5|A5|null|3", "4|null|null|null|4", "5|A6|null|null|6");
        assertThat(rows(library.database(), "SELECT id, next_seq, updated_at FROM import_run ORDER BY id")).containsExactly(
                "1|6|" + clock.instant(), "2|9|2026-10-01T00:00:00Z", "3|7|2026-10-01T00:00:00Z");
    }

    @Test
    void onlyACustomSpeciesCanBeDeleted() {
        for (String id : new String[] {"A5", "A1", "U:butterflies", "G:butterflies", "U9", null}) {
            assertThatThrownBy(() -> custom.delete(id)).as(id).isInstanceOfSatisfying(AtlasException.class,
                    e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        }
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM taxon WHERE id IN ('A5', 'A1', 'U:butterflies')"))
                .containsExactly("3");
    }

    @org.junit.jupiter.api.Test
    void photoCountsInTheMessageHaveThousandsSeparators() {
        assertThat(CustomSpecies.its(1340)).as("fixes J11").isEqualTo("its 1,340 photos");
        assertThat(CustomSpecies.its(1)).isEqualTo("its photo");
    }
}
