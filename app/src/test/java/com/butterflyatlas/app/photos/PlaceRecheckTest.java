package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The GPS re-check after a place update (5c contract §5): the catch-up looks every GPS photo up again from its details. */
class PlaceRecheckTest {

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
    private final PlaceBundles chain = UpdatePlaces.bundles(UpdatePlaces.fixture(), UpdatePlaces.fixtureNext());
    private Library library;
    private long alphaWest;
    private long alphaEast;
    private long islet;
    private long beta;
    private long westfar;
    private long alphaByHand;
    private long noGps;
    private int seed;

    private long add(PhotoStore store, double[] at) {
        clock.advance(Duration.ofMinutes(1));
        byte[] jpeg = TestImages.jpeg(32 + ++seed, 24, seed);
        if (at != null) {
            jpeg = TestExif.camera().gps(at[0], at[1], 10).jpeg(jpeg);
        }
        return store.add("A5", new ByteArrayInputStream(jpeg), "image/jpeg", null, List.of()).photo().id();
    }

    /** A library on the fixture list with GPS photos in every changed place, then opened with the next list (one step). */
    @BeforeEach
    void setUp() throws Exception {
        Path folder = dir.resolve("Atlas");
        Library first = Library.open(folder, TestBundles.fixture(), TestPlaces.bundles(), clock);
        PhotoStore store = new PhotoStore(() -> first, processor, clock, id -> { }, List.of(new Place(TestPlaces.bundles()
                .index())));
        alphaWest = add(store, new double[] {18.5, 73.25});
        alphaEast = add(store, new double[] {18.5, 73.75});
        islet = add(store, new double[] {18.5, 76.5});
        beta = add(store, new double[] {18.5, 74.5});
        westfar = add(store, new double[] {-14.5, -179.5});
        alphaByHand = add(store, new double[] {18.5, 73.25});
        new PhotoPlaces(() -> first, clock).place(List.of(alphaByHand), "P4", false);
        noGps = add(store, null);
        library = Library.open(folder, TestBundles.fixture(), chain, clock);
        assertThat(library.placeSteps()).containsExactly("2026-11-01");
    }

    private ConsumerRunner runner(int batch) {
        return new ConsumerRunner(() -> library, new PhotoIngest(processor), List.of(new Place(chain.index(),
                chain.position(chain.version()), clock)), null, clock, batch);
    }

    private List<String> rows(String sql) {
        return library.database().read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        row.append(i > 1 ? "|" : "").append(rs.getString(i));
                    }
                    rows.add(row.toString());
                }
            }
            return rows;
        });
    }

    private List<String> expectedMoves() {
        return List.of(
                "{\"photoId\":" + alphaWest + ",\"from\":\"P4\",\"to\":\"P11\",\"version\":\"2026-11-01\"}",
                "{\"photoId\":" + alphaEast + ",\"from\":\"P4\",\"to\":\"P12\",\"version\":\"2026-11-01\"}",
                "{\"photoId\":" + westfar + ",\"from\":\"P9\",\"to\":null,\"version\":\"2026-11-01\"}");
    }

    @Test
    void everyGpsPhotoIsLookedUpAgainFromItsDetailsWithoutReadingItsFileAndEachMoveIsLogged() throws Exception {
        assertThat(rows("SELECT id, place_id FROM image WHERE id IN (" + alphaWest + ", " + islet + ") ORDER BY id"))
                .as("after the step: the split leaves its photos, the merge carried Islet's").containsExactly(
                        alphaWest + "|P4", islet + "|P6");
        try (Stream<Path> files = Files.walk(library.folder().resolve("images"))) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }

        runner(200).run();

        assertThat(rows("SELECT id, place_id, place_source FROM image ORDER BY id")).containsExactly(
                alphaWest + "|P11|gps", alphaEast + "|P12|gps", islet + "|P6|gps", beta + "|P5|gps",
                westfar + "|null|null",                     // its country retired with its area: no place now
                alphaByHand + "|P4|user",                   // placed by hand: never re-checked
                noGps + "|null|null");
        assertThat(rows("SELECT payload_json FROM change_log WHERE kind = 'place.recheck' ORDER BY id"))
                .isEqualTo(expectedMoves());
        assertThat(rows("SELECT COUNT(*) FROM image_processing WHERE consumer = 'place' AND version = 2 AND status = 'done'"))
                .containsExactly("7");
    }

    @Test
    void aReCheckCutShortGoesOnFromItsLastBatchAndLogsEachMoveOnce() {
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicInteger commits = new AtomicInteger();
        TestDatabases.beforeCommit(library.database(), () -> {
            if (armed.get() && commits.incrementAndGet() == 3) {
                throw new AssertionError("the app was killed");
            }
        });

        assertThatThrownBy(() -> runner(1).run()).isInstanceOf(AssertionError.class);
        assertThat(rows("SELECT COUNT(*) FROM image_processing WHERE consumer = 'place' AND version = 2"))
                .as("two batches of one photo committed").containsExactly("2");
        armed.set(false);

        runner(1).run();

        assertThat(rows("SELECT payload_json FROM change_log WHERE kind = 'place.recheck' ORDER BY id"))
                .isEqualTo(expectedMoves());
        assertThat(rows("SELECT place_id FROM image WHERE id = " + alphaEast)).containsExactly("P12");
    }

    @Test
    void aPhotoTheReCheckCannotDoIsRecordedAsFailedAtTheCurrentVersion() {
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("CREATE TRIGGER fail_beta BEFORE UPDATE OF place_id ON image"
                    + " WHEN NEW.id = " + beta + " BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END")) {
                ps.executeUpdate();
            }
            return null;
        });

        runner(200).run();

        assertThat(rows("SELECT image_id, status FROM image_processing WHERE consumer = 'place' AND version = 2"
                + " AND status = 'failed'")).containsExactly(beta + "|failed");
        assertThat(rows("SELECT place_id FROM image WHERE id = " + beta)).as("it keeps its old place").containsExactly("P5");
    }
}
