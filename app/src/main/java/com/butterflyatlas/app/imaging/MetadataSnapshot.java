package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.photos.PhotoFacts;
import com.drew.lang.GeoLocation;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import java.lang.reflect.Array;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The parts of metadata-extractor's view of a photo that the app keeps (design §3.1): orientation,
 * capture time, GPS, and a compact snapshot of the standard EXIF fields (IFD0 and the Exif sub-IFD)
 * and basic XMP. Maker notes, embedded thumbnails (IFD1), strip tables, ICC profiles and other
 * binary blocks are left out, so a snapshot stays around 1–2 KB.
 */
final class MetadataSnapshot {

    /** IFD0 tag every DNG carries; metadata-extractor has no constant for it but keeps the value. */
    static final int DNG_VERSION = 0xC612;

    /** Longer descriptions (comments, binary dumps) are left out. */
    static final int MAX_VALUE_LENGTH = 100;
    static final int MAX_EXIF = 60;
    static final int MAX_XMP = 40;
    /** Arrays (and byte blocks) longer than this are tables, not facts. */
    private static final int MAX_ARRAY = 16;

    private static final Set<Integer> LEFT_OUT = Set.of(ExifDirectoryBase.TAG_MAKERNOTE, ExifDirectoryBase.TAG_STRIP_OFFSETS,
            ExifDirectoryBase.TAG_STRIP_BYTE_COUNTS, ExifDirectoryBase.TAG_TILE_OFFSETS, ExifDirectoryBase.TAG_TILE_BYTE_COUNTS,
            ExifDirectoryBase.TAG_APPLICATION_NOTES, ExifDirectoryBase.TAG_IPTC_NAA, ExifDirectoryBase.TAG_PHOTOSHOP_SETTINGS,
            ExifDirectoryBase.TAG_INTER_COLOR_PROFILE, ExifDirectoryBase.TAG_PADDING, ExifDirectoryBase.TAG_SUB_IFD_OFFSET,
            DNG_VERSION, 0x8769, 0x8825, 0xA005); // the last three: Exif, GPS and interoperability pointers

    /** XMP properties worth keeping: titles, descriptions, keywords, rating, label, dates and places. */
    private static final List<String> XMP_KEPT = List.of("dc:", "xmp:Rating", "xmp:Label", "xmp:CreateDate",
            "photoshop:DateCreated", "photoshop:City", "photoshop:State", "photoshop:Country", "Iptc4xmpCore:Location",
            "lr:hierarchicalSubject");

    private static final DateTimeFormatter EXIF_TIME = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss");

    private MetadataSnapshot() {
    }

