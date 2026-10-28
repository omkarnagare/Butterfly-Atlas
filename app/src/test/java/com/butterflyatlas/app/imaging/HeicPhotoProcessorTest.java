package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.UnsupportedPhotoException;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestHeif;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HEIC photos are read in pure Java (Phase 7b design §3): size and turn from the HEIF boxes, metadata from the Exif item,
 * the EXIF orientation inside never applied; sequences and other codings refused in the contract's words.
 */
class HeicPhotoProcessorTest {

    @TempDir
    Path dir;

    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor(ZoneOffset.UTC);

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void aHeicPhotosDetailsComeFromItsBoxesAndItsExifItem() throws IOException {
        byte[] tiff = TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").gps(18.52, 73.85, 560).orientation(6)
                .tiffBlock();
        Path file = write("IMG_0042.HEIC", TestHeif.still(4032, 3024).grid(8, 6, "hvc1").turn(3).exif(tiff).build());

        PhotoFacts facts = processor.read(file);

        assertThat(facts.format()).isEqualTo(PhotoFormat.HEIC);
        assertThat(facts.width()).as("turned").isEqualTo(3024);
        assertThat(facts.height()).isEqualTo(4032);
        assertThat(facts.orientation()).as("irot 3 as EXIF; the EXIF 6 inside is the same turn, applied once").isEqualTo(6);
        assertThat(facts.capturedAt()).isEqualTo(Instant.parse("2024-05-01T04:52:33Z"));
        assertThat(facts.gps().lat()).isCloseTo(18.52, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(facts.gps().lon()).isCloseTo(73.85, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(facts.heif()).isEqualTo(new PhotoFacts.Heif(3, null, false, 8, false, 4032, 3024));
        assertThat(PhotoFacts.fromJson(facts.metaJson())).isEqualTo(facts);
    }

    @Test
    void theExifOrientationInsideIsNeverApplied() throws IOException {
        // As a sips-made file of an upright photo whose EXIF still says 8: the boxes have no turn
        Path file = write("a.heic", TestHeif.still(640, 480).exif(TestExif.camera().orientation(8).tiffBlock()).build());

        PhotoFacts facts = processor.read(file);

        assertThat(facts.orientation()).isEqualTo(1);
        assertThat(facts.width()).isEqualTo(640);
        assertThat(facts.height()).isEqualTo(480);
    }

    @Test
    void theSnapshotKeepsHowItIsStored() throws IOException {
        Path file = write("a.hif", TestHeif.still(6000, 4000).brands("heix", "mif1", "heix").turn(1).mirror(1).bitDepth(10)
                .auxiliary().exif(TestExif.camera().tiffBlock()).exifInIdat().build());

        PhotoFacts facts = processor.read(file);
        JsonNode heif = new ObjectMapper().readTree(facts.metaJson()).get("heif");

        assertThat(heif.toString()).isEqualTo("{\"turn\":1,\"mirror\":1,\"bitDepth\":10,\"auxiliary\":true,"
                + "\"storedWidth\":6000,\"storedHeight\":4000}");
        assertThat(facts.orientation()).isEqualTo(7);
        assertThat(facts.width()).isEqualTo(4000);
        assertThat(PhotoFacts.heif(facts.metaJson())).isEqualTo(new PhotoFacts.Heif(1, 1, false, 10, true, 6000, 4000));
        assertThat(PhotoFacts.heif("{\"v\":1}")).isNull();
    }

    @Test
    void sequencesOtherCodingsAndAvifAreRefusedInTheirWords() throws IOException {
        Path noPrimary = write("s.heic", TestHeif.still(64, 32).withoutPrimary().withMoov().build());
        Path jpegInside = write("j.heic", TestHeif.still(64, 32).brands("heic", "mif1", "heic").primaryType("jpeg").build());
        Path av1 = write("v.heic", TestHeif.still(64, 32).brands("heic", "mif1", "heic").primaryType("av01").build());

        assertThatThrownBy(() -> processor.read(noPrimary)).isInstanceOf(UnsupportedPhotoException.class)
                .hasMessage(PhotoFormat.HEIC_SEQUENCE);
        assertThatThrownBy(() -> processor.read(jpegInside)).isInstanceOf(UnsupportedPhotoException.class)
                .hasMessage(PhotoFormat.HEIF_OTHER);
        assertThatThrownBy(() -> processor.read(av1)).isInstanceOf(UnsupportedPhotoException.class)
                .hasMessage(PhotoFormat.AVIF);
    }

    @Test
    void damagedBoxesAreUnreadable() throws IOException {
        byte[] good = TestHeif.still(64, 32).build();
        Path cut = write("cut.heic", java.util.Arrays.copyOf(good, good.length - 3));

        assertThatThrownBy(() -> processor.read(cut)).isInstanceOf(IOException.class)
                .isNotInstanceOf(UnsupportedPhotoException.class);
    }

    @Test
    void anUploadIsNamedHeicAndASequenceIsRefused() throws IOException {
        PhotoIngest ingest = new PhotoIngest(processor);
        byte[] heif = TestHeif.still(640, 480).brands("mif1", "mif1", "heic").build();

        PhotoIngest.Result result = ingest.ingest(new ByteArrayInputStream(heif), dir.resolve("up.part"), "IMG_1.HEIF");

        assertThat(result.format()).isEqualTo(PhotoFormat.HEIC);
        assertThat(result.extension()).isEqualTo("heic");
        byte[] sequence = TestHeif.still(64, 32).brands("msf1", "msf1", "heic").withMoov().build();
        assertThatThrownBy(() -> ingest.ingest(new ByteArrayInputStream(sequence), dir.resolve("seq.part"), "a.heics"))
                .isInstanceOf(AtlasException.class).hasMessage(PhotoFormat.HEIC_SEQUENCE);
        // a still image whose boxes say it is a sequence (no primary item) is refused when read
        byte[] noPrimary = TestHeif.still(64, 32).withoutPrimary().withMoov().build();
        assertThatThrownBy(() -> ingest.ingest(new ByteArrayInputStream(noPrimary), dir.resolve("np.part"), "a.heic"))
                .isInstanceOf(AtlasException.class).hasMessage(PhotoFormat.HEIC_SEQUENCE);
    }
}
