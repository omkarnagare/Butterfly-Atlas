package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/**
 * A read-only view of {@code length} bytes at {@code offset} of another channel (a RAW file's preview), so the JPEG
 * decoder or an answer reads the preview and nothing around it. Closing it closes the underlying channel.
 */
public final class SliceChannel implements SeekableByteChannel {

    private final SeekableByteChannel channel;
    private final long offset;
    private final long length;
    private long position;

    /** Fails when the slice is not inside the channel. */
    public SliceChannel(SeekableByteChannel channel, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset > channel.size() || length > channel.size() - offset) {
            throw new IOException("The slice " + offset + "+" + length + " is outside the file");
        }
        this.channel = channel;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (position >= length) {
            return -1;
        }
        int want = (int) Math.min(dst.remaining(), length - position);
        ByteBuffer limited = dst.slice().limit(want);
        channel.position(offset + position);
        int n = channel.read(limited);
        if (n > 0) {
            dst.position(dst.position() + n);
            position += n;
        }
        return n;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) {
        if (newPosition < 0) {
            throw new IllegalArgumentException("A negative position");
        }
        position = newPosition;
        return this;
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
