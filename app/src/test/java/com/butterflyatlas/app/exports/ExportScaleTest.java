package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestLocations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thousands of photos (4b contract §3): the start answers quickly (its writes batched), {@code /api/exports} and
 * {@code /api/status} reads stay fast while the copy runs, and the counts come out right. Times are logged.
 */
class ExportScaleTest {

    private static final int PHOTOS = 5_000;
    /** The design's target for the start, and the CI budgets: the start, and each read while copying. */
    private static final long START_TARGET_MS = 1_000;
    private static final long START_BUDGET_MS = 5_000;
    private static final long READ_BUDGET_MS = 500;
    /** The photos of the check's timing (the design's large library), and its CI budget. */
    private static final int CHECKED = 50_000;
    private static final long CHECK_BUDGET_MS = 3_000;
    /**
     * The CI budget of a Remove of every photo (on the request thread): 30 s on Windows, whose file system is slower to
     * remove files (CI took 26.8 s once), 5 s elsewhere.
     */
    private static final long REMOVE_BUDGET_MS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
            ? 30_000 : 5_000;
    /** How long the copy of the photos may take at most before the test gives up. */
    private static final Duration COPY_WAIT = Duration.ofSeconds(120);

    @TempDir
    Path home;

    private ExportFixture f;
    private long bytes;

