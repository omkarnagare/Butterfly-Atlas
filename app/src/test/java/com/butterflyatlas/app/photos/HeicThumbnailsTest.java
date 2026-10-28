package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestHeif;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEIC thumbnails and the ledger (Phase 7b audit 8, 9, 12): a timeout is held back, not failed, until its third; no
 * decoder slot puts the job back in line; becoming available forgets failures and queues the rest.
 */
class HeicThumbnailsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    /** What the stub decoder does next for a HEIC: null makes the thumbnail, else throws this. */
    private final Deque<IOException> next = new ArrayDeque<>();
    private final AtomicInteger heicCalls = new AtomicInteger();
    private Library library;
    private Thumbnails thumbnails;
    private PhotoStore store;

    /** The real processor, except that a HEIC "decode" answers from {@link #next} and draws a JPEG. */
    private final PhotoProcessor processor = new PhotoProcessor() {
        private final ImageIoPhotoProcessor real = new ImageIoPhotoProcessor();

        @Override
        public PhotoFacts read(Path file) throws IOException {
            return real.read(file);
        }

        @Override
        public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
            thumbnail(source, orientation, longSide, target, Use.CATCH_UP);
        }

        @Override
        public void thumbnail(Path source, int orientation, int longSide, Path target, Use use) throws IOException {
            if (source.getFileName().toString().endsWith(".heic")) {
                heicCalls.incrementAndGet();
                IOException e;
                synchronized (next) {
                    e = next.pollFirst();
                }
                if (e != null) {
                    throw e;
                }
                Files.write(target, com.butterflyatlas.app.testing.TestImages.jpeg(40, 30));
                return;
            }
            real.thumbnail(source, orientation, longSide, target, use);
        }

        @Override
        public String dominantColor(Path image) throws IOException {
            return real.dominantColor(image);
        }
    };

    @BeforeEach
    void setUp() {
        HeicStatus.publish(HeicStatus.available("sips", "mac"));
        library = TestBundles.fixtureLibrary(dir);
        thumbnails = new Thumbnails(() -> library, processor, new TestClock(), Thumbnails.VERSION, nanos::get,
                Thumbnails.RETRY_FAILED_AFTER, Thumbnails.DISPLAY_WAIT);
        store = new PhotoStore(() -> library, processor, new TestClock(), x -> { });
    }

    @AfterEach
    void tearDown() {
        thumbnails.close();
        HeicStatus.publish(HeicStatus.checking("mac"));
    }

    private long addHeic() {
        return store.add("A5", new ByteArrayInputStream(TestHeif.still(640, 480).build()), "image/heic", null, List.of())
                .photo().id();
    }

    private String ledger(long id) {
        return library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, error, attempts FROM image_processing"
                    + " WHERE image_id = ? AND consumer = 'thumbnail'")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) + "|" + rs.getString(2) : "none";
                }
            }
        });
    }

    private static HeicDecodeException kind(HeicDecodeException.Kind kind) {
        return new HeicDecodeException(kind, kind.name());
    }

    @Test
    void aTimeoutIsHeldBackAndNeverFailed() {
        long id = addHeic();

        for (int round = 1; round <= 4; round++) {
            next.add(kind(HeicDecodeException.Kind.TIMED_OUT));
            assertThat(thumbnails.await(id, WAIT)).isEmpty();
            assertThat(ledger(id)).as("round %d: never written as failed (fixes J11)", round).isEqualTo("none");
            assertThat(thumbnails.lookup(id).pending()).as("held back: the tile keeps waiting").isTrue();
            assertThat(heicCalls.get()).as("not tried again within ten minutes").isEqualTo(round);
            nanos.addAndGet(Thumbnails.RETRY_FAILED_AFTER.toNanos() + 1); // ten minutes on: tried again
        }
        assertThat(thumbnails.await(id, WAIT)).isPresent();
    }

    @Test
    void noDecoderSlotPutsTheJobBackInLine() {
        long id = addHeic();
        next.add(kind(HeicDecodeException.Kind.BUSY));
        next.add(kind(HeicDecodeException.Kind.BUSY));

        assertThat(thumbnails.await(id, WAIT)).isPresent();
        assertThat(heicCalls.get()).isEqualTo(3);
        assertThat(ledger(id)).isEqualTo("done|null");
        assertThat(thumbnails.progress().pending()).isZero();
    }

    @Test
    void noDecoderLeavesNothingBehindAndBecomingAvailableQueuesTheRest() {
        HeicStatus.publish(HeicStatus.missing("mac", "failed"));
        long id = addHeic();
        assertThat(thumbnails.catchUp()).as("not queued while missing").isZero();
        assertThat(thumbnails.await(id, Duration.ZERO)).isEmpty();
        assertThat(heicCalls.get()).isZero();

        // a real failure while it worked before
        HeicStatus.publish(HeicStatus.available("sips", "mac"));
        next.add(new IOException("could not read"));
        assertThat(thumbnails.await(id, WAIT)).isEmpty();
        assertThat(ledger(id)).startsWith("failed|");

        assertThat(thumbnails.heicAvailable()).isEqualTo(1);
        assertThat(thumbnails.await(id, WAIT)).isPresent();
        assertThat(ledger(id)).isEqualTo("done|null");
    }
}
