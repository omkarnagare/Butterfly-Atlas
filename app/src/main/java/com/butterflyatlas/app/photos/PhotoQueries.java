package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.places.PlaceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Reading photos: a species' gallery, Recently deleted, originals, and the photo facts the taxon views show. */
public final class PhotoQueries {

    private static final Logger LOG = LoggerFactory.getLogger(PhotoQueries.class);

    /** How long photos stay in Recently deleted. */
    public static final Duration KEEP_DELETED = Duration.ofDays(30);

    /** A photo in Recently deleted: the {@link Photo} fields, when it was deleted, days left, and its species. */
    public record DeletedPhoto(long id, String taxonId, String fileName, String relPath, List<String> tags, String addedAt,
                               Integer width, Integer height, boolean isCover, String v, PlaceRef place, String fileType,
                               Photo.RawPreview rawPreview, Photo.Heic heic, boolean unreadable, String deletedAt,
                               int daysLeft,
                               TaxonRef taxon) {
    }

    /** A photo's file on disk and its content type. */
    public record Original(Path file, String contentType) {
    }

    /** A species card's cover: the photo id and a cache key that changes whenever the file does. */
    public record Cover(long id, String v) {
    }

    /** Which life stages a species' live photos show. */
    public record Stages(boolean egg, boolean larva, boolean pupa, boolean adult) {

        public static final Stages NONE = new Stages(false, false, false, false);
        /** The tag keys of the stages before the adult, in life order (Missing life stages looks for these). */
        public static final List<String> EARLY_KEYS = List.of("egg", "larva", "pupa");

        public boolean full() {
            return egg && larva && pupa && adult;
        }

        static Stages of(Set<String> keys) {
            return new Stages(keys.contains("egg"), keys.contains("larva"), keys.contains("pupa"),
                    keys.stream().anyMatch(PhotoRows.ADULT_KEYS::contains));
        }
    }

    private final Supplier<Library> library;
    private final Clock clock;

    public PhotoQueries(Supplier<Library> library, Clock clock) {
        this.library = library;
        this.clock = clock;
    }

