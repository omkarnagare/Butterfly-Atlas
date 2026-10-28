package com.butterflyatlas.app.imaging;

import com.drew.lang.BufferBoundsException;
import com.drew.lang.RandomAccessReader;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * metadata-extractor's random access over an NIO channel, for TIFF, whose directories can sit
 * anywhere in the file: only the 64 KB blocks a directory touches are read, where the stream
 * reader would buffer everything up to the furthest offset (up to the whole of a large scan).
 */
final class ChannelRandomAccessReader extends RandomAccessReader {

    private static final int BLOCK = 64 * 1024;

    private final SeekableByteChannel channel;
    private final long length;
    private final ByteBuffer block = ByteBuffer.allocate(BLOCK);
    private long blockStart = -1;
    private int blockLength;

    ChannelRandomAccessReader(SeekableByteChannel channel) throws IOException {
        this.channel = channel;
        this.length = Math.min(channel.size(), Integer.MAX_VALUE); // the reader's offsets are ints
    }

    @Override
    public int toUnshiftedOffset(int localOffset) {
        return localOffset;
    }

    @Override
    public long getLength() {
        return length;
    }

    @Override
    public byte getByte(int index) throws IOException {
        validateIndex(index, 1);
        load(index);
        return block.get((int) (index - blockStart));
    }

    @Override
    public byte[] getBytes(int index, int count) throws IOException {
        validateIndex(index, count);
        byte[] bytes = new byte[count];
        int done = 0;
        while (done < count) {
            long at = (long) index + done;
            load(at);
            int from = (int) (at - blockStart);
            int n = Math.min(count - done, blockLength - from);
            block.get(from, bytes, done, n);
            done += n;
        }
        return bytes;
    }

    @Override
    protected void validateIndex(int index, int bytesRequested) throws IOException {
        if (!isValidIndex(index, bytesRequested)) {
            throw new BufferBoundsException(index, bytesRequested, length);
        }
    }

    @Override
    protected boolean isValidIndex(int index, int bytesRequested) {
        return bytesRequested >= 0 && index >= 0 && (long) index + bytesRequested <= length;
    }

    private void load(long at) throws IOException {
        if (blockStart >= 0 && at >= blockStart && at < blockStart + blockLength) {
            return;
        }
        block.clear();
        channel.position(at);
        int read = 0;
        while (block.hasRemaining()) {
            int n = channel.read(block);
            if (n < 0) {
                break;
            }
            read += n;
        }
        if (read == 0) {
            throw new EOFException("No bytes at " + at);
        }
        blockStart = at;
        blockLength = read;
    }
}
