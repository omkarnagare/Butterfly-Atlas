package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.AtlasException;

import com.butterflyatlas.app.ErrorCodes;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The shared filter of the Export screen (4a contract §1), as the UI's address and every {@code /api/filter/*} request
 * carry it in a query string. {@code taxa} are taxon ids at any rank, {@code tags} tag keys, {@code places} place ids
 * and/or {@code unknown}; each list once per id, in the order given. {@code stages} is null or {@code "missing"}.
 * {@code types} are file types ({@code jpeg png tiff webp raw heic}, 7a contract §4, Phase 7b contract §5), once each in
 * the order given; none = all.
 */
public record Filter(List<String> taxa, List<String> tags, String tagMode, List<String> places, String status,
                     String source, String stages, List<String> types) {

    public static final String ANY = "any";
    public static final String ALL = "all";
    public static final String PHOTOGRAPHED = "photographed";
    public static final String NOT = "not";
    public static final String OFFICIAL = "official";
    public static final String MANUAL = "manual";
    public static final String MISSING = "missing";
    /** The place "id" of photos with no place. */
    public static final String UNKNOWN = "unknown";
    /** The most taxa, and the most places, one filter may name. */
    public static final int MAX_IDS = 200;

    private static final List<String> TAG_MODES = List.of(ANY, ALL);
    private static final List<String> STATUSES = List.of(ALL, PHOTOGRAPHED, NOT);
    private static final List<String> SOURCES = List.of(ALL, OFFICIAL, MANUAL);
    private static final List<String> STAGES = List.of(MISSING);
    /** The File type choices (contract §0). */
    public static final List<String> TYPES = List.of("jpeg", "png", "tiff", "webp", "raw", "heic");

    public Filter {
        Objects.requireNonNull(tagMode, "tagMode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        taxa = List.copyOf(taxa);
        tags = List.copyOf(tags);
        places = List.copyOf(places);
        types = List.copyOf(types);
    }

    /** A filter of every file type. */
    public Filter(List<String> taxa, List<String> tags, String tagMode, List<String> places, String status, String source,
                  String stages) {
        this(taxa, tags, tagMode, places, status, source, stages, List.of());
    }

    /**
     * The Life stages filter is on: it counts every photo of a species, so it cannot have tags or a Photo status
     * ({@link #parse} refuses them; a tag mode is still echoed).
     */
    public boolean byStages() {
        return stages != null;
    }

    /** This filter with other ids (the ones actually used); everything else as it is. */
    public Filter withIds(List<String> usedTaxa, List<String> usedTags, List<String> usedPlaces) {
        return new Filter(usedTaxa, usedTags, tagMode, usedPlaces, status, source, stages, types);
    }

    /**
     * Reads a raw query string ({@code taxa=A12,A230&tags=egg}); null or empty is no filter (every default). Unknown
     * keys are ignored, an empty value is the default, and of a repeated key the first counts. A list is split on its
     * commas before each item is decoded (an encoded comma, {@code %2C}, stays inside its id); {@code +} is a literal
     * plus, as in the UI. A value outside its list (a file type among them: "Unknown file type “x”."), or more than
     * {@value #MAX_IDS} taxa or places, is refused (400 {@code invalid}). The UI reads a query string alike (shared cases: {@code fixtures/filter-parity.json}).
     */
    public static Filter parse(String rawQuery) {
        Map<String, String> values = new HashMap<>(); // still encoded
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                values.putIfAbsent(key, eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        List<String> taxa = ids(values.get("taxa"));
        if (taxa.size() > MAX_IDS) {
            throw invalid("Choose at most 200 taxa.");
        }
        List<String> places = ids(values.get("places"));
        if (places.size() > MAX_IDS) {
            throw invalid("Choose at most 200 places.");
        }
        List<String> types = ids(values.get("types"));
        for (String type : types) {
            if (!TYPES.contains(type)) {
                throw invalid("Unknown file type “" + type + "”.");
            }
        }
        Filter filter = new Filter(taxa, ids(values.get("tags")), choice(values.get("tagMode"), TAG_MODES, ANY, "tag mode"),
                places, choice(values.get("status"), STATUSES, ALL, "status"),
                choice(values.get("source"), SOURCES, ALL, "source"), choice(values.get("stages"), STAGES, null, "stages"),
                types);
        if (filter.byStages() && (!filter.tags().isEmpty() || !ALL.equals(filter.status()))) {
            throw invalid("Life stages can't be combined with Tags or Photo status.");
        }
        return filter;
    }

    /** The query string of this filter: defaults left out, keys in the contract's order (§1). */
    public String query() {
        List<String> parts = new ArrayList<>();
        list(parts, "taxa", taxa);
        list(parts, "tags", tags);
        if (!ANY.equals(tagMode)) {
            parts.add("tagMode=" + tagMode);
        }
        list(parts, "places", places);
        if (!ALL.equals(status)) {
            parts.add("status=" + status);
        }
        if (!ALL.equals(source)) {
            parts.add("source=" + source);
        }
        if (stages != null) {
            parts.add("stages=" + stages);
        }
        list(parts, "types", types);
        return String.join("&", parts);
    }

    /** 400 {@code invalid} with this message. */
    public static AtlasException invalid(String message) {
        return new AtlasException(AtlasException.Kind.INVALID, ErrorCodes.INVALID, message);
    }

    private static void list(List<String> parts, String key, List<String> ids) {
        if (!ids.isEmpty()) {
            parts.add(key + "=" + String.join(",", ids.stream().map(Filter::encode).toList()));
        }
    }

    /** The items of a still-encoded list: split on commas, then each decoded and trimmed; empty items skipped. */
    private static List<String> ids(String raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String id = trim(decode(item));
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private static String choice(String raw, List<String> allowed, String fallback, String what) {
        String value = raw == null ? null : decode(raw);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw invalid("Unknown " + what + " “" + value + "”.");
        }
        return value;
    }

    /** Percent-decoded as the UI's decodeURIComponent does: {@code +} stays a plus, a stray % leaves the text as it is. */
    private static String decode(String text) {
        try {
            return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    /**
     * Trimmed as JavaScript's {@code String.prototype.trim} trims (so the UI's ids are the server's): of tabs, line
     * breaks and every Unicode space separator, no-break spaces included, and the byte order mark. {@link String#strip}
     * would keep the no-break spaces.
     */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && space(text.charAt(start))) {
            start++;
        }
        while (end > start && space(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean space(char ch) {
        return "\t\n\u000B\f\r\u2028\u2029\uFEFF".indexOf(ch) >= 0 || Character.getType(ch) == Character.SPACE_SEPARATOR;
    }

    /** Percent-encodes everything but letters, digits and {@code - . _ ~ :} (ids and tag keys print as they are). */
    private static String encode(String id) {
        StringBuilder out = new StringBuilder();
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (b & 0xFF);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || "-._~:".indexOf(ch) >= 0) {
                out.append(ch);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }
}
