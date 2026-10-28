package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.places.PlaceRef;
import com.butterflyatlas.app.places.PlaceStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Reading photos, their tags and the cover rule, inside a caller's connection. */
final class PhotoRows {

    /** Tags that mean "an adult": they count for the life cycle and win the automatic cover. */
    static final Set<String> ADULT_KEYS = Set.of("upper_wing", "under_wing", "male", "female", "dsf", "wsf");

    private static final String ADULT_LIST = ADULT_KEYS.stream().sorted().map(k -> "'" + k + "'")
            .collect(Collectors.joining(", "));

    /**
     * SQL: the photo {@code c} cannot be shown (Photo's {@code unreadable}): its thumbnail failed at the current version,
     * or it is a RAW without a preview (no size).
     */
    private static final String UNREADABLE_C = "(EXISTS (SELECT 1 FROM image_processing f WHERE f.image_id = c.id"
            + " AND f.consumer = '" + Thumbnails.CONSUMER + "' AND f.version = " + Thumbnails.VERSION
            + " AND f.status = 'failed') OR (coalesce(c.file_type, '') = 'raw' AND (c.width IS NULL OR c.height IS NULL)))";
    /** SQL: the photo {@code c} is a HEIC (shown only while the computer's decoder is available). */
    private static final String HEIC_C = "coalesce(c.file_type, '') = 'heic'";

    /**
     * SQL for the cover of the species in column {@code t.id}: the user's choice, else the newest adult photo, else the
     * newest (ties by the higher id); the automatic cover skips photos that cannot be shown (fixes J12), so a species
     * whose photos all cannot be shown has none. While HEIC photos cannot be shown (the decoder is not available now,
     * Phase 7b audit 5), every cover skips them, a chosen one included (the rule stands in until they can be). The
     * newest-first walks follow the index image_live_species (V11) and stop at the first photo that fits, instead of
     * sorting all the species' photos. Made for the decoder's status at the time of the query.
     */
    static String coverOfT() {
        String shown = HeicStatus.current().isAvailable() ? "" : " AND NOT " + HEIC_C;
        return "coalesce("
                + "(SELECT c.id FROM image c WHERE c.id = t.cover_image_id AND c.taxon_id = t.id AND c.deleted_at IS NULL"
                + shown + "),"
                + " (SELECT c.id FROM image c WHERE c.taxon_id = t.id AND c.deleted_at IS NULL AND NOT " + UNREADABLE_C
                + shown + " AND EXISTS (SELECT 1 FROM image_tag ct JOIN tag k ON k.id = ct.tag_id"
                + " WHERE ct.image_id = c.id AND k.key IN (" + ADULT_LIST + ")) ORDER BY c.added_at DESC, c.id DESC LIMIT 1),"
                + " (SELECT c.id FROM image c WHERE c.taxon_id = t.id AND c.deleted_at IS NULL AND NOT " + UNREADABLE_C
                + shown + " ORDER BY c.added_at DESC, c.id DESC LIMIT 1))";
    }

    /**
     * The photo's columns; the last says its thumbnail failed at the current version, from the first failure (fixes J2:
     * the ledger still tries it again at the next start, up to {@link ProcessingLedger#MAX_ATTEMPTS} times).
     */
    static final String COLUMNS = "i.id, i.taxon_id, i.file_name, i.rel_path, i.added_at, i.width, i.height, i.modified_at, i.file_size,"
            + " i.place_id, i.place_source, i.file_type, coalesce(i.orientation, 1), EXISTS (SELECT 1 FROM image_processing f"
            + " WHERE f.image_id = i.id AND f.consumer = '" + Thumbnails.CONSUMER + "' AND f.version = " + Thumbnails.VERSION
            + " AND f.status = 'failed'),"
            + " CASE WHEN i.file_type = 'heic' THEN (SELECT json_extract(m.json, '$.heif') FROM image_meta m"
            + " WHERE m.image_id = i.id AND json_valid(m.json)) END";

    /** SQL for the number of species with live photos. */
    static final String SPECIES_PHOTOGRAPHED = "SELECT COUNT(DISTINCT taxon_id) FROM image WHERE deleted_at IS NULL";

    private PhotoRows() {
    }

