package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoIngestTest {

    @TempDir
    Path dir;

    private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger bytesRead = new AtomicInteger();

    /** Counts every read of photo facts. */
    private final PhotoProcessor counting = new PhotoProcessor() {
        @Override
        public PhotoFacts read(Path file) throws IOException {
            reads.incrementAndGet();
            return processor.read(file);
        }

        @Override
        public PhotoFacts readRaw(Path file) throws IOException {
            reads.incrementAndGet();
            return processor.readRaw(file);
        }

        @Override
        public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
            processor.thumbnail(source, orientation, longSide, target);
        }

        @Override
        public String dominantColor(Path image) throws IOException {
            return processor.dominantColor(image);
        }
    };

    /** Opens files through NIO, as the app does, counting the opens and the bytes that come through. */
    private final PhotoIngest ingest = new PhotoIngest(counting, PhotoStore.MAX_BYTES, file -> {
        opened.incrementAndGet();
        InputStream in = Files.newInputStream(file);
        return new InputStream() {
            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) {
                    bytesRead.incrementAndGet();
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len);
                if (n > 0) {
                    bytesRead.addAndGet(n);
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    });

    private Path file(String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    private static void refused(ThrowingRunnable work, String message) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.UNSUPPORTED);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void aFileInPlaceIsStreamedOnceAndItsFactsReadOnce() throws Exception {
        byte[] bytes = TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").jpeg(TestImages.jpeg(400, 300));
        Path photo = file("IMG_1.JPG", bytes);

        PhotoIngest.Result result = ingest.ingest(photo);

        assertThat(opened).as("one streaming pass").hasValue(1);
        assertThat(bytesRead).as("every byte once").hasValue(bytes.length);
        assertThat(reads).as("one read of the facts").hasValue(1);
        assertThat(result.sha256()).isEqualTo(PhotoFiles.sha256(photo));
        assertThat(result.size()).isEqualTo(bytes.length);
        assertThat(result.format()).isEqualTo(PhotoFormat.JPEG);
        assertThat(result.facts().width()).isEqualTo(400);
        assertThat(result.facts().capturedAt()).hasToString("2024-05-01T04:52:33Z");
    }

    @Test
    void factsOfAFileInPlaceReadOnlyItsFirstBytesAndThenTheFactsOnce() throws Exception {
        byte[] bytes = TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").jpeg(TestImages.jpeg(400, 300));
        Path photo = file("IMG_2.JPG", bytes);

        PhotoFacts facts = ingest.facts(photo);

        assertThat(opened).as("one look at the first bytes").hasValue(1);
        assertThat(bytesRead).as("no more than the first bytes, not the whole file").hasValueLessThanOrEqualTo(PhotoFormat.HEAD_BYTES);
        assertThat(bytes.length).isGreaterThan(PhotoFormat.HEAD_BYTES * 10);
        assertThat(reads).as("one read of the facts").hasValue(1);
        assertThat(facts.width()).isEqualTo(400);
        assertThat(facts.capturedAt()).hasToString("2024-05-01T04:52:33Z");
    }

    @Test
    void factsRefuseWhatIngestRefusesAndAreNullForAnUnreadablePhoto() throws Exception {
        refused(() -> ingest.facts(file("raw.tif", TestExif.camera().dng().tiff(8, 6))), PhotoFormat.RAW_NO_PREVIEW);
        refused(() -> ingest.facts(file("DSC_0002.NEF", TestExif.camera().tiff(8, 6))), PhotoFormat.RAW_NO_PREVIEW);
        refused(() -> ingest.facts(file("notes2.jpg", "hello".getBytes(StandardCharsets.US_ASCII))),
                "This file isn't a photo Butterfly Atlas can read.");
        assertThat(ingest.facts(file("broken2.jpg", Arrays.copyOf(TestImages.jpeg(40, 30), 20))))
                .as("a supported photo whose details cannot be read").isNull();
        assertThatThrownBy(() -> ingest.facts(dir.resolve("missing.jpg"))).isInstanceOf(IOException.class);
    }

    @Test
    void anUploadIsCopiedWhileItIsHashed() throws Exception {
        byte[] bytes = TestImages.png(30, 10);
        Path target = dir.resolve("incoming.part");

        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        AtomicInteger uploadBytes = new AtomicInteger();
        InputStream counted = new InputStream() {
            @Override
            public int read() throws IOException {
                int b = source.read();
                if (b >= 0) {
                    uploadBytes.incrementAndGet();
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = source.read(b, off, len);
                if (n > 0) {
                    uploadBytes.addAndGet(n);
                }
                return n;
            }
        };

        PhotoIngest.Result result = ingest.ingest(counted, target, "leaf.png");

        assertThat(uploadBytes).as("every byte of the upload once").hasValue(bytes.length);
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
        assertThat(result.sha256()).isEqualTo(PhotoFiles.sha256(target));
        assertThat(result.size()).isEqualTo(bytes.length);
        assertThat(result.format()).isEqualTo(PhotoFormat.PNG);
        assertThat(result.facts().height()).isEqualTo(10);
        assertThat(reads).hasValue(1);
        assertThat(opened).as("the upload itself is the stream").hasValue(0);
    }

    @Test
    void anUploadOverTheLimitIsRefusedWhileStreaming() {
        PhotoIngest small = new PhotoIngest(processor, 1000);
        byte[] bytes = TestImages.jpeg(200, 200);

        assertThatThrownBy(() -> small.ingest(new ByteArrayInputStream(bytes), dir.resolve("big.part"), "big.jpg"))
                .isInstanceOfSatisfying(AtlasException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo(
                            "This file is larger than 1 MB, which is more than Butterfly Atlas accepts for one photo.");
                });
        assertThat(small.maxBytes()).isEqualTo(1000);
    }

    @Test
    void rawPhotosComeInWithTheirFamilysOwnExtension() throws Exception {
        byte[] nef = TestRaw.tiff(new TestRaw.Ifd().shortValue(0x0112, 1).jpeg(TestRaw.preview()));
        byte[] dng = TestRaw.tiff(new TestRaw.Ifd().bytes(0xC612, TestRaw.BYTE, new byte[] {1, 4, 0, 0}).strip(TestRaw.preview(), 7));
        byte[] raf = TestRaw.raf(TestRaw.preview());

        PhotoIngest.Result byName = ingest.ingest(new ByteArrayInputStream(nef), dir.resolve("n.part"), "DSC_0042.NEF");
        PhotoIngest.Result dngAsTif = ingest.ingest(new ByteArrayInputStream(dng), dir.resolve("d.part"), "scan.tif");
        PhotoIngest.Result byBytes = ingest.ingest(new ByteArrayInputStream(raf), dir.resolve("r.part"), "upload");
        PhotoIngest.Result inPlace = ingest.ingest(file("IMG_0001.Cr3", TestRaw.cr3(TestRaw.preview(), null, null, null, null)));

        assertThat(List.of(byName.format(), dngAsTif.format(), byBytes.format(), inPlace.format()))
                .containsOnly(PhotoFormat.RAW);
        assertThat(List.of(byName.extension(), dngAsTif.extension(), byBytes.extension(), inPlace.extension()))
                .containsExactly("nef", "dng", "raf", "cr3");
        assertThat(byName.facts().rawPreview().storedWidth()).isEqualTo(1024);
        assertThat(ingest.ingest(new ByteArrayInputStream(TestImages.jpeg(40, 30)), dir.resolve("j.part"), "fake.nef")
                .extension()).as("a JPEG named .nef is a JPEG").isEqualTo("jpg");
    }

    @Test
    void aRawWithoutAPreviewInPlaceIsKeptForTheRescanWithoutFacts() throws Exception {
        byte[] bare = TestExif.camera().tiff(8, 6);
        PhotoIngest.Result read = ingest.ingestKeepingRaw(file("DSC_0002.NEF", bare));

        assertThat(read.format()).isEqualTo(PhotoFormat.RAW);
        assertThat(read.facts()).isNull();
        assertThat(read.size()).isEqualTo(bare.length);
        assertThat(read.sha256()).isEqualTo(PhotoFiles.sha256(dir.resolve("DSC_0002.NEF")));
        assertThat(ingest.ingestKeepingRaw(file("raw.tif", TestExif.camera().dng().tiff(8, 6))).format())
                .isEqualTo(PhotoFormat.RAW);
        refused(() -> ingest.ingestKeepingRaw(file("notes3.jpg", "hello".getBytes(StandardCharsets.US_ASCII))),
                "This file isn't a photo Butterfly Atlas can read.");
        refused(() -> ingest.ingest(file("DSC_0003.NEF", bare)), PhotoFormat.RAW_NO_PREVIEW);
    }

    @Test
    void unsupportedPhotosAreRefusedWithTheirReason() throws Exception {
        byte[] tiff = TestExif.camera().tiff(8, 6);
        byte[] avif = "\0\0\0\u001cftypavif\0\0\0\0avifmif1miaf".getBytes(StandardCharsets.ISO_8859_1);

        refused(() -> ingest.ingest(new ByteArrayInputStream(tiff), dir.resolve("a.part"), "DSC_0001.NEF"),
                PhotoFormat.RAW_NO_PREVIEW);
        refused(() -> ingest.ingest(file("raw.tif", TestExif.camera().dng().tiff(8, 6))), PhotoFormat.RAW_NO_PREVIEW);
        refused(() -> ingest.ingest(new ByteArrayInputStream(TestExif.camera().dng().tiff(8, 6)), dir.resolve("b.part"), "x.tif"),
                PhotoFormat.RAW_NO_PREVIEW);
        refused(() -> ingest.ingest(new ByteArrayInputStream(avif), dir.resolve("c.part"), "x.avif"),
                "AVIF photos aren't supported yet.");
        refused(() -> ingest.ingest(file("notes.jpg", "hello".getBytes(StandardCharsets.US_ASCII))),
                "This file isn't a photo Butterfly Atlas can read.");
        assertThat(ingest.ingest(new ByteArrayInputStream(tiff), dir.resolve("d.part"), "scan.tif").format())
                .as("an ordinary TIFF").isEqualTo(PhotoFormat.TIFF);
    }

    @Test
    void aPhotoWhoseDetailsCannotBeReadIsRefusedAsAnUploadButKeptInPlace() throws Exception {
        byte[] broken = Arrays.copyOf(TestImages.jpeg(40, 30), 20);

        refused(() -> ingest.ingest(new ByteArrayInputStream(broken), dir.resolve("e.part"), "x.jpg"),
                "This file isn't a photo Butterfly Atlas can read.");
        PhotoIngest.Result inPlace = ingest.ingest(file("broken.jpg", broken));
        assertThat(inPlace.format()).isEqualTo(PhotoFormat.JPEG);
        assertThat(inPlace.facts()).as("registered without a size, as in 3a").isNull();
    }

    @Test
    void anUploadThatStopsPartWayIsInterrupted() {
        InputStream cut = new InputStream() {
            private boolean sent;

            @Override
            public int read() throws IOException {
                return read(new byte[1], 0, 1);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (sent) {
                    throw new IOException("Connection reset");
                }
                sent = true;
                Arrays.fill(b, off, off + 10, (byte) 1);
                return 10;
            }
        };

        assertThatThrownBy(() -> ingest.ingest(cut, dir.resolve("f.part"), "x.jpg"))
                .isInstanceOfSatisfying(AtlasException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AtlasException.Kind.INTERRUPTED);
                    assertThat(e.getMessage()).isEqualTo("The upload was interrupted.");
                });
    }
}
