package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RAW photos in an export (7a contract §5): the check, the acceptance, exact copies, and the counts kept on the run. */
class ExportRawTest {

    private static final String TICK = "Tick the RAW photos box, or leave RAW photos out with File type.";

    @TempDir
    Path home;

    private ExportFixture f;
    private long jpeg;
    private long rawWithGps;
    private long rawWithout;
    private byte[] nef;

    @BeforeEach
    void open() {
        f = new ExportFixture(home);
        jpeg = f.photo("A5", "Danaus_chrysippus_01.jpg", TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(16, 12, 1)),
                null);
        nef = TestRaw.tiff(new TestRaw.Ifd().shortValue(0x0112, 1).jpeg(TestRaw.preview())
                .child(0x8825, TestRaw.gps(18.5, 73.5)));
        rawWithGps = f.photo("A5", "Danaus_chrysippus_02.nef", nef, null);
        rawWithout = f.photo("A12", "Papilio_polytes_01.cr3", TestRaw.cr3(TestRaw.preview(), null, null, null, null), null);
        f.sql("UPDATE image SET file_type = 'jpeg' WHERE id = " + jpeg);
        f.sql("UPDATE image SET file_type = 'raw' WHERE id IN (" + rawWithGps + ", " + rawWithout + ")");
        f.sql("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + rawWithGps
                + ", '{\"v\":1,\"format\":\"RAW\",\"gps\":{\"lat\":18.5,\"lon\":73.5}}', 't'), (" + rawWithout
                + ", '{\"v\":1,\"format\":\"RAW\"}', 't'), (" + jpeg + ", 'not json', 't')");
    }

    @AfterEach
    void close() {
        f.exports.close();
    }

    private List<String> rows(String sql) {
        return f.db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        });
    }

    @Test
    void theCheckCountsTheRawPhotosAndAsksForTheTickLast() {
        Exports.Check check = f.exports.check("", "photos", f.dest.toString());

        assertThat(check.raw()).isEqualTo(new Exports.Raw(2, 1, 0));
        assertThat(check.problem()).isEqualTo(new Exports.Refusal("raw_location", TICK));
        assertThat(f.exports.check("", "photos", f.dest.toString(), true).problem()).isNull();
        assertThat(f.exports.check("", "both", f.dest.toString()).problem().code()).isEqualTo("raw_location");
        assertThat(f.exports.check("", "csv", f.dest.toString()).problem()).as("no photos are copied").isNull();
        Exports.Check jpegsOnly = f.exports.check("types=jpeg", "photos", f.dest.toString());
        assertThat(jpegsOnly.raw()).isEqualTo(new Exports.Raw(0, 0, 0));
        assertThat(jpegsOnly.problem()).isNull();
        assertThat(f.exports.check("", "photos", "relative/path").problem().code()).as("folder problems first")
                .isEqualTo("invalid");
        f.free.set(1);
        assertThat(f.exports.check("", "photos", f.dest.toString()).problem().code()).as("space before the tick")
                .isEqualTo("no_space");
    }

    @Test
    void rawPhotosWhoseDetailsCouldNotBeReadAreCountedAsUnknown() {
        long noDetails = f.photo("A5", "Danaus_chrysippus_03.nef", nef, null);
        long damaged = f.photo("A5", "Danaus_chrysippus_04.nef", nef, null);
        f.sql("UPDATE image SET file_type = 'raw' WHERE id IN (" + noDetails + ", " + damaged + ")");
        f.sql("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + damaged + ", 'not json', 't')");

        Exports.Check check = f.exports.check("", "photos", f.dest.toString());

        assertThat(check.raw()).as("fixes J7: GPS unknown for two; withGps stays the known ones")
                .isEqualTo(new Exports.Raw(4, 1, 2));
    }

    @Test
    void aStartWithRawPhotosNeedsTheAcceptance() {
        assertThatThrownBy(() -> f.exports.start(f.request("", "photos"))).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("raw_location");
            assertThat(e.getMessage()).isEqualTo(TICK);
        });
        assertThat(f.dest.toFile().list()).as("nothing created").isEmpty();
        assertThat(rows("SELECT COUNT(*) FROM export_run")).containsExactly("0");
    }

    @Test
    void acceptedRawPhotosAreCopiedExactlyAndCounted() throws Exception {
        Exports.Request request = new Exports.Request("", "photos", f.dest.toString(), "flat", true, null, true);

        Exports.Export started = f.exports.start(request);

        assertThat(started.rawCount()).as("RAW photos copied: none yet (fixes J6)").isZero();
        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Exports.Export done = f.exports.list().past().get(0);
        assertThat(done.state()).isEqualTo("done");
        assertThat(done.copied()).isEqualTo(3);
        assertThat(done.rawCount()).isEqualTo(2);
        Path folder = Path.of(done.path());
        assertThat(Files.readAllBytes(folder.resolve("Danaus_chrysippus_02.nef"))).as("exactly as it is, location and all")
                .isEqualTo(nef);
        assertThat(Files.readAllBytes(folder.resolve("Danaus_chrysippus_01.jpg"))).as("a JPEG still loses its location")
                .isNotEqualTo(Files.readAllBytes(f.file(jpeg)));
        assertThat(rows("SELECT raw_with_location || '|' || raw_count FROM export_run")).containsExactly("1|2");
        assertThat(rows("SELECT image_id || '|' || raw FROM export_item ORDER BY seq"))
                .containsExactlyInAnyOrder(jpeg + "|0", rawWithGps + "|1", rawWithout + "|1");
    }

    @Test
    void acceptingWithNoRawPhotosIsIgnored() {
        Exports.Export started = f.exports.start(new Exports.Request("types=jpeg", "photos", f.dest.toString(), "flat", true,
                null, true));

        assertThat(started.rawCount()).isZero();
        assertThat(rows("SELECT raw_with_location FROM export_run")).containsExactly("1");
    }

    @Test
    void onlyRawPhotosActuallyCopiedAreCounted() throws Exception {
        Files.delete(f.file(rawWithout));

        f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null, true));

        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Exports.Export done = f.exports.list().past().get(0);
        assertThat(done.copied()).isEqualTo(2);
        assertThat(done.rawCount()).as("fixes J6: the RAW whose file was gone is not counted").isEqualTo(1);
    }

    @Test
    void aCancelledExportWithNoRawPhotoCopiedCountsNone() throws Exception {
        f.exports.beforePhoto(item -> {
            if (item.seq() == 1) {
                f.exports.pause(f.exports.current().orElseThrow().id());
            }
        });
        long id = f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null, true)).id();
        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        f.exports.beforePhoto(item -> { });
        assertThat(f.exports.status().orElseThrow().rawCount()).as("the status says what is copied so far").isZero();

        Exports.Export cancelled = f.exports.cancel(id, false);

        assertThat(cancelled.state()).isEqualTo("cancelled");
        assertThat(cancelled.copied()).as("the photo in hand when paused").isEqualTo(1);
        assertThat(rows("SELECT image_id || '|' || raw || '|' || state FROM export_item WHERE seq = 1"))
                .containsExactly(jpeg + "|0|done");
        assertThat(cancelled.rawCount()).as("nothing RAW copied: nothing to say (fixes J6)").isZero();
    }

    @Test
    void aRawWhoseFileIsNoLongerRawLosesItsLocationLikeAnyPhoto() throws Exception {
        byte[] located = TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(16, 12, 2));
        Files.write(f.file(rawWithGps), located); // replaced by a JPEG after the check, before its copy

        f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null, true));

        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Exports.Export done = f.exports.list().past().get(0);
        assertThat(done.copied()).isEqualTo(3);
        Path copy = Path.of(done.path()).resolve("Danaus_chrysippus_02.nef");
        assertThat(Files.readAllBytes(copy)).as("fixes J8: through the stripper").isNotEqualTo(located);
        assertThat(new com.butterflyatlas.app.imaging.CommonsMetadataStripper().hasLocation(copy)).isFalse();
        assertThat(done.rawCount()).as("only the RAW that went out as it is").isEqualTo(1);
        assertThat(rows("SELECT raw FROM export_item WHERE image_id = " + rawWithGps)).containsExactly("0");
    }
}