    @BeforeEach
    void open() throws Exception {
        f = new ExportFixture(home);
        byte[] plain = TestImages.jpeg(8, 6, 1);
        byte[] located = TestLocations.jpeg();
        String[] species = {"A5", "A6", "A8", "A12", "A13", "A14"};
        Path images = f.library.folder().resolve("images");
        for (String s : species) {
            Files.createDirectories(images.resolve(s));
        }
        f.db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, number, file_size,"
                    + " modified_at, sha256, added_at, source) VALUES (?, ?, ?, ?, ?, 't', ?, 't', 'app')")) {
                for (int n = 1; n <= PHOTOS; n++) {
                    String s = species[n % species.length];
                    byte[] content = n % 10 == 0 ? located : plain;
                    String name = s + "_" + n + ".jpg";
                    Files.write(images.resolve(s).resolve(name), content);
                    ps.setString(1, s);
                    ps.setString(2, "images/" + s + "/" + name);
                    ps.setString(3, name);
                    ps.setInt(4, n);
                    ps.setLong(5, content.length);
                    ps.setString(6, "sha" + n);
                    ps.addBatch();
                    bytes += content.length;
                }
                ps.executeBatch();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        });
    }

    @AfterEach
    void close() {
        f.exports.close();
    }

    private static <T> T timed(String what, long budget, Supplier<T> call) {
        return timed(what, 0, budget, call);
    }

    /** Times the call, logs it with the design's target (0: none) and the CI budget, and holds it to the budget. */
    private static <T> T timed(String what, long target, long budget, Supplier<T> call) {
        long start = System.nanoTime();
        T answer = call.get();
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("export scale: %-60s %5d ms (%sbudget %d ms)%n", what, ms,
                target > 0 ? "target " + target + " ms, " : "", budget);
        assertThat(ms).as(what + " took " + ms + " ms").isLessThan(budget);
        return answer;
    }

    @Test
    void fiveThousandPhotosStartQuicklyReadFastWhileCopyingAndCountRight() throws Exception {
        Exports.Export started = timed("start of " + PHOTOS + " photos (folder, CSV, run and items)", START_TARGET_MS,
                START_BUDGET_MS,
                () -> f.exports.start(new Exports.Request("", "both", f.dest.toString(), null, true, "none")));

        assertThat(started.total()).isEqualTo(PHOTOS);
        assertThat(started.bytesTotal()).isEqualTo(bytes);
        List<Integer> seen = new ArrayList<>();
        long[] slowest = new long[2];
        long copyStart = System.nanoTime();
        long deadline = copyStart + COPY_WAIT.toNanos();
        while (f.exports.current().isPresent()) {
            assertThat(System.nanoTime()).as("the copy finishes within " + COPY_WAIT).isLessThan(deadline);
            long at = System.nanoTime();
            Exports.Exported list = f.exports.list();
            slowest[0] = Math.max(slowest[0], (System.nanoTime() - at) / 1_000_000);
            at = System.nanoTime();
            f.exports.status();
            slowest[1] = Math.max(slowest[1], (System.nanoTime() - at) / 1_000_000);
            if (list.current() != null) {
                seen.add(list.current().done());
                assertThat(list.current().done()).isBetween(0, PHOTOS);
            }
            Thread.sleep(100);
        }
        assertThat(f.exports.awaitIdle(COPY_WAIT)).isTrue();
        System.out.printf("export scale: copy of %d photos (1 in 10 with a location)                 %5d ms%n", PHOTOS,
                (System.nanoTime() - copyStart) / 1_000_000);
        System.out.printf("export scale: slowest of %d reads while copying: list %d ms, status %d ms (budget %d ms)%n",
                seen.size(), slowest[0], slowest[1], READ_BUDGET_MS);
        assertThat(Math.max(slowest[0], slowest[1])).isLessThan(READ_BUDGET_MS);

        Exports.Export done = Exports.Export.of(f.exports.store().run(started.id()));
        assertThat(done.state()).isEqualTo("done");
        assertThat(List.of(done.copied(), done.done(), done.skipped(), done.failed())).containsExactly(PHOTOS, PHOTOS, 0, 0);
        assertThat(seen).as("progress was seen part-way, in batches, never going back").isSorted();
        assertThat(seen.stream().anyMatch(n -> n > 0 && n < PHOTOS)).isTrue();
        Path folder = Path.of(done.path());
        try (Stream<Path> files = Files.walk(folder)) {
            List<Path> written = files.filter(Files::isRegularFile).toList();
            assertThat(written).hasSize(PHOTOS + 1); // and species-list.csv
            assertThat(done.bytesDone()).as("done = total, so bytesDone = bytesTotal").isEqualTo(bytes)
                    .isEqualTo(done.bytesTotal());
        }
    }

    @Test
    void removingFiveThousandCopiedPhotosIsQuick() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == PHOTOS - 1) {
                f.exports.pause(f.exports.current().orElseThrow().id());
            }
        });
        long id = f.exports.start(new Exports.Request("", "both", f.dest.toString(), null, false, "none")).id();
        assertThat(f.exports.awaitIdle(COPY_WAIT)).isTrue();
        assertThat(f.exports.store().run(id).done()).isEqualTo(PHOTOS - 1);

        AtomicLong realPaths = new AtomicLong();
        ExportCleanup.onRealPathRead(realPaths::incrementAndGet);
        Exports.Export cancelled;
        try {
            cancelled = timed("Remove of " + (PHOTOS - 1) + " copied photos and the list", REMOVE_BUDGET_MS,
                    () -> f.exports.cancel(id, true));
        } finally {
            ExportCleanup.onRealPathRead(null);
        }
        long read = realPaths.get();
        System.out.printf("export scale: real paths read by that Remove                                %5d%n", read);

        assertThat(read).as("each folder's real path read once, not once per photo").isLessThan(200);
        assertThat(cancelled.notRemoved()).isZero();
        assertThat(Path.of(cancelled.path())).doesNotExist();
    }

    @Test
    void theCheckOfFiftyThousandPhotosAnswersQuickly() {
        f.db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, number, file_size,"
                    + " modified_at, sha256, added_at, source) VALUES (?, ?, ?, ?, 2000, 't', ?, 't', 'app')")) {
                for (int n = PHOTOS + 1; n <= CHECKED; n++) {
                    String s = n % 2 == 0 ? "A5" : "A12";
                    ps.setString(1, s);
                    ps.setString(2, "images/" + s + "/" + s + "_" + n + ".jpg");
                    ps.setString(3, s + "_" + n + ".jpg");
                    ps.setInt(4, n);
                    ps.setString(5, "sha" + n);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });

        for (String what : List.of("photos", "both")) {
            Exports.Check check = timed("check of " + CHECKED + " photos (" + what + ", species folders)", CHECK_BUDGET_MS,
                    () -> f.exports.check("", what, f.dest.toString()));
            assertThat(check.photos()).isEqualTo(CHECKED);
            assertThat(check.problem()).isNull();
        }
    }
}
