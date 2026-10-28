package com.butterflyatlas.app.photos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;

/**
 * What one read of a photo yields (design §3.1). {@code width} and {@code height} are as displayed
 * (EXIF orientation applied); {@code orientation} is the EXIF value 1..8. {@code capturedAt} is EXIF
 * DateTimeOriginal (null when absent). {@code metaJson} is the stored snapshot ({@code image_meta.json}):
 * these facts plus the standard EXIF fields and basic XMP, so the facts can be rebuilt from it
 * without reading the file again ({@link #fromJson}).
 *
 * <p>Snapshot shape (keys in this order, absent ones left out):
 * {@code {"v":1,"format":"JPEG","width":4000,"height":3000,"orientation":1,"capturedAt":"2024-05-01T04:52:33Z",
 * "gps":{"lat":12.97,"lon":77.59,"alt":920.0},"exif":{"Make":"Canon",…},"xmp":{"dc:title[1]":"…",…}}}.
 * EXIF values are metadata-extractor's descriptions, keyed by tag name. A RAW photo's snapshot (format {@code RAW})
 * also holds, before {@code exif}, {@code "rawPreview":{"offset","length","width","height","orientation","storedWidth",
 * "storedHeight"}} (where its JPEG preview is, contract §6; {@code width}/{@code height} of the facts are the preview's
 * as displayed) and {@code "sensor":{"width","height"}} when known. A HEIC photo's snapshot (format {@code HEIC}) holds,
 * in the same place, {@code "heif":{"turn","mirror","bitDepth","auxiliary","storedWidth","storedHeight"}} (Phase 7b
 * contract §7; {@code "mirrorFirst":true} too when its {@code imir} comes before its {@code irot}); its {@code orientation}
 * is the HEIF turn and mirror as an EXIF value (the EXIF orientation inside a HEIC is never applied).
 */
