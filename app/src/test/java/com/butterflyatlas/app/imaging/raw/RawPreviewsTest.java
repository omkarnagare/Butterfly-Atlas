package com.butterflyatlas.app.imaging.raw;

import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import com.butterflyatlas.app.testing.TestRaw.Ifd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

import static com.butterflyatlas.app.testing.TestRaw.concat;
import static org.assertj.core.api.Assertions.assertThat;

class RawPreviewsTest {

    @TempDir
    Path dir;

    private RawContents read(byte[] file) throws IOException {
        Path path = dir.resolve("photo.raw");
        Files.write(path, file);
        try (SeekableByteChannel channel = Files.newByteChannel(path)) {
            return RawPreviews.read(channel);
        }
    }

    private static byte[] slice(byte[] file, RawPreview preview) {
        return Arrays.copyOfRange(file, (int) preview.offset(), (int) (preview.offset() + preview.length()));
    }

    // ---- TIFF-style ----

    @Test
    void findsAJpegInterchangePreviewInIfd0() throws IOException {
        byte[] jpeg = TestRaw.preview();
        byte[] file = TestRaw.tiff(new Ifd().shortValue(0x0112, 8).jpeg(jpeg));

        RawContents found = read(file);

        assertThat(found.preview()).isNotNull();
        assertThat(slice(file, found.preview())).isEqualTo(jpeg);
        assertThat(found.preview().storedWidth()).isEqualTo(1024);
        assertThat(found.preview().storedHeight()).isEqualTo(768);
        assertThat(found.preview().orientation()).isEqualTo(8);
        assertThat(found.preview().width()).isEqualTo(768);
        assertThat(found.preview().height()).isEqualTo(1024);
        assertThat(found.family()).isNull(); // a TIFF-shaped RAW only its name tells apart
    }

    @Test
    void choosesTheLargestPreviewAcrossSubIfdsAndTheChain() throws IOException {
        byte[] small = TestImages.jpeg(1200, 800);
        byte[] large = TestImages.jpeg(1600, 1200);
        byte[] thumb = TestImages.jpeg(160, 120);
        Ifd ifd0 = new Ifd().longValue(0x00FE, 1).jpeg(thumb)
                .subIfds(new Ifd().longValue(0x00FE, 1).jpeg(small),
                        new Ifd().longValue(0x00FE, 0).longValue(0x0100, 6000).longValue(0x0101, 4000).shortValue(0x0103, 7)
                                .blob(0x0111, TestRaw.lossless(3000, 4000)).longValue(0x0117, 100))
                .next(new Ifd().jpeg(large));

        RawContents found = read(TestRaw.tiff(ifd0));

        assertThat(found.preview().storedWidth()).isEqualTo(1600);
        assertThat(found.sensor()).isEqualTo(new RawContents.Size(6000, 4000));
    }

    @Test
    void readsADngPreviewStripAndKnowsTheFamily() throws IOException {
        byte[] jpeg = TestRaw.preview();
        Ifd ifd0 = new Ifd().bytes(0xC612, TestRaw.BYTE, new byte[] {1, 4, 0, 0}).longValue(0x00FE, 1).strip(jpeg, 7)
                .subIfds(new Ifd().longValue(0x00FE, 0).strip(TestRaw.lossless(4000, 3000), 7));

        RawContents found = read(TestRaw.tiff(ifd0));

        assertThat(found.family()).isEqualTo("dng");
        assertThat(found.preview().storedWidth()).isEqualTo(1024);
    }

    @Test
    void aLosslessJpegIsNeverThePreview() throws IOException {
        Ifd ifd0 = new Ifd().subIfds(new Ifd().strip(TestRaw.lossless(6000, 4000), 7));

        assertThat(read(TestRaw.tiff(ifd0)).preview()).isNull();
    }

    @Test
    void aPreviewUnder1024PxIsNotUsable() throws IOException {
        assertThat(read(TestRaw.tiff(new Ifd().jpeg(TestImages.jpeg(1000, 700)))).preview()).isNull();
        assertThat(read(TestRaw.tiff(new Ifd().jpeg(TestImages.jpeg(700, 1024)))).preview()).isNotNull();
    }

    @Test
    void readsACr2FullSizeStrip() throws IOException {
        byte[] jpeg = TestRaw.preview();
        RawContents found = read(TestRaw.cr2(new Ifd().shortValue(0x0112, 1).strip(jpeg, 6)
                .next(new Ifd().jpeg(TestImages.jpeg(160, 120)))));

        assertThat(found.family()).isEqualTo("cr2");
        assertThat(found.preview().storedWidth()).isEqualTo(1024);
    }

