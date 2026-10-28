package com.butterflyatlas.app.imaging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Location removal for PNG and WebP, chunk by chunk (Commons Imaging reads these formats but cannot rewrite them without
 * decoding): the metadata chunks are dropped whole and every other chunk is copied byte for byte. A file that has none
 * of them comes back as it is.
 */
final class MetadataChunks {

    /** PNG chunks that carry EXIF, XMP (iTXt) or text that may name a place. */
    static final Set<String> PNG_DROPPED = Set.of("eXIf", "iTXt", "tEXt", "zTXt");
    /** WebP chunks that carry EXIF and XMP. */
    static final Set<String> WEBP_DROPPED = Set.of("EXIF", "XMP ");
    /** The VP8X flags that say an EXIF or XMP chunk follows. */
    private static final int VP8X_EXIF = 0x08;
    private static final int VP8X_XMP = 0x04;
    private static final int PNG_SIGNATURE = 8;

    private MetadataChunks() {
    }

    /** The PNG without {@link #PNG_DROPPED} chunks; anything after IEND is kept as it is. */
    static byte[] png(byte[] png) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length);
        out.write(png, 0, PNG_SIGNATURE);
        ByteBuffer in = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        int at = PNG_SIGNATURE;
        boolean dropped = false;
        while (true) {
            if (png.length - at < 12) {
                throw new IOException("The PNG ends before its IEND chunk");
            }
            long length = Integer.toUnsignedLong(in.getInt(at));
            if (length > png.length - at - 12L) {
                throw new IOException("A PNG chunk runs past the end of the file");
            }
            String type = new String(png, at + 4, 4, StandardCharsets.ISO_8859_1);
            int whole = 12 + (int) length;
            if (PNG_DROPPED.contains(type)) {
                dropped = true;
            } else {
                out.write(png, at, whole);
            }
            at += whole;
            if (type.equals("IEND")) {
                out.write(png, at, png.length - at);
                return dropped ? out.toByteArray() : png;
            }
        }
    }

    /**
     * The WebP without {@link #WEBP_DROPPED} chunks, the VP8X flags for them cleared and the RIFF size set to what is
     * left; anything after the RIFF data is kept as it is.
     */
    static byte[] webp(byte[] webp) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
        long riff = Integer.toUnsignedLong(in.getInt(4));
        if (riff < 4 || riff > webp.length - 8L) {
            throw new IOException("The WebP is shorter than its RIFF header says");
        }
        int end = 8 + (int) riff;
        ByteArrayOutputStream body = new ByteArrayOutputStream(webp.length);
        body.write(webp, 8, 4); // WEBP
        int at = 12;
        boolean changed = false;
        while (at < end) {
            if (end - at < 8) {
                throw new IOException("A WebP chunk header runs past the end of the file");
            }
            String type = new String(webp, at, 4, StandardCharsets.ISO_8859_1);
            long size = Integer.toUnsignedLong(in.getInt(at + 4));
            if (size > end - at - 8L) {
                throw new IOException("A WebP chunk runs past the end of the file");
            }
            int whole = (int) Math.min(8 + size + (size & 1), end - at); // the last chunk's padding may be missing
            if (WEBP_DROPPED.contains(type)) {
                changed = true;
            } else if (type.equals("VP8X") && size >= 1 && (webp[at + 8] & (VP8X_EXIF | VP8X_XMP)) != 0) {
                byte[] chunk = new byte[whole];
                System.arraycopy(webp, at, chunk, 0, whole);
                chunk[8] &= (byte) ~(VP8X_EXIF | VP8X_XMP);
                body.writeBytes(chunk);
                changed = true;
            } else {
                body.write(webp, at, whole);
            }
            at += whole;
        }
        if (!changed) {
            return webp;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(webp.length);
        out.write(webp, 0, 4); // RIFF
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size()).array());
        out.writeBytes(body.toByteArray());
        out.write(webp, end, webp.length - end);
        return out.toByteArray();
    }
}
