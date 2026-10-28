package com.butterflyatlas.app.photos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Which consumer has processed which photo ({@code image_processing}, design §3.4). A live photo is
 * pending for a consumer when it has no row at the consumer's current version (a row at any other version,
 * older or newer, means pending), or a failed one that has failed fewer than {@link #MAX_ATTEMPTS} times at that
 * version ({@code attempts}; a photo that keeps failing is left until the consumer's version changes);
 * callers walk pending photos by id with a cursor, so a run tries each photo once and a failure is
 * retried only at the next run. Every method works inside the caller's connection or transaction.
 * Marking a photo that was purged meanwhile does nothing (background work may finish after a purge).
 */
public final class ProcessingLedger {

    public static final String DONE = "done";
    public static final String FAILED = "failed";

    /** Longer error messages are cut, so a stack of causes cannot bloat the table. */
    static final int MAX_ERROR = 500;

    /** A photo that failed this many times at a consumer's version is no longer pending for it. */
    public static final int MAX_ATTEMPTS = 3;

    private static final String UPSERT = "INSERT OR REPLACE INTO image_processing (image_id, consumer, version, status, error,"
            + " done_at, attempts) SELECT ?, ?, ?, ?, ?, ?, 0 WHERE EXISTS (SELECT 1 FROM image WHERE id = ?)";

    /** As {@link #UPSERT}, counting one more failure at this version (the first at a new version counts 1). */
    private static final String UPSERT_FAILED = "INSERT OR REPLACE INTO image_processing (image_id, consumer, version, status,"
            + " error, done_at, attempts) SELECT ?, ?, ?, ?, ?, ?, 1 + coalesce((SELECT p.attempts FROM image_processing p"
            + " WHERE p.image_id = ? AND p.consumer = ? AND p.version = ? AND p.status = 'failed'), 0)"
            + " WHERE EXISTS (SELECT 1 FROM image WHERE id = ?)";

    private final Clock clock;

    public ProcessingLedger(Clock clock) {
        this.clock = clock;
    }

    public void markDone(Connection tx, long imageId, String consumer, int version) throws SQLException {
        markDone(tx, List.of(imageId), consumer, version);
    }

    /** Marks many photos done in one batch of statements. */
    public void markDone(Connection tx, List<Long> imageIds, String consumer, int version) throws SQLException {
        String now = clock.instant().toString();
        try (PreparedStatement ps = tx.prepareStatement(UPSERT)) {
            for (long id : imageIds) {
                bind(ps, id, consumer, version, DONE, null, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Records a failure: one more attempt at this version (done rows and other versions count from nothing). */
    public void markFailed(Connection tx, long imageId, String consumer, int version, String error) throws SQLException {
        String reason = error == null || error.isBlank() ? "unknown error" : error;
        try (PreparedStatement ps = tx.prepareStatement(UPSERT_FAILED)) {
            ps.setLong(1, imageId);
            ps.setString(2, consumer);
            ps.setInt(3, version);
            ps.setString(4, FAILED);
            ps.setString(5, reason.length() > MAX_ERROR ? reason.substring(0, MAX_ERROR) : reason);
            ps.setString(6, clock.instant().toString());
            ps.setLong(7, imageId);
            ps.setString(8, consumer);
            ps.setInt(9, version);
            ps.setLong(10, imageId);
            ps.executeUpdate();
        }
    }

    /** Up to {@code limit} pending live photos with ids above {@code afterId}, in id order. */
    public List<Long> pending(Connection tx, String consumer, int version, long afterId, int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT i.id FROM image i" + PENDING
                + " AND i.id > ? ORDER BY i.id LIMIT ?")) {
            ps.setString(1, consumer);
            ps.setInt(2, version);
            ps.setString(3, FAILED);
            ps.setLong(4, afterId);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** How many live photos are pending for this consumer. */
    public int countPending(Connection tx, String consumer, int version) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT COUNT(*) FROM image i" + PENDING)) {
            ps.setString(1, consumer);
            ps.setInt(2, version);
            ps.setString(3, FAILED);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The photo's content changed: every consumer processes it again. */
    public void forget(Connection tx, long imageId) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM image_processing WHERE image_id = ?")) {
            ps.setLong(1, imageId);
            ps.executeUpdate();
        }
    }

    private static final String PENDING = " LEFT JOIN image_processing p ON p.image_id = i.id AND p.consumer = ?"
            + " WHERE i.deleted_at IS NULL AND (p.image_id IS NULL OR p.version <> ? OR (p.status = ? AND p.attempts < "
            + MAX_ATTEMPTS + "))";

    private static void bind(PreparedStatement ps, long id, String consumer, int version, String status, String error,
                             String now) throws SQLException {
        ps.setLong(1, id);
        ps.setString(2, consumer);
        ps.setInt(3, version);
        ps.setString(4, status);
        ps.setString(5, error);
        ps.setString(6, now);
        ps.setLong(7, id);
    }
}