    @Test
    void readsAnOrfMakerNotePreview() throws IOException {
        byte[] jpeg = TestRaw.preview();
        byte[] note = TestRaw.olympusMakerNote(jpeg);
        byte[] file = TestRaw.orf(new Ifd().shortValue(0x0112, 6)
                .child(0x8769, new Ifd().bytes(0x927C, TestRaw.UNDEFINED, note)));

        RawContents found = read(file);

        assertThat(found.family()).isEqualTo("orf");
        assertThat(slice(file, found.preview())).isEqualTo(jpeg);
        assertThat(found.preview().orientation()).isEqualTo(6);
    }

    @Test
    void readsAnRw2JpgFromRaw() throws IOException {
        byte[] jpeg = TestRaw.preview();
        byte[] file = TestRaw.rw2(new Ifd().shortValue(0x0112, 3).bytes(0x002E, TestRaw.UNDEFINED, jpeg));

        RawContents found = read(file);

        assertThat(found.family()).isEqualTo("rw2");
        assertThat(slice(file, found.preview())).isEqualTo(jpeg);
        assertThat(found.preview().orientation()).isEqualTo(3);
    }

    // ---- CR3 and RAF ----

    @Test
    void readsTheCr3TrackJpegAndItsCmtBoxes() throws IOException {
        byte[] full = TestImages.jpeg(1600, 1200);
        byte[] prvw = TestRaw.preview();
        byte[] cmt1 = TestRaw.cmt(new Ifd().shortValue(0x0112, 6));
        byte[] file = TestRaw.cr3(full, prvw, cmt1, TestRaw.cmt(new Ifd().longValue(0x8827, 400)), null);

        RawContents found = read(file);

        assertThat(found.family()).isEqualTo("cr3");
        assertThat(slice(file, found.preview())).isEqualTo(full);
        assertThat(found.preview().orientation()).isEqualTo(6);
        assertThat(found.cmt()).extracting(RawContents.Slice::name).containsExactly("CMT1", "CMT2");
        RawContents.Slice first = found.cmt().get(0);
        assertThat(Arrays.copyOfRange(file, (int) first.offset(), (int) (first.offset() + first.length()))).isEqualTo(cmt1);
    }

    @Test
    void fallsBackToTheCr3PrvwBox() throws IOException {
        byte[] prvw = TestRaw.preview();
        byte[] file = TestRaw.cr3(null, prvw, null, null, null);

        assertThat(slice(file, read(file).preview())).isEqualTo(prvw);
    }

    @Test
    void readsTheRafJpegAndItsOrientation() throws IOException {
        byte[] jpeg = TestExif.camera().orientation(6).jpeg(TestRaw.preview());
        byte[] file = TestRaw.raf(jpeg);

        RawContents found = read(file);

        assertThat(found.family()).isEqualTo("raf");
        assertThat(slice(file, found.preview())).isEqualTo(jpeg);
        assertThat(found.preview().orientation()).isEqualTo(6);
        assertThat(found.embeddedJpeg().offset()).isEqualTo(found.preview().offset());
    }

    @Test
    void notARawFileHasNoPreview() throws IOException {
        assertThat(read(TestImages.jpeg(1200, 900)).preview()).isNull();
        assertThat(read("hello".getBytes(StandardCharsets.US_ASCII)).preview()).isNull();
        assertThat(read(new byte[0]).preview()).isNull();
    }

    // ---- family by bytes ----

    @Test
    void theFamilyComesFromTheBytes() throws IOException {
        assertThat(family(TestRaw.raf(TestRaw.preview()))).contains("raf");
        assertThat(family(TestRaw.cr3(null, TestRaw.preview(), null, null, null))).contains("cr3");
        assertThat(family(TestRaw.orf(new Ifd().shortValue(0x0112, 1)))).contains("orf");
        assertThat(family(TestRaw.rw2(new Ifd().shortValue(0x0112, 1)))).contains("rw2");
        assertThat(family(TestRaw.cr2(new Ifd().shortValue(0x0112, 1)))).contains("cr2");
        assertThat(family(TestRaw.tiff(new Ifd().bytes(0xC612, TestRaw.BYTE, new byte[] {1, 4, 0, 0})))).contains("dng");
        assertThat(family(TestImages.tiff(8, 8))).isEmpty();
        assertThat(family(TestImages.jpeg(8, 8))).isEmpty();
    }

