package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.ConsumerRunner;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The Home note of a place update and its part of /api/status (5c contract §6, §9). */
class PlaceNotesTest {

    @TempDir
    Path dir;

    private PlaceUpdateFixture f;
    private Library library;
    private ConsumerRunner runner;
    private PlaceNotes notes;
    private long westfarGps;

    /** The usual library, opened with the next place list (one step), and its re-check not run yet. */
    @BeforeEach
    void setUp() {
        f = new PlaceUpdateFixture(dir).populated();
        westfarGps = f.add("A13", new double[] {-14.5, -179.5});
        PlaceBundles chain = PlaceUpdateFixture.next();
        library = Library.open(f.library.folder(), TestBundles.fixture(), chain, f.clock);
        runner = new ConsumerRunner(() -> library, new PhotoIngest(new ImageIoPhotoProcessor()),
                List.of(new Place(chain.index(), 2, f.clock)), null, f.clock);
        notes = new PlaceNotes(() -> java.util.Optional.of(library.database()), 2, 3, f.clock);
        runner.onPlacesChecked(notes::changed);
        runner.onPlacesFinished(notes::placesFinished);
        notes.opened();
    }

    /** A count may still be reading the library; Windows can't delete the temp folder while it is open. */
    @AfterEach
    void tearDown() {
        notes.close();
    }

    private static PlaceNotes.PlaceRef ref(String id, String name, String level, boolean former, String... above) {
        List<String> path = new java.util.ArrayList<>(List.of(name));
        path.addAll(List.of(above));
        return new PlaceNotes.PlaceRef(id, name, path, level, former);
    }

