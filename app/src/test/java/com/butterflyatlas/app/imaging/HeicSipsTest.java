package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.imaging.heif.HeicDecoders;
import com.butterflyatlas.app.imaging.heif.HeicFixtures;
import com.butterflyatlas.app.imaging.heif.HeifBoxes;
import com.butterflyatlas.app.imaging.heif.HeifInfo;
import com.butterflyatlas.app.imaging.heif.TestDecoders;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.testing.TestExif;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * HEIC photos made by Apple's own encoder ({@code sips}, from pictures this test draws) and shown through it (Phase 7b
 * design §12): their details, an iPhone-sized 48-tile grid, every EXIF orientation upright through the processor, GPS,
 * and the thumbnail and display sizes. Skipped where {@code /usr/bin/sips} is absent (Windows, Linux).
 */
class HeicSipsTest {

    @TempDir
    Path dir;

    private HeicDecoders sips;
    private ImageIoPhotoProcessor processor;

    @BeforeAll
    static void needsSips() {
        assumeThat(Files.isRegularFile(Path.of("/usr/bin/sips")))
                .as("/usr/bin/sips is not on this computer (only macOS has it): the real-decoder HEIC tests are skipped")
                .isTrue();
    }

    @BeforeEach
    void start() throws Exception {
        sips = TestDecoders.sips(() -> dir.resolve(".thumbs").resolve(".decode"));
        assertThat(sips.check().get(60, TimeUnit.SECONDS)).isEqualTo(HeicStatus.available("sips", "mac"));
        processor = new ImageIoPhotoProcessor(ZoneOffset.UTC, sips);
    }

    @AfterEach
    void reset() {
        HeicStatus.publish(HeicStatus.checking("mac"));
    }

    private Path heic(String name, byte[] jpeg) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, HeicFixtures.heic(jpeg));
        return file;
    }

    private static HeifInfo boxes(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return HeifBoxes.read(channel, channel.size());
        }
    }

    private BufferedImage thumbnail(Path file, int orientation, int longSide) throws IOException {
        Path target = dir.resolve(file.getFileName() + "-" + longSide + ".jpg");
        processor.thumbnail(file, orientation, longSide, target, PhotoProcessor.Use.VIEW);
        return ImageIO.read(target.toFile());
    }

    /** The corner whose pixel near the edge is white; null when none or several. */
    private static String whiteCorner(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int d = Math.max(1, Math.min(w, h) / 16);
        String[] names = {"top-left", "top-right", "bottom-left", "bottom-right"};
        int[][] at = {{d, d}, {w - 1 - d, d}, {d, h - 1 - d}, {w - 1 - d, h - 1 - d}};
        String white = null;
        for (int i = 0; i < 4; i++) {
            int rgb = image.getRGB(at[i][0], at[i][1]);
            if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) > 200) {
                if (white != null) {
                    return null;
                }
                white = names[i];
            }
        }
        return white;
    }

    @Test
    void anIphoneSizedPhotoIsAGridWithItsDetailsReadInJava() throws Exception {
        byte[] jpeg = TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").gps(18.5204, 73.8567, 560)
                .jpeg(HeicFixtures.jpeg(HeicFixtures.gradient(4032, 3024)));
        Path file = heic("IMG_0042.HEIC", jpeg);

        HeifInfo info = boxes(file);
        PhotoFacts facts = processor.read(file);

        assertThat(info.kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(info.primaryType()).as("Apple's 512 px tiles").isEqualTo("grid");
        assertThat(info.codedAs()).isEqualTo("hvc1");
        assertThat(facts.format()).isEqualTo(PhotoFormat.HEIC);
        assertThat(facts.width()).isEqualTo(4032);
        assertThat(facts.height()).isEqualTo(3024);
        assertThat(facts.orientation()).isEqualTo(1);
        assertThat(facts.capturedAt()).isEqualTo(Instant.parse("2024-05-01T04:52:33Z"));
        assertThat(facts.gps().lat()).isCloseTo(18.5204, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(facts.gps().lon()).isCloseTo(73.8567, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(facts.heif().bitDepth()).isEqualTo(8);

        BufferedImage thumb = thumbnail(file, facts.orientation(), 480);
        assertThat(thumb.getWidth()).isEqualTo(480);
        assertThat(thumb.getHeight()).isEqualTo(360);
        BufferedImage display = thumbnail(file, facts.orientation(), 2560);
        assertThat(display.getWidth()).isEqualTo(2560);
        assertThat(display.getHeight()).isEqualTo(1920);
        assertThat(whiteCorner(display)).isEqualTo("top-left");
    }

    @Test
    void everyOrientationComesOutUpright() throws Exception {
        // Where the stored picture's top-left white square is once each EXIF orientation is applied.
        String[] corner = {null, "top-left", "top-right", "bottom-right", "bottom-left", "top-left", "top-right",
                "bottom-right", "bottom-left"};
        for (int o = 1; o <= 8; o++) {
            Path file = heic("o" + o + ".heic", TestExif.camera().orientation(o).jpeg(HeicFixtures.jpeg(
                    HeicFixtures.gradient(640, 320))));
            PhotoFacts facts = processor.read(file);
            assertThat(facts.orientation()).as("orientation %d from irot/imir", o).isEqualTo(o);

            BufferedImage shown = thumbnail(file, facts.orientation(), 480);

            assertThat(shown.getWidth()).as("orientation %d", o).isEqualTo(o >= 5 ? 240 : 480);
            assertThat(shown.getHeight()).as("orientation %d", o).isEqualTo(o >= 5 ? 480 : 240);
            assertThat(whiteCorner(shown)).as("orientation %d", o).isEqualTo(corner[o]);
            assertThat(facts.width()).as("orientation %d", o).isEqualTo(o >= 5 ? 320 : 640);
        }
    }

    @Test
    void aSixteenBitPictureBecomesATenBitHeicShownInStandardRange() throws Exception {
        BufferedImage grey = new BufferedImage(640, 320, BufferedImage.TYPE_USHORT_GRAY);
        for (int x = 0; x < 640; x++) {
            for (int y = 0; y < 320; y++) {
                grey.getRaster().setSample(x, y, 0, x * 100);
            }
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(grey, "png", png);
        Path file = heic("deep.heic", png.toByteArray());

        PhotoFacts facts = processor.read(file);

        assertThat(facts.heif().bitDepth()).isEqualTo(10);
        assertThat(com.butterflyatlas.app.photos.Photo.Heic.of(facts.heif()).tenBit()).isTrue();
        assertThat(thumbnail(file, facts.orientation(), 480).getWidth()).isEqualTo(480);
    }

    @Test
    void sipsLeavesPicturesAsStoredSoTheAppTurnsThem() throws Exception {
        // the check's finding on this Mac (verified 2026-10-27): sips applies neither irot nor imir
        Path file = heic("r.heic", TestExif.camera().orientation(6).jpeg(HeicFixtures.jpeg(HeicFixtures.gradient(640, 320))));

        HeicDecoders.Decoded decoded = sips.decode(file, 640, boxes(file), PhotoProcessor.Use.VIEW);

        assertThat(decoded.image().getWidth()).isEqualTo(640);
        assertThat(decoded.orientation()).isEqualTo(6);
    }
}
