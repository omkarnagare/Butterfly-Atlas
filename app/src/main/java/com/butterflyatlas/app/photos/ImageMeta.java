package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.db.SqlLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stored snapshot of each photo ({@code image_meta}, design §3.2): {@link PhotoFacts#metaJson()},
 * written once when a photo is read, so fact consumers never read the file again.
 */
public final class ImageMeta {

    private static final Logger LOG = LoggerFactory.getLogger(ImageMeta.class);

    /**
     * The details step's key and version in the processing ledger (place updates read its failures too); a failed read
     * of a photo's details is recorded under it, and the row goes once a read succeeds. Version 2 (Phase 7a) reads RAW
     * photos through their preview; V13 moved the failures of every photo that is not RAW (or of unknown type) to it,
     * so only those are read again. Version 3 (Phase 7b) reads HEIC photos from their boxes; V14 moved the failures of
     * every photo whose type is known and not HEIC to it.
     */
    public static final String DETAILS = "details";
    public static final int DETAILS_VERSION = 3;

    private ImageMeta() {
    }

    /** Stores (or replaces) a photo's snapshot; nothing for a photo purged meanwhile. */
    static void write(Connection c, long imageId, PhotoFacts facts, Instant readAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO image_meta (image_id, json, read_at)"
                + " SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM image WHERE id = ?)")) {
            ps.setLong(1, imageId);
            ps.setString(2, facts.metaJson());
            ps.setString(3, readAt.toString());
            ps.setLong(4, imageId);
            ps.executeUpdate();
        }
        fileType(c, imageId, facts);
        // On purpose: a snapshot now exists, so an earlier failed read of this photo's details no longer applies.
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM image_processing WHERE image_id = ? AND consumer = ?")) {
            ps.setLong(1, imageId);
            ps.setString(2, DETAILS);
            ps.executeUpdate();
        }
    }

    /**
     * Stores a photo's snapshot only if it has none yet (and still exists), so a slow read never replaces one
     * written meanwhile by a rescan or an import. Returns whether it was stored.
     */
    static boolean writeIfMissing(Connection c, long imageId, PhotoFacts facts, Instant readAt) throws SQLException {
        int inserted;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO image_meta (image_id, json, read_at) SELECT ?, ?, ?"
                + " WHERE EXISTS (SELECT 1 FROM image WHERE id = ?)"
                + " AND NOT EXISTS (SELECT 1 FROM image_meta WHERE image_id = ?)")) {
            ps.setLong(1, imageId);
            ps.setString(2, facts.metaJson());
            ps.setString(3, readAt.toString());
            ps.setLong(4, imageId);
            ps.setLong(5, imageId);
            inserted = ps.executeUpdate();
        }
        fileType(c, imageId, facts);
        // As in write: the photo has a snapshot now (this one or the one written meanwhile), so a failed read no longer applies.
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM image_processing WHERE image_id = ? AND consumer = ?")) {
            ps.setLong(1, imageId);
            ps.setString(2, DETAILS);
            ps.executeUpdate();
        }
        return inserted > 0;
    }

    /** Sets the photo's file type from its details when it has none yet (a photo of a library from before V13). */
    private static void fileType(Connection c, long imageId, PhotoFacts facts) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE image SET file_type = ? WHERE id = ? AND file_type IS NULL")) {
            ps.setString(1, facts.format().fileType());
            ps.setLong(2, imageId);
            ps.executeUpdate();
        }
    }

    /** Drops a snapshot that no longer matches the file (it is read again at the next start). */
    static void delete(Connection c, long imageId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM image_meta WHERE image_id = ?")) {
            ps.setLong(1, imageId);
            ps.executeUpdate();
        }
    }

    /** The facts of those of {@code ids} that have a readable snapshot, in id order; an unreadable one is deleted (see {@link #drop}). */
    static Map<Long, PhotoFacts> read(Connection c, List<Long> ids) throws SQLException {
        Map<Long, PhotoFacts> facts = new LinkedHashMap<>();
        List<Long> unreadable = new ArrayList<>();
        if (ids.isEmpty()) {
            return facts;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT image_id, json FROM image_meta WHERE image_id IN ("
                + SqlLists.marks(ids.size()) + ") ORDER BY image_id")) {
            SqlLists.bind(ps, 1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        facts.put(rs.getLong(1), PhotoFacts.fromJson(rs.getString(2)));
                    } catch (IllegalArgumentException e) {
                        unreadable.add(rs.getLong(1));
                    }
                }
            }
        }
        drop(c, unreadable);
        return facts;
    }

    /** Subjects for consumers: those of {@code ids} that have a snapshot, with their live file, in id order. */
    static List<PhotoConsumer.Subject> subjects(Connection c, Path data, List<Long> ids) throws SQLException {
        List<PhotoConsumer.Subject> subjects = new ArrayList<>();
        List<Long> unreadable = new ArrayList<>();
        if (ids.isEmpty()) {
            return subjects;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id, i.rel_path, m.json FROM image i"
                + " JOIN image_meta m ON m.image_id = i.id WHERE i.id IN (" + SqlLists.marks(ids.size()) + ") ORDER BY i.id")) {
            SqlLists.bind(ps, 1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PhotoFacts facts;
                    try {
                        facts = PhotoFacts.fromJson(rs.getString(3));
                    } catch (IllegalArgumentException e) {
                        unreadable.add(rs.getLong(1));
                        continue;
                    }
                    subjects.add(new PhotoConsumer.Subject(rs.getLong(1), facts, PhotoFiles.resolve(data, rs.getString(2))));
                }
            }
        }
        drop(c, unreadable);
        return subjects;
    }

    /** Deletes snapshots that cannot be parsed, so those photos count as missing and are read again. */
    private static void drop(Connection c, List<Long> imageIds) throws SQLException {
        for (long id : imageIds) {
            LOG.warn("Dropping the unreadable snapshot of photo {}; it will be read again", id);
            delete(c, id);
        }
    }

    /** Up to {@code limit} live photos with no snapshot and ids above {@code afterId}, in id order. */
    static List<Long> missing(Connection c, long afterId, int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id FROM image i" + MISSING + " AND i.id > ? ORDER BY i.id LIMIT ?")) {
            ps.setLong(1, afterId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    static int countMissing(Connection c) throws SQLException {
        return PhotoRows.count(c, "SELECT COUNT(*) FROM image i" + MISSING, ps -> { });
    }

    /** Live photos without a snapshot, except those whose details failed {@link ProcessingLedger#MAX_ATTEMPTS} times. */
    private static final String MISSING = " LEFT JOIN image_meta m ON m.image_id = i.id WHERE i.deleted_at IS NULL"
            + " AND m.image_id IS NULL AND NOT EXISTS (SELECT 1 FROM image_processing f WHERE f.image_id = i.id"
            + " AND f.consumer = '" + DETAILS + "' AND f.version = " + DETAILS_VERSION + " AND f.status = 'failed'"
            + " AND f.attempts >= " + ProcessingLedger.MAX_ATTEMPTS + ")";
}
