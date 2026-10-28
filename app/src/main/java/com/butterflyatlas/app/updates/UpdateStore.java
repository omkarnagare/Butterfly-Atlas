package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.JobStates;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** The {@code taxonomy_update} rows (5b contract §1): one update applied to the library, its stage, state and counts. */
final class UpdateStore {

    static final String BUNDLE = "bundle";
    static final String MATCH = "match";

    static final String PLAN = "plan";
    static final String MOVES = "moves";
    static final String SWITCH = "switch";

    static final String RUNNING = JobStates.RUNNING;
    static final String PAUSED = JobStates.PAUSED;
    static final String DONE = JobStates.DONE;

    /** One update as it is recorded. */
    record Row(long id, String kind, String from, String to, String step, String customId, String officialId, String stage,
               String state, String noticeCode, String notice, int total, int done, String startedAt, String finishedAt) {

        boolean match() {
            return MATCH.equals(kind);
        }
    }

    private static final String COLUMNS = "id, kind, from_version, to_version, step_version, custom_id, official_id, stage,"
            + " state, notice_code, notice, total, done, started_at, finished_at";

    private UpdateStore() {
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getInt(12),
                rs.getInt(13), rs.getString(14), rs.getString(15));
    }

    /** The update running or paused, if any (at most one). */
    static Optional<Row> unfinished(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM taxonomy_update"
                + " WHERE state IN ('running', 'paused')"); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(row(rs)) : Optional.empty();
        }
    }

    static Row get(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM taxonomy_update WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No taxonomy update " + id);
                }
                return row(rs);
            }
        }
    }

    /** A new update, running from its first step's plan. */
    static long create(Connection c, String kind, String from, String to, String step, String customId, String officialId,
                       Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO taxonomy_update (kind, from_version, to_version,"
                + " step_version, custom_id, official_id, stage, state, started_at) VALUES (?, ?, ?, ?, ?, ?, 'plan', 'running', ?)"
                + " RETURNING id")) {
            ps.setString(1, kind);
            ps.setString(2, from);
            ps.setString(3, to);
            ps.setString(4, step);
            ps.setString(5, customId);
            ps.setString(6, officialId);
            ps.setString(7, now.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The stage of a step ({@code stepVersion} the bundle being applied). */
    static void stage(Connection c, long id, String stage, String stepVersion) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxonomy_update SET stage = ?, step_version = ? WHERE id = ?")) {
            ps.setString(1, stage);
            ps.setString(2, stepVersion);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    static void addTotal(Connection c, long id, int moves) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxonomy_update SET total = total + ? WHERE id = ?")) {
            ps.setInt(1, moves);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    static void addDone(Connection c, long id, int moves) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxonomy_update SET done = done + ? WHERE id = ?")) {
            ps.setInt(1, moves);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Paused with a notice (code and words); Retry runs it again. */
    static void pause(Connection c, long id, String code, String notice) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxonomy_update SET state = 'paused', notice_code = ?,"
                + " notice = ? WHERE id = ? AND state <> 'done'")) {
            ps.setString(1, code);
            ps.setString(2, notice);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** Running again: the notice goes. */
    static void resume(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxonomy_update SET state = 'running', notice_code = NULL,"
                + " notice = NULL WHERE id = ? AND state <> 'done'")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    static void done(Connection c, long id, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE taxonomy_update SET state = 'done', notice_code = NULL,"
                + " notice = NULL, finished_at = ? WHERE id = ?")) {
            ps.setString(1, now.toString());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** The next {@code seq} of a step's moves. */
    static int nextSeq(Connection c, long id, String step) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(max(seq), 0) + 1 FROM taxonomy_move"
                + " WHERE update_id = ? AND step_version = ?")) {
            ps.setLong(1, id);
            ps.setString(2, step);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
