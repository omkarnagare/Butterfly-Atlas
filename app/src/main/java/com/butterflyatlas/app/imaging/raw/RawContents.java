package com.butterflyatlas.app.imaging.raw;

import java.util.List;

/**
 * What one look inside a RAW file found: its family (the lowercase extension its bytes say, or null when they do not
 * say: a TIFF-shaped file without DNGVersion or CR2's mark), the chosen preview (null when none is usable), the sensor
 * size when known, and where the file's EXIF sits when metadata-extractor cannot find it by itself: a CR3's
 * {@code CMT1}–{@code CMT4} boxes (each a TIFF structure: IFD0, the Exif sub-IFD, the maker note, GPS) and a RAF's
 * embedded JPEG.
 */
public record RawContents(String family, RawPreview preview, Size sensor, List<Slice> cmt, Slice embeddedJpeg) {

    /** A width and height in pixels. */
    public record Size(int width, int height) {
    }

    /** A range of the file: {@code length} bytes at {@code offset}. {@code name} says which box ({@code CMT1} …). */
    public record Slice(String name, long offset, long length) {
    }

    static final RawContents NONE = new RawContents(null, null, null, List.of(), null);
}