    /** The cover photo id of a species, or null when it has no live photos. */
    static Long coverId(Connection c, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + coverOfT() + " FROM taxon t WHERE t.id = ?")) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return rs.wasNull() ? null : id;
                }
                return null;
            }
        }
    }

    /** One photo, live or in Recently deleted. */
    static Optional<Photo> photo(Connection c, long id) throws SQLException {
        List<Photo> found = query(c, "SELECT " + COLUMNS + " FROM image i WHERE i.id = ?", ps -> ps.setLong(1, id));
        return found.stream().findFirst();
    }

    /** A live photo, or NOT_FOUND. */
    static Photo live(Connection c, long id) throws SQLException {
        List<Photo> found = query(c, "SELECT " + COLUMNS + " FROM image i WHERE i.id = ? AND i.deleted_at IS NULL",
                ps -> ps.setLong(1, id));
        if (found.isEmpty()) {
            throw AtlasException.noPhoto(id);
        }
        return found.get(0);
    }

    /**
     * Live photos in the order of {@code ids} (read {@value SqlLists#CHUNK} at a time), or NOT_FOUND naming the
     * first id that is not a live photo.
     */
    static List<Photo> live(Connection c, List<Long> ids) throws SQLException {
        Map<Long, Photo> found = found(c, ids);
        List<Photo> photos = new ArrayList<>(ids.size());
        for (long id : ids) {
            Photo photo = found.get(id);
            if (photo == null) {
                throw AtlasException.noPhoto(id);
            }
            photos.add(photo);
        }
        return photos;
    }

    /** The photos of {@code ids} that are live, in the order of {@code ids}; the others are left out. */
    static List<Photo> stillLive(Connection c, List<Long> ids) throws SQLException {
        Map<Long, Photo> found = found(c, ids);
        return ids.stream().filter(found::containsKey).map(found::get).toList();
    }

    private static Map<Long, Photo> found(Connection c, List<Long> ids) throws SQLException {
        Map<Long, Photo> found = new HashMap<>();
        for (List<Long> chunk : SqlLists.chunks(ids)) {
            for (Photo photo : query(c, "SELECT " + COLUMNS + " FROM image i WHERE i.deleted_at IS NULL AND i.id IN ("
                    + SqlLists.marks(chunk.size()) + ")", ps -> SqlLists.bind(ps, 1, chunk))) {
                found.put(photo.id(), photo);
            }
        }
        return found;
    }

    /** A species' live photos, newest first. */
    static List<Photo> ofTaxon(Connection c, String taxonId) throws SQLException {
        return query(c, "SELECT " + COLUMNS + " FROM image i WHERE i.taxon_id = ? AND i.deleted_at IS NULL"
                + " ORDER BY i.added_at DESC, i.id DESC", ps -> ps.setString(1, taxonId));
    }

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** A photo and the extra columns that followed {@link #COLUMNS} in its query. */
    record WithExtra(Photo photo, String[] extra) {
    }

    /** Photos from a query selecting {@link #COLUMNS} (image aliased {@code i}), with their tags, cover flags and places. */
    static List<Photo> query(Connection c, String sql, Binder binder) throws SQLException {
        return queryWithExtra(c, sql, binder, 0).stream().map(WithExtra::photo).toList();
    }

    /** Like {@link #query}, with {@code extraColumns} more string columns after the photo's, read in the same statement. */
    static List<WithExtra> queryWithExtra(Connection c, String sql, Binder binder, int extraColumns) throws SQLException {
        record Raw(long id, String taxonId, String fileName, String relPath, String addedAt, Integer width, Integer height,
                   String modifiedAt, long fileSize, String placeId, String placeSource, String fileType, int orientation,
                   boolean thumbnailFailed, String heif, String[] extra) {
        }
        final int base = 15;
        List<Raw> raws = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String[] extra = new String[extraColumns];
                    for (int i = 0; i < extraColumns; i++) {
                        extra[i] = rs.getString(base + 1 + i);
                    }
                    raws.add(new Raw(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            intOrNull(rs, 6), intOrNull(rs, 7), rs.getString(8), rs.getLong(9), rs.getString(10),
                            rs.getString(11), rs.getString(12), rs.getInt(13), rs.getBoolean(14), rs.getString(15),
                            extra));
                }
            }
        }
        Map<Long, List<String>> tags = tagsOf(c, raws.stream().map(Raw::id).toList());
        Map<String, PlaceStore.PlaceView> places = PlaceStore.views(c, raws.stream().map(Raw::placeId)
                .filter(Objects::nonNull).toList());
        Map<String, Long> covers = new HashMap<>();
        List<WithExtra> photos = new ArrayList<>(raws.size());
        for (Raw raw : raws) {
            if (!covers.containsKey(raw.taxonId())) {
                covers.put(raw.taxonId(), coverId(c, raw.taxonId()));
            }
            Long cover = covers.get(raw.taxonId());
            // A place the library does not have (none should be missing; place_id has no foreign key) reads as unknown.
            PlaceStore.PlaceView place = raw.placeId() == null ? null : places.get(raw.placeId());
            // A RAW photo's size is its preview's (as displayed); without one (its details could not be read) it has none.
            boolean isRaw = PhotoFormat.RAW.fileType().equals(raw.fileType());
            // A HEIC cannot be shown while the decoder is missing (never while it is checked: audit 18).
            boolean heicHidden = PhotoFormat.HEIC.fileType().equals(raw.fileType()) && HeicStatus.current().isMissing();
            Photo.RawPreview preview = isRaw && raw.width() != null && raw.height() != null
                    ? new Photo.RawPreview(raw.width(), raw.height(), raw.orientation()) : null;
            photos.add(new WithExtra(new Photo(raw.id(), raw.taxonId(), raw.fileName(), raw.relPath(),
                    tags.getOrDefault(raw.id(), List.of()), raw.addedAt(), raw.width(), raw.height(),
                    cover != null && cover == raw.id(), cacheKey(raw.modifiedAt(), raw.fileSize()),
                    place == null ? null : PlaceRef.of(place, raw.placeSource()), raw.fileType(), preview,
                    Photo.Heic.of(PhotoFacts.heifObject(raw.heif())),
                    raw.thumbnailFailed() || (isRaw && preview == null) || heicHidden), raw.extra()));
        }
        return photos;
    }

    /** The tag keys of many photos at once (built-in tags in their order, then the user's by label). */
    private static Map<Long, List<String>> tagsOf(Connection c, List<Long> ids) throws SQLException {
        Map<Long, List<String>> tags = new HashMap<>();
        for (List<Long> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT it.image_id, t.key FROM image_tag it"
                    + " JOIN tag t ON t.id = it.tag_id WHERE it.image_id IN (" + SqlLists.marks(chunk.size()) + ")"
                    + " ORDER BY t.source = 'user', t.sort_order, t.label COLLATE NOCASE")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tags.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            }
        }
        return tags;
    }

    /** The species with this id, or NOT_FOUND / INVALID for other ranks. */
    static TaxonRef species(Connection c, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT rank, name, common_name FROM taxon WHERE id = ?")) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AtlasException.noTaxon(taxonId);
                }
                if (!"species".equals(rs.getString(1))) {
                    throw AtlasException.invalid("Photos can only be added to a species.");
                }
                return new TaxonRef(taxonId, rs.getString(2), rs.getString(3));
            }
        }
    }

    /** Fails with INVALID naming the first key that is not a tag. */
    static void requireTags(Connection c, List<String> keys) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM tag WHERE key = ?")) {
            for (String key : keys) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw AtlasException.invalid("There is no tag called \"" + key + "\".");
                    }
                }
            }
        }
    }

    /** Adds the tags; returns how many rows went in (fewer than {@code keys.size()} if a tag is gone or already set). */
    static int addTags(Connection c, long imageId, List<String> keys) throws SQLException {
        int inserted = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO image_tag (image_id, tag_id) SELECT ?, id FROM tag WHERE key = ?")) {
            for (String key : keys) {
                ps.setLong(1, imageId);
                ps.setString(2, key);
                inserted += ps.executeUpdate();
            }
        }
        return inserted;
    }

    /** The up-front message for the first of {@code keys} that is not a tag (any key, if none is found). */
    static String noSuchTag(List<String> keys, Connection c) throws SQLException {
        try {
            requireTags(c, keys);
        } catch (AtlasException e) {
            return e.getMessage();
        }
        return "A tag changed while the photo was being added.";
    }

    static boolean livePathTaken(Connection c, String relPath) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM image WHERE rel_path = ? AND deleted_at IS NULL")) {
            ps.setString(1, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The cache key of a file ({@code image.modified_at}, {@code image.file_size}): {@code <modified millis>-<bytes>}. */
    static String cacheKey(String modifiedAt, long fileSize) {
        return Thumbnails.cacheKey(modifiedAt, fileSize);
    }

    private static Integer intOrNull(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static int count(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
