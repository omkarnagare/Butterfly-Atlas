package com.butterflyatlas.app.imaging.heif;

import java.util.List;

/**
 * What one look at a HEIF file's boxes found (design §3, contract §8): its brands, the primary item and how it is coded,
 * its stored size ({@code ispe}), its turn ({@code irot}, quarter turns anticlockwise) and mirror ({@code imir} axis:
 * {@code 1} flips left and right, {@code 0} top and bottom, as Apple's encoder writes them), the bit depth ({@code pixi}),
 * whether it holds auxiliary images (depth, mattes, gain maps), and where its Exif and XMP items are in the file.
 * Nothing here comes from decoding the picture.
 *
 * @param readable     false when the boxes could not be made sense of (the other fields are then empty)
 * @param mirrorFirst  whether {@code imir} comes before {@code irot} in the primary item's properties (they are applied
 *                     in that order)
 * @param exif         the Exif item's bytes, as ranges of the file in order (empty when it has none)
 * @param xmp          the XMP item's bytes, likewise
 */
public record HeifInfo(boolean readable, String majorBrand, List<String> brands, boolean sequence, int primaryId,
                       String primaryType, String codedAs, Integer width, Integer height, int turn, Integer mirror,
                       boolean mirrorFirst, int bitDepth, boolean auxiliary, List<Extent> exif, List<Extent> xmp) {

    /** {@code length} bytes at {@code offset} of the file. */
    public record Extent(long offset, long length) {
    }

    /** What the app does with the file. */
    public enum Kind {
        /** A still HEVC image (a single image or a grid of them): read and kept. */
        HEIC,
        /** An image sequence (several frames), or a file with no primary item. */
        SEQUENCE,
        /** AV1 inside (AVIF). */
        AVIF,
        /** A still image coded some other way (JPEG in HEIF, uncompressed, VVC …). */
        OTHER_CODING,
        /** Boxes that do not make sense. */
        UNREADABLE
    }

    static HeifInfo unreadable(String majorBrand, List<String> brands) {
        return new HeifInfo(false, majorBrand, brands, false, -1, null, null, null, null, 0, null, false, 8, false,
                List.of(), List.of());
    }

    public HeifInfo {
        brands = List.copyOf(brands);
        exif = List.copyOf(exif);
        xmp = List.copyOf(xmp);
    }

    /** What the app does with this file, in the order the reasons are checked. */
    public Kind kind() {
        if (majorBrand.equals("avif") || majorBrand.equals("avis")) {
            return Kind.AVIF;
        }
        if (sequence) {
            return Kind.SEQUENCE;
        }
        if (!readable) {
            return Kind.UNREADABLE;
        }
        if ("av01".equals(codedAs)) {
            return Kind.AVIF;
        }
        if (!"hvc1".equals(codedAs)) {
            return Kind.OTHER_CODING;
        }
        return width == null || height == null ? Kind.UNREADABLE : Kind.HEIC;
    }

    /** Whether the turn shows the picture on its side (a quarter or three-quarter turn). */
    public boolean sideways() {
        return turn % 2 == 1;
    }

    /** The width as displayed (turned). */
    public Integer displayedWidth() {
        return sideways() ? height : width;
    }

    /** The height as displayed (turned). */
    public Integer displayedHeight() {
        return sideways() ? width : height;
    }

    /** The EXIF orientation (1–8) that shows the stored picture as {@code irot} and {@code imir} say, in their order. */
    public int orientation() {
        return orientation(turn, mirror, mirrorFirst);
    }

    /** {@link #orientation()} of only the turn ({@code withMirror} false) or only the mirror ({@code withTurn} false). */
    public int orientation(boolean withTurn, boolean withMirror) {
        return orientation(withTurn ? turn : 0, withMirror ? mirror : null, mirrorFirst);
    }

    /**
     * The transforms as 2 × 2 matrices on (x, y) with y down, composed in order, then looked up among EXIF's eight.
     * A quarter turn anticlockwise is (x, y) → (y, −x); mirror 1 is (x, y) → (−x, y), mirror 0 (x, y) → (x, −y).
     */
    static int orientation(int turn, Integer mirror, boolean mirrorFirst) {
        int[] m = {1, 0, 0, 1};
        int[] turnMatrix = {1, 0, 0, 1};
        for (int i = 0; i < (turn & 3); i++) {
            turnMatrix = times(new int[] {0, 1, -1, 0}, turnMatrix);
        }
        int[] mirrorMatrix = mirror == null ? new int[] {1, 0, 0, 1}
                : mirror == 1 ? new int[] {-1, 0, 0, 1} : new int[] {1, 0, 0, -1};
        if (mirrorFirst) {
            m = times(turnMatrix, times(mirrorMatrix, m));
        } else {
            m = times(mirrorMatrix, times(turnMatrix, m));
        }
        int[][] exif = {
                {1, 0, 0, 1}, {-1, 0, 0, 1}, {-1, 0, 0, -1}, {1, 0, 0, -1},
                {0, 1, 1, 0}, {0, -1, 1, 0}, {0, -1, -1, 0}, {0, 1, -1, 0}};
        for (int i = 0; i < exif.length; i++) {
            if (java.util.Arrays.equals(exif[i], m)) {
                return i + 1;
            }
        }
        return 1;
    }

    /** a · b, each {@code {m00, m01, m10, m11}}. */
    private static int[] times(int[] a, int[] b) {
        return new int[] {
                a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
                a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3]};
    }
}
