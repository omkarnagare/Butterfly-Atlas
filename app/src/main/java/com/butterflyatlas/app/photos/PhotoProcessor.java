package com.butterflyatlas.app.photos;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads and shrinks photos. The core only knows this interface; the desktop implementation
 * ({@code imaging.ImageIoPhotoProcessor}) uses AWT image classes, which Android lacks (design D6).
 */
public interface PhotoProcessor {

    /**
     * What a picture is decoded for, in the order the HEIC decoder's slots are handed out (Phase 7b audit 9): the viewer
     * and the import's large view, thumbnails on screen, import reading, then catch-up. Other formats ignore it.
     */
    enum Use {
        VIEW, SHOWN, IMPORT, CATCH_UP
    }

    /**
     * Format, displayed size, orientation, capture time, GPS and the metadata snapshot, without decoding
     * the pixels. A file RAW by its bytes (its own header, CR2's mark, DNGVersion) is read as {@link #readRaw} does.
     * Fails with an {@link IOException} if the file cannot be read.
     */
    PhotoFacts read(Path file) throws IOException;

    /**
     * The facts of a RAW file (one its name alone may tell apart from a TIFF), through the JPEG preview inside it: the
     * preview's displayed size and orientation, the metadata from the RAW itself, and where the preview is. Fails with
     * {@link UnsupportedPhotoException} ({@link PhotoFormat#RAW_NO_PREVIEW}) when it has no preview the app can show.
     */
    default PhotoFacts readRaw(Path file) throws IOException {
        throw new UnsupportedPhotoException(PhotoFormat.RAW_NO_PREVIEW);
    }

    /**
     * Writes a JPEG of {@code source} (for a RAW file, of its preview) to {@code target}, upright for {@code orientation}, in sRGB,
     * at most {@code longSide} pixels on its longer side (never enlarged).
     */
    void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException;

    /**
     * {@link #thumbnail(Path, int, int, Path)} for a {@code use}, which decides a HEIC decode's place in line (Phase 7b
     * audit 9). A HEIC photo is decoded by the computer's decoder; when that cannot be done for a reason that is not the
     * photo's fault, this fails with {@link HeicDecodeException}. {@code orientation} is ignored for a HEIC: its turn is
     * read from its boxes and applied only where the decoder did not.
     */
    default void thumbnail(Path source, int orientation, int longSide, Path target, Use use) throws IOException {
        thumbnail(source, orientation, longSide, target);
    }

    /** The most common colour of a (small) image, as {@code #rrggbb}. */
    String dominantColor(Path image) throws IOException;
}
