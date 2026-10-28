package com.butterflyatlas.app.photos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The photo formats Butterfly Atlas reads, recognised by their first bytes rather than by name. Camera RAW files
 * (Phase 7a) are kept exactly and shown through the JPEG preview inside them; a TIFF-shaped RAW (NEF, ARW, PEF …) is
 * told apart from a TIFF by its RAW extension or its DNGVersion tag, which only the whole file shows (see
 * {@code PhotoIngest}), so {@link #detect} calls those TIFF. HEIC photos (Phase 7b) are kept exactly and shown through
 * the computer's own decoder; {@link #detect} goes by the {@code ftyp} brands, and the boxes behind them decide the
 * rest (a sequence, or a primary item not coded with HEVC, is refused when the file is read).
 */
public enum PhotoFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    TIFF("tif", "image/tiff"),
    WEBP("webp", "image/webp"),
    /** Camera RAW: its library file keeps its family's own extension ({@code nef}, {@code cr3} …), never one of the enum's. */
    RAW(null, "application/octet-stream"),
    /** A HEIF still image coded with HEVC ({@code .heic}, {@code .heif}, {@code .hif}); its library file is {@code .heic}. */
    HEIC("heic", "image/heic");

    /** How many leading bytes {@link #detect} and {@link #refusal} need (an ISO-BMFF {@code ftyp} box with its brands). */
    public static final int HEAD_BYTES = 64;

    public static final String UNREADABLE = "This file isn't a photo Butterfly Atlas can read.";
    /** A HEIF image sequence (contract §1). */
    public static final String HEIC_SEQUENCE = "HEIC image sequences (several frames in one file) aren't supported. Save"
            + " the frame you want as a photo and add that.";
    /** A HEIF still image whose primary item is not HEVC, nor AV1 (contract §1). */
    public static final String HEIF_OTHER = "This HEIF photo is stored in a way Butterfly Atlas can't read. Save it as"
            + " JPEG and add that.";
    public static final String AVIF = "AVIF photos aren't supported yet.";
    /** A RAW file without a preview the app can show (contract §1). */
    public static final String RAW_NO_PREVIEW = "This RAW photo has no preview Butterfly Atlas can show. Save it as JPEG"
            + " from your RAW software and add that.";
    /** A RAW photo whose preview no longer reads (contract §1), with what to do (fixes U2, its server message). */
    public static final String RAW_PREVIEW_UNREADABLE = "This RAW photo's preview can't be read any more. The file may have"
            + " changed or be damaged. Add it again from your camera's card, or save it as JPEG from your RAW software and"
            + " add that.";

    /**
     * The {@code Content-Type}s of RAW uploads: {@code image/x-raw} (what the UI sends for any RAW extension) and what
     * browsers and systems call camera RAW files (these vary by platform). The bytes still decide the format.
     */
    public static final Set<String> RAW_CONTENT_TYPES = Set.of("image/x-raw", "image/x-adobe-dng", "image/x-nikon-nef",
            "image/x-nikon-nrw", "image/x-sony-arw", "image/x-sony-srf", "image/x-sony-sr2", "image/x-canon-cr2",
            "image/x-canon-cr3", "image/x-pentax-pef", "image/x-samsung-srw", "image/x-olympus-orf", "image/x-panasonic-rw2",
            "image/x-fuji-raf", "image/x-hasselblad-3fr", "image/x-phaseone-iiq", "image/x-leica-rwl", "image/x-dcraw");

    /** The {@code Content-Type}s of HEIC uploads (contract §2); the bytes decide (a sequence is refused). */
    public static final Set<String> HEIC_CONTENT_TYPES = Set.of("image/heic", "image/heif", "image/heic-sequence",
            "image/heif-sequence");

    /** Major brands of a HEIF still image coded with HEVC (design §2). */
    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "heim", "heis");
    /** The generic HEIF brands: a HEIC when {@code heic} or {@code heix} is listed too. */
    private static final Set<String> GENERIC_BRANDS = Set.of("mif1", "miaf");
    /** Major brands of HEIF image sequences. */
    private static final Set<String> SEQUENCE_BRANDS = Set.of("msf1", "hevc", "hevx");
    private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis");
    /** Camera RAW extensions; several of these are TIFF inside, so the extension is what tells them apart. */
    private static final Set<String> RAW_EXTENSIONS = Set.of("nef", "nrw", "dng", "arw", "srf", "sr2", "cr2", "cr3", "pef",
            "srw", "orf", "rw2", "raf", "3fr", "iiq", "rwl");

    private final String extension;
    private final String contentType;

    PhotoFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** The extension the app gives files it names ({@code jpg}, {@code png}, {@code tif}, {@code webp}); none for RAW. */
    public String extension() {
        if (extension == null) {
            throw new IllegalStateException("A RAW file keeps its family's extension");
        }
        return extension;
    }

    /** The stored file type ({@code image.file_type}, contract §0): {@code jpeg png tiff webp raw heic}. */
    public String fileType() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The format of a stored file type; empty for anything else. */
    public static Optional<PhotoFormat> fromFileType(String fileType) {
        return Arrays.stream(values()).filter(f -> f.fileType().equals(fileType)).findFirst();
    }

    public String contentType() {
        return contentType;
    }

    /**
     * The format of a file from its first bytes; empty for anything else. RAW for the RAW headers of their own (RAF, CR3,
     * ORF, RW2) and CR2's mark; other TIFF-shaped RAW files come back as TIFF. HEIC for the HEIC major brands, and for the
     * generic {@code mif1}/{@code miaf} with {@code heic} or {@code heix} listed (and no AV1 brand).
     */
    public static Optional<PhotoFormat> detect(byte[] head) {
        if (starts(head, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (starts(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (canonRaw(head) || ascii(head, 0, 15).equals("FUJIFILMCCD-RAW")
                || (ascii(head, 4, 4).equals("ftyp") && ascii(head, 8, 4).equals("crx "))
                || starts(head, 'I', 'I', 'R', 'O') || starts(head, 'I', 'I', 'R', 'S') || starts(head, 'M', 'M', 'O', 'R')
                || starts(head, 'I', 'I', 'U', 0x00)) {
            return Optional.of(RAW);
        }
        if (starts(head, 'I', 'I', 0x2A, 0x00) || starts(head, 'M', 'M', 0x00, 0x2A)) {
            return Optional.of(TIFF);
        }
        if (starts(head, 'R', 'I', 'F', 'F') && ascii(head, 8, 4).equals("WEBP")) {
            return Optional.of(WEBP);
        }
        if (ascii(head, 4, 4).equals("ftyp") && !avif(head)) {
            String brand = ascii(head, 8, 4);
            if (HEIC_BRANDS.contains(brand) || (GENERIC_BRANDS.contains(brand)
                    && compatibleBrands(head).stream().anyMatch(b -> b.equals("heic") || b.equals("heix")))) {
                return Optional.of(HEIC);
            }
        }
        return Optional.empty();
    }

    /**
     * A plain-language reason why a file with these first bytes cannot be added: AVIF; a HEIF image sequence (major brand
     * {@code msf1}, {@code hevc}, {@code hevx}); another generic HEIF ({@code mif1}/{@code miaf} without a HEIC brand:
     * JPEG in HEIF, uncompressed …); else not a photo. (A HEIC by its brands whose boxes say otherwise is refused when it
     * is read, with the same words.)
     */
    public static String refusal(byte[] head) {
        if (ascii(head, 4, 4).equals("ftyp")) {
            String brand = ascii(head, 8, 4);
            if (avif(head)) {
                return AVIF;
            }
            if (SEQUENCE_BRANDS.contains(brand)) {
                return HEIC_SEQUENCE;
            }
            if (GENERIC_BRANDS.contains(brand)) {
                return compatibleBrands(head).stream().anyMatch(SEQUENCE_BRANDS::contains) ? HEIC_SEQUENCE : HEIF_OTHER;
            }
        }
        return UNREADABLE;
    }

    /** AVIF is HEIF with AV1 inside: major brand avif/avis, or the generic mif1/msf1/miaf with an AV1 brand listed. */
    private static boolean avif(byte[] head) {
        String brand = ascii(head, 8, 4);
        return AVIF_BRANDS.contains(brand) || (Set.of("mif1", "msf1", "miaf").contains(brand)
                && compatibleBrands(head).stream().anyMatch(b -> AVIF_BRANDS.contains(b) || b.equals("av01")));
    }

    /**
     * The format for an upload's {@code Content-Type} (parameters such as {@code ;charset} ignored): the four image types,
     * RAW for {@link #RAW_CONTENT_TYPES} and HEIC for {@link #HEIC_CONTENT_TYPES}.
     */
    public static Optional<PhotoFormat> fromContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String essence = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (RAW_CONTENT_TYPES.contains(essence)) {
            return Optional.of(RAW);
        }
        if (HEIC_CONTENT_TYPES.contains(essence)) {
            return Optional.of(HEIC);
        }
        return Arrays.stream(values()).filter(f -> f != RAW && f.contentType.equals(essence)).findFirst();
    }

    /**
     * The format a file name suggests ({@code .jpg .jpeg .png .tif .tiff .webp .heic .heif .hif}, any case); empty for RAW names (see
     * {@link #rawExtension}), so a renamed or moved RAW keeps its own extension.
     */
    public static Optional<PhotoFormat> fromFileName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> Optional.of(JPEG);
            case "png" -> Optional.of(PNG);
            case "tif", "tiff" -> Optional.of(TIFF);
            case "webp" -> Optional.of(WEBP);
            case "heic", "heif", "hif" -> Optional.of(HEIC);
            default -> Optional.empty();
        };
    }

    /** Whether a file name has a camera RAW extension ({@code .nef .dng .cr2 …}, any case). */
    public static boolean isRawFileName(String name) {
        return rawExtension(name).isPresent();
    }

    /** The name's camera RAW extension in lowercase ({@code nef} for {@code DSC_0042.NEF}); empty for any other name. */
    public static Optional<String> rawExtension(String name) {
        if (name == null) {
            return Optional.empty();
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return RAW_EXTENSIONS.contains(ext) ? Optional.of(ext) : Optional.empty();
    }

    /** The compatible brands of an {@code ftyp} box: 4-character codes from byte 16 to the box's end (as far as {@code head} goes). */
    private static List<String> compatibleBrands(byte[] head) {
        int boxSize = head.length < 4 ? 0 : (head[0] & 0xFF) << 24 | (head[1] & 0xFF) << 16 | (head[2] & 0xFF) << 8 | (head[3] & 0xFF);
        int end = Math.min(head.length, boxSize);
        List<String> brands = new ArrayList<>();
        for (int at = 16; at + 4 <= end; at += 4) {
            brands.add(ascii(head, at, 4));
        }
        return brands;
    }

    // Canon CR2 files are TIFF-shaped but carry "CR" at offset 8.
    private static boolean canonRaw(byte[] head) {
        return starts(head, 'I', 'I', 0x2A, 0x00) && ascii(head, 8, 2).equals("CR");
    }

    private static boolean starts(byte[] head, int... expected) {
        if (head.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] head, int from, int length) {
        if (head.length < from + length) {
            return "";
        }
        return new String(head, from, length, StandardCharsets.ISO_8859_1);
    }
}