public record PhotoFacts(PhotoFormat format, Integer width, Integer height, int orientation, Instant capturedAt, Gps gps,
                         String metaJson) {

    /** Where a photo was taken: degrees (negative = south / west), altitude in metres (negative = below sea level). */
    public record Gps(double lat, double lon, Double altitude) {
    }

    /**
     * Where a RAW photo's JPEG preview is in its file: {@code length} bytes at {@code offset}; {@code width} and
     * {@code height} as displayed, {@code orientation} the turn that shows the stored preview upright, and its stored
     * size before that turn.
     */
    public record Preview(long offset, long length, int width, int height, int orientation, int storedWidth,
                          int storedHeight) {
    }

    /**
     * How a HEIC photo is stored (Phase 7b contract §7): {@code turn} is its {@code irot} in quarter turns anticlockwise,
     * {@code mirror} its {@code imir} axis (null for none; 1 flips left and right, 0 top and bottom), {@code mirrorFirst}
     * whether the mirror comes before the turn, {@code bitDepth} its {@code pixi} depth, {@code auxiliary} whether it
     * holds depth maps or other extra images, and its stored size before the turn.
     */
    public record Heif(int turn, Integer mirror, boolean mirrorFirst, int bitDepth, boolean auxiliary, int storedWidth,
                       int storedHeight) {
    }

    /** Raised when the snapshot's shape changes in a way readers must know about. */
    public static final int SNAPSHOT_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Facts and their snapshot; {@code exif} and {@code xmp} keep their iteration order and are left out when empty. */
    public static PhotoFacts of(PhotoFormat format, Integer width, Integer height, int orientation, Instant capturedAt,
                                Gps gps, Map<String, String> exif, Map<String, String> xmp) {
        return of(format, width, height, orientation, capturedAt, gps, null, null, null, null, exif, xmp);
    }

    /**
     * A HEIC photo's facts: its size as displayed (turned) and its orientation come from the HEIF boxes, never from the
     * EXIF inside it; the snapshot keeps how it is stored.
     */
    public static PhotoFacts heic(Heif heif, int orientation, Instant capturedAt, Gps gps, Map<String, String> exif,
                                  Map<String, String> xmp) {
        boolean sideways = orientation >= 5;
        return of(PhotoFormat.HEIC, sideways ? heif.storedHeight() : heif.storedWidth(),
                sideways ? heif.storedWidth() : heif.storedHeight(), orientation, capturedAt, gps, null, null, null, heif,
                exif, xmp);
    }

    /**
     * A RAW photo's facts: its size and orientation are the preview's ({@code preview.width()} … as displayed), and the
     * snapshot keeps where the preview is and the sensor size when known ({@code sensorWidth}/{@code Height} null when not).
     */
    public static PhotoFacts raw(Preview preview, Integer sensorWidth, Integer sensorHeight, Instant capturedAt, Gps gps,
                                 Map<String, String> exif, Map<String, String> xmp) {
        return of(PhotoFormat.RAW, preview.width(), preview.height(), preview.orientation(), capturedAt, gps, preview,
                sensorWidth, sensorHeight, null, exif, xmp);
    }

    private static PhotoFacts of(PhotoFormat format, Integer width, Integer height, int orientation, Instant capturedAt,
                                 Gps gps, Preview preview, Integer sensorWidth, Integer sensorHeight, Heif heif,
                                 Map<String, String> exif, Map<String, String> xmp) {
        ObjectNode root = JSON.createObjectNode();
        root.put("v", SNAPSHOT_VERSION);
        root.put("format", format.name());
        if (width != null) {
            root.put("width", width);
        }
        if (height != null) {
            root.put("height", height);
        }
        root.put("orientation", orientation);
        if (capturedAt != null) {
            root.put("capturedAt", capturedAt.toString());
        }
        if (gps != null) {
            ObjectNode where = root.putObject("gps");
            where.put("lat", gps.lat());
            where.put("lon", gps.lon());
            if (gps.altitude() != null) {
                where.put("alt", gps.altitude());
            }
        }
        if (preview != null) {
            ObjectNode raw = root.putObject("rawPreview");
            raw.put("offset", preview.offset());
            raw.put("length", preview.length());
            raw.put("width", preview.width());
            raw.put("height", preview.height());
            raw.put("orientation", preview.orientation());
            raw.put("storedWidth", preview.storedWidth());
            raw.put("storedHeight", preview.storedHeight());
        }
        if (sensorWidth != null && sensorHeight != null) {
            ObjectNode sensor = root.putObject("sensor");
            sensor.put("width", sensorWidth);
            sensor.put("height", sensorHeight);
        }
        if (heif != null) {
            ObjectNode h = root.putObject("heif");
            h.put("turn", heif.turn());
            if (heif.mirror() == null) {
                h.putNull("mirror");
            } else {
                h.put("mirror", heif.mirror());
            }
            if (heif.mirrorFirst()) {
                h.put("mirrorFirst", true);
            }
            h.put("bitDepth", heif.bitDepth());
            h.put("auxiliary", heif.auxiliary());
            h.put("storedWidth", heif.storedWidth());
            h.put("storedHeight", heif.storedHeight());
        }
        putAll(root, "exif", exif);
        putAll(root, "xmp", xmp);
        try {
            return new PhotoFacts(format, width, height, orientation, capturedAt, gps, JSON.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A snapshot of strings and numbers always serialises", e);
        }
    }

    /** The facts a snapshot was made from (the inverse of {@link #of}). */
    public static PhotoFacts fromJson(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a photo snapshot: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject() || root.path("v").asInt(0) != SNAPSHOT_VERSION) {
            throw new IllegalArgumentException("Not a photo snapshot of version " + SNAPSHOT_VERSION);
        }
        try {
            Gps gps = null;
            JsonNode where = root.get("gps");
            if (where != null) {
                JsonNode alt = where.get("alt");
                gps = new Gps(required(where, "lat").asDouble(), required(where, "lon").asDouble(),
                        alt == null ? null : alt.asDouble());
            }
            JsonNode taken = root.get("capturedAt");
            return new PhotoFacts(PhotoFormat.valueOf(required(root, "format").asText()), intOrNull(root, "width"),
                    intOrNull(root, "height"), root.path("orientation").asInt(1),
                    taken == null ? null : Instant.parse(taken.asText()), gps, json);
        } catch (RuntimeException e) {
            throw e instanceof IllegalArgumentException ? e : new IllegalArgumentException("Not a photo snapshot: " + e.getMessage(), e);
        }
    }

    /** Where the RAW photo's preview is, from its snapshot; null for any other photo, or a snapshot without one. */
    public Preview rawPreview() {
        return rawPreview(metaJson);
    }

    /** {@link #rawPreview()} of a stored snapshot ({@code image_meta.json}); null when it has none or cannot be read. */
    public static Preview rawPreview(String metaJson) {
        try {
            JsonNode raw = metaJson == null ? null : JSON.readTree(metaJson).get("rawPreview");
            if (raw == null || !raw.isObject()) {
                return null;
            }
            Preview preview = new Preview(required(raw, "offset").asLong(), required(raw, "length").asLong(),
                    required(raw, "width").asInt(), required(raw, "height").asInt(), required(raw, "orientation").asInt(),
                    required(raw, "storedWidth").asInt(), required(raw, "storedHeight").asInt());
            return preview.offset() >= 0 && preview.length() > 0 ? preview : null;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    /** How the HEIC photo is stored, from its snapshot; null for any other photo, or a snapshot without it. */
    public Heif heif() {
        return heif(metaJson);
    }

    /** {@link #heif()} of a stored snapshot ({@code image_meta.json}); null when it has none or cannot be read. */
    public static Heif heif(String metaJson) {
        try {
            JsonNode h = metaJson == null ? null : JSON.readTree(metaJson).get("heif");
            return h == null || !h.isObject() ? null : heif(h);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    /** {@link #heif()} of the {@code heif} object itself (as {@code json_extract} gives it); null when it cannot be read. */
    public static Heif heifObject(String heifJson) {
        try {
            JsonNode h = heifJson == null ? null : JSON.readTree(heifJson);
            return h == null || !h.isObject() ? null : heif(h);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Heif heif(JsonNode h) {
        JsonNode mirror = h.get("mirror");
        return new Heif(required(h, "turn").asInt(), mirror == null || mirror.isNull() ? null : mirror.asInt(),
                h.path("mirrorFirst").asBoolean(false), required(h, "bitDepth").asInt(), h.path("auxiliary").asBoolean(),
                required(h, "storedWidth").asInt(), required(h, "storedHeight").asInt());
    }

    private static void putAll(ObjectNode root, String name, Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            ObjectNode section = root.putObject(name);
            values.forEach(section::put);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Not a photo snapshot: no " + field);
        }
        return value;
    }

    private static Integer intOrNull(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