    private Optional<String> family(byte[] file) throws IOException {
        Path path = dir.resolve("family.raw");
        Files.write(path, file);
        return RawPreviews.family(path);
    }

    // ---- damaged and hostile files ----

    @Test
    void anOffsetPastTheEndIsIgnored() throws IOException {
        assertThat(read(TestRaw.tiff(new Ifd().longValue(0x0201, 999_999_999L).longValue(0x0202, 50_000))).preview()).isNull();
        assertThat(read(TestRaw.tiff(new Ifd().longValue(0x8769, 999_999_999L))).preview()).isNull();
        assertThat(read(TestRaw.tiff(new Ifd().raw(0x014A, TestRaw.LONG, 3, 999_999_999L))).preview()).isNull();
    }

    @Test
    void aHugeLengthIsIgnored() throws IOException {
        byte[] jpeg = TestRaw.preview();
        Ifd ifd0 = new Ifd().blob(0x0201, jpeg).longValue(0x0202, 0xFFFF_FFF0L);
        assertThat(read(TestRaw.tiff(ifd0)).preview()).isNull();
    }

    @Test
    void aPreviewOver64MbIsIgnored() throws IOException {
        byte[] jpeg = TestRaw.preview();
        byte[] file = TestRaw.tiff(new Ifd().blob(0x0201, jpeg).longValue(0x0202, 65L * 1024 * 1024));

        RawContents found = RawPreviews.read(new SizedChannel(file, 200L * 1024 * 1024));

        assertThat(found.preview()).isNull();
    }

    @Test
    void aLoopOfDirectoriesStopsTheWalk() throws IOException {
        byte[] jpeg = TestRaw.preview();
        byte[] file = TestRaw.tiff(new Ifd().jpeg(jpeg));
        int entries = (file[8] & 0xFF) | (file[9] & 0xFF) << 8;
        byte[] looped = TestRaw.patch32(file, 8 + 2 + 12 * entries, 8); // IFD0's next is IFD0

        RawContents found = read(looped);

        assertThat(slice(looped, found.preview())).isEqualTo(jpeg); // what came before the loop still counts
    }

    @Test
    void nestingDeeperThanFourLevelsIsNotFollowed() throws IOException {
        Ifd deepest = new Ifd().jpeg(TestRaw.preview());
        Ifd level = deepest;
        for (int i = 0; i < 5; i++) {
            level = new Ifd().subIfds(level);
        }
        assertThat(read(TestRaw.tiff(level)).preview()).isNull();

        Ifd allowed = new Ifd().jpeg(TestRaw.preview());
        for (int i = 0; i < 4; i++) {
            allowed = new Ifd().subIfds(allowed);
        }
        assertThat(read(TestRaw.tiff(allowed)).preview()).isNotNull();
    }

    @Test
    void moreDirectoriesThanAnyCameraWritesStopTheWalk() throws IOException {
        Ifd last = new Ifd().jpeg(TestRaw.preview());
        Ifd chain = last;
        for (int i = 0; i < 70; i++) {
            chain = new Ifd().shortValue(0x0112, 1).next(chain);
        }
        assertThat(read(TestRaw.tiff(chain)).preview()).isNull();
    }

