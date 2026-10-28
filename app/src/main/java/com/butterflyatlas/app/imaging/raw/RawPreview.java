package com.butterflyatlas.app.imaging.raw;

/**
 * The JPEG preview chosen inside a RAW file (design §2): where its bytes are ({@code offset}, {@code length}), its
 * stored size in pixels, and the EXIF orientation 1..8 that shows it upright (the RAW's own, as cameras store the
 * preview the way the sensor was held).
 */
public record RawPreview(long offset, long length, int storedWidth, int storedHeight, int orientation) {

    /** The width as displayed (orientation applied). */
    public int width() {
        return orientation >= 5 ? storedHeight : storedWidth;
    }

    /** The height as displayed (orientation applied). */
    public int height() {
        return orientation >= 5 ? storedWidth : storedHeight;
    }
}
