package com.butterflyatlas.app.imaging.heif;

import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestHeif;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.butterflyatlas.app.testing.TestHeif.box;
import static com.butterflyatlas.app.testing.TestHeif.concat;
import static com.butterflyatlas.app.testing.TestHeif.fullBox;
import static com.butterflyatlas.app.testing.TestHeif.u16;
import static com.butterflyatlas.app.testing.TestHeif.u32;
import static org.assertj.core.api.Assertions.assertThat;

class HeifBoxesTest {

    @TempDir
    Path dir;

    private HeifInfo read(byte[] file) throws IOException {
        Path path = dir.resolve("photo.heic");
        Files.write(path, file);
        try (SeekableByteChannel channel = Files.newByteChannel(path)) {
            return HeifBoxes.read(channel, channel.size());
        }
    }

    private static byte[] bytesOf(byte[] file, List<HeifInfo.Extent> extents) {
        byte[] out = new byte[0];
        for (HeifInfo.Extent e : extents) {
            out = concat(out, Arrays.copyOfRange(file, (int) e.offset(), (int) (e.offset() + e.length())));
        }
        return out;
    }

    @Test
    void aSingleHevcImage() throws IOException {
        HeifInfo info = read(TestHeif.still(640, 480).build());

        assertThat(info.kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(info.majorBrand()).isEqualTo("heic");
        assertThat(info.brands()).containsExactly("mif1", "heic");
        assertThat(info.primaryId()).isEqualTo(1);
        assertThat(info.primaryType()).isEqualTo("hvc1");
        assertThat(info.width()).isEqualTo(640);
        assertThat(info.height()).isEqualTo(480);
        assertThat(info.turn()).isZero();
        assertThat(info.mirror()).isNull();
        assertThat(info.orientation()).isEqualTo(1);
        assertThat(info.bitDepth()).isEqualTo(8);
        assertThat(info.auxiliary()).isFalse();
        assertThat(info.exif()).isEmpty();
    }

    @Test
    void anIphoneStyleGridTakesItsSizeFromTheGridItem() throws IOException {
        HeifInfo info = read(TestHeif.still(4032, 3024).grid(8, 6, "hvc1").build());

        assertThat(info.kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(info.primaryType()).isEqualTo("grid");
        assertThat(info.codedAs()).isEqualTo("hvc1");
        assertThat(info.width()).isEqualTo(4032);
        assertThat(info.height()).isEqualTo(3024);
    }

    @Test
    void turnsAndMirrorsGiveTheExifOrientationApplesEncoderMeans() throws IOException {
        // As sips writes them from a JPEG of each EXIF orientation (checked 2026-10-27): irot first, then imir.
        int[][] cases = {{1, -1, -1}, {2, -1, 1}, {3, 2, -1}, {4, -1, 0}, {5, 1, 0}, {6, 3, -1}, {7, 1, 1}, {8, 1, -1}};
        for (int[] c : cases) {
            TestHeif heif = TestHeif.still(64, 32);
            if (c[1] >= 0) {
                heif.turn(c[1]);
            }
            if (c[2] >= 0) {
                heif.mirror(c[2]);
            }
            HeifInfo info = read(heif.build());
            assertThat(info.orientation()).as("EXIF %d", c[0]).isEqualTo(c[0]);
            assertThat(info.displayedWidth()).as("EXIF %d", c[0]).isEqualTo(c[0] >= 5 ? 32 : 64);
            assertThat(info.displayedHeight()).as("EXIF %d", c[0]).isEqualTo(c[0] >= 5 ? 64 : 32);
        }
    }

    @Test
    void aMirrorListedBeforeTheTurnIsAppliedFirst() throws IOException {
        // mirror left-right, then a quarter turn anticlockwise: a transpose (5); the other order is 7
        HeifInfo info = read(TestHeif.still(64, 32).mirror(1).turn(1).mirrorFirst().build());

        assertThat(info.mirrorFirst()).isTrue();
        assertThat(info.orientation()).isEqualTo(5);
        assertThat(read(TestHeif.still(64, 32).mirror(1).turn(1).build()).orientation()).isEqualTo(7);
        assertThat(info.orientation(true, false)).as("the turn alone").isEqualTo(8);
        assertThat(info.orientation(false, true)).as("the mirror alone").isEqualTo(2);
    }

    @Test
    void bitDepthFromPixiOfTheImageOrItsTiles() throws IOException {
        assertThat(read(TestHeif.still(64, 32).bitDepth(10).build()).bitDepth()).isEqualTo(10);
        assertThat(read(TestHeif.still(1024, 512).grid(2, 1, "hvc1").bitDepth(10).build()).bitDepth()).isEqualTo(10);
    }

    @Test
    void auxiliaryImagesAreNoticed() throws IOException {
        assertThat(read(TestHeif.still(64, 32).auxiliary().build()).auxiliary()).isTrue();
    }

    @Test
    void exifStoredInMdatThroughIloc() throws IOException {
        byte[] tiff = TestExif.camera().taken("2024:05:01 10:22:33", null).tiffBlock();
        byte[] file = TestHeif.still(64, 32).exif(tiff).build();

        HeifInfo info = read(file);

        assertThat(bytesOf(file, info.exif())).isEqualTo(concat(u32(6), "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiff));
    }

    @Test
    void exifStoredInIdat() throws IOException {
        byte[] tiff = TestExif.camera().gps(18.52, 73.85, 560).tiffBlock();
        byte[] file = TestHeif.still(64, 32).exif(tiff).exifInIdat().build();

        HeifInfo info = read(file);

        assertThat(info.exif()).hasSize(1);
        assertThat(bytesOf(file, info.exif())).endsWith(tiff);
    }

    @Test
    void sequencesByBrandOrWithoutAPrimaryItem() throws IOException {
        assertThat(read(TestHeif.still(64, 32).brands("msf1", "msf1", "heic").withMoov().build()).kind())
                .isEqualTo(HeifInfo.Kind.SEQUENCE);
        assertThat(read(TestHeif.still(64, 32).brands("hevc", "mif1", "hevc").build()).kind()).isEqualTo(HeifInfo.Kind.SEQUENCE);
        assertThat(read(TestHeif.still(64, 32).brands("hevx", "mif1").build()).kind()).isEqualTo(HeifInfo.Kind.SEQUENCE);
        assertThat(read(TestHeif.still(64, 32).withoutPrimary().build()).kind()).isEqualTo(HeifInfo.Kind.SEQUENCE);
    }

    @Test
    void otherCodingsAndAvif() throws IOException {
        assertThat(read(TestHeif.still(64, 32).brands("mif1", "mif1", "heic").primaryType("jpeg").build()).kind())
                .isEqualTo(HeifInfo.Kind.OTHER_CODING);
        assertThat(read(TestHeif.still(64, 32).brands("mif1", "mif1").primaryType("unci").build()).kind())
                .isEqualTo(HeifInfo.Kind.OTHER_CODING);
        assertThat(read(TestHeif.still(64, 32).brands("mif1", "mif1").grid(2, 1, "vvc1").build()).kind())
                .isEqualTo(HeifInfo.Kind.OTHER_CODING);
        assertThat(read(TestHeif.still(64, 32).brands("avif", "mif1", "miaf").primaryType("av01").build()).kind())
                .isEqualTo(HeifInfo.Kind.AVIF);
        assertThat(read(TestHeif.still(64, 32).brands("mif1", "mif1", "miaf").primaryType("av01").build()).kind())
                .isEqualTo(HeifInfo.Kind.AVIF);
    }

    @Test
    void cameraHifAndSamsungBrandListsAreHeic() throws IOException {
        // Canon / Sony .HIF: heix with 10-bit tiles; Samsung: mif1 major with heic listed
        assertThat(read(TestHeif.still(6000, 4000).brands("heix", "mif1", "heix", "miaf").grid(12, 8, "hvc1").bitDepth(10)
                .build()).kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(read(TestHeif.still(4000, 3000).brands("mif1", "mif1", "heic", "miaf").build()).kind())
                .isEqualTo(HeifInfo.Kind.HEIC);
    }

    @Test
    void noImageSizeIsUnreadable() throws IOException {
        assertThat(read(TestHeif.still(64, 32).withoutSize().build()).kind()).isEqualTo(HeifInfo.Kind.UNREADABLE);
    }

    // ---- damaged input ----

    @Test
    void aBoxLongerThanTheFileIsUnreadable() throws IOException {
        byte[] file = TestHeif.still(64, 32).build();
        byte[] cut = Arrays.copyOf(file, file.length - 5); // mdat now claims more than is there

        assertThat(read(cut).readable()).isFalse();
    }

    @Test
    void aZeroSizedBoxCannotLoop() throws IOException {
        byte[] ftyp = box("ftyp", "heic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1));
        // a child box of size 4 (smaller than its header) would loop forever if taken at its word
        byte[] meta = fullBox("meta", 0, 0, concat(u32(4), "pitm".getBytes(StandardCharsets.ISO_8859_1), new byte[8]));

        assertThat(read(concat(ftyp, meta)).readable()).isFalse();
    }

    @Test
    void tooManyBoxesStop() throws IOException {
        byte[] ftyp = box("ftyp", "heic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1));
        byte[] many;
        byte[] free = box("free", new byte[0]);
        byte[][] parts = new byte[HeifBoxes.MAX_BOXES + 1][];
        Arrays.fill(parts, free);
        many = concat(parts);

        assertThat(read(concat(ftyp, many)).readable()).isFalse();
    }

    @Test
    void hugeCountsAreRefused() throws IOException {
        byte[] ftyp = box("ftyp", "heic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1));
        byte[] pitm = fullBox("pitm", 0, 0, u16(1));
        byte[] iinf = fullBox("iinf", 1, 0, u32(0xFFFFFFF0L));
        byte[] iloc = fullBox("iloc", 1, 0, concat(new byte[] {0x44, 0x00}, u16(0xFFFF)));
        byte[] ipma = box("iprp", fullBox("ipma", 0, 0, u32(0x7FFFFFFF)));

        assertThat(read(concat(ftyp, fullBox("meta", 0, 0, concat(pitm, iinf)))).readable()).isFalse();
        assertThat(read(concat(ftyp, fullBox("meta", 0, 0, concat(pitm, iloc)))).readable()).isFalse();
        assertThat(read(concat(ftyp, fullBox("meta", 0, 0, concat(pitm, ipma)))).readable()).isFalse();
    }

    @Test
    void aPropertyIndexPastTheListIsUnreadable() throws IOException {
        byte[] ftyp = box("ftyp", "heic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1));
        byte[] pitm = fullBox("pitm", 0, 0, u16(1));
        byte[] iinf = fullBox("iinf", 0, 0, concat(u16(1), fullBox("infe", 2, 0, concat(u16(1), u16(0),
                "hvc1\0".getBytes(StandardCharsets.ISO_8859_1)))));
        byte[] iprp = box("iprp", concat(box("ipco", new byte[0]),
                fullBox("ipma", 0, 0, concat(u32(1), u16(1), new byte[] {1, (byte) 0x85}))));

        assertThat(read(concat(ftyp, fullBox("meta", 0, 0, concat(pitm, iinf, iprp)))).readable()).isFalse();
    }

    @Test
    void exifOutsideTheFileIsLeftOutNotFollowed() throws IOException {
        byte[] file = TestHeif.still(64, 32).exif(TestExif.camera().tiffBlock()).build();
        // the mdat holds the picture then the Exif item: cut the Exif item's bytes and shrink mdat to match
        int exifLength = 4 + 6 + TestExif.camera().tiffBlock().length;
        byte[] cut = Arrays.copyOf(file, file.length - exifLength);
        int mdat = indexOf(cut, "mdat") - 4;
        System.arraycopy(u32(cut.length - mdat), 0, cut, mdat, 4);

        HeifInfo info = read(cut);

        assertThat(info.kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(info.exif()).isEmpty();
    }

    @Test
    void anIlocOfEmptyExtentsCannotFillMemory() throws IOException {
        // the review's bomb (fixes J1): iloc v0 with offset, length and base sizes 0, 4,096 items of 65,535 extents each
        byte[] ftyp = box("ftyp", "heic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1));
        java.io.ByteArrayOutputStream items = new java.io.ByteArrayOutputStream();
        for (int id = 1; id <= 4096; id++) {
            items.writeBytes(concat(u16(id), u16(0), u16(0xFFFF)));
        }
        byte[] iloc = fullBox("iloc", 0, 0, concat(new byte[] {0x00, 0x00}, u16(4096), items.toByteArray()));
        byte[] file = concat(ftyp, fullBox("meta", 0, 0, concat(fullBox("pitm", 0, 0, u16(1)), iloc)));
        assertThat(file.length).isBetween(24_000, 26_000);
        long start = System.nanoTime();

        HeifInfo info = read(file);

        assertThat(info.readable()).isFalse();
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        // more extents in all than items allowed, each with bytes, is refused too
        java.io.ByteArrayOutputStream many = new java.io.ByteArrayOutputStream();
        for (int id = 1; id <= 3; id++) {
            many.writeBytes(concat(u16(id), u16(0), u16(2000)));
            for (int e = 0; e < 2000; e++) {
                many.writeBytes(u32(0));
            }
        }
        byte[] wide = fullBox("iloc", 0, 0, concat(new byte[] {0x40, 0x00}, u16(3), many.toByteArray()));
        assertThat(read(concat(ftyp, fullBox("meta", 0, 0, concat(fullBox("pitm", 0, 0, u16(1)), wide)))).readable())
                .isFalse();
    }

    @Test
    void deepNestingIsRefused() throws IOException {
        byte[] ftyp = box("ftyp", "heic\0\0\0\0mif1heic".getBytes(StandardCharsets.ISO_8859_1));
        // iprp inside iprp … deeper than any real file
        byte[] nested = box("ipco", new byte[0]);
        for (int i = 0; i < 20; i++) {
            nested = box("iprp", nested);
        }
        HeifInfo info = read(concat(ftyp, fullBox("meta", 0, 0, concat(fullBox("pitm", 0, 0, u16(1)), nested))));

        assertThat(info.readable()).isFalse();
    }

    @Test
    void randomBytesNeverThrow() throws IOException {
        Random random = new Random(7);
        byte[] good = TestHeif.still(4032, 3024).grid(8, 6, "hvc1").exif(TestExif.camera().tiffBlock()).auxiliary().build();
        for (int round = 0; round < 500; round++) {
            byte[] file = good.clone();
            for (int k = 0; k < 8; k++) {
                file[16 + random.nextInt(file.length - 16)] = (byte) random.nextInt(256);
            }
            read(file); // never anything but a result
        }
        byte[] noise = new byte[2048];
        random.nextBytes(noise);
        assertThat(read(noise).readable()).isFalse();
        assertThat(read(new byte[0]).readable()).as("no ftyp box").isFalse();
    }

    private static int indexOf(byte[] b, String text) {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + t.length <= b.length; i++) {
            for (int j = 0; j < t.length; j++) {
                if (b[i + j] != t[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
