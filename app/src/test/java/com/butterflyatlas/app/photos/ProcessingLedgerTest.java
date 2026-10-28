package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingLedgerTest {

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final ProcessingLedger ledger = new ProcessingLedger(clock);
    private Database db;
    private long a;
    private long b;
    private long c;

    @BeforeEach
    void setUp() {
        Library library = TestBundles.fixtureLibrary(dir);
        db = library.database();
        a = TestBundles.addImage(db, "A5");
        b = TestBundles.addImage(db, "A5");
        c = TestBundles.addImage(db, "A6");
    }

    private List<Long> pending(String consumer, int version, long after) {
        return db.read(tx -> ledger.pending(tx, consumer, version, after, 100));
    }

    private Map<Long, PhotoFacts> meta(List<Long> ids) {
        return db.read(tx -> ImageMeta.read(tx, ids));
    }

    private List<String> rows() {
        return db.read(tx -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement("SELECT image_id, consumer, version, status, coalesce(error, '-'),"
                    + " done_at FROM image_processing ORDER BY image_id, consumer");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getLong(1) + " " + rs.getString(2) + " v" + rs.getInt(3) + " " + rs.getString(4) + " "
                            + rs.getString(5) + " " + rs.getString(6));
                }
            }
            return rows;
        });
    }

    @Test
    void aPhotoIsPendingUntilItIsDoneAtTheConsumersVersion() {
        assertThat(pending("dimensions", 1, 0)).containsExactly(a, b, c);
        int count = db.read(tx -> ledger.countPending(tx, "dimensions", 1));
        assertThat(count).isEqualTo(3);

        db.write(tx -> {
            ledger.markDone(tx, a, "dimensions", 1);
            ledger.markDone(tx, List.of(b, c), "dimensions", 1);
            return null;
        });

        assertThat(pending("dimensions", 1, 0)).isEmpty();
        assertThat(pending("place", 1, 0)).as("each consumer has its own rows").containsExactly(a, b, c);
        assertThat(pending("dimensions", 2, 0)).as("a raised version processes every photo again").containsExactly(a, b, c);
        assertThat(rows()).contains(a + " dimensions v1 done - 2026-10-11T09:00:00Z");
    }

    @Test
    void aFailedPhotoIsPendingAgainButACursorVisitsEachPhotoOnce() {
        db.write(tx -> {
            ledger.markDone(tx, List.of(a, c), "dimensions", 1);
            ledger.markFailed(tx, b, "dimensions", 1, "x".repeat(2000));
            return null;
        });

        assertThat(pending("dimensions", 1, 0)).containsExactly(b);
        assertThat(pending("dimensions", 1, b)).as("after the cursor").isEmpty();
        assertThat(rows().get(1)).startsWith(b + " dimensions v1 failed xxx").hasSizeLessThan(600);

        db.write(tx -> {
            ledger.markDone(tx, b, "dimensions", 1);
            return null;
        });
        assertThat(rows().get(1)).isEqualTo(b + " dimensions v1 done - 2026-10-11T09:00:00Z");
    }

    private List<String> attempts() {
        return db.read(tx -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement("SELECT image_id, consumer, version, status, attempts"
                    + " FROM image_processing ORDER BY image_id, consumer");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getLong(1) + " " + rs.getString(2) + " v" + rs.getInt(3) + " " + rs.getString(4) + " x"
                            + rs.getInt(5));
                }
            }
            return rows;
        });
    }

    private List<Long> missing() {
        return db.read(tx -> ImageMeta.missing(tx, 0, 100));
    }

    private void fail(long id, String consumer, int version) {
        db.write(tx -> {
            ledger.markFailed(tx, id, consumer, version, "broken");
            return null;
        });
    }

    @Test
    void aPhotoThatFailedThreeTimesAtAVersionIsLeftUntilTheVersionChanges() {
        fail(a, "place", 1);
        fail(a, "place", 1);
        assertThat(attempts()).containsExactly(a + " place v1 failed x2");
        assertThat(pending("place", 1, 0)).as("tried twice: still pending").containsExactly(a, b, c);

        fail(a, "place", 1);

        assertThat(attempts()).containsExactly(a + " place v1 failed x3");
        assertThat(pending("place", 1, 0)).as("three failures: given up").containsExactly(b, c);
        int count = db.read(tx -> ledger.countPending(tx, "place", 1));
        assertThat(count).isEqualTo(2);
        assertThat(pending("place", 2, 0)).as("a new version tries again").containsExactly(a, b, c);
        fail(a, "place", 2);
        assertThat(attempts()).as("counted afresh at the new version").containsExactly(a + " place v2 failed x1");
    }

    @Test
    void aPhotoThatFailedAtAnotherVersionIsPendingAtThisOneHoweverOftenItFailed() {
        fail(a, "place", 2);
        fail(a, "place", 2);
        fail(a, "place", 2);
        assertThat(pending("place", 2, 0)).as("given up at version 2").containsExactly(b, c);

        assertThat(pending("place", 1, 0)).as("a row at a newer version is not this version's").containsExactly(a, b, c);
        int count = db.read(tx -> ledger.countPending(tx, "place", 1));
        assertThat(count).isEqualTo(3);
    }

    @Test
    void doneResetsTheAttemptsAndAFailureAfterwardsCountsFromOne() {
        fail(b, "thumbnail", 1);
        fail(b, "thumbnail", 1);
        db.write(tx -> {
            ledger.markDone(tx, b, "thumbnail", 1);
            return null;
        });
        assertThat(attempts()).containsExactly(b + " thumbnail v1 done x0");

        fail(b, "thumbnail", 1);

        assertThat(attempts()).containsExactly(b + " thumbnail v1 failed x1");
    }

    @Test
    void aPhotoWhoseDetailsCouldNotBeReadThreeTimesIsNoLongerMissingItsSnapshot() {
        assertThat(missing()).containsExactly(a, b, c);
        for (int i = 0; i < 3; i++) {
            fail(a, ImageMeta.DETAILS, ImageMeta.DETAILS_VERSION);
        }
        fail(b, ImageMeta.DETAILS, ImageMeta.DETAILS_VERSION);

        assertThat(missing()).containsExactly(b, c);
        int missing = db.read(ImageMeta::countMissing);
        assertThat(missing).isEqualTo(2);
    }

    @Test
    void photosInRecentlyDeletedAreNotPendingAndForgettingClearsEveryConsumer() {
        TestBundles.markDeleted(db, b);
        db.write(tx -> {
            ledger.markDone(tx, a, "dimensions", 1);
            ledger.markDone(tx, a, "thumbnail", 1);
            ledger.markDone(tx, c, "thumbnail", 1);
            ledger.forget(tx, a);
            return null;
        });

        assertThat(pending("dimensions", 1, 0)).containsExactly(a, c);
        assertThat(pending("thumbnail", 1, 0)).containsExactly(a);
        assertThat(rows()).containsExactly(c + " thumbnail v1 done - 2026-10-11T09:00:00Z");
    }

    @Test
    void rowsAndSnapshotsGoWithTheirPhoto() {
        PhotoFacts facts = PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, null, Map.of("Make", "Canon"), Map.of());
        db.write(tx -> {
            ledger.markDone(tx, a, "dimensions", 1);
            ImageMeta.write(tx, a, facts, Instant.parse("2026-10-11T09:00:00Z"));
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM image WHERE id = ?")) {
                ps.setLong(1, a);
                ps.executeUpdate();
            }
            return null;
        });

        assertThat(rows()).isEmpty();
        assertThat(meta(List.of(a))).isEmpty();
    }

    @Test
    void aPhotoPurgedMeanwhileIsSkipped() {
        PhotoFacts facts = PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, null, Map.of(), Map.of());
        db.write(tx -> {
            ledger.markDone(tx, List.of(a, 9999L), "dimensions", 1);
            ledger.markFailed(tx, 9998, "dimensions", 1, "gone");
            ImageMeta.write(tx, 9997, facts, Instant.parse("2026-10-11T09:00:00Z"));
            return null;
        });

        assertThat(rows()).containsExactly(a + " dimensions v1 done - 2026-10-11T09:00:00Z");
        assertThat(meta(List.of(9997L))).isEmpty();
    }

    @Test
    void theSnapshotIsStoredOncePerPhotoAndClearsAFailedDetailsRead() {
        PhotoFacts first = PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, null, Map.of(), Map.of());
        PhotoFacts second = PhotoFacts.of(PhotoFormat.JPEG, 20, 40, 6, null, null, Map.of(), Map.of());
        db.write(tx -> {
            ledger.markFailed(tx, a, ImageMeta.DETAILS, 1, "could not read");
            ImageMeta.write(tx, a, first, Instant.parse("2026-10-11T09:00:00Z"));
            ImageMeta.write(tx, a, second, Instant.parse("2026-10-11T10:00:00Z"));
            ImageMeta.write(tx, b, first, Instant.parse("2026-10-11T09:00:00Z"));
            ImageMeta.delete(tx, b);
            return null;
        });

        assertThat(meta(List.of(a, b, c))).containsExactly(Map.entry(a, second));
        assertThat(rows()).isEmpty();
        List<Long> missing = db.read(tx -> ImageMeta.missing(tx, 0, 10));
        int countMissing = db.read(ImageMeta::countMissing);
        assertThat(missing).containsExactly(b, c);
        assertThat(countMissing).isEqualTo(2);
    }
}
