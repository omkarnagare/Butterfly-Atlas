package com.butterflyatlas.app.imaging;

import javax.imageio.stream.ImageInputStreamImpl;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A seekable ImageIO input over an NIO channel. Unlike {@code ImageIO.createImageInputStream(File)}
 * (a {@code RandomAccessFile}), a channel from {@link Files#newByteChannel} is opened with
 * FILE_SHARE_DELETE on Windows, so the file can still be moved or deleted while it is being read.
 */
final class ChannelImageInputStream extends ImageInputStreamImpl {

    private boolean isClosed() {
        try {
            checkClosed();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static final int BUFFER = 64 * 1024;

    private final SeekableByteChannel channel;
    private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER);
    private long bufferStart;
    private int bufferLength;

    ChannelImageInputStream(Path file) throws IOException {
        this(Files.newByteChannel(file));
    }

    /** Reads {@code channel} from its start; closing this stream closes the channel. */
    ChannelImageInputStream(SeekableByteChannel channel) {
        this.channel = channel;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkClosed();
        if (len == 0) {
            return 0;
        }
        if (streamPos < bufferStart || streamPos >= bufferStart + bufferLength) {
            if (!fill()) {
                return -1;
            }
        }
        int from = (int) (streamPos - bufferStart);
        int n = Math.min(len, bufferLength - from);
        buffer.position(from);
        buffer.get(b, off, n);
        streamPos += n;
        bitOffset = 0;
        return n;
    }

    private boolean fill() throws IOException {
        channel.position(streamPos);
        buffer.clear();
        int read = 0;
        while (read == 0) {
            read = channel.read(buffer);
        }
        if (read < 0) {
            bufferLength = 0;
            return false;
        }
        bufferStart = streamPos;
        bufferLength = read;
        return true;
    }

    @Override
    public long length() {
        try {
            return channel.size();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void close() throws IOException {
        if (!isClosed()) { // ImageIO.read closes the stream itself, and try-with-resources closes it again
            super.close();
            channel.close();
        }
    }
}