    private static void eventually(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.get()).isTrue();
    }

    @Test
    void theNoteSaysWhatTouchesTheLibraryWithTheNamesBeforeTheStepAndTheUsersMergedPlace() {
        assertThat(notes.status()).as("the re-check counts as running from the opening").isEqualTo(
                new PlaceNotes.Status(true, 5, 0, 11, 0, 0, true, false));

        PlaceNotes.Note note = notes.note();

        assertThat(note.dismissed()).isFalse();
        assertThat(note.versions()).containsExactly("2026-11-01");
        assertThat(note.counts()).isEqualTo(new PlaceNotes.Counts(1, 1, 1, 1, 1, 0, 3));
        assertThat(note.items()).extracting(PlaceNotes.Item::type).containsExactly("split", "merge", "retire", "rename",
                "move");
        PlaceNotes.Item merge = note.items().get(1);
        assertThat(merge.from()).as("labelled, with its name before the step").containsExactly(
                ref("P7", "Islet (former district)", "district", true, "Northstate", "Testland"));
        assertThat(merge.to()).containsExactly(ref("P6", "Lakeland", "district", false, "Northstate", "Testland"));
        assertThat(merge.photos()).isEqualTo(2);
        assertThat(note.items().get(3).from()).as("a rename reads with the old name")
                .containsExactly(ref("P5", "Beta", "district", false, "Southstate", "Testland"));
        assertThat(note.items().get(3).to()).containsExactly(ref("P5", "Betania", "district", false, "Southstate", "Testland"));
        assertThat(note.mine()).containsExactly(new PlaceNotes.Mine("Alpha West",
                ref("P11", "Alpha West", "district", false, "Northstate", "Testland"), 1, false));
        assertThat(note.kept()).isEmpty();
        assertThat(note.lost()).isEmpty();
        assertThat(note.pending()).isEqualTo(11);
        assertThat(note.rechecking()).isTrue();
        assertThat(note.former().photos()).isEqualTo(5);
        assertThat(note.former().species()).extracting(s -> s.id() + " " + s.place().name() + " " + s.photos())
                .containsExactly("A5 Alpha (former district) 2", "A6 Alpha (former district) 1",
                        "A13 Westfar (former country) 1", "A12 Westfar (former country) 1");
        assertThat(note.moved()).isEqualTo(new PlaceNotes.Moved(0, List.of()));
        assertThat(note.notChecked()).isEmpty();
    }

    @Test
    void theReCheckMovesShowAsTheyGoAndTheStatusFollowsWithoutReadingTheDatabase() throws Exception {
        runner.run();

        eventually(() -> notes.status().moved() == 2 && !notes.status().rechecking());
        AtomicInteger opened = TestDatabases.countConnections(library.database());
        assertThat(notes.status()).as("Westfar's GPS photo left with no place").isEqualTo(
                new PlaceNotes.Status(true, 2, 0, 0, 2, 1, false, false));
        assertThat(opened.get()).as("a poll reads no database").isZero();
        PlaceNotes.Note note = notes.note();
        assertThat(note.moved().total()).as("a photo left with no place is lost, not moved").isEqualTo(2);
        assertThat(note.moved().groups()).allSatisfy(g -> assertThat(g.to()).isNotNull());
        assertThat(f.rows("SELECT rechecked_at IS NOT NULL FROM place_update")).containsExactly("1");
        assertThat(note.moved().groups()).contains(
                new PlaceNotes.MoveGroup(ref("P4", "Alpha (former district)", "district", true, "Northstate", "Testland"),
                        ref("P11", "Alpha West", "district", false, "Northstate", "Testland"), 1),
                new PlaceNotes.MoveGroup(ref("P4", "Alpha (former district)", "district", true, "Northstate", "Testland"),
                        ref("P12", "Alpha East", "district", false, "Northstate", "Testland"), 1));
        assertThat(note.former().species()).extracting(s -> s.id() + " " + s.photos()).containsExactly("A6 1", "A12 1");
        assertThat(note.lost()).containsExactly(new PlaceNotes.Lost(westfarGps, "A13", "Papilio demoleus",
                f.one("SELECT file_name FROM image WHERE id = " + westfarGps), ref("P9", "Westfar (former country)",
                "country", true)));
        assertThat(note.pending()).isZero();

        f.photoPlaces.place(List.of(westfarGps), null, true);
        assertThat(notes.note().lost()).as("its place removed by the user: not lost any more").isEmpty();
        assertThat(notes.status().lost()).isZero();
    }

    @Test
    void afterDismissOnlyTheFormerNotCheckedAndLostPartsStayAndTheyGoWhenNoPhotoIsLeft() throws Exception {
        runner.run();
        eventually(() -> !notes.status().rechecking());
        f.sql("UPDATE image_processing SET status = 'failed', error = 'disk I/O error', attempts = 3 WHERE consumer = 'place'"
                + " AND image_id = " + f.beta);
        notes.opened();

        notes.dismiss();

        assertThat(notes.status()).as("the lost photo was lost before the Dismiss").isEqualTo(
                new PlaceNotes.Status(false, 2, 1, 0, 0, 0, false, false));
        PlaceNotes.Note note = notes.note();
        assertThat(note.dismissed()).isTrue();
        assertThat(note.versions()).isEmpty();
        assertThat(note.items()).isEmpty();
        assertThat(note.former().photos()).isEqualTo(2);
        assertThat(note.notChecked()).containsExactly(new PlaceNotes.NotChecked(f.beta, "A8", "Tirumala limniace",
                f.one("SELECT file_name FROM image WHERE id = " + f.beta), "Your library couldn't be read or written.", true));
        assertThat(f.rows("SELECT dismissed_at IS NOT NULL FROM place_update")).containsExactly("1");

        f.place("P11", f.alphaByHand);
        f.place("P8", f.westfarByHand);
        f.sql("UPDATE image_processing SET status = 'done', error = NULL, attempts = 0 WHERE image_id = " + f.beta);

        assertThat(notes.note()).isNull();
        assertThat(notes.status()).isNull();
    }

    @Test
    void aUserPlaceUnderARetiredStateIsKeptThereAndTheReasonsAreInPlainWords() {
        PlaceUpdateFixture g = new PlaceUpdateFixture(dir.resolve("kept")).populated();
        long inCoast = g.add("A5", null);
        g.place(g.coast, inCoast);
        UpdatePlaces.Built v2 = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01").retire("P3").build();
        Library opened = Library.open(g.library.folder(), TestBundles.fixture(),
                UpdatePlaces.bundles(UpdatePlaces.fixture(), v2), g.clock);
        PlaceNotes kept = new PlaceNotes(() -> java.util.Optional.of(opened.database()), 2, 3, g.clock);
        kept.opened();

        assertThat(kept.note().kept()).containsExactly(new PlaceNotes.Kept("Coast",
                ref("P3", "Leafstate (former region)", "state", true, "Testland"), 1));
        assertThat(PlaceNotes.reason("SQLiteException: [SQLITE_FULL] database or disk is full"))
                .isEqualTo("The drive with your library was full.");
        assertThat(PlaceNotes.reason("SQLiteException: [SQLITE_BUSY] database is locked"))
                .isEqualTo("Another program was using your library.");
        assertThat(PlaceNotes.reason("IllegalStateException: The place boundaries could not be read: x"))
                .isEqualTo("The place boundaries couldn't be read.");
        assertThat(PlaceNotes.reason("NullPointerException")).isEqualTo("Something unexpected stopped it.");
    }

    @Test
    void aPhotoWhoseDetailsCanNotBeReadIsNotCheckedNotPendingAndTheReCheckStillFinishes() throws Exception {
        f.sql("DELETE FROM image_meta WHERE image_id = " + f.beta);
        java.nio.file.Files.delete(library.folder().resolve(f.one("SELECT rel_path FROM image WHERE id = " + f.beta)));

        runner.run();

        eventually(() -> !notes.status().rechecking());
        assertThat(notes.status()).isEqualTo(new PlaceNotes.Status(true, 2, 1, 0, 2, 1, false, false));
        assertThat(f.rows("SELECT rechecked_at IS NOT NULL FROM place_update")).containsExactly("1");
        assertThat(notes.note().notChecked()).containsExactly(new PlaceNotes.NotChecked(f.beta, "A8", "Tirumala limniace",
                f.one("SELECT file_name FROM image WHERE id = " + f.beta), "Its details couldn't be read.", false));

        f.sql("UPDATE image_processing SET attempts = 3 WHERE consumer = 'details' AND image_id = " + f.beta);
        assertThat(notes.note().notChecked()).extracting(PlaceNotes.NotChecked::last).as("the app has given up")
                .containsExactly(true);
    }

    @Test
    void aPhotoWhoseDetailsAreNotReadYetIsPendingAndTheReCheckIsNotOverWithoutIt() {
        f.sql("DELETE FROM image_meta WHERE image_id = " + f.beta);
        f.sql("UPDATE image_processing SET version = 2, status = 'done' WHERE consumer = 'place' AND image_id <> " + f.beta);
        notes.placesFinished();

        assertThat(notes.status().pending()).as("its details step has not run yet").isEqualTo(1);
        assertThat(notes.status().notChecked()).isZero();
        assertThat(f.rows("SELECT rechecked_at IS NULL FROM place_update")).as("not stamped while it waits").containsExactly("1");
    }

    @Test
    void theDetailsStepsLedgerKeyIsThePhotosOwn() {
        assertThat(PlaceNotes.DETAILS_KEY).isEqualTo(com.butterflyatlas.app.photos.ImageMeta.DETAILS);
        assertThat(PlaceNotes.DETAILS_VERSION).isEqualTo(com.butterflyatlas.app.photos.ImageMeta.DETAILS_VERSION);
    }

    @Test
    void aPlaceTheLibraryNoLongerHasIsNamedSoNotByItsId() {
        f.sql("DELETE FROM place WHERE id = 'P12'");

        PlaceNotes.Item split = notes.note().items().stream().filter(i -> i.type().equals("split")).findFirst().orElseThrow();

        assertThat(split.to()).contains(new PlaceNotes.PlaceRef("P12", "a place that no longer exists",
                List.of("a place that no longer exists"), null, false));
    }

    @Test
    void anOlderCountNeverLandsAfterANewerOne() throws Exception {
        java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean(true);
        notes.betweenReadAndStore(() -> {
            if (once.getAndSet(false)) {
                read.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread older = new Thread(notes::opened);
        older.start();
        read.await();

        Thread dismiss = new Thread(notes::dismiss);
        dismiss.start();
        Thread.sleep(200); // the Dismiss's count would land first if counts were not taken one at a time
        release.countDown();
        older.join();
        dismiss.join();

        assertThat(notes.status().note()).as("the Dismiss's count is the last").isFalse();
    }

    @Test
    void nothingIsCountedOrStartedWithoutAnOpenLibraryAndALibraryThatNeverHadAStepShowsNoReCheck() {
        PlaceNotes none = new PlaceNotes(java.util.Optional::empty, 2, 3, f.clock);
        none.changed();
        assertThat(none.status()).isNull();

        PlaceUpdateFixture first = new PlaceUpdateFixture(dir.resolve("first")).populated();
        PlaceNotes plain = new PlaceNotes(() -> java.util.Optional.of(first.db), 1, 3, f.clock);
        plain.opened();
        assertThat(plain.status()).as("the fixture library on its first list").isNull();
    }

    @Test
    void countsThatCannotBeReadAreSaidFailedKeepingTheLastOnes() {
        TestDatabases.failStatements(library.database(), () -> true);

        notes.opened();

        assertThat(notes.status()).isEqualTo(new PlaceNotes.Status(true, 5, 0, 11, 0, 0, true, true));
    }
}
