package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.raw.RawPreview;
import com.butterflyatlas.app.imaging.raw.RawPreviews;
import com.butterflyatlas.app.imaging.raw.SliceChannel;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

/**
 * A RAW photo's preview for the viewer (contract §2, audit 11): read straight from its place inside the file on each
 * view, nothing cached. The stored place is used only while the file still has the size and modified time it was read
 * at and the bytes there start a JPEG; otherwise the preview is found again once (and {@link Preview#found} says so, for
 * the caller to write it back to the details), and a file with none usable is UNSUPPORTED with
 * {@link PhotoFormat#RAW_PREVIEW_UNREADABLE}. The bytes sent come from the very channel that was checked (fixes J9),
 * which is closed on every failure and when the answer is done; a file cut short while it is sent ends the answer with
 * an error, never with fewer bytes than its length passed off as the whole preview.
 */
public final class RawDisplays {

    /** Modified times differing by no more than this are the same (file systems round them differently). */
    private static final Duration SAME_TIME = Duration.ofSeconds(2);

    /** Where a preview found again is: for the details, with the file's size and modified time it was found in. */
    public record Found(long offset, long length, long fileSize, String modifiedAt) {
    }

    /**
     * Where the preview's bytes are in the open, checked {@code channel}, the file's size and time (the ETag), the bytes
     * to change on the way out, and where it was found when the stored place no longer held it (else null). Whoever gets
     * a Preview closes it, or the stream {@link #open} gives.
     */
    public record Preview(SeekableByteChannel channel, long offset, long length, long fileSize, FileTime modified,
                          RawPreviews.Patch patch, Found found) implements Closeable {

        /**
         * The preview's bytes, with its own EXIF orientation set to 1 (see {@link RawPreviews#uprightExif}); closing the
         * stream closes the channel. A file that ends before {@code length} bytes fails the read (EOFException).
         */
        public InputStream open() throws IOException {
            InputStream in;
            try {
                in = new Exact(Channels.newInputStream(new SliceChannel(channel, offset, length)), length);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            return patch == null ? in : new Patched(in, patch.at() - offset, patch.bytes());
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private RawDisplays() {
    }

    /**
     * The preview of the RAW {@code file}. {@code stored} is where its details say it is (null when they do not say),
     * read when the file had {@code storedSize} bytes and was modified at {@code storedModified}.
     */
    public static Preview of(Path file, PhotoFacts.Preview stored, long storedSize, String storedModified) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(file);
        try {
            long size = channel.size();
            FileTime modified = Files.getLastModifiedTime(file);
            long offset;
            long length;
            Found found = null;
            if (stored != null && size == storedSize && sameTime(modified, storedModified)
                    && startsAJpeg(channel, stored.offset(), stored.length())) {
                offset = stored.offset();
                length = stored.length();
            } else {
                RawPreview again = RawPreviews.read(channel).preview();
                if (again == null || !startsAJpeg(channel, again.offset(), again.length())) {
                    throw AtlasException.unsupported(PhotoFormat.RAW_PREVIEW_UNREADABLE);
                }
                offset = again.offset();
                length = again.length();
                found = new Found(offset, length, size, modified.toInstant().toString());
            }
            return new Preview(channel, offset, length, size, modified, RawPreviews.uprightExif(channel, offset, length),
                    found);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static boolean sameTime(FileTime modified, String stored) {
        try {
            return Duration.between(Instant.parse(stored), modified.toInstant()).abs().compareTo(SAME_TIME) <= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether {@code [offset, offset + length)} fits the file and starts with a JPEG's SOI. */
    private static boolean startsAJpeg(SeekableByteChannel channel, long offset, long length) throws IOException {
        if (offset < 0 || length < 4 || length > RawPreviews.MAX_PREVIEW_BYTES || offset > channel.size() - length) {
            return false;
        }
        ByteBuffer head = ByteBuffer.allocate(3);
        channel.position(offset);
        while (head.hasRemaining() && channel.read(head) > 0) {
            // keep reading: a channel may return fewer bytes than asked
        }
        byte[] b = head.array();
        return !head.hasRemaining() && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    /** A stream of exactly {@code length} bytes: one that ends sooner fails instead of looking complete. */
    private static final class Exact extends InputStream {
        private final InputStream in;
        private long left;

        Exact(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left == 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) {
                throw new EOFException("The RAW file ended " + left + " bytes before the end of its preview");
            }
            left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** A stream with a few bytes at {@code at} replaced. */
    private static final class Patched extends InputStream {
        private final InputStream in;
        private final long at;
        private final byte[] bytes;
        private long position;

        Patched(InputStream in, long at, byte[] bytes) {
            this.in = in;
            this.at = at;
            this.bytes = bytes;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            for (int i = 0; i < n; i++) {
                long p = position + i - at;
                if (p >= 0 && p < bytes.length) {
                    b[off + i] = bytes[(int) p];
                }
            }
            if (n > 0) {
                position += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