    /** A taxon's live photos, newest first; empty for ranks above species. */
    public List<Photo> photos(String taxonId) {
        return library.get().database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM taxon WHERE id = ?")) {
                ps.setString(1, taxonId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw AtlasException.noTaxon(taxonId);
                    }
                }
            }
            return PhotoRows.ofTaxon(c, taxonId);
        });
    }

    /** Live photos in the order of {@code ids}, inside the caller's connection; NOT_FOUND names the first that is not live. */
    public static List<Photo> live(Connection c, List<Long> ids) throws SQLException {
        return PhotoRows.live(c, ids);
    }

    /** The photos of {@code ids} that are still live, in the order of {@code ids}, inside the caller's connection. */
    public static List<Photo> stillLive(Connection c, List<Long> ids) throws SQLException {
        return PhotoRows.stillLive(c, ids);
    }

    /** One live photo. */
    public Photo photo(long id) {
        return library.get().database().read(c -> PhotoRows.live(c, id));
    }

    /** Recently deleted, most recently deleted first. */
    public List<DeletedPhoto> deleted() {
        Instant now = clock.instant();
        return library.get().database().read(c -> {
            // one statement: the photo, when it was deleted and its species, so nothing can change between reads
            List<PhotoRows.WithExtra> rows = PhotoRows.queryWithExtra(c, "SELECT " + PhotoRows.COLUMNS
                    + ", i.deleted_at, t.name, t.common_name FROM image i JOIN taxon t ON t.id = i.taxon_id"
                    + " WHERE i.deleted_at IS NOT NULL ORDER BY i.deleted_at DESC, i.id DESC", ps -> { }, 3);
            List<DeletedPhoto> deleted = new ArrayList<>();
            for (PhotoRows.WithExtra row : rows) {
                Photo p = row.photo();
                String[] e = row.extra();
                deleted.add(new DeletedPhoto(p.id(), p.taxonId(), p.fileName(), p.relPath(), p.tags(), p.addedAt(),
                        p.width(), p.height(), false, p.v(), p.place(), p.fileType(), p.rawPreview(), p.heic(), p.unreadable(), e[0],
                        daysLeft(Instant.parse(e[0]), now),
                        new TaxonRef(p.taxonId(), e[1], e[2])));
            }
            return deleted;
        });
    }

    /** The file of a photo, live or in Recently deleted, for the viewer; a RAW file is {@code application/octet-stream}. */
    public Original original(long id) {
        Library lib = library.get();
        Stored stored = stored(lib, id);
        Path file = PhotoFiles.resolve(lib.folder(), stored.relPath());
        if (!Files.isRegularFile(file)) {
            throw AtlasException.notFound("The file of this photo can no longer be found.");
        }
        return new Original(file, PhotoFormat.RAW.fileType().equals(stored.fileType()) ? PhotoFormat.RAW.contentType()
                : contentType(file));
    }

    /**
     * A RAW photo's preview for the viewer ({@link RawDisplays}), live or in Recently deleted; empty for any other photo.
     * UNSUPPORTED when the preview no longer reads.
     */
    public Optional<RawDisplays.Preview> rawDisplay(long id) {
        Library lib = library.get();
        Stored stored = stored(lib, id);
        if (!PhotoFormat.RAW.fileType().equals(stored.fileType())) {
            return Optional.empty();
        }
        Path file = PhotoFiles.resolve(lib.folder(), stored.relPath());
        if (!Files.isRegularFile(file)) {
            throw AtlasException.notFound("The file of this photo can no longer be found.");
        }
        RawDisplays.Preview preview;
        try {
            preview = RawDisplays.of(file, PhotoFacts.rawPreview(stored.metaJson()), stored.fileSize(), stored.modifiedAt());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (preview.found() != null) {
            remember(lib, id, preview.found());
        }
        return Optional.of(preview);
    }

    /**
     * A preview found again goes back into the photo's details, with the file's size and time it was found in, so the
     * next view reads it at once instead of looking for it again (fixes J9); a photo without readable details is left
     * to the details catch-up. The rescan rewrites the details when it reads the changed file. A failure is only logged.
     */
    private static void remember(Library lib, long id, RawDisplays.Found found) {
        try {
            lib.database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE image_meta SET json = json_set(json,"
                        + " '$.rawPreview.offset', ?, '$.rawPreview.length', ?, '$.rawPreview.fileSize', ?,"
                        + " '$.rawPreview.modifiedAt', ?) WHERE image_id = ? AND json_valid(json)"
                        + " AND json_type(json, '$.rawPreview') = 'object'")) {
                    ps.setLong(1, found.offset());
                    ps.setLong(2, found.length());
                    ps.setLong(3, found.fileSize());
                    ps.setString(4, found.modifiedAt());
                    ps.setLong(5, id);
                    return ps.executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not remember where the preview of photo {} is now", id, e);
        }
    }

    /**
     * What the viewer needs to know of a photo's file; {@code fileSize} and {@code modifiedAt} are those its stored
     * preview was found at (a preview found again remembers its own, else the photo's).
     */
    private record Stored(String relPath, String fileType, long fileSize, String modifiedAt, String metaJson) {
    }

    private static Stored stored(Library lib, long id) {
        return lib.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT CASE WHEN i.deleted_at IS NULL THEN i.rel_path"
                    + " ELSE i.deleted_rel_path END, i.file_type, i.file_size, i.modified_at, m.json,"
                    + " CASE WHEN json_valid(m.json) THEN json_extract(m.json, '$.rawPreview.fileSize') END,"
                    + " CASE WHEN json_valid(m.json) THEN json_extract(m.json, '$.rawPreview.modifiedAt') END"
                    + " FROM image i LEFT JOIN image_meta m ON m.image_id = i.id WHERE i.id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString(1) == null) {
                        throw AtlasException.noPhoto(id);
                    }
                    long foundSize = rs.getLong(6);
                    boolean found = !rs.wasNull() && rs.getString(7) != null;
                    return new Stored(rs.getString(1), rs.getString(2), found ? foundSize : rs.getLong(3),
                            found ? rs.getString(7) : rs.getString(4), rs.getString(5));
                }
            }
        });
    }

    /** Whole days left before a photo deleted at {@code deletedAt} is removed for good, rounded up. */
    static int daysLeft(Instant deletedAt, Instant now) {
        long seconds = Duration.between(now, deletedAt.plus(KEEP_DELETED)).toSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (int) ((seconds + 86_399) / 86_400);
    }

    private static String contentType(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(PhotoFormat.HEAD_BYTES);
            return PhotoFormat.detect(head)
                    .or(() -> PhotoFormat.fromFileName(file.getFileName().toString()))
                    .map(PhotoFormat::contentType)
                    .orElse("application/octet-stream");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- for the taxon views (TaxonService), inside its read ----

    /** How many live photos the library holds. */
    public static int liveCount(Connection c) throws SQLException {
        return PhotoRows.count(c, "SELECT COUNT(*) FROM image WHERE deleted_at IS NULL", ps -> { });
    }

    /** A species' cover photo: chosen, else the newest adult photo, else the newest. */
    public static Optional<Photo> cover(Connection c, String taxonId) throws SQLException {
        Long id = PhotoRows.coverId(c, taxonId);
        return id == null ? Optional.empty() : PhotoRows.photo(c, id);
    }

    /** The cover of every species that has live photos, by species id. */
    public static Map<String, Cover> covers(Connection c) throws SQLException {
        return covers(c, null);
    }

    /** Like {@link #covers(Connection)}, but only for the species directly under {@code parentId} (all when null). */
    public static Map<String, Cover> covers(Connection c, String parentId) throws SQLException {
        Map<String, Cover> covers = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT x.taxon_id, i.id, i.modified_at, i.file_size FROM (SELECT t.id AS taxon_id, "
                + PhotoRows.coverOfT() + " AS cover FROM taxon t WHERE t.rank = 'species'"
                + (parentId == null ? "" : " AND t.parent_id = ?")
                + " AND EXISTS (SELECT 1 FROM image l WHERE l.taxon_id = t.id AND l.deleted_at IS NULL)) x"
                + " JOIN image i ON i.id = x.cover")) {
            if (parentId != null) {
                ps.setString(1, parentId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    covers.put(rs.getString(1), new Cover(rs.getLong(2), cacheKey(rs.getString(3), rs.getLong(4))));
                }
            }
        }
        return covers;
    }

    /** A species' life stages. */
    public static Stages stages(Connection c, String taxonId) throws SQLException {
        return Stages.of(tagKeys(c, " AND i.taxon_id = ?", ps -> ps.setString(1, taxonId))
                .getOrDefault(taxonId, Set.of()));
    }

    /** The life stages of every species with tagged live photos. */
    public static Map<String, Stages> allStages(Connection c) throws SQLException {
        Map<String, Stages> stages = new HashMap<>();
        tagKeys(c, "", ps -> { }).forEach((taxonId, keys) -> stages.put(taxonId, Stages.of(keys)));
        return stages;
    }

    /** The newest live photos across the library. */
    public static List<Photo> recent(Connection c, int limit) throws SQLException {
        return PhotoRows.query(c, "SELECT " + PhotoRows.COLUMNS + " FROM image i WHERE i.deleted_at IS NULL"
                + " ORDER BY i.added_at DESC, i.id DESC LIMIT ?", ps -> ps.setInt(1, limit));
    }

    /** The thumbnail cache key for a photo's modified time: epoch milliseconds. */
    public static String cacheKey(String modifiedAt, long fileSize) {
        return PhotoRows.cacheKey(modifiedAt, fileSize);
    }

    private static Map<String, Set<String>> tagKeys(Connection c, String where, PhotoRows.Binder binder) throws SQLException {
        Map<String, Set<String>> keys = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT i.taxon_id, t.key FROM image i"
                + " JOIN image_tag it ON it.image_id = i.id JOIN tag t ON t.id = it.tag_id WHERE i.deleted_at IS NULL" + where)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                }
            }
        }
        return keys;
    }
}
