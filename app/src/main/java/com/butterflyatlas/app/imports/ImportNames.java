package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.photos.PhotoFormat;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

/**
 * What the folder browser and the walk recognise by name alone (contract §1, design B8): photo files, videos, and the
 * hidden or system folders they leave out. Content is checked only when an import reads a file.
 */
final class ImportNames {

    /** Photo formats Butterfly Atlas reads, by extension (and RAW, {@link PhotoFormat#isRawFileName}). */
    private static final Set<String> SUPPORTED = Set.of("jpg", "jpeg", "png", "tif", "tiff", "webp", "heic", "heif", "hif");
    /**
     * HEIF image sequences by name (Phase 7b audit 2): listed as photos, and their first bytes decide (a sequence gets
     * its words; a still image saved under such a name is read).
     */
    private static final Set<String> HEIF_SEQUENCES = Set.of("heics", "heifs");
    /** Photos it recognises but cannot read yet. */
    private static final Set<String> AVIF = Set.of("avif");
    /** Videos are listed under Problems, never read. */
    private static final Set<String> VIDEO = Set.of("mp4", "mov", "m4v", "avi", "mts", "m2ts", "3gp", "mkv", "wmv", "mpg",
            "mpeg");
    private static final Set<String> SYSTEM_FOLDERS = Set.of("@eadir", "system volume information", "$recycle.bin",
            "lost+found");
    /** Extensions of folders that are really one document (see {@link #isPackage}). */
    private static final Set<String> PACKAGES = Set.of("photoslibrary", "photolibrary", "migratedphotolibrary", "aplibrary",
            "lrlibrary", "lrdata", "app", "bundle", "framework", "plugin", "kext", "pkg", "imovielibrary", "tvlibrary");

    /** The problem shown for a video. */
    static final String VIDEO_PROBLEM = "Videos can't be imported.";

    /** Whether the name is a {@code .mov} (any case): the video a Live Photo keeps beside its still (Phase 7b audit 16). */
    static boolean isLivePhotoVideo(String name) {
        return extension(name).equals("mov");
    }

    /** Natural order, ignoring case: "Day 2" before "Day 10". */
    static final Comparator<String> NATURAL = ImportNames::compareNatural;

    private ImportNames() {
    }

    /**
     * A photo by its extension: one Butterfly Atlas reads (RAW and HEIC included), a HEIF sequence whose bytes decide, or
     * a recognised one it cannot read yet (AVIF).
     */
    static boolean isPhoto(String name) {
        String ext = extension(name);
        return SUPPORTED.contains(ext) || HEIF_SEQUENCES.contains(ext) || AVIF.contains(ext) || PhotoFormat.isRawFileName(name);
    }

    static boolean isVideo(String name) {
        return VIDEO.contains(extension(name));
    }

    /** A file the walk lists as an item: a photo or a video (videos only to say why they are not imported). */
    static boolean isListed(String name) {
        return !isHidden(name) && (isPhoto(name) || isVideo(name));
    }

    /** Why a file is not read at all, from its name; null when its bytes decide. */
    static String problemByName(String name) {
        if (isVideo(name)) {
            return VIDEO_PROBLEM;
        }
        return null;
    }

    /**
     * The refusal its name points to (AVIF), for a file whose bytes are not a photo either; else null. A damaged
     * {@code .heic} is not a photo Butterfly Atlas can read, like any other (Phase 7b audit 2).
     */
    static String unsupportedByName(String name) {
        String ext = extension(name);
        if (AVIF.contains(ext)) {
            return PhotoFormat.AVIF;
        }
        return null;
    }

    /** Whether the name is one Butterfly Atlas reads (jpg, png, tif, webp, heic, heif, hif, and the RAW extensions). */
    static boolean isSupported(String name) {
        return SUPPORTED.contains(extension(name)) || PhotoFormat.isRawFileName(name);
    }

    /** Hidden files and folders ({@code .x}, {@code $x}). */
    static boolean isHidden(String name) {
        return name.startsWith(".") || name.startsWith("$");
    }

    /** Whether this is Windows, where cloud placeholders are "other" files. */
    static final boolean WINDOWS = java.nio.file.FileSystems.getDefault().getSeparator().equals("\\");

    /**
     * Whether an entry (read without following links) counts as a file: a regular file, or on Windows an "other"
     * non-folder non-link (a cloud placeholder such as OneDrive's). Elsewhere "other" is a FIFO or device, never listed:
     * reading it could block.
     */
    static boolean isFile(java.nio.file.attribute.BasicFileAttributes attrs, boolean windows) {
        return attrs.isRegularFile() || (windows && attrs.isOther() && !attrs.isDirectory() && !attrs.isSymbolicLink());
    }

    /** Folders the browser and the walk leave out: hidden ones, system folders such as {@code @eaDir}, and packages. */
    static boolean isSkippedFolder(String name) {
        return isHidden(name) || SYSTEM_FOLDERS.contains(name.toLowerCase(Locale.ROOT)) || isPackage(name);
    }

    /**
     * A folder that is really one document (macOS packages and photo-library databases): the Photos and Aperture
     * libraries, Lightroom catalogues' data, applications and bundles. By name: asking the system whether a folder is a
     * package needs AppKit, which the core does not use.
     */
    static boolean isPackage(String name) {
        return PACKAGES.contains(extension(name));
    }

    /** Whether the folder is a package or inside one (by the names along its path; nothing is asked of the disk). */
    static boolean isInPackage(Path folder) {
        for (Path part : folder) {
            if (isPackage(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int byLength = Integer.compare(na.length(), nb.length());
                if (byLength != 0) {
                    return byLength;
                }
                int byDigits = na.compareTo(nb);
                if (byDigits != 0) {
                    return byDigits;
                }
            } else {
                int byChar = Character.compare(Character.toLowerCase(Character.toUpperCase(ca)),
                        Character.toLowerCase(Character.toUpperCase(cb)));
                if (byChar != 0) {
                    return byChar;
                }
                i++;
                j++;
            }
        }
        int byRest = Integer.compare(a.length() - i, b.length() - j);
        return byRest != 0 ? byRest : a.compareTo(b);
    }
}
