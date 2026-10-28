package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.exports.MetadataStripper;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestLocations;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.GpsDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The read-back check after each copy (design D11): does the written file still carry a location? */
class LocationReadBackTest {

    private final MetadataStripper stripper = new CommonsMetadataStripper();

    @TempDir
    Path dir;

    private Path file(String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    @Test
    void everySampleCarriesALocationUntilItIsStripped() throws Exception {
        byte[][] samples = {TestLocations.jpeg(), TestLocations.tiff(), TestLocations.png(), TestLocations.webp()};
        String[] names = {"a.jpg", "b.tif", "c.png", "d.webp"};
        for (int i = 0; i < samples.length; i++) {
            assertThat(stripper.hasLocation(file(names[i], samples[i]))).as(names[i]).isTrue();
            assertThat(stripper.hasLocation(file("stripped_" + names[i], stripper.strip(samples[i])))).as(names[i]).isFalse();
        }
    }

    @Test
    void gpsInExifOrInXmpAloneCounts() throws Exception {
        byte[] plain = TestImages.jpeg(32, 24);

        assertThat(stripper.hasLocation(file("exif.jpg", TestLocations.exif(plain, TestLocations.exif(true))))).isTrue();
        assertThat(stripper.hasLocation(file("xmp.jpg", TestLocations.xmp(plain)))).isTrue();
        assertThat(stripper.hasLocation(file("zero.jpg", TestExif.camera().gps(0, 0, 0).jpeg(plain))))
                .as("even 0,0 is a position written").isTrue();
        assertThat(stripper.hasLocation(file("camera.jpg", TestLocations.exif(plain, TestLocations.exif(false))))).isFalse();
        assertThat(stripper.hasLocation(file("iptc.jpg", TestLocations.iptc(plain)))).as("a city is not GPS").isFalse();
        assertThat(stripper.hasLocation(file("plain.png", TestImages.png(8, 6)))).isFalse();
    }

    @Test
    void aFileThatCannotBeReadIsAnError() throws Exception {
        Path text = file("note.jpg", "not a photo".getBytes());

        assertThatThrownBy(() -> stripper.hasLocation(text)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> stripper.hasLocation(dir.resolve("gone.jpg"))).isInstanceOf(IOException.class);
    }

    @Test
    void aGpsBlockThatCannotBeReadIsAnError() throws Exception {
        byte[] photo = TestExif.camera().gps(18.5, 73.8, 560).jpeg(TestImages.jpeg(32, 24));
        String entry = "\u0088\u0025\u0000\u0004\u0000\u0000\u0000\u0001";
        int pointer = new String(photo, StandardCharsets.ISO_8859_1).indexOf(entry);
        assertThat(pointer).as("the GPS pointer of IFD0 (tag 0x8825, LONG, count 1)").isPositive();
        ByteBuffer.wrap(photo).putInt(pointer + 8, 0x7FFFFFF0); // the GPS IFD past the end

        assertThatThrownBy(() -> stripper.hasLocation(file("lost.jpg", photo))).isInstanceOf(IOException.class);
    }

    @Test
    void aTiffWhoseFirstDirectoryOffsetIsWrongIsAnError() throws Exception {
        byte[] tiff = TestExif.camera().gps(18.5, 73.8, 560).tiff(8, 6);
        ByteBuffer.wrap(tiff).putInt(4, 0x7FFFFFF0); // IFD0 past the end

        assertThatThrownBy(() -> stripper.hasLocation(file("lost.tif", tiff))).isInstanceOf(IOException.class);
    }

    @Test
    void aJpegWhoseSegmentsCannotBeReadIsAnError() throws Exception {
        byte[] broken = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x01, 0, 0, 0, 0, 0, 0}; // size 1 < 2

        assertThatThrownBy(() -> stripper.hasLocation(file("broken.jpg", broken))).isInstanceOf(IOException.class)
                .hasCauseInstanceOf(ImageProcessingException.class);
    }

