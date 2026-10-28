package com.butterflyatlas.app.placeupdates;

import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceChange;
import com.butterflyatlas.app.places.PlaceDef;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.PlaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One step of a place update (5c contract §4): brings a library from one shipped place list to the next, inside the
 * caller's write transaction, so a step is applied whole or not at all. In order: the new list's adds (so a merge may go
 * into a place new in it); the merges (photos of either source,
 * Recently deleted included, import choices and the recent place move to the survivor; the merged place's user districts
 * move under it; the merged place stays, inactive, {@code replaced_by} its survivor, and its names become the survivor's
 * other names); the renames (the old name kept as another name) and moves (the {@code ancestor_path} of
 * the whole subtree, user places included); the splits and retires (the place inactive, its photos and import choices
 * left on it, shown as former); a user place with the name of an active place beside it (accents and case ignored) merged
 * into it and deleted (design P4); the other names from the list, {@code place_state}, {@code place_update} and
 * {@code change_log}.
 *
 * <p>Never: a bundled place's row deleted, a photo's place set to none, a user place renamed, moved or deleted except by
 * its parent's merge or move or by P4.
 */
public final class PlaceStep {

    public static final String STEP = "place.step";
    public static final String RENAME = "bundled.rename";
    public static final String MOVE = "bundled.move";
    public static final String ADD = "bundled.add";
    public static final String MERGE = "bundled.merge";
    public static final String SPLIT = "bundled.split";
    public static final String RETIRE = "bundled.retire";
    /** A user place merged into a place of the list (design P4), or into a user place beside it after a merge. */
    public static final String USER_MERGED = ChangeLog.USER_PLACE_MERGED;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How many changes of each kind the list made (places merged, splits, retires, places renamed, moved, added). */
    public record Counts(int merged, int split, int retired, int renamed, int moved, int added) {
    }

    /** The names of a change's places: {@code from} as the list before had them, {@code to} as the new list has them. */
    public record Names(List<String> from, List<String> to) {
    }

    /** A change of the list that touches the library, with the live photos it changed or that sit at or below it. */
    public record Item(String type, List<String> from, List<String> to, Names names, int photos) {
    }

    /**
     * A user place merged into an official one ({@code into}), or into another user place ({@code intoMine}: two of the
     * user's places of one name came together under a merge's survivor), with how many live photos moved with it.
     */
    public record Mine(String userId, String name, String into, int photos, boolean intoMine) {
    }

    /** What a step did ({@code place_update.summary_json}): every change counted, those that touch, the user's merged. */
    public record Summary(Counts counts, List<Item> items, List<Mine> mine) {

        /** Whether the step concerns the library (design P8): a change touches it, or a user place was merged. */
        public boolean touches() {
            return !items.isEmpty() || !mine.isEmpty();
        }
    }

    /** A {@code place} row as the step keeps it in memory while it works. */
    private static final class Row {
        final String id;
        String parent;
        final String level;
        String name;
        final boolean user;
        String path;
        boolean active;

        Row(String id, String parent, String level, String name, boolean user, String path, boolean active) {
            this.id = id;
            this.parent = parent;
            this.level = level;
            this.name = name;
            this.user = user;
            this.path = path;
            this.active = active;
        }
    }

    /** What the library uses at or below each place before the step: live photos, import choices, user places. */
    private record Use(int photos, int uses) {
    }

    private final Clock clock;

    public PlaceStep(Clock clock) {
        this.clock = clock;
    }

