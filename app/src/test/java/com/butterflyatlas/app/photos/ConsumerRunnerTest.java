package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.photos.consumers.Place;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerRunnerTest {

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicLong bytesRead = new AtomicLong();
    private Library library;
    private PhotoStore store;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        store = new PhotoStore(() -> library, processor, clock, id -> { });
    }

    /** Counts the streaming passes (opens) and the reads of facts. */
    private PhotoIngest countingIngest(PhotoProcessor with) {
        PhotoProcessor counting = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                reads.incrementAndGet();
                return with.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                with.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws IOException {
                return with.dominantColor(image);
            }
        };
        return new PhotoIngest(counting, PhotoStore.MAX_BYTES, file -> {
            opened.incrementAndGet();
            InputStream in = Files.newInputStream(file);
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) {
                        bytesRead.incrementAndGet();
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, len);
                    if (n > 0) {
                        bytesRead.addAndGet(n);
                    }
                    return n;
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        });
    }

    private ConsumerRunner runner(PhotoConsumer... consumers) {
        return new ConsumerRunner(() -> library, countingIngest(processor), List.of(consumers), null, clock);
    }

    private long add(byte[] bytes) {
        return store.add("A5", new ByteArrayInputStream(bytes), "image/jpeg", null, List.of()).photo().id();
    }

    /** As a library looks after the upgrade to 3b-1: photos without snapshots, ledger or (here) dimensions. */
    private void forgetEverything() {
        exec("DELETE FROM image_meta");
        exec("DELETE FROM image_processing");
        exec("UPDATE image SET width = NULL, height = NULL, orientation = NULL");
    }

    private void exec(String sql) {
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
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

    /** A fact consumer that records what it is given, and can be told to fail for one photo. */
    private static class Recorder implements PhotoConsumer {
        final List<Subject> seen = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();
        final String key;
        final int version;
        volatile long failFor = -1;

        Recorder(String key, int version) {
            this.key = key;
            this.version = version;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public Kind kind() {
            return Kind.FACTS;
        }

        @Override
        public void apply(List<Subject> batch, Connection tx) throws SQLException {
            calls.incrementAndGet();
            if (batch.stream().anyMatch(s -> s.imageId() == failFor)) {
                throw new SQLException("no place for photo " + failFor);
            }
            seen.addAll(batch);
        }
    }

    @Test
    void theFirstStartReadsEachPhotoOnceAndEveryConsumerGetsTheFacts() {
        long a = add(TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").gps(12.5, 77.25, 900).jpeg(TestImages.jpeg(40, 20)));
        long b = add(TestImages.withOrientation(TestImages.jpeg(40, 20, 2), 6));
        long c = add(TestImages.png(30, 10));
        forgetEverything();
        Recorder place = new Recorder("place", 1);
        ConsumerRunner runner = runner(new Dimensions(), place);

        runner.run();

        assertThat(opened).as("one pass per photo").hasValue(3);
        assertThat(reads).as("one read of facts per photo").hasValue(3);
        assertThat(strings("SELECT image_id FROM image_meta ORDER BY image_id")).containsExactly("" + a, "" + b, "" + c);
        assertThat(strings("SELECT width || 'x' || height || ' o' || orientation FROM image ORDER BY id"))
                .containsExactly("40x20 o1", "20x40 o6", "30x10 o1");
        assertThat(place.seen).extracting(PhotoConsumer.Subject::imageId).containsExactly(a, b, c);
        assertThat(place.seen.get(0).facts().gps().lat()).isEqualTo(12.5);
        assertThat(place.seen.get(0).file()).isEqualTo(library.folder().resolve(
                "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/Danaus_chrysippus_01.jpg"));
        assertThat(strings("SELECT consumer || ' ' || status FROM image_processing ORDER BY consumer, image_id"))
                .containsExactly("dimensions done", "dimensions done", "dimensions done", "place done", "place done", "place done");
        assertThat(runner.progress()).as("idle").isNull();

        runner.run();
        assertThat(reads).as("nothing is read again").hasValue(3);
        assertThat(place.seen).hasSize(3);
    }

    @Test
    void photosAddedBeforePlacesExistedGetThemAtTheNextStartFromTheirStoredDetails() throws Exception {
        long inAlpha = add(TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(40, 20, 1)));
        long noGps = add(TestImages.jpeg(40, 20, 2));
        long setByUser = add(TestExif.camera().gps(18.5, 74.5, 600).jpeg(TestImages.jpeg(40, 20, 3)));
        exec("UPDATE image SET place_source = 'user', place_id = 'P8' WHERE id = " + setByUser);
        // As a library looks after the upgrade to 3c-1: details and dimensions done, no place row; files out of reach.
        for (String relPath : strings("SELECT rel_path FROM image")) {
            Files.delete(library.folder().resolve(relPath));
        }

        runner(new Dimensions(), new Place(TestPlaces.bundles().index())).run();

        assertThat(opened).as("no file is read").hasValue(0);
        assertThat(strings("SELECT id || ' ' || coalesce(place_id, '-') || ' ' || coalesce(place_source, '-') FROM image"
                + " ORDER BY id")).containsExactly(inAlpha + " P4 gps", noGps + " - -", setByUser + " P8 user");
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'place' AND status = 'done'"))
                .containsExactly("3");
    }

    @Test
    void theDetailsStepReadsOnlyTheFirstBytesOfEachFileNotTheWholeFile() {
        List<byte[]> photos = List.of(TestImages.jpeg(400, 300, 1), TestImages.jpeg(400, 300, 2), TestImages.png(300, 200));
        for (byte[] bytes : photos) {
            assertThat(bytes.length).isGreaterThan(PhotoFormat.HEAD_BYTES * 10);
            add(bytes);
        }
        forgetEverything();
        // The facts come from a fake that never opens the file, so every byte counted is one the details step read itself.
        PhotoProcessor fake = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) {
                return PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, null, Map.of(), Map.of());
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) {
            }

            @Override
            public String dominantColor(Path image) {
                return "#000000";
            }
        };

        new ConsumerRunner(() -> library, countingIngest(fake), List.of(new Dimensions()), null, clock).run();

        assertThat(opened).as("one look per photo").hasValue(3);
        assertThat(reads).as("one read of facts per photo").hasValue(3);
        assertThat(bytesRead).as("only the first bytes of each file").hasValueLessThanOrEqualTo(3L * PhotoFormat.HEAD_BYTES);
        assertThat(strings("SELECT COUNT(*) FROM image_meta")).containsExactly("3");
    }

    @Test
    void aNewConsumerOrARaisedVersionCatchesUpFromTheSnapshotWithoutReadingFiles() {
        add(TestImages.jpeg(40, 20, 1));
        add(TestImages.jpeg(40, 20, 2));
        Recorder first = new Recorder("place", 1);

        runner(new Dimensions(), first).run();
        assertThat(first.seen).hasSize(2);
        Recorder raised = new Recorder("place", 2);
        runner(new Dimensions(), raised).run();

        assertThat(raised.seen).as("version 2 processes every photo again").hasSize(2);
        assertThat(reads).as("added photos already have snapshots").hasValue(0);
        assertThat(strings("SELECT DISTINCT version FROM image_processing WHERE consumer = 'place'")).containsExactly("2");
    }

    @Test
    void aFailingPhotoIsRecordedAndTriedAgainOnlyAtTheNextRun() {
        long a = add(TestImages.jpeg(40, 20, 1));
        long b = add(TestImages.jpeg(40, 20, 2));
        long c = add(TestImages.jpeg(40, 20, 3));
        Recorder place = new Recorder("place", 1);
        place.failFor = b;

        runner(place).run();

        assertThat(place.calls).as("the batch, then each photo once").hasValue(4);
        assertThat(place.seen).extracting(PhotoConsumer.Subject::imageId).containsExactly(a, c);
        assertThat(strings("SELECT status || ': ' || error FROM image_processing WHERE consumer = 'place' AND image_id = " + b))
                .singleElement().asString().startsWith("failed: ").contains("no place for photo " + b);

        place.failFor = -1;
        runner(place).run();
        assertThat(place.seen).extracting(PhotoConsumer.Subject::imageId).containsExactly(a, c, b);
        assertThat(strings("SELECT DISTINCT status FROM image_processing")).containsExactly("done");
    }

    @Test
    void anInterruptedRunCarriesOnWhereItStopped() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(add(TestImages.jpeg(40, 20, i)));
        }
        exec("DELETE FROM image_meta WHERE image_id IN (" + ids.get(3) + ", " + ids.get(4) + ")");
        exec("INSERT INTO image_processing (image_id, consumer, version, status, done_at) SELECT id, 'place', 1, 'done',"
                + " '2026-10-11T09:00:00Z' FROM image WHERE id <= " + ids.get(2));
        Recorder place = new Recorder("place", 1);

        runner(place).run();

        assertThat(reads).as("only the photos without a snapshot are read").hasValue(2);
        assertThat(place.seen).extracting(PhotoConsumer.Subject::imageId).containsExactly(ids.get(3), ids.get(4));
    }

    @Test
    void aPhotoThatCannotBeReadIsRecordedAndReadAgainAtTheNextStart() throws Exception {
        long a = add(TestImages.jpeg(40, 20, 1));
        Path file = library.folder().resolve(strings("SELECT rel_path FROM image").get(0));
        byte[] bytes = Files.readAllBytes(file);
        forgetEverything();
        Files.delete(file);

        runner(new Dimensions()).run();

        assertThat(strings("SELECT consumer || ' ' || status || ': ' || error FROM image_processing"))
                .singleElement().asString().startsWith("details failed: NoSuchFileException");
        assertThat(strings("SELECT COUNT(*) FROM image_meta")).containsExactly("0");

        Files.write(file, bytes);
        runner(new Dimensions()).run();
        assertThat(strings("SELECT image_id FROM image_meta")).containsExactly("" + a);
        assertThat(strings("SELECT consumer || ' ' || status FROM image_processing")).containsExactly("dimensions done");
    }

    @Test
    void aPhotoWhoseDetailsCannotBeReadIsTriedAtThreeStartsAndThenLeft() throws Exception {
        add(TestImages.jpeg(40, 20, 1));
        Path file = library.folder().resolve(strings("SELECT rel_path FROM image").get(0));
        forgetEverything();
        Files.delete(file);

        for (int start = 1; start <= 3; start++) {
            runner(new Dimensions()).run();
            assertThat(opened).as("start " + start + " tries the file").hasValue(start);
        }
        runner(new Dimensions()).run();

        assertThat(opened).as("the fourth start leaves it").hasValue(3);
        assertThat(strings("SELECT consumer || ' ' || status || ' ' || attempts FROM image_processing"))
                .containsExactly("details failed 3");
    }

    @Test
    void aConsumerThatKeepsFailingForAPhotoGivesUpAfterThreeRuns() {
        long a = add(TestImages.jpeg(40, 20, 1));
        Recorder place = new Recorder("place", 1);
        place.failFor = a;

        for (int run = 0; run < 4; run++) {
            runner(place).run();
        }

        assertThat(place.calls).as("three runs: the batch, then the photo alone").hasValue(6);
        assertThat(strings("SELECT status || ' ' || attempts FROM image_processing WHERE consumer = 'place'"))
                .containsExactly("failed 3");
    }

    @Test
    void progressNamesTheStepWithAFixedTotal() throws Exception {
        add(TestImages.jpeg(40, 20, 1));
        add(TestImages.jpeg(40, 20, 2));
        forgetEverything();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        PhotoProcessor slow = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                reading.countDown();
                try {
                    goOn.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) {
            }

            @Override
            public String dominantColor(Path image) {
                return "#000000";
            }
        };
        CountDownLatch applying = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        PhotoConsumer gated = new Recorder("place", 1) {
            @Override
            public void apply(List<Subject> batch, Connection tx) throws SQLException {
                applying.countDown();
                try {
                    finish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ConsumerRunner runner = new ConsumerRunner(() -> library, countingIngest(slow), List.of(new Dimensions(), gated), null, clock);
        Thread run = new Thread(runner::run);
        run.start();

        assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.progress()).isEqualTo(new ConsumerRunner.Progress("details", 0, 2));
        goOn.countDown();
        assertThat(applying.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.progress()).as("dimensions done, places under way: a step of their own")
                .isEqualTo(new ConsumerRunner.Progress("places", 0, 2));
        finish.countDown();
        run.join(10_000);
        assertThat(runner.progress()).isNull();
    }

    @Test
    void thePlaceConsumerReportsTheStepPlacesBatchByBatchAndOthersReportUpdate() {
        for (int i = 0; i < 5; i++) {
            add(TestImages.jpeg(40, 20, i));
        }
        exec("DELETE FROM image_processing");
        List<ConsumerRunner.Progress> reported = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<ConsumerRunner> runner = new AtomicReference<>();
        Recorder watchingPlace = new Recorder("place", 1) {
            @Override
            public void apply(List<Subject> batch, Connection tx) {
                reported.add(runner.get().progress());
            }
        };
        Recorder watchingOther = new Recorder("habitat", 1) {
            @Override
            public void apply(List<Subject> batch, Connection tx) {
                reported.add(runner.get().progress());
            }
        };
        runner.set(new ConsumerRunner(() -> library, countingIngest(processor), List.of(new Dimensions(), watchingPlace,
                watchingOther), null, clock, 2));

        runner.get().run();

        assertThat(reported).containsExactly(
                new ConsumerRunner.Progress("update", 5, 10), new ConsumerRunner.Progress("update", 7, 10),
                new ConsumerRunner.Progress("update", 9, 10),
                new ConsumerRunner.Progress("places", 0, 5), new ConsumerRunner.Progress("places", 2, 5),
                new ConsumerRunner.Progress("places", 4, 5));
        assertThat(runner.get().progress()).isNull();
    }

    @Test
    void detailsProgressAdvancesPhotoByPhotoWithinABatch() {
        for (int i = 0; i < 3; i++) {
            add(TestImages.jpeg(40, 20, i));
        }
        forgetEverything();
        AtomicReference<ConsumerRunner> runner = new AtomicReference<>();
        List<ConsumerRunner.Progress> seen = Collections.synchronizedList(new ArrayList<>());
        PhotoProcessor watching = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                seen.add(runner.get().progress());
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) {
            }

            @Override
            public String dominantColor(Path image) {
                return "#000000";
            }
        };
        runner.set(new ConsumerRunner(() -> library, countingIngest(watching), List.of(), null, clock));

        runner.get().run();

        assertThat(seen).as("one batch of three, counted as each photo is read").containsExactly(
                new ConsumerRunner.Progress("details", 0, 3),
                new ConsumerRunner.Progress("details", 1, 3),
                new ConsumerRunner.Progress("details", 2, 3));
    }

    @Test
    void boundariesThatCannotBeReadSkipThePlaceStepForThisRunWithoutUsingUpThePhotosAttempts() {
        long a = add(TestImages.jpeg(40, 20, 1));
        add(TestImages.jpeg(40, 20, 2));
        add(TestImages.jpeg(40, 20, 3));
        AtomicInteger tries = new AtomicInteger();
        PhotoConsumer place = new Recorder("place", 1) {
            @Override
            public void apply(List<Subject> batch, Connection tx) {
                tries.incrementAndGet();
                throw new IllegalStateException("The place boundaries could not be read: damaged");
            }
        };
        Recorder other = new Recorder("other", 1);

        new ConsumerRunner(() -> library, countingIngest(processor), List.of(place, other), null, clock, 1).run();

        assertThat(tries).as("the first batch only: no photo alone, no later batch").hasValue(1);
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'place'")).as("no attempt counted")
                .containsExactly("0");
        int pending = library.database().read(c -> new ProcessingLedger(clock).countPending(c, "place", 1));
        assertThat(pending).as("left for the next start").isEqualTo(3);
        assertThat(other.seen).as("other consumers still run").hasSize(3);

        new ConsumerRunner(() -> library, countingIngest(processor), List.of(place), null, clock, 1).run();
        assertThat(tries).as("tried again at the next run").hasValue(2);
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'place' AND image_id = " + a))
                .containsExactly("0");
    }

    @Test
    void aConsumerWhoseLedgerWritesFailDoesNotHoldBackTheNext() {
        long a = add(TestImages.jpeg(40, 20, 1));
        long b = add(TestImages.jpeg(40, 20, 2));
        exec("CREATE TRIGGER no_bad BEFORE INSERT ON image_processing WHEN NEW.consumer = 'bad'"
                + " BEGIN SELECT RAISE(ABORT, 'the ledger is full'); END");
        Recorder bad = new Recorder("bad", 1);
        Recorder good = new Recorder("good", 1);

        runner(bad, good).run();

        assertThat(good.seen).extracting(PhotoConsumer.Subject::imageId).containsExactly(a, b);
        assertThat(strings("SELECT consumer || ' ' || status FROM image_processing WHERE consumer IN ('bad', 'good')"
                + " ORDER BY consumer, image_id")).containsExactly("good done", "good done");
    }

    @Test
    void aSnapshotThatCannotBeWrittenIsRecordedWhileTheOtherPhotosAndBatchesCarryOn() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(add(TestImages.jpeg(40, 20, i)));
        }
        forgetEverything();
        long stuck = ids.get(1);
        exec("CREATE TRIGGER no_meta BEFORE INSERT ON image_meta WHEN NEW.image_id = " + stuck
                + " BEGIN SELECT RAISE(ABORT, 'no room for this snapshot'); END");

        new ConsumerRunner(() -> library, countingIngest(processor), List.of(new Dimensions()), null, clock, 2).run();

        assertThat(strings("SELECT image_id FROM image_meta ORDER BY image_id")).containsExactly(
                "" + ids.get(0), "" + ids.get(2), "" + ids.get(3), "" + ids.get(4));
        assertThat(strings("SELECT consumer || ' ' || status || ': ' || error FROM image_processing WHERE image_id = " + stuck))
                .singleElement().asString().startsWith("details failed: ").contains("no room for this snapshot");
        assertThat(strings("SELECT COUNT(*) FROM image_processing WHERE consumer = 'dimensions' AND status = 'done'"))
                .containsExactly("4");
    }

    @Test
    void aSecondRunDuringTheThumbnailCatchUpReturnsAtOnce() throws Exception {
        add(TestImages.jpeg(40, 20, 1));
        CountDownLatch catchingUp = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        try (Thumbnails thumbnails = new Thumbnails(() -> {
            catchingUp.countDown();
            try {
                goOn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return library;
        }, processor)) {
            ConsumerRunner runner = new ConsumerRunner(() -> library, countingIngest(processor), List.of(), thumbnails, clock);
            Thread first = new Thread(runner::run);
            first.start();
            try {
                assertThat(catchingUp.await(10, TimeUnit.SECONDS)).isTrue();
                Thread second = new Thread(runner::run);
                second.start();
                second.join(5_000);
                assertThat(second.isAlive()).as("the second run returned at once").isFalse();
            } finally {
                goOn.countDown();
                first.join(10_000);
            }
        }
    }

    @Test
    void aSnapshotWrittenWhileTheFileWasReadIsKept() {
        long a = add(TestImages.jpeg(40, 20, 1));
        forgetEverything();
        PhotoProcessor racing = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                library.database().write(c -> {
                    ImageMeta.write(c, a, PhotoFacts.of(PhotoFormat.JPEG, 99, 77, 1, null, null, Map.of(), Map.of()),
                            Instant.parse("2020-01-01T00:00:00Z"));
                    return null;
                });
                return processor.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                processor.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws IOException {
                return processor.dominantColor(image);
            }
        };

        new ConsumerRunner(() -> library, countingIngest(racing), List.of(new Dimensions()), null, clock).run();

        assertThat(strings("SELECT read_at FROM image_meta")).containsExactly("2020-01-01T00:00:00Z");
        assertThat(strings("SELECT width || 'x' || height FROM image")).as("consumers use the kept snapshot").containsExactly("99x77");
    }

    @Test
    void aStepThatFailsDoesNotStopTheNext() throws Exception {
        long a = add(TestImages.jpeg(40, 20, 1));
        try (Thumbnails thumbnails = new Thumbnails(() -> library, processor)) {
            thumbnails.await(a, Duration.ofSeconds(10)).orElseThrow();
            forgetEverything();
            exec("ALTER TABLE image_meta RENAME TO image_meta_away");

            new ConsumerRunner(() -> library, countingIngest(processor), List.of(new Recorder("place", 1)), thumbnails, clock).run();

            assertThat(strings("SELECT consumer || ' ' || status FROM image_processing"))
                    .as("details and update fail, the thumbnail catch-up still runs").containsExactly("thumbnail done");
        }
    }
}
