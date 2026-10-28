package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageMetaTest {

    @TempDir
    Path dir;

    private Database db;
    private long a;
    private long b;
    private long c;
    private final PhotoFacts facts = PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 1, null, null, Map.of(), Map.of());

    @BeforeEach
    void setUp() {
        db = TestBundles.fixtureLibrary(dir).database();
        a = TestBundles.addImage(db, "A5");
        b = TestBundles.addImage(db, "A5");
        c = TestBundles.addImage(db, "A6");
    }

    private void store(long id, String json) {
        db.write(tx -> {
            try (PreparedStatement ps = tx.prepareStatement("INSERT OR REPLACE INTO image_meta (image_id, json, read_at)"
                    + " VALUES (?, ?, '2026-10-11T09:00:00Z')")) {
                ps.setLong(1, id);
                ps.setString(2, json);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private List<Long> missing(long after, int limit) {
        return db.read(tx -> ImageMeta.missing(tx, after, limit));
    }

    private void write(long id) {
        db.write(tx -> {
            ImageMeta.write(tx, id, facts, Instant.parse("2026-10-11T09:00:00Z"));
            return null;
        });
    }

    @Test
    void subjectsComeInIdOrderAndLeaveOutPhotosWithoutASnapshot() {
        write(c);
        write(a);

        List<PhotoConsumer.Subject> subjects = db.read(tx -> ImageMeta.subjects(tx, dir, List.of(c, b, a)));

        assertThat(subjects).extracting(PhotoConsumer.Subject::imageId).containsExactly(a, c);
    }

    @Test
    void anUnreadableSnapshotIsDroppedSoThePhotoIsReadAgainAndTheOthersStillCome() {
        write(a);
        store(b, "{not json");
        store(c, "{\"v\":99,\"format\":\"JPEG\"}");

        List<PhotoConsumer.Subject> subjects = db.read(tx -> ImageMeta.subjects(tx, dir, List.of(a, b, c)));

        assertThat(subjects).extracting(PhotoConsumer.Subject::imageId).containsExactly(a);
        assertThat(missing(0, 10)).containsExactly(b, c);
        int countMissing = db.read(ImageMeta::countMissing);
        assertThat(countMissing).isEqualTo(2);
    }

    @Test
    void readDropsAnUnreadableSnapshotToo() {
        write(a);
        store(b, "{\"v\":99}");

        Map<Long, PhotoFacts> read = db.read(tx -> ImageMeta.read(tx, List.of(a, b)));

        assertThat(read).containsOnlyKeys(a);
        assertThat(missing(0, 10)).containsExactly(b, c);
    }

    @Test
    void missingSkipsRecentlyDeletedPhotosAndRespectsTheCursor() {
        TestBundles.markDeleted(db, b);

        assertThat(missing(0, 10)).containsExactly(a, c);
        assertThat(missing(a, 10)).containsExactly(c);
        assertThat(missing(0, 1)).containsExactly(a);
        int countMissing = db.read(ImageMeta::countMissing);
        assertThat(countMissing).isEqualTo(2);
    }

    @Test
    void markingNoPhotoDoneChangesNothing() {
        ProcessingLedger ledger = new ProcessingLedger(new TestClock());

        db.write(tx -> {
            ledger.markDone(tx, List.of(), "dimensions", 1);
            return null;
        });

        int pending = db.read(tx -> ledger.countPending(tx, "dimensions", 1));
        assertThat(pending).isEqualTo(3);
    }

    @Test
    void writeIfMissingNeverReplacesASnapshotAndSkipsPurgedPhotos() {
        store(a, "{\"kept\":true}");
        PhotoFacts other = PhotoFacts.of(PhotoFormat.JPEG, 99, 77, 1, null, null, Map.of(), Map.of());

        boolean[] written = db.write(tx -> new boolean[] {
                ImageMeta.writeIfMissing(tx, a, other, Instant.parse("2026-10-12T09:00:00Z")),
                ImageMeta.writeIfMissing(tx, b, other, Instant.parse("2026-10-12T09:00:00Z")),
                ImageMeta.writeIfMissing(tx, 9999, other, Instant.parse("2026-10-12T09:00:00Z"))});

        assertThat(written).containsExactly(false, true, false);
        String kept = db.read(tx -> {
            try (PreparedStatement ps = tx.prepareStatement("SELECT json FROM image_meta WHERE image_id = ?")) {
                ps.setLong(1, a);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        assertThat(kept).isEqualTo("{\"kept\":true}");
        Map<Long, PhotoFacts> stored = db.read(tx -> ImageMeta.read(tx, List.of(b)));
        assertThat(stored.get(b).width()).isEqualTo(99);
        int countMissing = db.read(ImageMeta::countMissing);
        assertThat(countMissing).as("only c is still missing").isEqualTo(1);
    }
}