    /**
     * Applies the step from the library's list {@code from} to the next one, {@code to} (whose {@code basedOn} is
     * {@code from}), in the caller's transaction. Returns its summary, also written into {@code place_update}.
     */
    public Summary apply(Connection tx, PlaceBundles.Shipped from, PlaceBundles.Shipped to) throws SQLException {
        if (!from.version().equals(to.basedOn())) {
            throw new IllegalArgumentException(to.version() + " is not based on " + from.version());
        }
        Instant now = clock.instant();
        String version = to.version();
        Map<String, Row> rows = rows(tx);
        Map<String, Use> use = use(tx, rows);
        Map<String, String> oldNames = new HashMap<>();
        from.places().forEach(p -> oldNames.put(p.id(), p.name()));
        Map<String, PlaceDef> listed = new LinkedHashMap<>();
        to.places().forEach(p -> listed.put(p.id(), p));
        Map<String, Set<String>> carried = new HashMap<>();   // other names a place gains in this step
        List<Mine> mine = new ArrayList<>();
        Set<String> reparented = new HashSet<>();             // user places moved under a merge's survivor

        // 1. Adds first, parents before children (the list's order), so a merge may go into a place new in this list.
        List<String> added = new ArrayList<>();
        try (PreparedStatement insert = tx.prepareStatement("INSERT INTO place (id, parent_id, level, name, source,"
                + " ancestor_path, sort_key, active) VALUES (?, ?, ?, ?, 'bundled', '/', ?, 1)")) {
            for (PlaceDef def : listed.values()) {
                if (!rows.containsKey(def.id())) {
                    insert.setString(1, def.id());
                    insert.setString(2, def.parentId());
                    insert.setString(3, def.level());
                    insert.setString(4, def.name());
                    insert.setString(5, PlaceSearch.fold(def.name()));
                    insert.executeUpdate();
                    rows.put(def.id(), new Row(def.id(), def.parentId(), def.level(), def.name(), false, "/", true));
                    added.add(def.id());
                }
            }
        }

        // 2. Merges.
        for (PlaceChange change : changes(to, PlaceChange.MERGE)) {
            String into = change.to().get(0);
            List<Long> photos = new ArrayList<>();
            int items = 0;
            for (String gone : change.from()) {
                Row row = rows.get(gone);
                if (row == null) {
                    continue; // never in this library (it was added and merged between two of its lists)
                }
                Moved moved = moveUses(tx, gone, into, now);
                photos.addAll(moved.photos());
                items += moved.importItems();
                for (Row child : List.copyOf(rows.values())) {
                    if (child.user && gone.equals(child.parent)) {
                        child.parent = into;
                        reparented.add(child.id);
                        update(tx, "UPDATE place SET parent_id = ? WHERE id = ?", into, child.id);
                    }
                }
                row.active = false;
                update(tx, "UPDATE place SET active = 0, replaced_by = ? WHERE id = ?", into, gone);
                Set<String> names = carried.computeIfAbsent(into, k -> new LinkedHashSet<>());
                names.add(row.name);
                names.addAll(aliases(tx, gone));
            }
            ChangeLog.record(tx, now, MERGE, ChangeLog.payload("from", change.from(), "to", into, "photos", photos,
                    "importItems", items, "version", version));
        }

        // 3. Renames and moves, as the list has them.
        List<Map<String, Object>> renames = new ArrayList<>();
        List<Map<String, Object>> moves = new ArrayList<>();
        try (PreparedStatement rename = tx.prepareStatement("UPDATE place SET name = ?, sort_key = ? WHERE id = ?");
             PreparedStatement move = tx.prepareStatement("UPDATE place SET parent_id = ? WHERE id = ?")) {
            for (PlaceDef def : listed.values()) {
                Row row = rows.get(def.id());
                if (!row.name.equals(def.name())) {
                    renames.add(ChangeLog.payload("id", def.id(), "from", row.name, "to", def.name(), "version", version));
                    carried.computeIfAbsent(def.id(), k -> new LinkedHashSet<>()).add(row.name);
                    rename.setString(1, def.name());
                    rename.setString(2, PlaceSearch.fold(def.name()));
                    rename.setString(3, def.id());
                    rename.executeUpdate();
                    row.name = def.name();
                }
                if (!Objects.equals(row.parent, def.parentId())) {
                    moves.add(ChangeLog.payload("id", def.id(), "fromParent", row.parent, "toParent", def.parentId(),
                            "version", version));
                    move.setString(1, def.parentId());
                    move.setString(2, def.id());
                    move.executeUpdate();
                    row.parent = def.parentId();
                }
            }
        }
        paths(tx, rows);

        // 4. Splits and retires: the place stays, inactive; its photos and import choices stay on it.
        for (PlaceChange change : changes(to, PlaceChange.SPLIT)) {
            deactivate(tx, rows, change.from().get(0), change.to());
            ChangeLog.record(tx, now, SPLIT, ChangeLog.payload("from", change.from().get(0), "to", change.to(),
                    "version", version));
        }
        for (PlaceChange change : changes(to, PlaceChange.RETIRE)) {
            deactivate(tx, rows, change.from().get(0), List.of());
            ChangeLog.record(tx, now, RETIRE, ChangeLog.payload("id", change.from().get(0), "version", version));
        }

        // 5. User places with the name of an active place beside them (design P4).
        for (Row user : List.copyOf(rows.values())) {
            if (!user.user || !rows.containsKey(user.id)) {
                continue;
            }
            Row same = sameNamed(rows, user, reparented);
            if (same != null) {
                mine.add(mergeUserPlace(tx, rows, user, same, version, now));
            }
        }

        // 6. Other names, the record of the step.
        aliases(tx, listed.values(), carried);
        if (!added.isEmpty()) {
            ChangeLog.record(tx, now, ADD, ChangeLog.payload("ids", added, "version", version));
        }
        ChangeLog.recordAll(tx, now, RENAME, renames);
        ChangeLog.recordAll(tx, now, MOVE, moves);
        Counts counts = counts(to);
        ChangeLog.record(tx, now, STEP, ChangeLog.payload("from", from.version(), "to", version, "merged", counts.merged(),
                "split", counts.split(), "retired", counts.retired(), "renamed", counts.renamed(), "moved", counts.moved(),
                "added", counts.added()));
        Summary summary = new Summary(counts, items(to, oldNames, listed, rows, use), List.copyOf(mine));
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO place_state (bundle_version, bundle_sha256, applied_at)"
                + " VALUES (?, ?, ?)")) {
            ps.setString(1, version);
            ps.setString(2, to.sha256());
            ps.setString(3, now.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO place_update (version, from_version, applied_at, touches,"
                + " summary_json) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, version);
            ps.setString(2, from.version());
            ps.setString(3, now.toString());
            ps.setInt(4, summary.touches() ? 1 : 0);
            ps.setString(5, json(summary));
            ps.executeUpdate();
        }
        return summary;
    }

    /** What {@link #moveUses} moved: the photos (ids) and how many import items. */
    private record Moved(List<Long> photos, int importItems) {
    }

    /**
     * Moves everything that names {@code gone} to {@code into}: photos (live and in Recently deleted, their source kept),
     * import items' GPS place and chosen place (an open import's items stamped, so its grid hears of it) and the recent
     * place (kept once, at the newer of the two times).
     */
    private static Moved moveUses(Connection tx, String gone, String into, Instant now) throws SQLException {
        List<Long> photos = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT id FROM image WHERE place_id = ? ORDER BY id")) {
            ps.setString(1, gone);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    photos.add(rs.getLong(1));
                }
            }
        }
        update(tx, "UPDATE image SET place_id = ? WHERE place_id = ?", into, gone);
        Map<Long, List<Long>> itemsByRun = new LinkedHashMap<>();
        int items = 0;
        try (PreparedStatement ps = tx.prepareStatement("SELECT i.id, i.run_id, r.state = 'open' FROM import_item i"
                + " JOIN import_run r ON r.id = i.run_id WHERE i.gps_place_id = ? OR (i.user_place_set = 1"
                + " AND i.user_place_id = ?) ORDER BY i.id")) {
            ps.setString(1, gone);
            ps.setString(2, gone);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items++;
                    if (rs.getInt(3) == 1) {
                        itemsByRun.computeIfAbsent(rs.getLong(2), k -> new ArrayList<>()).add(rs.getLong(1));
                    }
                }
            }
        }
        update(tx, "UPDATE import_item SET gps_place_id = ? WHERE gps_place_id = ?", into, gone);
        update(tx, "UPDATE import_item SET user_place_id = ? WHERE user_place_set = 1 AND user_place_id = ?", into, gone);
        for (Map.Entry<Long, List<Long>> run : itemsByRun.entrySet()) {
            stamp(tx, run.getKey(), run.getValue(), now);
        }
        String goneAt = recentAt(tx, gone);
        if (goneAt != null) {
            String intoAt = recentAt(tx, into);
            update(tx, "DELETE FROM place_recent WHERE place_id = ?", gone);
            if (intoAt == null || goneAt.compareTo(intoAt) > 0) {
                update(tx, "INSERT OR REPLACE INTO place_recent (place_id, used_at) VALUES (?, ?)", into, goneAt);
            }
        }
        return new Moved(photos, items);
    }

    /**
     * Gives the open import's items a change number (as {@code imports.ImportStore} stamps them: one statement takes the
     * number and moves it on), so its grid shows their new place. A copy of {@code ImportStore.stamp} (imports cannot be
     * reached from here without a package cycle): change both together.
     */
    private static void stamp(Connection tx, long runId, List<Long> itemIds, Instant now) throws SQLException {
        long seq;
        try (PreparedStatement ps = tx.prepareStatement("UPDATE import_run SET next_seq = next_seq + 1, updated_at = ?"
                + " WHERE id = ? RETURNING next_seq - 1")) {
            ps.setString(1, now.toString());
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                seq = rs.getLong(1);
            }
        }
        for (List<Long> chunk : SqlLists.chunks(itemIds)) {
            try (PreparedStatement ps = tx.prepareStatement("UPDATE import_item SET seq = ? WHERE id IN ("
                    + SqlLists.marks(chunk.size()) + ")")) {
                ps.setLong(1, seq);
                SqlLists.bind(ps, 2, chunk);
                ps.executeUpdate();
            }
        }
    }

    private static String recentAt(Connection tx, String placeId) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT used_at FROM place_recent WHERE place_id = ?")) {
            ps.setString(1, placeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** A split or retired place made inactive, unless it is one of the split's places. */
    private static void deactivate(Connection tx, Map<String, Row> rows, String id, List<String> keptBy) throws SQLException {
        Row row = rows.get(id);
        if (row != null && !keptBy.contains(id)) {
            row.active = false;
            update(tx, "UPDATE place SET active = 0 WHERE id = ?", id);
        }
    }

    /**
     * The active place beside a user place with its name (accents and case ignored): a bundled one; or, for a user place
     * moved under a merge's survivor here, a user place that was there already.
     */
    private static Row sameNamed(Map<String, Row> rows, Row user, Set<String> reparented) {
        String name = PlaceSearch.fold(user.name);
        Row userTwin = null;
        boolean before = true;   // other comes before user in the library's order
        for (Row other : rows.values()) {
            if (other == user) {
                before = false;
                continue;
            }
            if (!other.active || !Objects.equals(other.parent, user.parent) || !PlaceSearch.fold(other.name).equals(name)) {
                continue;
            }
            if (!other.user) {
                return other;
            }
            // A twin that was there already wins; of two moved here by this step's merges, the first one.
            if (reparented.contains(user.id) && (!reparented.contains(other.id) || before)
                    && (userTwin == null || reparented.contains(userTwin.id))) {
                userTwin = other;
            }
        }
        return userTwin;
    }

    /** Merges a user place into {@code into} (its photos, import choices and recent place), then deletes its row. */
    private static Mine mergeUserPlace(Connection tx, Map<String, Row> rows, Row user, Row into, String version, Instant now)
            throws SQLException {
        int live = 0;
        try (PreparedStatement ps = tx.prepareStatement("SELECT COUNT(*) FROM image WHERE place_id = ? AND deleted_at IS NULL")) {
            ps.setString(1, user.id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                live = rs.getInt(1);
            }
        }
        Moved moved = moveUses(tx, user.id, into.id, now);
        update(tx, "DELETE FROM place WHERE id = ?", user.id); // its other names go with it (ON DELETE CASCADE)
        rows.remove(user.id);
        ChangeLog.record(tx, now, USER_MERGED, ChangeLog.payload("userId", user.id, "name", user.name, "into", into.id,
                "photos", moved.photos(), "importItems", moved.importItems(), "version", version));
        return new Mine(user.id, user.name, into.id, live, into.user);
    }

    /**
     * Every bundled place of the list takes the list's other names, the names it gained in this step (a merged place's
     * names, a renamed place's old name) and those an earlier step gave it (5c contract §10: {@link #kept}); never its own
     * name. A place whose names are already those keeps its rows.
     */
    private static void aliases(Connection tx, Iterable<PlaceDef> listed, Map<String, Set<String>> carried)
            throws SQLException {
        Map<String, Set<String>> kept = kept(tx);
        Map<String, Set<String>> current = new HashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT a.place_id, a.name FROM place_alias a JOIN place p"
                + " ON p.id = a.place_id WHERE p.source = 'bundled'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                current.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        try (PreparedStatement drop = tx.prepareStatement("DELETE FROM place_alias WHERE place_id = ?");
             PreparedStatement add = tx.prepareStatement("INSERT OR IGNORE INTO place_alias (place_id, name) VALUES (?, ?)")) {
            for (PlaceDef def : listed) {
                Set<String> wanted = new LinkedHashSet<>(def.aliases());
                wanted.addAll(carried.getOrDefault(def.id(), Set.of()));
                wanted.addAll(kept.getOrDefault(def.id(), Set.of()));
                wanted.remove(def.name());
                if (wanted.equals(current.getOrDefault(def.id(), Set.of()))) {
                    continue;
                }
                drop.setString(1, def.id());
                drop.executeUpdate();
                for (String name : wanted) {
                    add.setString(1, def.id());
                    add.setString(2, name);
                    add.executeUpdate();
                }
            }
        }
    }

    /**
     * The other names earlier steps gave places, from {@code change_log}: a renamed place's old name
     * ({@code bundled.rename}), a merged place's name for its survivor ({@code bundled.merge}), a user place's name for
     * the place it was merged into ({@code place.merged}). A later list that leaves them out never takes them away.
     */
    static Map<String, Set<String>> kept(Connection tx) throws SQLException {
        Map<String, Set<String>> kept = new HashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT json_extract(payload_json, '$.id'),"
                + " json_extract(payload_json, '$.from') FROM change_log WHERE kind = ?")) {
            ps.setString(1, RENAME);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keep(tx, kept, rs.getString(1), rs.getString(2));
                }
            }
        }
        // A merged place's name and its other names (its rows stay, inactive).
        try (PreparedStatement ps = tx.prepareStatement("SELECT json_extract(c.payload_json, '$.to'), p.name, a.name"
                + " FROM change_log c JOIN json_each(c.payload_json, '$.from') f JOIN place p ON p.id = f.value"
                + " LEFT JOIN place_alias a ON a.place_id = p.id WHERE c.kind = ?")) {
            ps.setString(1, MERGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keep(tx, kept, rs.getString(1), rs.getString(2));
                    if (rs.getString(3) != null) {
                        keep(tx, kept, rs.getString(1), rs.getString(3));
                    }
                }
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT json_extract(payload_json, '$.into'),"
                + " json_extract(payload_json, '$.name') FROM change_log WHERE kind = ?")) {
            ps.setString(1, USER_MERGED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keep(tx, kept, rs.getString(1), rs.getString(2));
                }
            }
        }
        return kept;
    }

    /** Keeps {@code name} for the place {@code id} stands for now (the last survivor of its merges). */
    private static void keep(Connection tx, Map<String, Set<String>> kept, String id, String name) throws SQLException {
        kept.computeIfAbsent(PlaceStore.survivor(tx, id), k -> new LinkedHashSet<>()).add(name);
    }

    private static List<String> aliases(Connection tx, String placeId) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT name FROM place_alias WHERE place_id = ? ORDER BY name")) {
            ps.setString(1, placeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /** Every place's {@code ancestor_path} from its parent's, written where it changed (a moved place's subtree). */
    private static void paths(Connection tx, Map<String, Row> rows) throws SQLException {
        Map<String, String> paths = new HashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("UPDATE place SET ancestor_path = ? WHERE id = ?")) {
            for (Row row : rows.values()) {
                String path = path(rows, paths, row, 0);
                if (!path.equals(row.path)) {
                    row.path = path;
                    ps.setString(1, path);
                    ps.setString(2, row.id);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private static String path(Map<String, Row> rows, Map<String, String> paths, Row row, int depth) {
        String known = paths.get(row.id);
        if (known != null) {
            return known;
        }
        Row parent = row.parent == null ? null : rows.get(row.parent);
        if (depth > 3 || (row.parent != null && parent == null)) {
            throw new IllegalStateException("The place " + row.id + " has no parent " + row.parent + " in the library");
        }
        String path = (parent == null ? "/" : path(rows, paths, parent, depth + 1)) + row.id + "/";
        paths.put(row.id, path);
        return path;
    }

    private static Map<String, Row> rows(Connection tx) throws SQLException {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT id, parent_id, level, name, source, ancestor_path, active"
                + " FROM place ORDER BY rowid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.put(rs.getString(1), new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        "user".equals(rs.getString(5)), rs.getString(6), rs.getInt(7) == 1));
            }
        }
        return rows;
    }

    /**
     * What the library uses at or below each place, before the step: live photos placed there or below, and the import
     * choices (GPS or chosen place), user places and live photos there or below, any of which makes a change of the
     * place touch the library (design P8).
     */
    private static Map<String, Use> use(Connection tx, Map<String, Row> rows) throws SQLException {
        Map<String, int[]> counts = new HashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT place_id, COUNT(*) FROM image WHERE deleted_at IS NULL"
                + " AND place_id IS NOT NULL GROUP BY place_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                up(rows, counts, rs.getString(1), rs.getInt(2), rs.getInt(2));
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT place, COUNT(*) FROM (SELECT gps_place_id AS place FROM"
                + " import_item WHERE gps_place_id IS NOT NULL UNION ALL SELECT user_place_id FROM import_item"
                + " WHERE user_place_set = 1 AND user_place_id IS NOT NULL) GROUP BY place");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                up(rows, counts, rs.getString(1), 0, rs.getInt(2));
            }
        }
        for (Row row : rows.values()) {
            if (row.user) {
                up(rows, counts, row.id, 0, 1);
            }
        }
        Map<String, Use> use = new HashMap<>();
        counts.forEach((id, c) -> use.put(id, new Use(c[0], c[1])));
        return use;
    }

    /** Adds to the place and every place above it (its {@code ancestor_path}). */
    private static void up(Map<String, Row> rows, Map<String, int[]> counts, String placeId, int photos, int uses) {
        Row row = rows.get(placeId);
        if (row == null) {
            return;
        }
        for (String id : row.path.split("/")) {
            if (!id.isEmpty()) {
                int[] c = counts.computeIfAbsent(id, k -> new int[2]);
                c[0] += photos;
                c[1] += uses;
            }
        }
    }

    private static Counts counts(PlaceBundles.Shipped to) {
        int[] n = new int[PlaceChange.TYPES.size()];
        for (PlaceChange change : to.changes()) {
            int kind = PlaceChange.TYPES.indexOf(change.type());
            n[kind] += switch (change.type()) {
                case PlaceChange.MERGE, PlaceChange.RENAME, PlaceChange.MOVE -> change.from().size();
                case PlaceChange.ADD -> change.to().size();
                default -> 1;
            };
        }
        return new Counts(n[0], n[1], n[2], n[3], n[4], n[5]);
    }

    /**
     * The list's changes that touch the library, in the list's order: a merge, split, retire, rename or move of a place
     * the library uses at or below it (adds touch nothing yet). Each with the live photos at or below the places it
     * names, counted before the step.
     */
    private static List<Item> items(PlaceBundles.Shipped to, Map<String, String> oldNames, Map<String, PlaceDef> listed,
                                    Map<String, Row> rows, Map<String, Use> use) {
        List<Item> items = new ArrayList<>();
        for (PlaceChange change : to.changes()) {
            if (change.type().equals(PlaceChange.ADD)) {
                continue;
            }
            int photos = 0;
            int uses = 0;
            for (String id : change.from()) {
                Use u = use.getOrDefault(id, new Use(0, 0));
                photos += u.photos();
                uses += u.uses();
            }
            if (uses == 0) {
                continue;
            }
            List<String> fromNames = change.from().stream().map(id -> oldNames.getOrDefault(id, id)).toList();
            List<String> toNames = change.to().stream().map(id -> listed.containsKey(id) ? listed.get(id).name()
                    : rows.containsKey(id) ? rows.get(id).name : id).toList();
            items.add(new Item(change.type(), change.from(), change.to(), new Names(fromNames, toNames), photos));
        }
        return List.copyOf(items);
    }

    private static List<PlaceChange> changes(PlaceBundles.Shipped list, String type) {
        return list.changes().stream().filter(c -> c.type().equals(type)).toList();
    }

    private static void update(Connection tx, String sql, String... values) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setString(i + 1, values[i]);
            }
            ps.executeUpdate();
        }
    }

    /** The summary as {@code place_update.summary_json}. */
    static String json(Summary summary) {
        try {
            return JSON.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a place update's summary", e);
        }
    }

    /** A summary read back from {@code place_update.summary_json}. */
    static Summary summary(String json) {
        try {
            return JSON.readValue(json, Summary.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A place update's summary could not be read", e);
        }
    }
}