    @Test
    void anXmpPositionCountsWhateverTheCaseOfItsName() throws Exception {
        String drone = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:drone-dji=\"http://www.dji.com/drone-dji/1.0/\""
                + " drone-dji:GpsLatitude=\"18.5204\" drone-dji:GpsLongitude=\"73.8567\"/></rdf:RDF></x:xmpmeta>";

        assertThat(stripper.hasLocation(file("drone.jpg", TestLocations.xmp(TestImages.jpeg(32, 24), drone)))).isTrue();
    }

    @Test
    void aTiffWithAnUnknownMarkerIsAnErrorOfTheReader() throws Exception {
        byte[] tiff = TestExif.camera().gps(18.5, 73.8, 560).tiff(8, 6);
        ByteBuffer.wrap(tiff).putShort(2, (short) 0x2B); // BigTIFF's marker, which metadata-extractor does not read
        Path file = file("big.tif", tiff);

        // hasLocation itself refuses it earlier (not a TIFF Butterfly Atlas reads), so the strict read is asked directly
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            assertThatThrownBy(() -> PhotoMetadata.strict(channel, PhotoFormat.TIFF, file)).isInstanceOf(IOException.class)
                    .hasCauseInstanceOf(ImageProcessingException.class);
        }
        assertThatThrownBy(() -> stripper.hasLocation(file)).isInstanceOf(IOException.class);
    }

    @Test
    void aLocationFreeJpegWithABrokenIfd0EntryHasNoLocation() throws Exception {
        byte[] badOffset = TestExif.camera().jpeg(TestImages.jpeg(32, 24));
        int make = indexOf(badOffset, "\u0001\u000F\u0000\u0002\u0000\u0000\u0000\u0006"); // Make, ASCII, 6 bytes
        ByteBuffer.wrap(badOffset).putInt(make + 8, 0x00FFFF00); // its value past the end
        byte[] badType = TestExif.camera().jpeg(TestImages.jpeg(32, 24));
        badType[indexOf(badType, "\u0001\u0012\u0000\u0003") + 3] = 0x7F; // Orientation with type 127

        for (byte[] photo : new byte[][] {badOffset, badType}) {
            assertThat(stripper.hasLocation(file("broken.jpg", photo))).isFalse();
            assertThat(stripper.strip(photo)).isEqualTo(photo);
        }
    }

    @Test
    void aGpsPhotoWhoseIfd0CannotBeReadIsAnError() throws Exception {
        byte[] photo = TestExif.camera().gps(18.5, 73.8, 560).jpeg(TestImages.jpeg(32, 24));
        int make = indexOf(photo, "\u0001\u000F\u0000\u0002");
        ByteBuffer.wrap(photo).putShort(make - 2, (short) 0x0F00); // IFD0 claims 3,840 entries
        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(photo), photo.length)
                .getFirstDirectoryOfType(GpsDirectory.class)).as("the GPS block is never reached").isNull();

        assertThatThrownBy(() -> stripper.hasLocation(file("hidden.jpg", photo))).isInstanceOf(IOException.class);
    }

    @Test
    void aLocationFreePngWithABrokenChunkHasNoLocation() throws Exception {
        byte[] png = TestImages.png(8, 6);
        byte[] type = "cHRM".getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = {0, 0, 0, 1}; // a cHRM holds 32 bytes
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, 33); // the signature and IHDR
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(type);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        out.write(png, 33, png.length - 33);

        assertThat(stripper.hasLocation(file("chrm.png", out.toByteArray()))).isFalse();
    }

    @Test
    void theGpsPointerScanFindsAnEntryInEitherByteOrderAndAcrossChunks() throws Exception {
        assertThat(holdsLocationEntry(new byte[] {9, (byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1}, 4096)).as("MM LONG").isTrue();
        assertThat(holdsLocationEntry(new byte[] {9, 0x25, (byte) 0x88, 4, 0, 1, 0, 0, 0}, 4096)).as("II LONG").isTrue();
        for (int type : new int[] {0x09, 0x0B, 0x0D}) {
            assertThat(holdsLocationEntry(new byte[] {(byte) 0x88, 0x25, 0, (byte) type}, 4096)).as("type " + type).isTrue();
        }
        assertThat(holdsLocationEntry(new byte[] {(byte) 0x88, 0x25, 0, 3, 0, 0, 0, 1}, 4096)).as("MM SHORT").isFalse();
        assertThat(holdsLocationEntry(new byte[] {0x25, (byte) 0x88, 3, 0, 1, 0, 0, 0}, 4096)).as("II SHORT").isFalse();
        for (int chunk = 1; chunk <= 8; chunk++) {
            assertThat(holdsLocationEntry(new byte[] {1, 2, 3, (byte) 0x88, 0x25, 0, 4, 5, 6}, chunk)).as("chunk " + chunk)
                    .isTrue();
        }
        assertThat(holdsLocationEntry(new byte[0], 4)).isFalse();
    }

    @Test
    void theScanAlsoFindsAnXmpOrPhotoshopEntry() throws Exception {
        assertThat(holdsLocationEntry(new byte[] {0x02, (byte) 0xBC, 0, 7, 0, 0, 0, 9}, 4096)).as("XMP UNDEFINED").isTrue();
        assertThat(holdsLocationEntry(new byte[] {(byte) 0xBC, 0x02, 1, 0, 9, 0, 0, 0}, 4096)).as("II XMP BYTE").isTrue();
        assertThat(holdsLocationEntry(new byte[] {(byte) 0x86, 0x49, 0, 7}, 4096)).as("Photoshop").isTrue();
        assertThat(holdsLocationEntry(new byte[] {0x02, (byte) 0xBC, 0, 3}, 4096)).as("XMP SHORT").isFalse();
    }

    @Test
    void aGpsPhotoWhoseExifByteOrderCannotBeReadIsAnError() throws Exception {
        byte[] photo = TestExif.camera().gps(18.5, 73.8, 560).jpeg(TestImages.jpeg(32, 24));
        int order = indexOf(photo, "Exif\0\0MM") + 6;
        photo[order] = 'X';
        photo[order + 1] = 'X';

        assertThatThrownBy(() -> stripper.hasLocation(file("unordered.jpg", photo))).isInstanceOf(IOException.class)
                .hasMessageContaining("Error");
    }

    private boolean holdsLocationEntry(byte[] bytes, int chunk) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file("scan.bin", bytes))) {
            return PhotoMetadata.holdsLocationEntry(channel, chunk);
        }
    }

    @Test
    void anExifInsidePhotoshopResourcesCountsAndGoesWithThem() throws Exception {
        byte[] exif = TestExif.camera().gps(18.5, 73.8, 560).tiff(1, 1);
        ByteArrayOutputStream resources = new ByteArrayOutputStream();
        resources.writeBytes("Photoshop 3.0\0".getBytes(StandardCharsets.ISO_8859_1));
        resources.writeBytes("8BIM".getBytes(StandardCharsets.ISO_8859_1));
        resources.writeBytes(new byte[] {0x04, 0x22, 0, 0}); // resource 0x0422 (EXIF data), an empty name, padded
        resources.writeBytes(ByteBuffer.allocate(4).putInt(exif.length).array());
        resources.writeBytes(exif);
        if (exif.length % 2 == 1) {
            resources.write(0);
        }
        byte[] photo = TestLocations.segment(TestImages.jpeg(32, 24), 0xED, resources.toByteArray());

        assertThat(stripper.hasLocation(file("photoshop.jpg", photo))).isTrue();
        assertThat(stripper.hasLocation(file("stripped.jpg", stripper.strip(photo)))).isFalse();
    }

    private static int indexOf(byte[] bytes, String text) {
        int at = new String(bytes, StandardCharsets.ISO_8859_1).indexOf(text);
        assertThat(at).as("found in the sample").isPositive();
        return at;
    }
}
