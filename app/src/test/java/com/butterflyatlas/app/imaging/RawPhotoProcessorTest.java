package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.UnsupportedPhotoException;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import com.butterflyatlas.app.testing.TestRaw.Ifd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RAW photos read and shrunk through their JPEG previews (design §3, §4). */
class RawPhotoProcessorTest {

    @TempDir
    Path dir;

    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor(ZoneId.of("Asia/Kolkata"));

    private Path file(String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    /** A NEF-shaped file: IFD0 with the orientation, the preview in a SubIFD, and the capture time in its Exif IFD. */
    private static byte[] nef(int orientation) {
        return TestRaw.tiff(new Ifd().text(0x010F, "NIKON CORPORATION").text(0x0110, "NIKON D500")
                .shortValue(0x0112, orientation).subIfds(new Ifd().longValue(0x00FE, 1).jpeg(TestRaw.preview()),
                        new Ifd().longValue(0x00FE, 0).longValue(0x0100, 4000).longValue(0x0101, 3000))
                .child(0x8769, new Ifd().text(0x9003, "2024:05:01 10:22:33")));
    }

    @Test
    void aRawPhotoIsReadThroughItsPreviewWithItsOwnMetadata() throws Exception {
        PhotoFacts facts = processor.readRaw(file("DSC_0001.NEF", nef(6)));

        assertThat(facts.format()).isEqualTo(PhotoFormat.RAW);
        assertThat(facts.width()).as("as displayed").isEqualTo(768);
        assertThat(facts.height()).isEqualTo(1024);
        assertThat(facts.orientation()).isEqualTo(6);
        assertThat(facts.capturedAt()).hasToString("2024-05-01T04:52:33Z");
        PhotoFacts.Preview preview = facts.rawPreview();
        assertThat(preview.width()).isEqualTo(768);
        assertThat(preview.storedWidth()).isEqualTo(1024);
        assertThat(preview.orientation()).isEqualTo(6);
        assertThat(facts.metaJson()).contains("\"format\":\"RAW\"", "\"rawPreview\":{\"offset\":" + preview.offset()
                        + ",\"length\":" + preview.length() + ",\"width\":768,\"height\":1024,\"orientation\":6,"
                        + "\"storedWidth\":1024,\"storedHeight\":768}", "\"sensor\":{\"width\":4000,\"height\":3000}",
                "\"Model\":\"NIKON D500\"");
        assertThat(PhotoFacts.fromJson(facts.metaJson()).rawPreview()).isEqualTo(preview);
    }

    @Test
    void readFindsRawByItsBytesAndReadRawByTheCallersWord() throws Exception {
        Path dng = file("photo.tif", TestRaw.tiff(new Ifd().bytes(0xC612, TestRaw.BYTE, new byte[] {1, 4, 0, 0})
                .longValue(0x00FE, 1).strip(TestRaw.preview(), 7)));
        assertThat(processor.read(dng).format()).as("a DNG named .tif").isEqualTo(PhotoFormat.RAW);

        Path nef = file("DSC_0002.NEF", nef(1));
        assertThat(PhotoFormat.detect(Files.readAllBytes(nef))).as("a NEF's bytes are a TIFF's").contains(PhotoFormat.TIFF);
        assertThat(processor.readRaw(nef).format()).isEqualTo(PhotoFormat.RAW);
    }

    @Test
    void aRawWithoutAUsablePreviewIsRefusedInPlainWords() throws Exception {
        Path small = file("small.nef", TestRaw.tiff(new Ifd().jpeg(TestImages.jpeg(800, 600))));
        Path none = file("none.cr2", TestRaw.cr2(new Ifd().shortValue(0x0112, 1)));

        assertThatThrownBy(() -> processor.readRaw(small)).isInstanceOf(UnsupportedPhotoException.class)
                .hasMessage("This RAW photo has no preview Butterfly Atlas can show. Save it as JPEG from your RAW software"
                        + " and add that.");
        assertThatThrownBy(() -> processor.read(none)).isInstanceOf(UnsupportedPhotoException.class)
                .hasMessage(PhotoFormat.RAW_NO_PREVIEW);
    }

    @Test
    void aCr3sMetadataComesFromItsCmtBoxes() throws Exception {
        byte[] cr3 = TestRaw.cr3(TestImages.jpeg(1600, 1200), null,
                TestRaw.cmt(new Ifd().text(0x010F, "Canon").text(0x0110, "Canon EOS R7").shortValue(0x0112, 8)),
                TestRaw.cmt(new Ifd().text(0x9003, "2024:05:01 10:22:33").text(0x9011, "+02:00")),
                TestRaw.cmt(TestRaw.gps(-27.62, 153.11)));

        PhotoFacts facts = processor.read(file("IMG_0001.CR3", cr3));

        assertThat(facts.format()).isEqualTo(PhotoFormat.RAW);
        assertThat(facts.orientation()).isEqualTo(8);
        assertThat(facts.width()).isEqualTo(1200);
        assertThat(facts.capturedAt()).hasToString("2024-05-01T08:22:33Z");
        assertThat(facts.gps().lat()).isCloseTo(-27.62, org.assertj.core.data.Offset.offset(0.001));
        assertThat(facts.gps().lon()).isCloseTo(153.11, org.assertj.core.data.Offset.offset(0.001));
        assertThat(facts.metaJson()).contains("\"Model\":\"Canon EOS R7\"");
    }

    @Test
    void aRafsMetadataComesFromItsJpeg() throws Exception {
        byte[] raf = TestRaw.raf(TestExif.camera().taken("2024:05:01 10:22:33", null).gps(12.97, 77.59, 920)
                .jpeg(TestRaw.preview()));

        PhotoFacts facts = processor.read(file("DSCF0001.RAF", raf));

        assertThat(facts.format()).isEqualTo(PhotoFormat.RAW);
        assertThat(facts.capturedAt()).hasToString("2024-05-01T04:52:33Z");
        assertThat(facts.gps().lat()).isCloseTo(12.97, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void aRawThumbnailIsMadeFromItsPreviewUpright() throws Exception {
        Path nef = file("DSC_0003.NEF", nef(6));
        Path target = dir.resolve("thumb.jpg");

        processor.thumbnail(nef, 6, 100, target);

        BufferedImage thumb = ImageIO.read(target.toFile());
        assertThat(thumb.getWidth()).isEqualTo(75);
        assertThat(thumb.getHeight()).isEqualTo(100);
        assertThat(((thumb.getRGB(37, 10) >> 16) & 0xFF)).as("red on top").isGreaterThan(160);
        assertThat(thumb.getRGB(37, 90) & 0xFF).as("blue below").isGreaterThan(160);
    }

    @Test
    void aRawWhosePreviewIsGoneHasNoThumbnail() throws Exception {
        Path nef = file("DSC_0004.NEF", TestRaw.tiff(new Ifd().shortValue(0x0112, 1)));

        assertThatThrownBy(() -> processor.thumbnail(nef, 1, 100, dir.resolve("none.jpg"))).isInstanceOf(IOException.class);
    }
}
