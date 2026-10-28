package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;

/**
 * Looks at a JPEG inside a RAW file without decoding it: it must start with SOI, and its frame header (reached by
 * walking the marker segments) must be baseline, extended or progressive, with 8-bit samples and 1 or 3 components. A
 * lossless frame (SOF3, as in a DNG's raw data or a CR2's sensor data) or any other kind is not a preview. Reads only
 * the segment headers it walks.
 */
final class JpegCheck {

    /** Segments walked before the frame header; real previews have a handful (APP0/APP1/APP2, DQT, DHT). */
    private static final int MAX_SEGMENTS = 64;

    /** A JPEG's stored size in pixels (before any orientation). */
    record Size(int width, int height) {

        long pixels() {
            return (long) width * height;
        }
    }

    private JpegCheck() {
    }

    /** The size of the JPEG at {@code [offset, offset + length)}, or null when it is not a usable preview's JPEG. */
    static Size size(RawBytes in, long offset, long length) throws IOException {
        try {
            long p = start(in, offset, length);
            if (p < 0) {
                return null;
            }
            long end = offset + length;
            for (int segments = 0; segments < MAX_SEGMENTS && p + 4 <= end; segments++) {
                byte[] marker = in.read(p, 4);
                if ((marker[0] & 0xFF) != 0xFF) {
                    return null;
                }
                int m = marker[1] & 0xFF;
                if (m == 0xFF) {
                    p++; // fill byte
                    continue;
                }
                if (m == 0xC0 || m == 0xC1 || m == 0xC2) {
                    if (p + 10 > end) {
                        return null;
                    }
                    byte[] frame = in.read(p, 10);
                    int precision = frame[4] & 0xFF;
                    int height = RawBytes.u16(frame, 5, false);
                    int width = RawBytes.u16(frame, 7, false);
                    int components = frame[9] & 0xFF;
                    // 8-bit greyscale or colour only (fixes J10): 12-bit samples or CMYK are not a camera's preview
                    return precision == 8 && (components == 1 || components == 3) && width > 0 && height > 0
                            ? new Size(width, height) : null;
                }
                if (m >= 0xC3 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    return null; // lossless, hierarchical or arithmetic: not a preview ImageIO shows
                }
                if (m == 0xD9 || m == 0xDA) {
                    return null; // the image ended, or its data began, before any frame header
                }
                if ((m >= 0xD0 && m <= 0xD7) || m == 0x01) {
                    p += 2; // markers without a length
                    continue;
                }
                int segment = RawBytes.u16(marker, 2, false);
                if (segment < 2) {
                    return null;
                }
                p += 2 + segment;
            }
            return null;
        } catch (RawBytes.Damaged e) {
            return null;
        }
    }

    /**
     * Where the TIFF structure of the JPEG's EXIF block starts (its APP1 "Exif" segment, before the frame header), or
     * -1 when it has none.
     */
    static long exifTiff(RawBytes in, long offset, long length) throws IOException {
        try {
            long p = start(in, offset, length);
            if (p < 0) {
                return -1;
            }
            long end = offset + length;
            for (int segments = 0; segments < MAX_SEGMENTS && p + 10 <= end; segments++) {
                byte[] head = in.read(p, 10);
                if ((head[0] & 0xFF) != 0xFF) {
                    return -1;
                }
                int m = head[1] & 0xFF;
                if (m == 0xDA || m == 0xD9 || (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC)) {
                    return -1;
                }
                int segment = RawBytes.u16(head, 2, false);
                if (segment < 2) {
                    return -1;
                }
                if (m == 0xE1 && RawBytes.ascii(head, 4, 6).equals("Exif\0\0")) {
                    return p + 10;
                }
                p += 2 + segment;
            }
            return -1;
        } catch (RawBytes.Damaged e) {
            return -1;
        }
    }

    /** The position after SOI, or -1 when the range is outside the file, too short or not a JPEG. */
    private static long start(RawBytes in, long offset, long length) throws IOException {
        if (length < 4 || !in.inside(offset, length)) {
            return -1;
        }
        byte[] soi = in.read(offset, 2);
        return (soi[0] & 0xFF) == 0xFF && (soi[1] & 0xFF) == 0xD8 ? offset + 2 : -1;
    }
}
