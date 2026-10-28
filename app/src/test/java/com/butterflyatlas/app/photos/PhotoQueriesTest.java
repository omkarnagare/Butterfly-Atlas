package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoQueries.DeletedPhoto;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoQueriesTest {

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private Library library;
    private PhotoStore store;
    private PhotoQueries queries;
    private int seed;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        store = new PhotoStore(() -> library, new ImageIoPhotoProcessor(), clock, id -> { });
        queries = new PhotoQueries(() -> library, clock);
    }

    private Photo add(String taxonId, String... tags) {
        clock.advance(Duration.ofMinutes(1));
        return store.add(taxonId, new ByteArrayInputStream(TestImages.jpeg(40, 30, ++seed)), "image/jpeg", null,
                List.of(tags)).photo();
    }

    private void sql(String sql, Object... args) {
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void markDeleted(long id, Instant at) {
        sql("UPDATE image SET deleted_at = ?, deleted_rel_path = rel_path WHERE id = ?", at.toString(), id);
    }

    @Test
    void theEarlyStagesAreTheEggLarvaAndPupaTags() {
        assertThat(PhotoQueries.Stages.EARLY_KEYS).containsExactly("egg", "larva", "pupa");
    }

    @Test
    void aSpeciesGalleryIsNewestFirstAndLiveOnly() {
        Photo first = add("A5");
        Photo second = add("A5", "larva");
        Photo third = add("A5");
        add("A6");
        markDeleted(third.id(), clock.instant());

        assertThat(queries.photos("A5")).extracting(Photo::id).containsExactly(second.id(), first.id());
        assertThat(queries.photos("A5").get(0).tags()).containsExactly("larva");
        assertThat(queries.photos("A4")).isEmpty();
        assertThatThrownBy(() -> queries.photos("A999")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
    }

    @Test
    void theCoverIsTheChoiceElseTheNewestAdultElseTheNewest() {
        Photo adult = add("A5", "male");
        Photo larva = add("A5", "larva");
        assertThat(queries.photo(adult.id()).isCover()).as("newest adult beats newer larva").isTrue();
        assertThat(queries.photo(larva.id()).isCover()).isFalse();

        sql("UPDATE taxon SET cover_image_id = ? WHERE id = 'A5'", larva.id());
        assertThat(queries.photo(larva.id()).isCover()).as("the user's choice wins").isTrue();
        assertThat(queries.photo(adult.id()).isCover()).isFalse();

        markDeleted(larva.id(), clock.instant());
        assertThat(queries.photo(adult.id()).isCover()).as("a deleted choice no longer counts").isTrue();

        Photo striped = add("A6", "egg");
        Photo stripedNewer = add("A6", "pupa");
        assertThat(queries.photo(stripedNewer.id()).isCover()).as("no adult: the newest").isTrue();
        assertThat(queries.photo(striped.id()).isCover()).isFalse();
    }

    @Test
    void recentlyDeletedShowsSpeciesAndDaysLeft() {
        Photo older = add("A5", "male");
        Photo newer = add("A6");
        markDeleted(older.id(), clock.instant().minus(Duration.ofDays(3)).minus(Duration.ofHours(1)));
        markDeleted(newer.id(), clock.instant());

        List<DeletedPhoto> deleted = queries.deleted();

        assertThat(deleted).extracting(DeletedPhoto::id).containsExactly(newer.id(), older.id());
        assertThat(deleted.get(0).daysLeft()).isEqualTo(30);
        assertThat(deleted.get(1).daysLeft()).isEqualTo(27);
        assertThat(deleted.get(1).taxon()).isEqualTo(new TaxonRef("A5", "Danaus chrysippus", "Plain Tiger"));
        assertThat(deleted.get(1).tags()).containsExactly("male");
        assertThat(deleted.get(1).relPath()).isEqualTo(older.relPath());
        assertThat(deleted.get(1).isCover()).isFalse();
        assertThat(deleted.get(1).v()).isEqualTo(older.v());
        assertThat(store.deletedCount()).isEqualTo(2);
        assertThatThrownBy(() -> queries.photo(older.id())).isInstanceOf(AtlasException.class);
    }

    @Test
    void recentlyDeletedKeepsEachPhotoWithItsOwnSpecies() {
        Photo a1 = add("A5", "larva");
        Photo b1 = add("A6", "male", "pupa");
        Photo a2 = add("A5");
        markDeleted(a1.id(), clock.instant().minus(Duration.ofDays(1)));
        markDeleted(b1.id(), clock.instant().minus(Duration.ofDays(2)));
        markDeleted(a2.id(), clock.instant());

        List<DeletedPhoto> deleted = queries.deleted();

        assertThat(deleted).extracting(DeletedPhoto::id).containsExactly(a2.id(), a1.id(), b1.id());
        assertThat(deleted).extracting(d -> d.taxon().id()).containsExactly("A5", "A5", "A6");
        assertThat(deleted.get(2).taxon().name()).isEqualTo("Danaus genutia");
        assertThat(deleted.get(2).tags()).containsExactly("pupa", "male");
        assertThat(deleted.get(1).tags()).containsExactly("larva");
        assertThat(deleted.get(0).tags()).isEmpty();
    }

    @Test
    void aChosenCoverThatMovedToAnotherSpeciesIsNoLongerTheCover() {
        Photo older = add("A5");
        Photo chosen = add("A5", "larva");
        add("A5");
        Photo newest = add("A5");
        sql("UPDATE taxon SET cover_image_id = ? WHERE id = 'A5'", chosen.id());
        assertThat(queries.photo(chosen.id()).isCover()).isTrue();

        sql("UPDATE image SET taxon_id = 'A6' WHERE id = ?", chosen.id());

        assertThat(queries.photo(chosen.id()).isCover()).as("it is A6's newest photo now").isTrue();
        assertThat(queries.photo(newest.id()).isCover()).as("A5 falls back to its newest").isTrue();
        assertThat(queries.photo(older.id()).isCover()).isFalse();
        PhotoQueries.Cover cover = library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT parent_id FROM taxon WHERE id = 'A5'");
                 java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return PhotoQueries.covers(c, rs.getString(1)).get("A5");
            }
        });
        assertThat(cover.id()).isEqualTo(newest.id());
    }

    @Test
    void daysLeftRoundUpAndNeverGoNegative() {
        Instant now = Instant.parse("2026-10-11T09:00:00Z");

        assertThat(PhotoQueries.daysLeft(now, now)).isEqualTo(30);
        assertThat(PhotoQueries.daysLeft(now.minus(Duration.ofDays(29).plusHours(23)), now)).isEqualTo(1);
        assertThat(PhotoQueries.daysLeft(now.minus(Duration.ofDays(31)), now)).isZero();
    }

    @Test
    void originalsComeWithTheirContentType() throws Exception {
        Photo jpeg = add("A5");
        Photo webp = store.add("A5", new ByteArrayInputStream(TestImages.webp()), "image/webp", null, List.of()).photo();

        PhotoQueries.Original original = queries.original(jpeg.id());

        assertThat(original.contentType()).isEqualTo("image/jpeg");
        assertThat(original.file()).isEqualTo(library.folder().resolve(jpeg.relPath()));
        assertThat(queries.original(webp.id()).contentType()).isEqualTo("image/webp");
        assertThatThrownBy(() -> queries.original(999)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        Files.delete(original.file());
        assertThatThrownBy(() -> queries.original(jpeg.id())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("The file of this photo can no longer be found."));
    }
}
