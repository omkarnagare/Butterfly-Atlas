package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;

/**
 * The RAF reader (Fujifilm, design §2): {@code FUJIFILMCCD-RAW} at byte 0, and the header's JPEG offset and length
 * (big-endian, at bytes 84 and 88). The JPEG carries the photo's EXIF, which also gives the orientation.
 */
final class RafPreviews {

    private RafPreviews() {
    }

    static boolean recognises(byte[] head) {
        return RawBytes.ascii(head, 0, 15).equals("FUJIFILMCCD-RAW");
    }

    static void read(RawBytes in, Found out) throws IOException {
        out.family = "raf";
        byte[] header = in.read(0, 92);
        long offset = RawBytes.u32(header, 84, false);
        long length = RawBytes.u32(header, 88, false);
        if (!in.inside(offset, length)) {
            return;
        }
        out.candidate(offset, length);
        out.embeddedJpeg = new RawContents.Slice("JPEG", offset, length);
        long exif = JpegCheck.exifTiff(in, offset, length);
        if (exif >= 0) {
            out.orientation(TiffPreviews.orientation(in, exif));
        }
    }
}
