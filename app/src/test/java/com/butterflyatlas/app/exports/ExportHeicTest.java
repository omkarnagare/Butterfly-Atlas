package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestHeif;
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

/**
 * HEIC photos in an export (Phase 7b contract §6, audit 17, 23): the check's {@code heic}, one tick for RAW and HEIC
 * with the words of what is there, {@code acceptedKinds}, exact copies, {@code heicCount}, and files that changed kind.
 */
class ExportHeicTest {

    @TempDir
    Path home;

    private ExportFixture f;
    private long jpeg;
    private long heicWithGps;
    private long heicWithout;
    private byte[] heif;

    @BeforeEach
    void open() {
        f = new ExportFixture(home);
        jpeg = f.photo("A5", "Danaus_chrysippus_01.jpg", TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(16, 12, 1)),
                null);
        heif = TestHeif.still(64, 32).exif(TestExif.camera().gps(18.5, 73.5, 600).tiffBlock()).build();
        heicWithGps = f.photo("A5", "Danaus_chrysippus_02.heic", heif, null);
        heicWithout = f.photo("A12", "Papilio_polytes_01.heic", TestHeif.still(64, 32).build(), null);
        f.sql("UPDATE image SET file_type = 'jpeg' WHERE id = " + jpeg);
        f.sql("UPDATE image SET file_type = 'heic' WHERE id IN (" + heicWithGps + ", " + heicWithout + ")");
        f.sql("INSERT INTO image_meta (image_id, json, read_at) VALUES (" + heicWithGps
                + ", '{\"v\":1,\"format\":\"HEIC\",\"gps\":{\"lat\":18.5,\"lon\":73.5}}', 't'), (" + heicWithout
                + ", '{\"v\":1,\"format\":\"HEIC\"}', 't')");
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

    private long addRaw() {
        long raw = f.photo("A5", "Danaus_chrysippus_03.nef", TestRaw.tiff(new TestRaw.Ifd().jpeg(TestRaw.preview())), null);
        f.sql("UPDATE image SET file_type = 'raw' WHERE id = " + raw);
        return raw;
    }

    @Test
    void theCheckCountsHeicPhotosAndTheTickNamesWhatIsThere() {
        Exports.Check check = f.exports.check("", "photos", f.dest.toString());

        assertThat(check.heic()).isEqualTo(new Exports.Raw(2, 1, 0));
        assertThat(check.raw()).isEqualTo(new Exports.Raw(0, 0, 0));
        assertThat(check.problem()).isEqualTo(new Exports.Refusal("raw_location",
                "Tick the HEIC photos box, or leave HEIC photos out with File type."));
        assertThat(f.exports.check("", "photos", f.dest.toString(), true).problem()).isNull();
        assertThat(f.exports.check("types=jpeg", "photos", f.dest.toString()).problem()).isNull();

        addRaw();
        assertThat(f.exports.check("", "photos", f.dest.toString()).problem().message())
                .isEqualTo("Tick the RAW and HEIC photos box, or leave them out with File type.");
    }