    @Test
    void aDirectoryWithTooManyEntriesIsDamaged() throws IOException {
        byte[] file = concat(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, (byte) 0xFF, (byte) 0xFF}, new byte[64]);
        assertThat(read(file).preview()).isNull();
    }

    @Test
    void aTruncatedFileHasNoPreview() throws IOException {
        byte[] file = TestRaw.tiff(new Ifd().jpeg(TestRaw.preview()));
        for (int cut : new int[] {3, 7, 9, 20, 40, file.length - 100}) {
            assertThat(read(Arrays.copyOf(file, cut)).preview()).as("cut at %d", cut).isNull();
        }
    }

    @Test
    void cr3BoxesThatDoNotFitOrNestTooDeepAreIgnored() throws IOException {
        byte[] prvw = TestRaw.preview();
        byte[] good = TestRaw.cr3(null, prvw, null, null, null);
        byte[] tooBig = concat(good, TestRaw.be32(0x7FFF_FFFFL), "moov".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(read(tooBig).preview()).isNotNull(); // the damaged box at the end only ends the walk

        assertThat(read(nestedTrack(1)).preview()).isNotNull();
        assertThat(read(nestedTrack(6)).preview()).isNull(); // the track's boxes sit more than 8 levels deep
    }

    /** A CR3 whose track (with its JPEG in mdat) sits inside {@code moovs} nested moov boxes. */
    private static byte[] nestedTrack(int moovs) {
        byte[] ftyp = TestRaw.box("ftyp", "crx \0\0\0\u0001".getBytes(StandardCharsets.ISO_8859_1));
        byte[] jpeg = TestRaw.preview();
        byte[] boxes = null;
        for (long sampleAt = 0; ; ) {
            byte[] nested = TestRaw.box("trak", TestRaw.box("mdia", TestRaw.box("minf", TestRaw.box("stbl", concat(
                    TestRaw.box("stsz", concat(new byte[4], TestRaw.be32(jpeg.length), TestRaw.be32(1))),
                    TestRaw.box("co64", concat(new byte[4], TestRaw.be32(1), TestRaw.be32(0), TestRaw.be32(sampleAt))))))));
            for (int i = 0; i < moovs; i++) {
                nested = TestRaw.box("moov", nested);
            }
            boxes = concat(ftyp, nested);
            if (sampleAt == boxes.length + 8L) {
                return concat(boxes, TestRaw.box("mdat", jpeg));
            }
            sampleAt = boxes.length + 8L;
        }
    }

    @Test
    void manyTinyCr3BoxesStopTheWalk() throws IOException {
        byte[] ftyp = TestRaw.box("ftyp", "crx \0\0\0\u0001".getBytes(StandardCharsets.ISO_8859_1));
        byte[] free = TestRaw.box("free", new byte[0]);
        byte[] many = new byte[free.length * 5000];
        for (int i = 0; i < 5000; i++) {
            System.arraycopy(free, 0, many, i * free.length, free.length);
        }
        byte[] prvwBox = TestRaw.cr3(null, TestRaw.preview(), null, null, null);
        byte[] file = concat(ftyp, many, Arrays.copyOfRange(prvwBox, ftyp.length, prvwBox.length));

        assertThat(read(file).preview()).isNull(); // the preview comes after the 4096th box
    }

    @Test
    void aRafWhoseJpegIsOutsideTheFileHasNoPreview() throws IOException {
        byte[] file = TestRaw.raf(TestRaw.preview());
        byte[] past = file.clone();
        System.arraycopy(TestRaw.be32(file.length), 0, past, 84, 4);
        assertThat(read(past).preview()).isNull();
        assertThat(read(Arrays.copyOf(file, 50)).preview()).isNull();
    }

    @Test
    void randomDamageNeverThrows() throws IOException {
        Random random = new Random(7);
        byte[][] samples = {
                TestRaw.tiff(new Ifd().shortValue(0x0112, 6).jpeg(TestRaw.preview()).subIfds(new Ifd().strip(TestRaw.preview(), 7))),
                TestRaw.orf(new Ifd().child(0x8769, new Ifd().bytes(0x927C, TestRaw.UNDEFINED, TestRaw.olympusMakerNote(TestRaw.preview())))),
                TestRaw.cr3(TestRaw.preview(), TestRaw.preview(), TestRaw.cmt(new Ifd().shortValue(0x0112, 3)), null, null),
                TestRaw.raf(TestExif.camera().jpeg(TestRaw.preview())),
        };
        for (byte[] sample : samples) {
            for (int round = 0; round < 150; round++) {
                byte[] damaged = sample.clone();
                int limit = Math.min(damaged.length, 600);
                for (int k = 0; k < 6; k++) {
                    damaged[random.nextInt(limit)] = (byte) random.nextInt(256);
                }
                RawContents found = read(damaged); // must not throw
                if (found.preview() != null) {
                    assertThat(found.preview().offset() + found.preview().length()).isLessThanOrEqualTo(damaged.length);
                }
            }
        }
    }

    /** A channel over {@code data} that reports a larger size; bytes past the data read as zeros. */
    private static final class SizedChannel implements SeekableByteChannel {
        private final byte[] data;
        private final long size;
        private long position;

        SizedChannel(byte[] data, long size) {
            this.data = data;
            this.size = size;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (position >= size) {
                return -1;
            }
            int n = (int) Math.min(dst.remaining(), size - position);
            for (int i = 0; i < n; i++) {
                long at = position + i;
                dst.put(at < data.length ? data[(int) at] : 0);
            }
            position += n;
            return n;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
