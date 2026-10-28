package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.library.Library;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The names an export creates (4b contract §1): no spaces, safe on every platform (exports travel), no two names in one
 * folder equal without case, every full path within Windows' 259 characters. Fixed at the start.
 */
public final class ExportNames {

    /**
     * The longest full path a file may have (Windows' MAX_PATH less the terminating character): a target with the
     * {@code .part} the copy writes first.
     */
    public static final int MAX_PATH = 259;
    /** The longest full path a folder may have (Windows' CreateDirectory: 248 less the terminating character). */
    public static final int MAX_FOLDER = 247;
    /** What the copy appends to a target while it writes it. */
    public static final String PART = Library.PART;
    /** The species list inside the export folder when photos and the list are exported together. */
    public static final String CSV_IN_FOLDER = "species-list.csv";
    /** Where a custom species goes, under its family (5a contract "Changed shapes"). */
    static final String CUSTOM_ADDITIONS = "Custom_additions";

    private static final Pattern UNSAFE = Pattern.compile("(?U)[<>:\"/\\\\|?*\\p{Cntrl}\\s\\p{Z}]");
    /** Dots and spaces (any Unicode space) at the end of a name, which Windows drops. */
    private static final Pattern TRAILING = Pattern.compile("(?U)[.\\s\\p{Z}]+$");
    /** Windows' device names, which it still treats as devices with an extension: COM and LPT with 0-9 or ¹ ² ³. */
    private static final Pattern DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[0-9\u00B9\u00B2\u00B3]"
            + "|LPT[0-9\u00B9\u00B2\u00B3])(\\..*)?");
    /** The {@code _NN} numbers at the end of a file name's stem ({@code _03}, {@code _03_2}), kept when it is shortened. */
    private static final Pattern NUMBERS = Pattern.compile("(_\\d+)+$");

    /** A photo to export: its library file name and the folders it goes in (already made safe). */
    public record Source(long imageId, String fileName, List<String> folders) {
    }

    /** Where it goes, relative to the export folder with {@code /}; {@code renamed} when shortened to fit the path. */
    public record Target(long imageId, String rel, boolean renamed) {
    }

    private ExportNames() {
    }

    /** {@code Butterfly_Atlas_export_2026-10-13}. */
    public static String exportFolder(LocalDate day) {
        return "Butterfly_Atlas_export_" + day;
    }

    /** {@code Butterfly_Atlas_species_list_2026-10-13.csv} (a species list exported on its own). */
    public static String csvFile(LocalDate day) {
        return "Butterfly_Atlas_species_list_" + day + ".csv";
    }

    /** The {@code n}th choice of a name: the name itself, then {@code _2}, {@code _3}, … before its extension. */
    public static String numbered(String name, int n) {
        if (n <= 1) {
            return name;
        }
        int dot = extensionAt(name);
        return name.substring(0, dot) + "_" + n + name.substring(dot);
    }

    /** The first choice of {@link #numbered} that is not in {@code taken}, compared without case. */
    public static String firstFree(String name, Collection<String> taken) {
        Set<String> lower = taken.stream().map(ExportNames::lower).collect(Collectors.toSet());
        for (int n = 1; ; n++) {
            String candidate = numbered(name, n);
            if (!lower.contains(lower(candidate))) {
                return candidate;
            }
        }
    }

    /**
     * A name made safe: in Unicode NFC, trailing dots and spaces dropped, then spaces (any Unicode space),
     * {@code < > : " / \ | ? *} and control characters become {@code _}, and a Windows device name ({@code CON},
     * {@code NUL}, {@code COM1}, …, with any extension) gets a leading {@code _}. Never empty.
     */
    public static String safe(String name) {
        String safe = TRAILING.matcher(Normalizer.normalize(name, Normalizer.Form.NFC)).replaceAll("");
        safe = UNSAFE.matcher(safe).replaceAll("_");
        if (safe.isEmpty()) {
            return "_";
        }
        return notDevice(safe);
    }

    /** The name with a leading {@code _} when it is a Windows device name. */
    private static String notDevice(String name) {
        return DEVICE.matcher(name).matches() ? "_" + name : name;
    }

    /**
     * The species-folders layout's folders: family, subfamily, tribe, genus and species, an absent rank left out; a
     * custom species as {@code <Family>/Custom_additions/<Species>} (an official species with no family has the ranks it
     * has, as any other). Names without the library's {@code [A1042]} ids.
     */
    public static List<String> speciesFolders(String family, String subfamily, String tribe, String genus, String species,
                                              boolean custom) {
        List<String> folders = new ArrayList<>();
        if (custom) {
            if (family != null) {
                folders.add(safe(family));
            }
            folders.add(CUSTOM_ADDITIONS);
        } else {
            for (String rank : new String[] {family, subfamily, tribe, genus}) {
                if (rank != null) {
                    folders.add(safe(rank));
                }
            }
        }
        folders.add(safe(species));
        return folders;
    }

    /**
     * The targets of the photos, in their order: folders that differ only by case are one at every level (the first
     * spelling of each), two names equal without case in one folder get {@code _2}, {@code _3}, …, and a name whose
     * full path with {@link #PART} would pass {@link #MAX_PATH} is shortened (its stem cut, its {@code _NN} and
     * extension kept) and marked renamed. Refused ({@code path_too_long}) when a folder's path passes
     * {@link #MAX_FOLDER}, the folders leave no room for a name, or a reserved name (with its {@link #PART}) would pass
     * {@link #MAX_PATH} in the export folder.
     *
     * @param exportFolder the export folder's full path, as the computer writes it
     */
    public static List<Target> targets(String exportFolder, List<Source> sources) {
        return targets(exportFolder, sources, List.of());
    }

    /** As {@link #targets(String, List)}, with these names taken in the export folder itself (the species list). */
    public static List<Target> targets(String exportFolder, List<Source> sources, List<String> reserved) {
        return targets(exportFolder, sources, reserved, false);
    }

    /**
     * As {@link #targets(String, List, List)}, for the layout chosen: {@code flat} (All photos in one folder) refuses with
     * the words for a path too long even there, else with the contract's words that suggest it.
     */
    public static List<Target> targets(String exportFolder, List<Source> sources, List<String> reserved, boolean flat) {
        Map<String, String> spelling = new HashMap<>();
        Map<String, Set<String>> used = new HashMap<>();
        used.put("", new HashSet<>(reserved.stream().map(ExportNames::lower).toList()));
        // the next number to try per folder and name (without case): every one below it is taken, so many equal names
        // are numbered in one pass
        Map<String, Integer> next = new HashMap<>();
        List<Target> targets = new ArrayList<>(sources.size());
        if (exportFolder.length() > MAX_FOLDER) {
            throw ExportErrors.pathTooLong(flat);
        }
        for (String name : reserved) {
            if (exportFolder.length() + 1 + name.length() > MAX_PATH) {
                throw ExportErrors.pathTooLong(flat);
            }
        }
        for (Source source : sources) {
            String folder = "";
            for (String part : source.folders()) {
                String parent = folder;
                folder = spelling.computeIfAbsent(lower(parent.isEmpty() ? part : parent + "/" + part),
                        k -> parent.isEmpty() ? part : parent + "/" + part);
            }
            if (!folder.isEmpty() && exportFolder.length() + 1 + folder.length() > MAX_FOLDER) {
                throw ExportErrors.pathTooLong(flat);
            }
            Set<String> names = used.computeIfAbsent(lower(folder), k -> new HashSet<>());
            int room = MAX_PATH - PART.length() - exportFolder.length() - 1 - (folder.isEmpty() ? 0 : folder.length() + 1);
            String name = safe(source.fileName());
            int dot = extensionAt(name);
            String extension = name.substring(dot);
            Matcher numbers = NUMBERS.matcher(name.substring(0, dot));
            String head = numbers.find() ? name.substring(0, numbers.start()) : name.substring(0, dot);
            String tail = name.substring(head.length(), dot);
            String key = lower(folder) + "/" + lower(name);
            for (int n = next.getOrDefault(key, 1); ; n++) {
                String numbered = n == 1 ? tail : tail + "_" + n;
                String candidate = null;
                String cut = head;
                for (int left = room - numbered.length() - extension.length(); candidate == null; left--) {
                    if (left < (head.isEmpty() ? 0 : 1)) {
                        throw ExportErrors.pathTooLong(flat);
                    }
                    cut = head.length() > left ? trimEnd(head.substring(0, cutAt(head, left))) : head;
                    // a cut that left nothing of the stem: its numbers alone ("_05"), or "_", so it is never empty
                    String stem = cut.isEmpty() && !head.isEmpty() && numbered.isEmpty() ? "_" : cut;
                    String c = notDevice(stem + numbered + extension);
                    if (c.length() <= room) {
                        candidate = c; // a cut that became a device name ("CON.jpg") has its "_" and still fits
                    }
                }
                if (names.add(lower(candidate))) {
                    next.put(key, n + 1);
                    targets.add(new Target(source.imageId(), folder.isEmpty() ? candidate : folder + "/" + candidate,
                            !cut.equals(head)));
                    break;
                }
            }
        }
        return targets;
    }

    /** The file name of a target ({@code a/b/c.jpg} → {@code c.jpg}). */
    public static String fileName(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    /** Where a name's extension starts (its last dot, not a leading one); the length when it has none. */
    private static int extensionAt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name.length() : dot;
    }

    /** Where to cut {@code text} to at most {@code max} characters without splitting a surrogate pair. */
    private static int cutAt(String text, int max) {
        int at = Math.max(0, max);
        return at > 0 && Character.isHighSurrogate(text.charAt(at - 1)) ? at - 1 : at;
    }

    /**
     * A cut stem without the {@code _ - .} and spaces (any Unicode space, as {@link #TRAILING}) left at its end, so
     * {@code Zizeeria_} + {@code _03} does not read {@code Zizeeria__03} and a stem of dots never makes a hidden file;
     * empty when nothing else is left.
     */
    private static String trimEnd(String text) {
        int end = text.length();
        while (end > 0 && ("_-.".indexOf(text.charAt(end - 1)) >= 0 || Character.isWhitespace(text.charAt(end - 1))
                || Character.isSpaceChar(text.charAt(end - 1)))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** A name compared without case, in full Unicode (upper then lower case, so a final sigma equals a medial one). */
    public static String lower(String name) {
        return name.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
}