    @Test
    void acceptedHeicPhotosAreCopiedExactlyAndCounted() throws Exception {
        Exports.Export started = f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null,
                true, List.of("heic")));

        assertThat(started.heicCount()).isZero();
        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Exports.Export done = f.exports.list().past().get(0);
        assertThat(done.copied()).isEqualTo(3);
        assertThat(done.heicCount()).isEqualTo(2);
        assertThat(done.rawCount()).isZero();
        assertThat(Files.readAllBytes(Path.of(done.path()).resolve("Danaus_chrysippus_02.heic")))
                .as("exactly as it is, location and all").isEqualTo(heif);
        assertThat(rows("SELECT image_id || '|' || raw || '|' || heic FROM export_item ORDER BY seq"))
                .containsExactlyInAnyOrder(jpeg + "|0|0", heicWithGps + "|0|1", heicWithout + "|0|1");
        assertThat(rows("SELECT heic_count FROM export_run")).containsExactly("2");
    }

    @Test
    void aStartNeedsTheTickAndATickForKindsItNamed() {
        assertThatThrownBy(() -> f.exports.start(f.request("", "photos"))).isInstanceOfSatisfying(AtlasException.class,
                e -> {
                    assertThat(e.code()).isEqualTo("raw_location");
                    assertThat(e.getMessage()).isEqualTo("Tick the HEIC photos box, or leave HEIC photos out with File type.");
                });
        // the sheet's tick named RAW only (the check it saw had no HEIC): the HEIC photos are not accepted (audit 17)
        assertThatThrownBy(() -> f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null,
                true, List.of("raw")))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("raw_location"));
        assertThatThrownBy(() -> f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null,
                true, List.of("gif")))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Unknown file type “gif”."));
        assertThat(rows("SELECT COUNT(*) FROM export_run")).containsExactly("0");

        // without acceptedKinds (older pages), the tick accepts what the export has
        f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null, true));
        assertThat(rows("SELECT COUNT(*) FROM export_run")).containsExactly("1");
    }

    @Test
    void aHeicWhoseFileIsNoLongerHeicLosesItsLocationAndARawNowHeicStaysExact() throws Exception {
        long raw = addRaw();
        byte[] located = TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(16, 12, 2));
        Files.write(f.file(heicWithGps), located); // replaced by a JPEG before its copy
        Files.write(f.file(raw), heif);             // the RAW replaced by a HEIC

        f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null, true));

        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Exports.Export done = f.exports.list().past().get(0);
        assertThat(done.copied()).isEqualTo(4);
        assertThat(Files.readAllBytes(Path.of(done.path()).resolve("Danaus_chrysippus_02.heic"))).isNotEqualTo(located);
        assertThat(Files.readAllBytes(Path.of(done.path()).resolve("Danaus_chrysippus_03.nef"))).isEqualTo(heif);
        assertThat(rows("SELECT raw || '|' || heic FROM export_item WHERE image_id = " + heicWithGps)).containsExactly("0|0");
        assertThat(rows("SELECT raw || '|' || heic FROM export_item WHERE image_id = " + raw)).containsExactly("0|1");
        assertThat(done.heicCount()).as("the HEIC that stayed one, and the RAW that became one").isEqualTo(2);
        assertThat(done.rawCount()).isZero();
    }

    @Test
    void onlyTheKindsTheTickAcceptedGoOutExactly() throws Exception {
        // fixes J5: the tick named HEIC; a HEIC replaced by a RAW before its copy is not accepted, so it fails
        Files.write(f.file(heicWithout), TestRaw.tiff(new TestRaw.Ifd().bytes(0xC612, 1, new byte[] {1, 4, 0, 0}).jpeg(TestRaw.preview())));

        f.exports.start(new Exports.Request("", "photos", f.dest.toString(), "flat", true, null, true, List.of("heic")));

        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Exports.Export done = f.exports.list().past().get(0);
        assertThat(rows("SELECT accepted_kinds FROM export_run")).containsExactly("heic");
        assertThat(done.copied()).isEqualTo(2);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.heicCount()).isEqualTo(1);
        assertThat(f.exports.detail(done.id()).problems().get(0).reason()).startsWith("This photo's file is now RAW or HEIC");
    }

    @Test
    void aPhotoThatBecameHeicSaysRemoveLocationCannotChangeIt() throws Exception {
        Files.write(f.file(jpeg), heif); // the JPEG replaced by a HEIC after the check: its kind was never accepted

        f.exports.start(new Exports.Request("types=jpeg", "photos", f.dest.toString(), "flat", true, null, false));

        assertThat(f.exports.awaitIdle(Duration.ofSeconds(20))).isTrue();
        Exports.Export done = f.exports.list().past().get(0);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(rows("SELECT accepted_kinds FROM export_run")).containsExactly("");
        assertThat(f.exports.detail(done.id()).problems().get(0).reason()).isEqualTo("This photo's file is now RAW or HEIC,"
                + " which Remove location can't change. Export it again and tick the box to include it.");
    }
}
