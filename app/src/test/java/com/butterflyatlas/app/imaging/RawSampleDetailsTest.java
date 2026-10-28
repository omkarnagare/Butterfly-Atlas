package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.testing.RawSamples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Photo details of every real sample family (audit 3: a family that fails here blocks the PR): capture time, orientation,
 * the camera model and the size as displayed, and GPS where the sample has it. Skipped when the samples are absent.
 */
class RawSampleDetailsTest {

    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor(ZoneOffset.UTC);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // file,                       displayed width × height, orientation
            "canon-eos-7d-mark-ii.cr2,     5472, 3648, 1",
            "canon-eos-r7.cr3,             6960, 4640, 1",
            "fujifilm-x-s10.raf,           4416, 2944, 1",
            "google-pixel-7-pro.dng,       1280,  964, 3",
            "nikon-d500.nef,               2784, 1856, 1",
            "nikon-z63.nef,                3984, 2656, 1",
            "olympus-e-m1markiii.orf,      3200, 2400, 1",
            "panasonic-dc-g9.rw2,          1920, 1440, 1",
            "pentax-k-1-mark-ii.pef,       4800, 3200, 1",
            "pentax-k-3-mark-iii.dng,      6192, 4128, 1",
            "sony-ilce-6400a.arw,          1080, 1616, 8",
    })
    void everyFamilysDetailsAreRead(String name, int width, int height, int orientation) throws Exception {
        Path file = RawSamples.file(name);

        PhotoFacts facts = processor.readRaw(file);

        assertThat(facts.format()).isEqualTo(PhotoFormat.RAW);
        assertThat(facts.width()).isEqualTo(width);
        assertThat(facts.height()).isEqualTo(height);
        assertThat(facts.orientation()).isEqualTo(orientation);
        assertThat(facts.capturedAt()).as("capture time").isNotNull();
        assertThat(facts.metaJson()).as("the camera model").contains("\"Model\":");
        assertThat(facts.rawPreview()).isNotNull();
    }

    @Test
    void thePixelDngsGpsIsRead() throws Exception {
        PhotoFacts facts = processor.readRaw(RawSamples.file("google-pixel-7-pro.dng"));

        assertThat(facts.gps()).isNotNull();
        assertThat(facts.gps().lat()).isCloseTo(-27.62, within(0.01));
        assertThat(facts.gps().lon()).isCloseTo(153.11, within(0.01));
    }

    @Test
    void aSonyThumbnailComesOutUprightFromItsPreview() throws Exception {
        Path target = Path.of(System.getProperty("java.io.tmpdir")).resolve("atlas-raw-sample-thumb-" + ProcessHandle.current().pid()
                + ".jpg");
        try {
            processor.thumbnail(RawSamples.file("sony-ilce-6400a.arw"), 8, 480, target);
            BufferedImage thumb = ImageIO.read(target.toFile());
            assertThat(thumb.getHeight()).isEqualTo(480);
            assertThat(thumb.getWidth()).isEqualTo(321);
        } finally {
            java.nio.file.Files.deleteIfExists(target);
        }
    }
}