    /** EXIF orientation 1..8; 1 (upright) when absent or out of range. */
    static int orientation(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            try {
                int value = ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                return value >= 1 && value <= 8 ? value : 1;
            } catch (MetadataException e) {
                return 1;
            }
        }
        return 1;
    }

    /**
     * EXIF DateTimeOriginal: at its OffsetTimeOriginal when the camera wrote one, else as a wall-clock
     * time in {@code zone} (the computer's time zone). Null when absent or unset ({@code 0000:00:00 …}).
     */
    static Instant capturedAt(Metadata metadata, ZoneId zone) {
        for (ExifSubIFDDirectory exif : metadata.getDirectoriesOfType(ExifSubIFDDirectory.class)) {
            String text = exif.getString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL);
            if (text == null) {
                continue;
            }
            LocalDateTime local;
            try {
                String trimmed = text.strip();
                local = LocalDateTime.parse(trimmed.length() > 19 ? trimmed.substring(0, 19) : trimmed, EXIF_TIME);
            } catch (DateTimeException e) {
                continue;
            }
            String offset = exif.getString(ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL);
            if (offset != null) {
                try {
                    return local.toInstant(ZoneOffset.of(offset.strip()));
                } catch (DateTimeException e) {
                    // an unreadable offset: fall back to local time
                }
            }
            return local.atZone(zone).toInstant();
        }
        return null;
    }

    /** Latitude and longitude, with altitude when present; null when absent or exactly 0,0 (an unset receiver). */
    static PhotoFacts.Gps gps(Metadata metadata) {
        GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        if (gps == null) {
            return null;
        }
        GeoLocation location = gps.getGeoLocation();
        if (location == null || location.isZero() || !Double.isFinite(location.getLatitude())
                || !Double.isFinite(location.getLongitude()) || Math.abs(location.getLatitude()) > 90
                || Math.abs(location.getLongitude()) > 180) {
            return null;
        }
        Double altitude = null;
        Rational value = gps.getRational(GpsDirectory.TAG_ALTITUDE);
        if (value != null && value.getDenominator() != 0) {
            Integer ref = gps.getInteger(GpsDirectory.TAG_ALTITUDE_REF);
            altitude = ref != null && ref == 1 ? -value.doubleValue() : value.doubleValue();
            if (!Double.isFinite(altitude)) {
                altitude = null;
            }
        }
        return new PhotoFacts.Gps(location.getLatitude(), location.getLongitude(), altitude);
    }

    /**
     * Whether the metadata carries a position at all (the read-back check of an export, design D11): a GPS directory with
     * a latitude, longitude or destination (any value, even 0,0), or XMP with a GPS latitude or longitude (in any
     * namespace and any case: {@code exif:GPSLatitude}, {@code drone-dji:GpsLatitude}).
     */
    static boolean hasLocation(Metadata metadata) {
        for (GpsDirectory gps : metadata.getDirectoriesOfType(GpsDirectory.class)) {
            for (int tag : new int[] {GpsDirectory.TAG_LATITUDE, GpsDirectory.TAG_LONGITUDE, GpsDirectory.TAG_DEST_LATITUDE,
                    GpsDirectory.TAG_DEST_LONGITUDE}) {
                if (gps.containsTag(tag)) {
                    return true;
                }
            }
        }
        for (XmpDirectory xmp : metadata.getDirectoriesOfType(XmpDirectory.class)) {
            if (xmp.getXmpProperties().keySet().stream().map(k -> k.toLowerCase(Locale.ROOT))
                    .anyMatch(k -> k.contains("gpslatitude") || k.contains("gpslongitude"))) {
                return true;
            }
        }
        return false;
    }

    /** Standard EXIF fields of IFD0 and the Exif sub-IFD, by tag name, as metadata-extractor describes them. */
    static Map<String, String> exif(Metadata metadata) {
        Map<String, String> values = new LinkedHashMap<>();
        List<Directory> directories = new ArrayList<>(metadata.getDirectoriesOfType(ExifIFD0Directory.class));
        directories.addAll(metadata.getDirectoriesOfType(ExifSubIFDDirectory.class));
        for (Directory directory : directories) {
            for (Tag tag : directory.getTags()) {
                if (values.size() >= MAX_EXIF) {
                    return values;
                }
                int type = tag.getTagType();
                if (LEFT_OUT.contains(type) || !directory.hasTagName(type) || tooBig(directory.getObject(type))) {
                    continue;
                }
                String description;
                try {
                    description = directory.getDescription(type);
                } catch (RuntimeException e) {
                    continue; // a value this tag's describer cannot make sense of: leave the tag out
                }
                if (description != null && !description.isBlank() && description.length() <= MAX_VALUE_LENGTH) {
                    values.putIfAbsent(tag.getTagName(), description.strip());
                }
            }
        }
        return values;
    }

    /** Basic XMP properties ({@link #XMP_KEPT}), sorted by path; qualifiers ({@code dc:title[1]/xml:lang}) and struct fields are left out. */
    static Map<String, String> xmp(Metadata metadata) {
        Map<String, String> values = new TreeMap<>();
        for (XmpDirectory xmp : metadata.getDirectoriesOfType(XmpDirectory.class)) {
            xmp.getXmpProperties().forEach((path, value) -> {
                if (value != null && !path.contains("/") && value.length() <= MAX_VALUE_LENGTH
                        && XMP_KEPT.stream().anyMatch(path::startsWith)) {
                    values.putIfAbsent(path, value.strip());
                }
            });
        }
        Map<String, String> kept = new LinkedHashMap<>();
        values.entrySet().stream().limit(MAX_XMP).forEach(e -> kept.put(e.getKey(), e.getValue()));
        return kept;
    }

    private static boolean tooBig(Object value) {
        return value != null && value.getClass().isArray() && Array.getLength(value) > MAX_ARRAY;
    }
}
