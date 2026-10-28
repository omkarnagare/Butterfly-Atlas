package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.places.PlaceRef;

import java.util.List;

/**
 * One photo as the UI sees it. {@code relPath} is relative to the data folder with {@code /}
 * separators; {@code tags} are tag keys (built-in ones first, in their order); {@code width} and
 * {@code height} are as displayed (null if the file could not be read); {@code isCover} says the
 * photo is its species' cover, chosen or automatic. {@code v} is the file's cache key, {@code <modified
 * millis>-<bytes>}: an opaque string in {@code ./api/photos/12/thumb?v=…}, as on species cards. {@code place} is where
 * it was observed, null for an unknown place. {@code fileType} is {@code jpeg png tiff webp raw} (null for a photo of an
 * older library not read yet); {@code rawPreview} is a RAW photo's preview as the viewer shows it (contract §0: size as
 * displayed, the turn the page applies to {@code /display}), null for every other photo and for a RAW whose preview
 * could not be read; {@code unreadable} says the photo cannot be shown (its thumbnail failed, from the first failure,
 * though it is tried again at the next start; or a RAW without a preview), so the page says so instead of waiting
 * (contract §2, decision D1, fixes J2). {@code heic} is a HEIC photo's {@code tenBit} (more than 8 bits per channel,
 * shown in standard range) and {@code auxiliary} (it holds depth maps or other extra images), null for every other photo
 * and for a HEIC whose details could not be read (Phase 7b contract §2).
 */
public record Photo(long id, String taxonId, String fileName, String relPath, List<String> tags, String addedAt,
                    Integer width, Integer height, boolean isCover, String v, PlaceRef place, String fileType,
                    RawPreview rawPreview, Heic heic, boolean unreadable) {

    /** A RAW photo's preview: its size as displayed and the EXIF orientation 1..8 that shows its bytes upright. */
    public record RawPreview(int width, int height, int orientation) {
    }

    /** What the viewer says of a HEIC photo: more than 8 bits per channel, and extra images it does not show. */
    public record Heic(boolean tenBit, boolean auxiliary) {

        /** From the stored details' {@code heif}; null when there are none. */
        public static Heic of(PhotoFacts.Heif heif) {
            return heif == null ? null : new Heic(heif.bitDepth() > 8, heif.auxiliary());
        }
    }
}
