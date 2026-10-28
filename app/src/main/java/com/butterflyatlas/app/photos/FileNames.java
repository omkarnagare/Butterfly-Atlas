package com.butterflyatlas.app.photos;

import java.util.Locale;
import java.util.Set;

/**
 * Folder and file names the app writes (design P2, P4): spaces become underscores, and anything
 * Windows cannot store in a name is replaced, so a library copied between computers keeps working.
 */
public final class FileNames {

    private static final String ILLEGAL = "<>:\"/\\|?*";
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNames() {
    }

    /**
     * One folder or file name, safe on macOS, Windows and Linux: {@code <>:"/\|?*}, control
     * characters and spaces become {@code _}; trailing dots are dropped; Windows device names
     * such as {@code CON} get a {@code _} appended. Applying it twice changes nothing.
     */
    public static String safe(String name) {
        StringBuilder out = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp == 0x7f || cp == ' ' || ILLEGAL.indexOf(cp) >= 0) {
                out.append('_');
            } else {
                out.appendCodePoint(cp);
            }
        });
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '.') {
            end--;
        }
        String safe = out.substring(0, end);
        if (safe.isEmpty()) {
            return "_";
        }
        String stem = safe.contains(".") ? safe.substring(0, safe.indexOf('.')) : safe;
        return RESERVED.contains(stem.toUpperCase(Locale.ROOT)) ? stem + "_" + safe.substring(stem.length()) : safe;
    }

    /** {@code Danaus_chrysippus_01.jpg}: the species' scientific name, the number with at least two digits, the extension. */
    public static String photoName(String scientificName, int number, String extension) {
        return safe(scientificName) + "_" + String.format(Locale.ROOT, "%02d", number) + "." + extension;
    }
}
