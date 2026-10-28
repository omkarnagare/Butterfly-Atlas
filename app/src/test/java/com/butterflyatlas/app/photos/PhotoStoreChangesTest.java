package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoStoreChangesTest {

    private static final String PLAIN = "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/";
    private static final String STRIPED = "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_genutia_[A6]/";

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

    private Path file(String relPath) {
        return library.folder().resolve(relPath);
    }

    private List<String> strings(String sql) {
        return library.database().read(c -> {
            List<String> values = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            return values;
        });
    }

    private void age(Path... files) throws Exception {
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(1)));
        for (Path f : files) {
            Files.setLastModifiedTime(f, old);
        }
    }

    private String deletedPath(long id) {
        return strings("SELECT deleted_rel_path FROM image WHERE id = " + id).get(0);
    }

    @Test
    void sweepRemovesOnlyWhatAPurgeLeftBehind() throws Exception {
        Photo parked = add("A5");
        Photo purged = add("A5");
        store.delete(List.of(parked.id(), purged.id()));
        Path kept = file(deletedPath(parked.id()));
        Path leftover = file(deletedPath(purged.id()));
        store.purge(List.of(purged.id()));
        Files.writeString(leftover, "a purge whose delete failed"); // the file the purge could not delete
        Path fresh = file(".deleted/78_Danaus_chrysippus_04.jpg");
        Files.writeString(fresh, "just arrived");
        Files.createDirectories(file(".deleted/folder"));
        age(kept, leftover);

        assertThat(store.sweepDeleted()).isEqualTo(1);

        assertThat(leftover).doesNotExist();
        assertThat(kept).as("its photo is in Recently deleted").exists();
        assertThat(fresh).as("younger than two minutes").exists();
        assertThat(file(".deleted/folder")).isDirectory();
        assertThat(store.restore(List.of(parked.id()))).hasSize(1);
    }

    @Test
    void sweepRemovesLeftoversAcrossSeveralShortTransactions() throws Exception {
        Files.createDirectories(file(".deleted"));
        int many = DeletedSweep.SWEEP_BATCH * 2 + 7;
        List<Path> leftovers = new ArrayList<>();
        library.database().write(c -> {
            for (int i = 0; i < many; i++) {
                ChangeLog.record(c, Instant.now(), ChangeLog.PURGE, ChangeLog.payload("id", 9000 + i, "fromPath",
                        ".deleted/" + (9000 + i) + "_gone.jpg"));
            }
            return null;
        });
        for (int i = 0; i < many; i++) {
            Path leftover = file(".deleted/" + (9000 + i) + "_gone.jpg");
            Files.writeString(leftover, "a purge whose delete failed");
            leftovers.add(leftover);
        }
        age(leftovers.toArray(Path[]::new));

        assertThat(store.sweepDeleted()).isEqualTo(many);

        assertThat(leftovers).allSatisfy(f -> assertThat(f).doesNotExist());
    }

    @Test
    void sweepKeepsAnUnknownFileAndAFileOfALivePhoto() throws Exception {
        Photo live = add("A5");
        Files.createDirectories(file(".deleted"));
        Path unknown = file(".deleted/77_lost.jpg");
        Path ofLive = file(".deleted/" + live.id() + "_" + live.fileName());
        Files.writeString(unknown, "no purge on record");
        Files.writeString(ofLive, "left by an undo that could not put it back");
        // Even with a purge on record for that exact path, a photo with that id still exists.
        library.database().write(c -> {
            ChangeLog.record(c, Instant.now(), ChangeLog.PURGE, ChangeLog.payload("id", 5000, "fromPath",
                    ".deleted/" + live.id() + "_" + live.fileName()));
            return null;
        });
        age(unknown, ofLive);

        assertThat(store.sweepDeleted()).isZero();

        assertThat(unknown).exists();
        assertThat(ofLive).exists();
    }

    @Test
    void aDeleteThatRollsBackLeavesNothingForTheSweep() throws Exception {
        Photo first = add("A5");
        Photo second = add("A5");
        Files.createDirectories(file(".deleted/" + second.id() + "_" + second.fileName() + "/inside")); // its move fails
        age(file(".deleted"));

        assertThatThrownBy(() -> store.delete(List.of(first.id(), second.id()))).isInstanceOf(AtlasException.class);

        assertThat(file(first.relPath())).as("put back before the lock was released").exists();
        assertThat(file(second.relPath())).exists();
        assertThat(file(".deleted/" + first.id() + "_" + first.fileName())).doesNotExist();
        assertThat(store.sweepDeleted()).isZero();
        assertThat(file(first.relPath())).exists();
    }

    @Test
    void sweepingWithoutARecentlyDeletedFolderDoesNothing() {
        assertThat(store.sweepDeleted()).isZero();
    }

    @Test
    void movingRenamesWithTheTargetsNextNumbers() {
        Photo a = add("A5", "male");
        Photo b = add("A5");
        add("A6");

        List<Photo> moved = store.move(List.of(a.id(), b.id()), "A6");

        assertThat(moved).extracting(Photo::fileName).containsExactly("Danaus_genutia_02.jpg", "Danaus_genutia_03.jpg");
        assertThat(moved).extracting(Photo::taxonId).containsOnly("A6");
        assertThat(moved.get(0).relPath()).isEqualTo(STRIPED + "Danaus_genutia_02.jpg");
        assertThat(moved.get(0).tags()).containsExactly("male");
        assertThat(file(moved.get(0).relPath())).exists();
        assertThat(file(a.relPath())).doesNotExist();
        assertThat(strings("SELECT next_photo_number FROM taxon WHERE id IN ('A5', 'A6') ORDER BY id")).containsExactly("3", "4");
        assertThat(queries.photos("A5")).isEmpty();
        assertThat(strings("SELECT kind FROM change_log")).containsExactly("photo.move", "photo.move");
        assertThat(strings("SELECT payload_json FROM change_log").get(0))
                .isEqualTo("{\"id\":" + a.id() + ",\"taxonId\":\"A6\",\"fromPath\":\"" + a.relPath() + "\",\"toPath\":\""
                        + STRIPED + "Danaus_genutia_02.jpg\"}");
    }

    @Test
    void aRawPhotoKeepsItsExtensionWhenItMovesOrItsSpeciesIsRenamed() throws Exception {
        byte[] cr3 = TestRaw.cr3(TestRaw.preview(), null, null, null, null);
        Photo raw = store.add("A5", new ByteArrayInputStream(cr3), "image/x-raw", "IMG_0001.CR3", List.of()).photo();
        assertThat(raw.fileName()).isEqualTo("Danaus_chrysippus_01.cr3");

        Photo moved = store.move(List.of(raw.id()), "A6").get(0);

        assertThat(moved.fileName()).isEqualTo("Danaus_genutia_01.cr3");
        assertThat(Files.readAllBytes(file(moved.relPath()))).isEqualTo(cr3);
        assertThat(PhotoNames.extension("IMG_0001.CR3")).isEqualTo("cr3");
        assertThat(SpeciesFiles.renamed("Danaus_genutia_01.cr3", 1, "Danaus genutia", "Danaus nova"))
                .isEqualTo("Danaus_nova_01.cr3");
    }

    @Test
    void manyPhotosMoveAndComeBackInOneCall() throws Exception {
        List<Long> ids = List.of(add("A5").id(), add("A5").id(), add("A5").id());

        assertThat(store.move(ids, "A6")).extracting(Photo::fileName)
                .containsExactly("Danaus_genutia_01.jpg", "Danaus_genutia_02.jpg", "Danaus_genutia_03.jpg");
        store.delete(ids);
        // Two of the old names are taken by hand-copied files meanwhile, so those two restore under new numbers.
        Files.writeString(file(STRIPED + "Danaus_genutia_01.jpg"), "by hand");
        Files.writeString(file(STRIPED + "Danaus_genutia_03.jpg"), "by hand");

        assertThat(store.restore(ids)).extracting(Photo::relPath)
                .containsExactly(STRIPED + "Danaus_genutia_04.jpg", STRIPED + "Danaus_genutia_02.jpg", STRIPED + "Danaus_genutia_05.jpg");
        assertThat(file(STRIPED + "Danaus_genutia_04.jpg")).exists();
        assertThat(file(STRIPED + "Danaus_genutia_05.jpg")).exists();
        assertThat(file(STRIPED + "Danaus_genutia_01.jpg")).hasContent("by hand");
        assertThat(file(STRIPED + "Danaus_genutia_03.jpg")).hasContent("by hand");
        assertThat(strings("SELECT next_photo_number FROM taxon WHERE id = 'A6'")).containsExactly("6");
    }

    @Test
    void movingClearsACoverThatPointedAtTheMovedPhoto() {
        Photo a = add("A5");
        add("A5");
        store.setCover("A5", a.id());

        store.move(List.of(a.id()), "A6");

        assertThat(strings("SELECT coalesce(cover_image_id, '-') FROM taxon WHERE id = 'A5'")).containsExactly("-");
        assertThat(queries.photo(a.id()).isCover()).as("only photo of A6, so its automatic cover").isTrue();
    }

    @Test
    void aFailedMovePutsEveryFileBack() {
        Photo a = add("A5");

        assertThatThrownBy(() -> store.move(List.of(a.id(), 999L), "A6")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));

        assertThat(file(a.relPath())).exists();
        assertThat(file(STRIPED + "Danaus_genutia_01.jpg")).doesNotExist();
        assertThat(queries.photo(a.id()).taxonId()).isEqualTo("A5");
        assertThatThrownBy(() -> store.move(List.of(a.id()), "A4")).isInstanceOf(AtlasException.class);
        assertThatThrownBy(() -> store.move(List.of(), "A6")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Choose at least one photo."));
        assertThat(store.move(List.of(a.id()), "A5").get(0).fileName()).isEqualTo("Danaus_chrysippus_01.jpg");
    }

    @Test
    void theCoverCanBeChosenAndCleared() {
        Photo a = add("A5");
        Photo b = add("A5");
        Photo striped = add("A6");

        assertThat(store.setCover("A5", a.id())).isEqualTo(a.id());
        assertThat(queries.photo(a.id()).isCover()).isTrue();
        assertThat(store.setCover("A5", null)).isNull();
        assertThat(queries.photo(b.id()).isCover()).isTrue();

        assertThatThrownBy(() -> store.setCover("A5", striped.id())).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("That photo is not of this species."));
        assertThat(strings("SELECT kind FROM change_log")).containsExactly("cover.set", "cover.set");
    }

    @Test
    void deletingMovesPhotosToRecentlyDeleted() {
        Photo a = add("A5");
        store.setCover("A5", a.id());

        assertThat(store.delete(List.of(a.id()))).isEqualTo(1);

        String parked = ".deleted/" + a.id() + "_Danaus_chrysippus_01.jpg";
        assertThat(file(a.relPath())).doesNotExist();
        assertThat(file(parked)).exists();
        assertThat(strings("SELECT deleted_at || '|' || deleted_rel_path FROM image"))
                .containsExactly("2026-10-11T09:01:00Z|" + parked);
        assertThat(strings("SELECT coalesce(cover_image_id, '-') FROM taxon WHERE id = 'A5'")).containsExactly("-");
        assertThat(queries.photos("A5")).isEmpty();
        assertThat(store.deletedCount()).isEqualTo(1);
        assertThat(new TaxonService(library::database).taxon("A5").species()).isEqualTo(new Count(0, 1));
        assertThat(strings("SELECT kind FROM change_log")).containsExactly("cover.set", "photo.delete");
        assertThatThrownBy(() -> store.delete(List.of(a.id()))).isInstanceOf(AtlasException.class);
    }

    @Test
    void restoringGivesThePhotoItsOldNameBack() {
        Photo a = add("A5", "larva");
        add("A5");
        store.delete(List.of(a.id()));

        List<Photo> restored = store.restore(List.of(a.id()));

        assertThat(restored.get(0).relPath()).isEqualTo(PLAIN + "Danaus_chrysippus_01.jpg");
        assertThat(restored.get(0).tags()).containsExactly("larva");
        assertThat(file(a.relPath())).exists();
        assertThat(store.deletedCount()).isZero();
        assertThat(strings("SELECT kind FROM change_log")).containsExactly("photo.delete", "photo.restore");
        assertThatThrownBy(() -> store.restore(List.of(a.id()))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("There is no photo " + a.id() + " in Recently deleted."));
    }

    @Test
    void aRestoredPhotoWhoseNameWasTakenGetsTheNextFreeNumber() throws Exception {
        Photo a = add("A5");
        add("A5");
        store.delete(List.of(a.id()));
        Files.writeString(file(PLAIN + "Danaus_chrysippus_01.jpg"), "copied in by hand meanwhile");

        Photo restored = store.restore(List.of(a.id())).get(0);

        assertThat(restored.fileName()).isEqualTo("Danaus_chrysippus_03.jpg");
        assertThat(Files.readString(file(PLAIN + "Danaus_chrysippus_01.jpg"))).isEqualTo("copied in by hand meanwhile");
        assertThat(strings("SELECT next_photo_number FROM taxon WHERE id = 'A5'")).containsExactly("4");
    }

    @Test
    void aPurgedIdIsNeverUsedAgain() {
        Photo newest = add("A5");
        store.delete(List.of(newest.id()));
        store.purge(List.of(newest.id()));

        Photo next = add("A5");

        assertThat(next.id()).isGreaterThan(newest.id());
    }

    @Test
    void purgingRemovesTheChosenPhotosForGood() {
        Photo a = add("A5", "male");
        Photo b = add("A5");
        Photo live = add("A5");
        store.delete(List.of(a.id(), b.id()));

        assertThat(store.purge(List.of(a.id(), live.id()))).isEqualTo(1);

        assertThat(file(".deleted/" + a.id() + "_Danaus_chrysippus_01.jpg")).doesNotExist();
        assertThat(file(".deleted/" + b.id() + "_Danaus_chrysippus_02.jpg")).exists();
        assertThat(strings("SELECT id FROM image ORDER BY id")).containsExactly(String.valueOf(b.id()), String.valueOf(live.id()));
        assertThat(strings("SELECT COUNT(*) FROM image_tag")).containsExactly("0");
        assertThat(strings("SELECT kind FROM change_log WHERE kind = 'photo.purge'")).hasSize(1);
    }

    @Test
    void anEmptyListEmptiesRecentlyDeleted() {
        Photo a = add("A5");
        Photo b = add("A6");
        add("A6");
        store.delete(List.of(a.id(), b.id()));

        assertThat(store.purge(List.of())).isEqualTo(2);

        assertThat(store.deletedCount()).isZero();
        assertThat(strings("SELECT COUNT(*) FROM image")).containsExactly("1");
    }

    @Test
    void onlyPhotosDeletedLongerAgoThanTheLimitArePurged() {
        Photo old = add("A5");
        store.delete(List.of(old.id()));
        clock.advance(Duration.ofDays(29));
        Photo recent = add("A5");
        store.delete(List.of(recent.id()));
        clock.advance(Duration.ofDays(2));

        assertThat(store.purgeOlderThan(PhotoQueries.KEEP_DELETED)).isEqualTo(1);
        assertThat(queries.deleted()).extracting(PhotoQueries.DeletedPhoto::id).containsExactly(recent.id());
        assertThat(store.purgeOlderThan(PhotoQueries.KEEP_DELETED)).isZero();
        assertThat(store.deletedCount()).isEqualTo(1);
    }

    @Test
    void aRestoredPhotoGoesToTheSpeciesCurrentFolder() throws Exception {
        Photo a = add("A5");
        store.delete(List.of(a.id()));
        Path oldFolder = file(PLAIN);
        Path newFolder = file("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_renamed/");
        Files.move(oldFolder, newFolder);
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE taxon SET folder_name = 'Danaus_chrysippus_renamed' WHERE id = 'A5'")) {
                ps.executeUpdate();
            }
            return null;
        });

        Photo restored = store.restore(List.of(a.id())).get(0);

        assertThat(restored.relPath()).isEqualTo("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_renamed/Danaus_chrysippus_01.jpg");
        assertThat(file(restored.relPath())).exists();
    }

    @Test
    void aPhotoWhoseFileWasRemovedByHandCanBeDeletedAndRestored() throws Exception {
        Photo a = add("A5");
        Files.delete(file(a.relPath()));

        assertThat(store.delete(List.of(a.id()))).isEqualTo(1);

        assertThat(strings("SELECT coalesce(deleted_rel_path, '-') FROM image")).containsExactly("-");
        assertThat(strings("SELECT payload_json FROM change_log WHERE kind = 'photo.delete'").get(0))
                .isEqualTo("{\"id\":" + a.id() + ",\"taxonId\":\"A5\",\"fromPath\":\"" + a.relPath()
                        + "\",\"toPath\":null,\"missing\":true}");
        assertThat(store.restore(List.of(a.id())).get(0).relPath()).isEqualTo(a.relPath());
        assertThat(file(a.relPath())).doesNotExist();
    }

    @Test
    void aMoveWhoseSecondFileIsGoneRollsTheWholeBatchBack() throws Exception {
        Photo a = add("A5");
        Photo b = add("A5");
        Files.delete(file(b.relPath()));

        assertThatThrownBy(() -> store.move(List.of(a.id(), b.id()), "A6")).isInstanceOfSatisfying(AtlasException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("The file of " + b.fileName() + " can no longer be found.");
                });

        assertThat(file(a.relPath())).exists();
        assertThat(file(STRIPED + "Danaus_genutia_01.jpg")).doesNotExist();
        assertThat(queries.photo(a.id()).taxonId()).isEqualTo("A5");
    }

    @Test
    void oneChangeLogShapePerKind() {
        Photo a = add("A5");
        store.setCover("A5", a.id());
        store.delete(List.of(a.id()));
        store.restore(List.of(a.id()));
        store.delete(List.of(a.id()));
        store.purge(List.of(a.id()));

        assertThat(strings("SELECT payload_json FROM change_log WHERE kind = 'cover.set'"))
                .containsExactly("{\"taxonId\":\"A5\",\"from\":null,\"to\":" + a.id() + "}");
        String parked = ".deleted/" + a.id() + "_Danaus_chrysippus_01.jpg";
        assertThat(strings("SELECT payload_json FROM change_log WHERE kind = 'photo.restore'"))
                .containsExactly("{\"id\":" + a.id() + ",\"taxonId\":\"A5\",\"fromPath\":\"" + parked
                        + "\",\"toPath\":\"" + a.relPath() + "\"}");
        assertThat(strings("SELECT payload_json FROM change_log WHERE kind = 'photo.purge'").get(0))
                .startsWith("{\"id\":" + a.id() + ",\"taxonId\":\"A5\",\"fromPath\":\"" + parked
                        + "\",\"toPath\":null,\"fileName\":\"Danaus_chrysippus_01.jpg\",\"sha256\":\"");
    }
}
