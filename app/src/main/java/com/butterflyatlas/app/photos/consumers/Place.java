package com.butterflyatlas.app.photos.consumers;

import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.photos.PhotoConsumer;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.places.PlaceIndex;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where each photo was observed, from its GPS ({@code image.place_id}, {@code place_source 'gps'}): the leaf place
 * the position lies in, or the nearest within 2 km; none without GPS or further out (both columns NULL). A place set
 * or removed by the user ({@code place_source 'user'}) is never written.
 *
 * <p>Its version is the newest place list's position in the shipped chain (5c contract §5), so after a place update
 * every live photo is due again and the catch-up looks it up again from its stored details. A photo looked up before (at
 * an older version) whose place changes now, while the library's newest list came with an update step, gets a
 * {@code place.recheck} entry in {@code change_log} (its old and new place), for the Home note.
 */
public final class Place implements PhotoConsumer {

    public static final String KEY = "place";
    /** The version for the first place list. */
    public static final int VERSION = 1;

    private final PlaceIndex index;
    private final int version;
    private final Clock clock;

    /** For the first place list (version 1). */
    public Place(PlaceIndex index) {
        this(index, VERSION);
    }

    /** With the newest place list's boundaries and its position in the chain ({@code PlaceBundles.position}). */
    public Place(PlaceIndex index, int version) {
        this(index, version, Clock.systemUTC());
    }

    /** As {@link #Place(PlaceIndex, int)}, the re-check's entries stamped by {@code clock}. */
    public Place(PlaceIndex index, int version, Clock clock) {
        if (version < 1) {
            throw new IllegalArgumentException("A place list's position starts at 1");
        }
        this.index = index;
        this.version = version;
        this.clock = clock;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public Kind kind() {
        return Kind.FACTS;
    }

    @Override
    public void apply(List<Subject> batch, Connection tx) throws SQLException {
        // Every lookup first: a lookup that fails (boundaries that cannot be read) leaves the batch unwritten.
        String[] found = new String[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            PhotoFacts.Gps gps = batch.get(i).facts().gps();
            found[i] = gps == null ? null : index.lookup(gps.lat(), gps.lon()).orElse(null);
        }
        Map<Long, String[]> before = before(tx, batch);
        String update = before.isEmpty() ? null : updateVersion(tx);
        List<Map<String, ?>> moves = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("UPDATE image SET place_id = ?, place_source = ? WHERE id = ?"
                + " AND (place_source IS NULL OR place_source <> 'user')")) {
            for (int i = 0; i < batch.size(); i++) {
                long id = batch.get(i).imageId();
                ps.setString(1, found[i]);
                ps.setString(2, found[i] == null ? null : "gps");
                ps.setLong(3, id);
                ps.addBatch();
                String[] was = before.get(id);
                if (update != null && was != null && !Objects.equals(was[0], found[i])) {
                    moves.add(ChangeLog.payload("photoId", id, "from", was[0], "to", found[i], "version", update));
                }
            }
            ps.executeBatch();
        }
        ChangeLog.recordAll(tx, clock.instant(), ChangeLog.PLACE_RECHECK, moves);
    }

    /**
     * The place of each photo of the batch looked up before at an older version and not placed by the user: {@code
     * {place_id}} (null: none found then). Photos new to this consumer (no ledger row) are left out: they were never
     * placed, so nothing moves.
     */
    private Map<Long, String[]> before(Connection tx, List<Subject> batch) throws SQLException {
        Map<Long, String[]> before = new HashMap<>();
        List<Long> ids = batch.stream().map(Subject::imageId).toList();
        for (List<Long> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = tx.prepareStatement("SELECT i.id, i.place_id FROM image i JOIN image_processing p"
                    + " ON p.image_id = i.id AND p.consumer = ? AND p.version <> ? WHERE (i.place_source IS NULL"
                    + " OR i.place_source <> 'user') AND i.id IN (" + SqlLists.marks(chunk.size()) + ")")) {
                ps.setString(1, KEY);
                ps.setInt(2, version);
                SqlLists.bind(ps, 3, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        before.put(rs.getLong(1), new String[] {rs.getString(2)});
                    }
                }
            }
        }
        return before;
    }

    /** The newest place list's version when an update step brought the library to it ({@code place_update}); else null. */
    private static String updateVersion(Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT u.version FROM place_update u WHERE u.version ="
                + " (SELECT max(bundle_version) FROM place_state)");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
