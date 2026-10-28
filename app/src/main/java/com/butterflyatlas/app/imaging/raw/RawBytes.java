package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Bounded reads from a RAW file: every read is checked against the file's size first, and reads are small (a
 * directory, a box header, a JPEG marker), so a damaged or hostile offset or length never reads past the end or fills
 * memory. A read outside the file is {@link Damaged}, which the readers catch: it only ends the part being read.
 */
final class RawBytes {

    /** The largest single read the readers make (a directory of {@value #MAX_ENTRIES} entries fits easily). */
    static final int MAX_READ = 64 * 1024;
    /** Entries a directory may list; more is not a real camera's directory. */
    static final int MAX_ENTRIES = 1000;

    /** An offset or length outside the file, or a structure that cannot be right. */
    static final class Damaged extends RuntimeException {
        Damaged(String what) {
            super(what, null, false, false);
        }
    }

    private final SeekableByteChannel channel;
    private final long size;

    RawBytes(SeekableByteChannel channel, long size) {
        this.channel = channel;
        this.size = size;
    }

    long size() {
        return size;
    }

    /** Whether {@code [offset, offset + length)} lies inside the file. */
    boolean inside(long offset, long length) {
        return offset >= 0 && length >= 0 && offset <= size && length <= size - offset;
    }

    /** {@code length} bytes at {@code offset}; {@link Damaged} when they are not all inside the file. */
    byte[] read(long offset, int length) throws IOException {
        if (length > MAX_READ || !inside(offset, length)) {
            throw new Damaged("A read of " + length + " bytes at " + offset + " is outside the file");
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new Damaged("The file ended early at " + (offset + buffer.position()));
            }
        }
        return buffer.array();
    }

    /** The bytes there, or fewer when the file ends first (for the first bytes of a file). */
    byte[] readUpTo(long offset, int length) throws IOException {
        long available = Math.max(0, Math.min(length, size - offset));
        return read(offset, (int) available);
    }

    static int u16(byte[] b, int at, boolean little) {
        check(b, at, 2);
        return little ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 : (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    static long u32(byte[] b, int at, boolean little) {
        check(b, at, 4);
        long value = 0;
        for (int i = 0; i < 4; i++) {
            int shift = little ? 8 * i : 8 * (3 - i);
            value |= (long) (b[at + i] & 0xFF) << shift;
        }
        return value;
    }

    /** A big-endian 64-bit value; {@link Damaged} when it does not fit a non-negative long. */
    static long u64(byte[] b, int at) {
        check(b, at, 8);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = value << 8 | (b[at + i] & 0xFF);
        }
        if (value < 0) {
            throw new Damaged("A 64-bit size or offset beyond any file");
        }
        return value;
    }

    static String ascii(byte[] b, int at, int length) {
        if (at < 0 || at + length > b.length) {
            return "";
        }
        return new String(b, at, length, StandardCharsets.ISO_8859_1);
    }

    private static void check(byte[] b, int at, int length) {
        if (at < 0 || at + length > b.length) {
            throw new Damaged("A value past the end of what was read");
        }
    }
}
