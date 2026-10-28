package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.exports.MetadataStripper;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestLocations;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.fieldtypes.AbstractFieldType;
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo;
import org.apache.commons.imaging.formats.tiff.write.TiffImageWriterLossless;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Location removed losslessly (design D11, contract §3): GPS, XMP, IPTC and the maker note go; the rest of EXIF and the image stay. */
class CommonsMetadataStripperTest {

    private final MetadataStripper stripper = new CommonsMetadataStripper();

    @TempDir
    Path dir;

    private static Metadata read(byte[] bytes) throws Exception {
        return ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
    }

    @Test
    void theSampleCarriesALocationEverywhere() throws Exception {
        Metadata before = read(TestLocations.jpeg());

        assertThat(before.getFirstDirectoryOfType(GpsDirectory.class).getGeoLocation().getLatitude())
                .isCloseTo(TestLocations.LAT, org.assertj.core.data.Offset.offset(0.001));
        assertThat(before.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties()).containsKey("exif:GPSLatitude");
        assertThat(before.getFirstDirectoryOfType(IptcDirectory.class).getString(IptcDirectory.TAG_CITY)).isEqualTo("Pune");
    }

    @Test
    void aJpegLosesItsGpsXmpAndIptcAndKeepsTheRestOfItsExif() throws Exception {
        byte[] original = TestLocations.jpeg();

        byte[] stripped = stripper.strip(original);

        Metadata after = read(stripped);
        assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(XmpDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(IptcDirectory.class)).isNull();
        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        byte[] latitude = TestLocations.gpsLatitudeBytes(original);
        assertThat(latitude).hasSize(24);
        assertThat(TestLocations.contains(original, latitude)).isTrue();
        assertThat(TestLocations.contains(stripped, latitude)).as("the old GPS values are not left behind").isFalse();
        assertThat(TestLocations.contains(stripped, "Photoshop 3.0")).as("the APP13 segment is gone").isFalse();
        ExifIFD0Directory ifd0 = after.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_MAKE)).isEqualTo("Canon");
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_MODEL)).isEqualTo("EOS R7");
        assertThat(ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(6);
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_COPYRIGHT)).isEqualTo(TestLocations.COPYRIGHT);
        assertThat(ifd0.containsTag(ExifIFD0Directory.TAG_GPS_INFO_OFFSET)).isFalse();
        assertThat(after.getFirstDirectoryOfType(ExifSubIFDDirectory.class).getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
                .isEqualTo(TestLocations.TAKEN);
        assertThat(TestLocations.jpegImageData(stripped)).as("compressed image data byte-identical")
                .isEqualTo(TestLocations.jpegImageData(original));
        assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
    }

    @Test
    void aCameraExifLosesItsMakerNoteAndKeepsItsThumbnail() throws Exception {
        byte[] original = TestLocations.iptc(TestExif.camera().taken(TestLocations.TAKEN, "+05:30").gps(18.5, 73.8, 560)
                .makerNote(300).withThumbnail().xmp("<dc:title>Plain Tiger</dc:title>").jpeg(TestImages.jpeg(80, 60)));

        byte[] stripped = stripper.strip(original);

        Metadata after = read(stripped);
        assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(XmpDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(IptcDirectory.class)).isNull();
        ExifSubIFDDirectory exif = after.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertThat(exif.containsTag(ExifSubIFDDirectory.TAG_MAKERNOTE)).as("the maker note is removed").isFalse();
        assertThat(TestLocations.contains(stripped, "M".repeat(300))).as("its bytes are not left behind").isFalse();
        assertThat(after.getFirstDirectoryOfType(ExifThumbnailDirectory.class).getInt(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH))
                .isPositive();
        assertThat(exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL)).isEqualTo("+05:30");
        assertThat(TestLocations.jpegImageData(stripped)).isEqualTo(TestLocations.jpegImageData(original));
    }

    /** A maker note naming the place, as Panasonic's City, State and Landmark fields do. */
    private static final String PLACE_NOTE = "City: Pune; State: Maharashtra; Landmark: Shaniwar Wada";

    @Test
    void aJpegLosesAMakerNoteThatNamesThePlaceAndKeepsTheRestOfItsExif() throws Exception {
        byte[] original = TestExif.camera().taken(TestLocations.TAKEN, null).makerNote(PLACE_NOTE).withThumbnail()
                .jpeg(TestImages.jpeg(32, 24));
        assertThat(TestLocations.contains(original, "Pune")).isTrue();

        byte[] stripped = stripper.strip(original);

        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        assertMakerNoteGoneAndExifKept(read(stripped));
        assertThat(TestLocations.jpegImageData(stripped)).isEqualTo(TestLocations.jpegImageData(original));
        assertThat(stripper.hasLocation(Files.write(dir.resolve("note.jpg"), stripped))).isFalse();
        assertThat(stripper.hasLocation(Files.write(dir.resolve("kept-note.jpg"), original)))
                .as("the read-back fails a copy that still has a maker note").isTrue();
    }

    @Test
    void aTiffLosesAMakerNoteThatNamesThePlaceAndKeepsTheRestOfItsExif() throws Exception {
        byte[] original = TestExif.camera().taken(TestLocations.TAKEN, null).makerNote(PLACE_NOTE).tiff(8, 6);
        assertThat(TestLocations.contains(original, "Pune")).isTrue();

        byte[] stripped = stripper.strip(original);

        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        assertMakerNoteGoneAndExifKept(read(stripped));
        assertThat(TestLocations.tiffImageData(stripped)).containsExactlyElementsOf(TestLocations.tiffImageData(original));
        assertThat(stripper.hasLocation(Files.write(dir.resolve("note.tif"), stripped))).isFalse();
        assertThat(stripper.hasLocation(Files.write(dir.resolve("kept-note.tif"), original))).isTrue();
    }

    private static void assertMakerNoteGoneAndExifKept(Metadata after) throws Exception {
        for (com.drew.metadata.Directory directory : after.getDirectories()) {
            assertThat(directory.getClass().getSimpleName()).doesNotContain("Makernote");
            assertThat(directory.containsTag(ExifSubIFDDirectory.TAG_MAKERNOTE)).as(directory.getName()).isFalse();
        }
        ExifIFD0Directory ifd0 = after.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_MAKE)).isEqualTo("Canon");
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_MODEL)).isEqualTo("EOS R7");
        assertThat(ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(1);
        ExifSubIFDDirectory exif = after.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertThat(exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)).isEqualTo(TestLocations.TAKEN);
        assertThat(exif.getInt(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)).isEqualTo(400);
        assertThat(exif.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME).toString()).isEqualTo("1/500");
    }

    @Test
    void eachBlockIsRemovedOnItsOwn() throws Exception {
        byte[] plain = TestImages.jpeg(32, 24);
        for (byte[] original : new byte[][] {TestLocations.exif(plain, TestLocations.exif(true)), TestLocations.xmp(plain),
                TestLocations.iptc(plain)}) {
            byte[] stripped = stripper.strip(original);

            Metadata after = read(stripped);
            assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
            assertThat(after.getFirstDirectoryOfType(XmpDirectory.class)).isNull();
            assertThat(after.getFirstDirectoryOfType(IptcDirectory.class)).isNull();
            assertThat(TestLocations.jpegImageData(stripped)).isEqualTo(TestLocations.jpegImageData(original));
        }
    }

    @Test
    void aJpegWithNothingToRemoveIsCopiedByteForByte() throws Exception {
        byte[] plain = TestImages.jpeg(32, 24);
        byte[] camera = TestLocations.exif(plain, TestLocations.exif(false));

        assertThat(stripper.strip(plain)).isEqualTo(plain);
        assertThat(stripper.strip(camera)).isEqualTo(camera);
    }

    @Test
    void aTiffLosesItsGpsXmpAndIptcAndKeepsItsStripsAndTheRestOfItsExif() throws Exception {
        byte[] original = TestLocations.tiff();
        Metadata before = read(original);
        assertThat(before.getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();
        assertThat(before.getFirstDirectoryOfType(XmpDirectory.class)).isNotNull();
        assertThat(before.getFirstDirectoryOfType(IptcDirectory.class)).isNotNull();

        byte[] stripped = stripper.strip(original);

        Metadata after = read(stripped);
        assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(XmpDirectory.class)).isNull();
        assertThat(after.getFirstDirectoryOfType(IptcDirectory.class)).isNull();
        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        assertThat(TestLocations.contains(stripped, TestLocations.gpsLatitudeBytes(original))).isFalse();
        ExifIFD0Directory ifd0 = after.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_MAKE)).isEqualTo("Canon");
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_COPYRIGHT)).isEqualTo(TestLocations.COPYRIGHT);
        assertThat(after.getFirstDirectoryOfType(ExifSubIFDDirectory.class).getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
                .isEqualTo(TestLocations.TAKEN);
        assertThat(TestLocations.tiffImageData(stripped)).as("strip data byte-identical")
                .containsExactlyElementsOf(TestLocations.tiffImageData(original));
        assertThat(ImageIO.read(new ByteArrayInputStream(stripped)).getRGB(1, 1))
                .isEqualTo(ImageIO.read(new ByteArrayInputStream(original)).getRGB(1, 1));
    }

    @Test
    void aTiffWithNothingToRemoveIsCopiedByteForByte() throws Exception {
        byte[] camera = TestExif.camera().taken(TestLocations.TAKEN, null).tiff(8, 6);

        assertThat(stripper.strip(camera)).isEqualTo(camera);
    }

    @Test
    void aPngDropsItsExifXmpAndTextChunksAndKeepsTheOthersByteForByte() throws Exception {
        byte[] original = TestLocations.png();
        assertThat(read(original).getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();
        assertThat(read(original).getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties()).containsKey("exif:GPSLatitude");

        byte[] stripped = stripper.strip(original);

        List<TestLocations.Chunk> kept = TestLocations.pngChunks(original).stream()
                .filter(c -> !List.of("eXIf", "iTXt", "tEXt", "zTXt").contains(c.type())).toList();
        assertThat(kept).extracting(TestLocations.Chunk::type).contains("IHDR", "pHYs", "IDAT", "IEND");
        assertThat(TestLocations.pngChunks(stripped)).containsExactlyElementsOf(kept);
        assertThat(stripped).isEqualTo(TestLocations.pngOf(kept));
        assertThat(read(stripped).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(read(stripped).getFirstDirectoryOfType(XmpDirectory.class)).isNull();
        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
    }

    @Test
    void aWebpDropsItsExifAndXmpChunksClearsTheirFlagsAndFixesTheRiffSize() throws Exception {
        byte[] original = TestLocations.webp();
        assertThat(read(original).getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();
        assertThat(read(original).getFirstDirectoryOfType(XmpDirectory.class)).isNotNull();

        byte[] stripped = stripper.strip(original);

        List<TestLocations.Chunk> chunks = TestLocations.webpChunks(stripped);
        assertThat(chunks).extracting(TestLocations.Chunk::type).containsExactly("VP8X", "VP8L");
        assertThat(chunks.get(0).data()).isEqualTo(new byte[] {0, 0, 0, 0, 1, 0, 0, 1, 0, 0});
        assertThat(chunks.get(1)).isEqualTo(TestLocations.webpChunks(original).get(1));
        assertThat(java.nio.ByteBuffer.wrap(stripped, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt())
                .isEqualTo(stripped.length - 8);
        assertThat(read(stripped).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(read(stripped).getFirstDirectoryOfType(XmpDirectory.class)).isNull();
        assertThat(ImageIO.read(new ByteArrayInputStream(stripped)).getRGB(1, 1))
                .isEqualTo(ImageIO.read(new ByteArrayInputStream(TestImages.webp())).getRGB(1, 1));
    }

    @Test
    void pngAndWebpWithNothingToRemoveAreCopiedByteForByte() throws Exception {
        byte[] png = TestImages.png(8, 6);
        byte[] webp = TestImages.webp();
        byte[] extendedWithIcc = TestLocations.webpOf(List.of(new TestLocations.Chunk("VP8X",
                        new byte[] {0x20, 0, 0, 0, 1, 0, 0, 1, 0, 0}), new TestLocations.Chunk("ICCP", new byte[] {1, 2, 3}),
                TestLocations.webpChunks(webp).get(0)));

        assertThat(stripper.strip(png)).isEqualTo(png);
        assertThat(stripper.strip(webp)).isEqualTo(webp);
        assertThat(stripper.strip(extendedWithIcc)).isEqualTo(extendedWithIcc);
    }

    @Test
    void aBrokenPngOrWebpIsRefused() {
        byte[] png = TestLocations.png();
        byte[] webp = TestLocations.webp();

        assertThatThrownBy(() -> stripper.strip(java.util.Arrays.copyOf(png, png.length - 20))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> stripper.strip(java.util.Arrays.copyOf(webp, webp.length - 20))).isInstanceOf(IOException.class);
    }

    @Test
    void aJpegWhoseOnlyLocationIsItsXmpLosesIt() throws Exception {
        byte[] original = TestLocations.xmp(TestLocations.exif(TestImages.jpeg(32, 24), TestLocations.exif(false)));
        assertThat(read(original).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(read(original).getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties()).containsKey("exif:GPSLatitude");

        byte[] stripped = stripper.strip(original);

        assertThat(read(stripped).getFirstDirectoryOfType(XmpDirectory.class)).isNull();
        assertThat(TestLocations.contains(stripped, "GPSLatitude")).isFalse();
        assertThat(read(stripped).getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_MAKE))
                .isEqualTo("Canon");
    }

    @Test
    void anImageAppendedAfterTheJpegAndItsMpfSegmentAreDropped() throws Exception {
        byte[] original = TestLocations.jpegWithAppendedImage();
        byte[] appended = TestLocations.jpeg();
        byte[] latitude = TestLocations.gpsLatitudeBytes(appended);
        assertThat(TestLocations.contains(original, latitude)).isTrue();
        assertThat(TestLocations.contains(original, "MPF\0")).isTrue();

        byte[] stripped = stripper.strip(original);

        assertThat(TestLocations.contains(stripped, latitude)).as("no GPS of the appended image").isFalse();
        assertThat(TestLocations.contains(stripped, "GPSLatitude")).isFalse();
        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        assertThat(TestLocations.contains(stripped, "MPF\0")).isFalse();
        assertThat(stripped.length).isEqualTo(original.length - appended.length - 4 - "MPF\0MM\0*\0\0\0\b\0\0".length());
        assertThat(TestLocations.jpegImageData(stripped)).as("the primary image's data unchanged")
                .isEqualTo(java.util.Arrays.copyOf(TestLocations.jpegImageData(original),
                        TestLocations.jpegImageData(original).length - appended.length));
        assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
    }

    @Test
    void everyPageOfATiffLosesItsXmpAndIptc() throws Exception {
        byte[] original = TestLocations.twoPageTiff();
        assertThat(TestLocations.tiffImageData(original)).hasSize(2);

        byte[] stripped = stripper.strip(original);

        assertThat(TestLocations.contains(stripped, "GPSLatitude")).as("the second page's XMP is gone too").isFalse();
        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        assertThat(read(stripped).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(TestLocations.tiffImageData(stripped)).containsExactlyElementsOf(TestLocations.tiffImageData(original));
    }

    @Test
    void aWebpWithExifAndAnIccProfileKeepsOnlyTheIccFlag() throws Exception {
        List<TestLocations.Chunk> image = TestLocations.webpChunks(TestImages.webp());
        byte[] original = TestLocations.webpOf(List.of(
                new TestLocations.Chunk("VP8X", new byte[] {0x2C, 0, 0, 0, 1, 0, 0, 1, 0, 0}),
                new TestLocations.Chunk("ICCP", new byte[] {1, 2, 3, 4}), image.get(0),
                new TestLocations.Chunk("EXIF", TestExif.camera().gps(18.5, 73.8, 560).tiff(1, 1))));

        List<TestLocations.Chunk> chunks = TestLocations.webpChunks(stripper.strip(original));

        assertThat(chunks).extracting(TestLocations.Chunk::type).containsExactly("VP8X", "ICCP", "VP8L");
        assertThat(chunks.get(0).data()[0]).isEqualTo((byte) 0x20);
        assertThat(chunks.get(1).data()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void somethingThatIsNotAPhotoIsRefused() {
        assertThatThrownBy(() -> stripper.strip("hello".getBytes())).isInstanceOf(IOException.class);
    }

    @Test
    void anOldPhotoshopIptcBlockIsRemovedToo() throws Exception {
        byte[] iptc = {0x1C, 0x02, 0x5A, 0x00, 0x04, 'P', 'u', 'n', 'e'}; // record 2, dataset 90 (City)
        byte[] original = TestLocations.segment(TestImages.jpeg(32, 24), 0xED,
                TestLocations.concat("Adobe_Photoshop2.5:".getBytes(StandardCharsets.ISO_8859_1), iptc));
        assertThat(TestLocations.contains(original, "Pune")).isTrue();

        byte[] stripped = stripper.strip(original);

        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        assertThat(TestLocations.contains(stripped, "Adobe_Photoshop2.5:")).isFalse();
        assertThat(TestLocations.jpegImageData(stripped)).isEqualTo(TestLocations.jpegImageData(original));
    }

    @Test
    void onlyAnExifWithGpsOrAnEmbeddedBlockHasSomethingToRemove() throws Exception {
        byte[] plain = TestImages.jpeg(32, 24);
        byte[] camera = TestExif.camera().taken(TestLocations.TAKEN, null).tiff(8, 6);

        assertThat(somethingToRemove(TestLocations.exif(plain, TestLocations.exif(false)))).isFalse();
        assertThat(somethingToRemove(camera)).isFalse();
        assertThat(somethingToRemove(TestLocations.exif(plain, TestLocations.exif(true)))).isTrue();
        assertThat(somethingToRemove(TestLocations.tiff())).isTrue();
        assertThat(somethingToRemove(withRootTag(camera, TiffTagConstants.TIFF_TAG_XMP))).as("XMP").isTrue();
        assertThat(somethingToRemove(withRootTag(camera, ExifTagConstants.EXIF_TAG_IPTC_NAA))).as("IPTC").isTrue();
        assertThat(somethingToRemove(withRootTag(camera, ExifTagConstants.EXIF_TAG_PHOTOSHOP_SETTINGS)))
                .as("Photoshop").isTrue();
        assertThat(somethingToRemove(TestLocations.twoPageTiff())).isTrue();
        assertThat(somethingToRemove(TestExif.camera().makerNote(PLACE_NOTE).tiff(8, 6))).as("maker note").isTrue();
        assertThat(somethingToRemove(TestExif.camera().makerNote(8).jpeg(plain))).as("maker note").isTrue();
    }

    private static boolean somethingToRemove(byte[] photo) throws IOException {
        TiffImageMetadata exif = Imaging.getMetadata(photo) instanceof JpegImageMetadata jpeg ? jpeg.getExif()
                : (TiffImageMetadata) Imaging.getMetadata(photo);
        return CommonsMetadataStripper.hasSomethingToRemove(exif);
    }

    /** The TIFF with one more IFD0 tag (a few undefined bytes). */
    private static byte[] withRootTag(byte[] tiff, TagInfo tag) throws IOException {
        TiffOutputSet set = ((TiffImageMetadata) Imaging.getMetadata(tiff)).getOutputSet();
        set.getRootDirectory().add(new TiffOutputField(tag.tag, tag, AbstractFieldType.UNDEFINED, 4, new byte[] {1, 2, 3, 4}));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new TiffImageWriterLossless(set.byteOrder, tiff).write(out, set);
        return out.toByteArray();
    }

    @Test
    void aJpegRewriteThatChangesTheImageDataIsRefused() {
        MetadataStripper tampering = new CommonsMetadataStripper(data -> {
            byte[] changed = data.clone();
            changed[changed.length - 3] ^= 0x01; // inside the scan, just before its end-of-image marker
            return changed;
        });

        assertThatThrownBy(() -> tampering.strip(TestLocations.jpeg())).isInstanceOf(IOException.class)
                .hasMessageContaining("changed its image data");
    }

    @Test
    void aTiffRewriteThatChangesTheImageDataIsRefused() {
        MetadataStripper tampering = new CommonsMetadataStripper(data -> TestExif.camera().tiff(8, 6));

        assertThatThrownBy(() -> tampering.strip(TestLocations.tiff())).isInstanceOf(IOException.class)
                .hasMessageContaining("changed its image data");
    }

    @Test
    void theEndOfAProgressiveJpegIsFoundAfterItsLastScan() throws Exception {
        byte[] progressive = jpeg(true, 0);
        assertThat(count(progressive, "\u00FF\u00DA")).as("several scans").isGreaterThan(1);

        assertThat(CommonsMetadataStripper.primaryEnd(progressive)).isEqualTo(progressive.length);
        assertThat(CommonsMetadataStripper.primaryEnd(TestLocations.concat(progressive, TestLocations.jpeg())))
                .isEqualTo(progressive.length);
    }

    @Test
    void theEndOfAJpegWithRestartMarkersIsFoundAfterItsScan() throws Exception {
        byte[] restarts = jpeg(false, 2);
        assertThat(count(restarts, "\u00FF\u00DD")).as("a restart interval").isEqualTo(1);
        assertThat(count(restarts, "\u00FF\u00D0")).as("restart markers").isPositive();

        assertThat(CommonsMetadataStripper.primaryEnd(restarts)).isEqualTo(restarts.length);
        assertThat(CommonsMetadataStripper.primaryEnd(TestLocations.concat(restarts, TestLocations.jpeg())))
                .isEqualTo(restarts.length);
    }

    /** A noisy 96×64 JPEG (so its scans hold stuffed {@code FF 00} bytes), progressive or with a restart interval. */
    private static byte[] jpeg(boolean progressive, int restartInterval) throws IOException {
        BufferedImage img = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (progressive) {
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
            if (restartInterval > 0) {
                String format = "javax_imageio_jpeg_image_1.0";
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode markers = (IIOMetadataNode) root.getElementsByTagName("markerSequence").item(0);
                IIOMetadataNode dri = new IIOMetadataNode("dri");
                dri.setAttribute("interval", Integer.toString(restartInterval));
                markers.insertBefore(dri, markers.getFirstChild());
                metadata.setFromTree(format, root);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(img, null, metadata), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    @Test
    void aRawIptcBlockWithoutAPhotoshopHeaderIsRemoved() throws Exception {
        byte[] iptc = {0x1C, 0x02, 0x5A, 0x00, 0x04, 'P', 'u', 'n', 'e'}; // record 2, dataset 90 (City), no prefix
        byte[] original = TestLocations.segment(TestImages.jpeg(32, 24), 0xED, iptc);
        assertThat(TestLocations.contains(original, "Pune")).isTrue();

        byte[] stripped = stripper.strip(original);

        assertThat(TestLocations.contains(stripped, "Pune")).isFalse();
        assertThat(TestLocations.jpegImageData(stripped)).isEqualTo(TestLocations.jpegImageData(original));
    }

    @Test
    void aLocationFreeExifThatCommonsCannotParseIsKeptAsItIs() throws Exception {
        byte[] camera = TestExif.camera().jpeg(TestImages.jpeg(32, 24));
        byte[] twoExifs = app1("Exif\0\0", exifTiff(camera), camera);
        for (byte[] original : List.of(thumbnailOffsetPastTheEnd(TestExif.camera()),
                ifd1CountBroken(TestExif.camera()), twoExifs)) {
            assertThatThrownBy(() -> ((JpegImageMetadata) Imaging.getMetadata(original)).getExif().getOutputSet())
                    .as("Commons cannot parse the sample's EXIF").isNotNull();
            byte[] withXmp = TestLocations.xmp(original);

            byte[] stripped = stripper.strip(original);
            byte[] strippedOfXmp = stripper.strip(withXmp);

            assertThat(stripped).as("nothing else to remove: byte for byte").isEqualTo(original);
            assertThat(read(strippedOfXmp).getFirstDirectoryOfType(XmpDirectory.class)).as("the XMP still goes").isNull();
            assertThat(read(strippedOfXmp).getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_MAKE))
                    .isEqualTo("Canon");
            assertThat(new CommonsMetadataStripper().hasLocation(Files.write(dir.resolve("kept.jpg"), strippedOfXmp)))
                    .as("the read-back passes").isFalse();
        }
    }

    @Test
    void anExifThatCommonsCannotParseIsRefusedWhenItHasAGpsPointer() {
        TestExif gps = TestExif.camera().gps(18.5, 73.8, 560);
        for (byte[] original : List.of(thumbnailOffsetPastTheEnd(gps), ifd1CountBroken(gps))) {
            assertThatThrownBy(() -> stripper.strip(original)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void anExifThatCommonsCannotParseIsRefusedWhenItHasAMakerNote() {
        TestExif note = TestExif.camera().makerNote(PLACE_NOTE);
        for (byte[] original : List.of(thumbnailOffsetPastTheEnd(note), ifd1CountBroken(note))) {
            assertThatThrownBy(() -> ((JpegImageMetadata) Imaging.getMetadata(original)).getExif().getOutputSet())
                    .as("Commons cannot parse the sample's EXIF").isNotNull();

            assertThatThrownBy(() -> stripper.strip(original)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void aSecondExifSegmentWithALittleEndianGpsPointerIsRefused() {
        byte[] little = TestLocations.exif(TestImages.jpeg(32, 24), TestLocations.exif(true));
        assertThat(new String(exifTiff(little), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("II");
        byte[] original = app1("Exif\0\0", exifTiff(TestExif.camera().jpeg(TestImages.jpeg(32, 24))), little);

        assertThatThrownBy(() -> stripper.strip(original)).isInstanceOf(IOException.class);
    }

    @Test
    void anExifThatCommonsCannotParseIsRefusedWhenItHasAnEmbeddedBlock() throws Exception {
        for (TagInfo tag : List.of(TiffTagConstants.TIFF_TAG_XMP, ExifTagConstants.EXIF_TAG_IPTC_NAA,
                ExifTagConstants.EXIF_TAG_PHOTOSHOP_SETTINGS)) {
            TiffOutputSet set = TestLocations.exif(false);
            set.getRootDirectory().add(new TiffOutputField(tag.tag, tag, AbstractFieldType.UNDEFINED, 4, new byte[] {1, 2, 3, 4}));
            byte[] embedded = TestLocations.exif(TestImages.jpeg(32, 24), set);
            byte[] original = app1("Exif\0\0", exifTiff(TestExif.camera().jpeg(TestImages.jpeg(32, 24))), embedded);

            assertThatThrownBy(() -> stripper.strip(original)).as(tag.name).isInstanceOf(IOException.class);
        }
    }

    @Test
    void anApp1ThatIsNeitherAStandardExifNorXmpIsDropped() throws Exception {
        TestExif gps = TestExif.camera().gps(18.5, 73.8, 560);
        byte[] latitude = TestLocations.gpsLatitudeBytes(gps.jpeg(TestImages.jpeg(32, 24)));
        byte[] gpsTiff = exifTiff(gps.jpeg(TestImages.jpeg(32, 24)));
        byte[] camera = TestExif.camera().jpeg(TestImages.jpeg(32, 24));
        byte[] brokenPreamble = thumbnailOffsetPastTheEnd(gps);
        brokenPreamble[indexOf(brokenPreamble, "Exif\0\0") + 5] = 0x01; // Exif\0\x01, which Commons reads as EXIF too
        List<byte[]> samples = List.of(brokenPreamble,
                app1("Exif\0\u0001", gpsTiff, camera), // after a standard, location-free EXIF
                app1("EXIF\0\0", gpsTiff, TestImages.jpeg(32, 24)),
                app1("", gpsTiff, TestImages.jpeg(32, 24))); // a bare TIFF header
        for (byte[] original : samples) {
            assertThat(TestLocations.contains(original, latitude)).isTrue();

            byte[] stripped = stripper.strip(original);

            assertThat(TestLocations.contains(stripped, latitude)).as("no GPS bytes left").isFalse();
            assertThat(read(stripped).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
            assertThat(TestLocations.jpegImageData(stripped)).isEqualTo(TestLocations.jpegImageData(original));
        }
        byte[] kept = stripper.strip(samples.get(1));
        assertThat(read(kept).getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_MAKE))
                .as("the standard EXIF stays").isEqualTo("Canon");
    }

    /** The JPEG with one more APP1 segment after its first segment: this preamble, then this payload. */
    private static byte[] app1(String preamble, byte[] payload, byte[] jpeg) {
        return TestLocations.segment(jpeg, 0xE1, TestLocations.concat(preamble.getBytes(StandardCharsets.ISO_8859_1), payload));
    }

    /** The TIFF block of a JPEG's first {@code Exif\0\0} APP1 segment. */
    private static byte[] exifTiff(byte[] jpeg) {
        int at = indexOf(jpeg, "Exif\0\0");
        int length = ((jpeg[at - 2] & 0xFF) << 8) | (jpeg[at - 1] & 0xFF);
        return Arrays.copyOfRange(jpeg, at + 6, at - 2 + length);
    }

    /** A JPEG whose EXIF has a thumbnail, its offset (tag 0x0201, LONG) past the end: Commons "Couldn't read array". */
    private static byte[] thumbnailOffsetPastTheEnd(TestExif exif) {
        byte[] photo = exif.withThumbnail().jpeg(TestImages.jpeg(32, 24));
        int at = indexOf(photo, "\u0002\u0001\u0000\u0004\u0000\u0000\u0000\u0001");
        ByteBuffer.wrap(photo).putInt(at + 8, 0x00FFFF00);
        return photo;
    }

    /** A JPEG whose EXIF has a thumbnail directory (IFD1) with a broken entry count: Commons "Not a Valid TIFF File". */
    private static byte[] ifd1CountBroken(TestExif exif) {
        byte[] photo = exif.withThumbnail().jpeg(TestImages.jpeg(32, 24));
        int at = indexOf(photo, "\u0001\u0003\u0000\u0003\u0000\u0000\u0000\u0001"); // IFD1's first entry, Compression
        ByteBuffer.wrap(photo).putShort(at - 2, (short) 0x0FFF);
        return photo;
    }

    private static int indexOf(byte[] bytes, String text) {
        int at = new String(bytes, StandardCharsets.ISO_8859_1).indexOf(text);
        assertThat(at).as("found in the sample").isPositive();
        return at;
    }

    private static int count(byte[] bytes, String text) {
        String all = new String(bytes, StandardCharsets.ISO_8859_1);
        int n = 0;
        for (int at = all.indexOf(text); at >= 0; at = all.indexOf(text, at + 1)) {
            n++;
        }
        return n;
    }
}
