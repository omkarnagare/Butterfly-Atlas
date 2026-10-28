package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.UnsupportedPhotoException;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageIoPhotoProcessorTest {

    @TempDir
    Path dir;

    /** Times without an offset are read in this zone (India, +05:30, no daylight saving). */
    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor(ZoneId.of("Asia/Kolkata"));

    private Path file(String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    private BufferedImage thumbnail(Path source, int orientation, int longSide) throws IOException {
        Path target = dir.resolve("thumb-" + source.getFileName() + ".jpg");
        processor.thumbnail(source, orientation, longSide, target);
        assertThat(PhotoFormat.detect(Files.readAllBytes(target))).contains(PhotoFormat.JPEG);
        return ImageIO.read(target.toFile());
    }

    /** Format, displayed width and height, orientation. */
    private static List<Object> shape(PhotoFacts facts) {
        return List.of(facts.format(), facts.width(), facts.height(), facts.orientation());
    }

    private static boolean reddish(int rgb) {
        return ((rgb >> 16) & 0xFF) > 160 && (rgb & 0xFF) < 100;
    }

    private static boolean bluish(int rgb) {
        return (rgb & 0xFF) > 160 && ((rgb >> 16) & 0xFF) < 100;
    }

    @Test
    void readsEverySupportedFormat() throws Exception {
        assertThat(shape(processor.read(file("a.jpg", TestImages.jpeg(40, 20))))).containsExactly(PhotoFormat.JPEG, 40, 20, 1);
        assertThat(shape(processor.read(file("a.png", TestImages.png(30, 10))))).containsExactly(PhotoFormat.PNG, 30, 10, 1);
        assertThat(shape(processor.read(file("a.tif", TestImages.tiff(12, 16))))).containsExactly(PhotoFormat.TIFF, 12, 16, 1);
        PhotoFacts webp = processor.read(file("a.webp", TestImages.webp()));
        assertThat(shape(webp)).containsExactly(PhotoFormat.WEBP, 2, 2, 1);
        assertThat(webp.capturedAt()).isNull();
        assertThat(webp.gps()).isNull();
        assertThat(webp.metaJson()).isEqualTo("{\"v\":1,\"format\":\"WEBP\",\"width\":2,\"height\":2,\"orientation\":1}");
    }

    @Test
    void aSidewaysPhotoReportsItsDisplayedSize() throws Exception {
        Path photo = file("turned.jpg", TestImages.withOrientation(TestImages.jpeg(40, 20), 6));

        assertThat(shape(processor.read(photo))).containsExactly(PhotoFormat.JPEG, 20, 40, 6);
    }

    @Test
    void readsWhenAndWhereAPhotoWasTaken() throws Exception {
        Path north = file("north.jpg", TestExif.camera().taken("2024:05:01 10:22:33", "+02:00")
                .gps(12.9716, 77.5946, 920).jpeg(TestImages.jpeg(40, 20)));
        Path south = file("south.jpg", TestExif.camera().gps(-33.8568, -70.6483, -12.5).jpeg(TestImages.jpeg(40, 20)));

        PhotoFacts facts = processor.read(north);
        assertThat(facts.capturedAt()).isEqualTo(Instant.parse("2024-05-01T08:22:33Z"));
        assertThat(facts.gps().lat()).isCloseTo(12.9716, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(facts.gps().lon()).isCloseTo(77.5946, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(facts.gps().altitude()).isEqualTo(920.0);
        PhotoFacts below = processor.read(south);
        assertThat(below.gps().lat()).isCloseTo(-33.8568, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(below.gps().lon()).isCloseTo(-70.6483, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(below.gps().altitude()).isEqualTo(-12.5);
        assertThat(below.capturedAt()).isNull();
    }

    @Test
    void aTimeWithoutAnOffsetIsReadAsLocalTime() throws Exception {
        Path local = file("local.jpg", TestExif.camera().taken("2024:05:01 10:22:33", null).jpeg(TestImages.jpeg(40, 20)));
        Path unset = file("unset.jpg", TestExif.camera().taken("0000:00:00 00:00:00", null).jpeg(TestImages.jpeg(40, 20)));

        assertThat(processor.read(local).capturedAt()).isEqualTo(Instant.parse("2024-05-01T04:52:33Z"));
        assertThat(processor.read(unset).capturedAt()).isNull();
    }

    @Test
    void theSnapshotKeepsStandardFieldsAndLeavesOutMakerNotesAndThumbnails() throws Exception {
        String history = "<crs:History>" + "x".repeat(3000) + "</crs:History>";
        Path photo = file("camera.jpg", TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").gps(12.5, 77.25, 900)
                .makerNote(4000).withThumbnail()
                .xmp("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Plain tiger</rdf:li></rdf:Alt></dc:title>"
                        + "<dc:subject><rdf:Bag><rdf:li>Danaus</rdf:li><rdf:li>garden</rdf:li></rdf:Bag></dc:subject>"
                        + "<xmp:Rating>4</xmp:Rating>" + history)
                .jpeg(TestImages.jpeg(40, 20)));

        PhotoFacts facts = processor.read(photo);

        String json = facts.metaJson();
        assertThat(json).contains("\"Make\":\"Canon\"", "\"Model\":\"EOS R7\"", "\"F-Number\":\"f/5.6\"",
                "\"Date/Time Original\":\"2024:05:01 10:22:33\"", "\"capturedAt\":\"2024-05-01T04:52:33Z\"",
                "\"gps\":{\"lat\":12.5,\"lon\":77.25,\"alt\":900.0}",
                "\"dc:title[1]\":\"Plain tiger\"", "\"dc:subject[2]\":\"garden\"", "\"xmp:Rating\":\"4\"");
        assertThat(json).doesNotContain("MMMM", "Makernote", "MakerNote", "Thumbnail", "crs:", "xxxx", "xml:lang");
        assertThat(json.length()).as(json).isLessThan(2048);
        assertThat(PhotoFacts.fromJson(json)).isEqualTo(facts);
    }

    @Test
    void aGpsPositionOutOfRangeIsLeftOut() throws Exception {
        Path pole = file("pole.jpg", TestExif.camera().gps(95, 10, 5).jpeg(TestImages.jpeg(40, 20)));
        Path far = file("far.jpg", TestExif.camera().gps(10, 200, 5).jpeg(TestImages.jpeg(40, 20)));

        assertThat(processor.read(pole).gps()).isNull();
        assertThat(processor.read(far).gps()).isNull();
        assertThat(processor.read(pole).metaJson()).doesNotContain("\"gps\"");
    }

    @Test
    void brokenMetadataDoesNotStopAPhotoBeingRead() throws Exception {
        byte[] good = TestExif.camera().taken("2024:05:01 10:22:33", null).gps(12.5, 77.25, 900).jpeg(TestImages.jpeg(40, 20));
        int tiff = indexOf(good, "Exif\0\0".getBytes()) + 6;
        byte[] badOffset = good.clone();
        badOffset[tiff + 4] = 0x7F; // IFD0 "offset" far past the segment
        byte[] scrambled = good.clone();
        for (int i = tiff + 8; i < tiff + 120; i++) {
            scrambled[i] = (byte) 0xFF;
        }

        assertThat(shape(processor.read(file("offset.jpg", badOffset)))).containsExactly(PhotoFormat.JPEG, 40, 20, 1);
        assertThat(shape(processor.read(file("scrambled.jpg", scrambled)))).containsExactly(PhotoFormat.JPEG, 40, 20, 1);
    }

    @Test
    void aTiffWhoseDirectoriesSitDeepInTheFileIsReadAcrossBlocks() throws Exception {
        // IFD0 starts 100 bytes before a 64 KB block edge, so its entries and values cross it.
        byte[] big = TestExif.camera().taken("2024:05:01 10:22:33", "+00:00").gps(12.5, 77.25, 900)
                .leadingBytes(65536 - 8 - 100).tiff(200, 200);
        assertThat(big.length).isGreaterThan(128 * 1024);

        PhotoFacts facts = processor.read(file("deep.tif", big));

        assertThat(shape(facts)).containsExactly(PhotoFormat.TIFF, 200, 200, 1);
        assertThat(facts.capturedAt()).isEqualTo(Instant.parse("2024-05-01T10:22:33Z"));
        assertThat(facts.gps().lat()).isCloseTo(12.5, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(facts.metaJson()).contains("\"Make\":\"Canon\"", "\"Model\":\"EOS R7\"");
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalArgumentException("pattern not found");
    }

    @Test
    void aDngWithoutAPreviewIsRefusedEvenThoughItIsATiff() throws Exception {
        Path tiff = file("scan.tif", TestExif.camera().tiff(8, 6));
        Path dng = file("raw.tif", TestExif.camera().dng().tiff(8, 6));

        assertThat(shape(processor.read(tiff))).containsExactly(PhotoFormat.TIFF, 8, 6, 1);
        assertThat(processor.read(tiff).metaJson()).contains("\"Make\":\"Canon\"").doesNotContain("Strip Offsets", "Strip Byte Counts");
        assertThatThrownBy(() -> processor.read(dng)).isInstanceOf(UnsupportedPhotoException.class)
                .hasMessage(PhotoFormat.RAW_NO_PREVIEW);
    }

    @Test
    void refusesFilesThatAreNotPhotos() throws Exception {
        Path text = file("notes.jpg", "not a photo at all".getBytes());
        byte[] jpeg = TestImages.jpeg(40, 20);
        Path cut = file("cut.jpg", java.util.Arrays.copyOf(jpeg, 4));

        assertThatThrownBy(() -> processor.read(text)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> processor.read(cut)).isInstanceOf(IOException.class);
    }

    @Test
    void thumbnailsAreShrunkToTheLongSide() throws Exception {
        BufferedImage thumb = thumbnail(file("wide.jpg", TestImages.jpeg(2400, 1600)), 1, 480);

        assertThat(thumb.getWidth()).isEqualTo(480);
        assertThat(thumb.getHeight()).isEqualTo(320);
        assertThat(reddish(thumb.getRGB(10, 160))).isTrue();
        assertThat(bluish(thumb.getRGB(470, 160))).isTrue();
    }

    @Test
    void thumbnailsAreTurnedUpright() throws Exception {
        // Stored landscape, red on the left; orientation 6 means "turn 90° clockwise to view".
        BufferedImage thumb = thumbnail(file("turned.jpg", TestImages.withOrientation(TestImages.jpeg(400, 200), 6)), 6, 100);

        assertThat(thumb.getWidth()).isEqualTo(50);
        assertThat(thumb.getHeight()).isEqualTo(100);
        assertThat(reddish(thumb.getRGB(25, 10))).as("red on top").isTrue();
        assertThat(bluish(thumb.getRGB(25, 90))).as("blue below").isTrue();
    }

    @Test
    void mirroredPhotosAreFlippedBack() throws Exception {
        BufferedImage thumb = thumbnail(file("mirror.jpg", TestImages.jpeg(400, 200)), 2, 100);

        assertThat(thumb.getWidth()).isEqualTo(100);
        assertThat(bluish(thumb.getRGB(10, 25))).as("blue now on the left").isTrue();
        assertThat(reddish(thumb.getRGB(90, 25))).isTrue();
    }

    @Test
    void anticlockwiseOrientationPutsTheLeftEdgeAtTheBottom() throws Exception {
        BufferedImage thumb = thumbnail(file("ccw.jpg", TestImages.jpeg(400, 200)), 8, 100);

        assertThat(thumb.getHeight()).isEqualTo(100);
        assertThat(bluish(thumb.getRGB(25, 10))).isTrue();
        assertThat(reddish(thumb.getRGB(25, 90))).isTrue();
    }

    @Test
    void thumbnailsOfTiffPngAndWebpWorkAndSmallPhotosAreNotEnlarged() throws Exception {
        assertThat(thumbnail(file("a.tif", TestImages.tiff(600, 300)), 1, 480).getWidth()).isEqualTo(480);
        assertThat(thumbnail(file("a.png", TestImages.png(200, 100)), 1, 480).getWidth()).isEqualTo(200);
        BufferedImage webp = thumbnail(file("a.webp", TestImages.webp()), 1, 480);
        assertThat(webp.getWidth()).isEqualTo(2);
        assertThat(webp.getHeight()).isEqualTo(2);
    }

    @Test
    void largePhotosAreDecodedSubsampled() {
        assertThat(ImageIoPhotoProcessor.subsampling(10_000, 480)).isEqualTo(20);
        assertThat(ImageIoPhotoProcessor.subsampling(2400, 480)).isEqualTo(5);
        assertThat(ImageIoPhotoProcessor.subsampling(900, 480)).isEqualTo(1);
    }

    @Test
    void dominantColourIsTheMostCommonOne() throws Exception {
        BufferedImage img = new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                img.setRGB(x, y, y < 20 ? 0x336699 : 0xFFFFFF);
            }
        }
        Path png = file("mostly-blue.png", TestImages.encode(img, "png"));

        assertThat(processor.dominantColor(png)).isEqualTo("#336699");
    }

    @Test
    void aLargePhotoIsDecodedAtBetweenOneAndTwoTimesTheTarget() throws Exception {
        Path big = file("big.jpg", TestImages.jpeg(6000, 4000));

        assertThat(ImageIoPhotoProcessor.subsampling(6000, 2560)).isEqualTo(2);
        BufferedImage decoded = ImageIoPhotoProcessor.decode(big, 2560);
        assertThat(decoded.getWidth()).isEqualTo(3000);
        assertThat(decoded.getHeight()).isEqualTo(2000);
        BufferedImage thumb = thumbnail(big, 1, 2560);
        assertThat(thumb.getWidth()).isEqualTo(2560);
        assertThat(thumb.getHeight()).isBetween(1706, 1708);
    }

    @Test
    void anUpsideDownPhotoIsTurnedRoundAndAMirroredSidewaysOneIsTransposed() throws Exception {
        BufferedImage upside = thumbnail(file("r3.jpg", TestImages.withOrientation(TestImages.jpeg(400, 200), 3)), 3, 100);
        assertThat(upside.getWidth()).isEqualTo(100);
        assertThat(upside.getHeight()).isEqualTo(50);
        assertThat(bluish(upside.getRGB(10, 25))).isTrue();
        assertThat(reddish(upside.getRGB(90, 25))).isTrue();

        BufferedImage transposed = thumbnail(file("r5.jpg", TestImages.withOrientation(TestImages.jpeg(400, 200), 5)), 5, 100);
        assertThat(transposed.getWidth()).isEqualTo(50);
        assertThat(transposed.getHeight()).isEqualTo(100);
        assertThat(reddish(transposed.getRGB(25, 10))).isTrue();
        assertThat(bluish(transposed.getRGB(25, 90))).isTrue();
    }

    @Test
    void greyscalePngsKeepTheirSizeAndBrightness() throws Exception {
        BufferedImage grey = new BufferedImage(40, 20, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage grey16 = new BufferedImage(40, 20, BufferedImage.TYPE_USHORT_GRAY);
        for (BufferedImage img : new BufferedImage[] {grey, grey16}) {
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 40, 20);
            g.dispose();
        }

        for (BufferedImage img : new BufferedImage[] {grey, grey16}) {
            Path png = file("g" + img.getType() + ".png", TestImages.encode(img, "png"));
            assertThat(shape(processor.read(png))).containsExactly(PhotoFormat.PNG, 40, 20, 1);
            BufferedImage thumb = thumbnail(png, 1, 40);
            assertThat(thumb.getWidth()).isEqualTo(40);
            assertThat(thumb.getHeight()).isEqualTo(20);
            int rgb = thumb.getRGB(20, 10);
            assertThat((rgb >> 16) & 0xFF).isGreaterThan(230);
            assertThat(rgb & 0xFF).isGreaterThan(230);
        }
    }

    @Test
    void transparentAreasOfAGreyPngWithAlphaComeOutWhite() throws Exception {
        java.awt.image.WritableRaster raster = java.awt.image.Raster.createInterleavedRaster(
                java.awt.image.DataBuffer.TYPE_BYTE, 20, 10, 2, null);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) {
                // Left half: opaque black. Right half: fully transparent (its grey value is black too).
                raster.setSample(x, y, 0, 0);
                raster.setSample(x, y, 1, x < 10 ? 255 : 0);
            }
        }
        java.awt.image.ColorModel model = new java.awt.image.ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY), true, false,
                java.awt.Transparency.TRANSLUCENT, java.awt.image.DataBuffer.TYPE_BYTE);
        BufferedImage img = new BufferedImage(model, raster, false, null);

        BufferedImage thumb = thumbnail(file("ga.png", TestImages.encode(img, "png")), 1, 20);

        assertThat(thumb.getRGB(15, 5) & 0xFFFFFF).isGreaterThan(0xF0F0F0);
        assertThat(thumb.getRGB(3, 5) & 0xFF).isLessThan(40);
    }
}
