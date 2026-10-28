package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.AtlasException.Kind;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.SqlLists;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * RAW + JPEG and RAW + HEIC pairs of an import (7a design §3.1, contract §3; Phase 7b contract §4, audit 14). A pair is
 * one {@code raw} and one {@code jpeg} or {@code heic} item, both read, in the same folder, with the same name before the extension compared without case, when that name has exactly
 * those two items. Pairs are worked out after each walk and reading: a half with no edits of the user's (species,
 * tags, skip, place) takes its partner's when the pair forms, and two halves whose edits differ do not form one (audit
 * 5); an item that becomes missing or a problem leaves its pair. A pair that filing has touched (an outcome, or the
 * half the choice left out) never changes again. The choice takes effect per shot (fixes J3): the other half is left
 * out in the transaction that files the chosen one, and the choice is locked only by such an outcome, so a pause, a
 * full drive or a failed copy before it leaves the choice changeable and both halves untouched. The choice {@code jpeg}
 * means "the JPEG or HEIC half"; the words follow the kinds of partner the import's pairs have ({@link #kinds}).
 */
final class ImportPairs {

    static final String BOTH = "both";
    static final String JPEG = "jpeg";
    static final String RAW = "raw";
    static final String HEIC = "heic";
    static final List<String> CHOICES = List.of(BOTH, JPEG, RAW);
    /** SQL list of the file types of a pair's other half (not RAW). */
    static final String PARTNER_TYPES = "('jpeg', 'heic')";

    /** The {@code error} of the half a choice leaves out (contract §1); with HEIC pairs, Phase 7b contract §1. */
    static final String RAW_LEFT = "The RAW of this pair was not filed: you chose JPEG only.";
    static final String JPEG_LEFT = "The JPEG of this pair was not filed: you chose RAW only.";

    /**
     * The {@code error} of a pair's half the choice leaves out: the RAW ("you chose JPEG only", "HEIC only", or with both
     * kinds in the import "JPEG or HEIC only"), or the JPEG or HEIC half by its own type ("you chose RAW only").
     */
    static String leftOut(String leftType, List<String> kinds) {
        if (RAW.equals(leftType)) {
            return "The RAW of this pair was not filed: you chose " + partnerWords(kinds, " or ") + " only.";
        }
        return "The " + (HEIC.equals(leftType) ? "HEIC" : "JPEG") + " of this pair was not filed: you chose RAW only.";
    }

    /** "JPEG", "HEIC", or "JPEG or HEIC" (with {@code joiner}) for these kinds of partner. */
    private static String partnerWords(List<String> kinds, String joiner) {
        if (kinds.contains(JPEG) && kinds.contains(HEIC)) {
            return "JPEG" + joiner + "HEIC";
        }
        return kinds.contains(HEIC) ? "HEIC" : "JPEG";
    }

    /** "RAW + JPEG", "RAW + HEIC", or "RAW + JPEG or RAW + HEIC" for these kinds of partner. */
    static String pairWords(List<String> kinds) {
        if (kinds.contains(JPEG) && kinds.contains(HEIC)) {
            return "RAW + JPEG or RAW + HEIC";
        }
        return kinds.contains(HEIC) ? "RAW + HEIC" : "RAW + JPEG";
    }

    /** The kinds of partner the import's pairs have, {@code jpeg} before {@code heic} (empty without pairs). */
    static List<String> kinds(Connection c, long runId) throws SQLException {
        List<String> kinds = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT file_type FROM import_item WHERE run_id = ?"
                + " AND pair_item_id IS NOT NULL AND file_type IN " + PARTNER_TYPES + " ORDER BY file_type = 'heic'")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    kinds.add(rs.getString(1));
                }
            }
        }
        return kinds;
    }

    /** SQL on {@code import_item} {@code i}: a half of a pair that filing has touched. */
    static final String TOUCHED = "(i.outcome IS NOT NULL OR i.pair_left = 1)";

    /**
     * SQL list of the outcomes that put a photo in the library (or find it there): the ones that lock the choice and leave
     * the other half out (fixes J3). A failure does neither.
     */
    static final String FILED_TYPE = "('filed', 'duplicate', 'also_elsewhere', 'copy_of')";

    private record Row(long id, String relPath, String readState, String fileType, boolean touched, Long pairId,
                       Edits edits) {

        /** The folder and the name before its extension, without case. */
        String key() {
            int slash = relPath.lastIndexOf('/');
            String name = relPath.substring(slash + 1);
            int dot = name.lastIndexOf('.');
            String base = dot < 0 ? name : name.substring(0, dot);
            return relPath.substring(0, slash + 1) + "\u0000" + base.toLowerCase(Locale.ROOT);
        }
    }

    /** The user's edits of an item; {@link #NONE} when it has none. */
    private record Edits(String taxonId, boolean skipped, Set<Long> tags, boolean placeSet, String placeId) {

        static final Edits NONE = new Edits(null, false, Set.of(), false, null);
    }

    private ImportPairs() {
    }

    /** 409 {@code pair_choice_needed}: filing waits for the choice (contract §1); "1,340" as the UI counts (fixes J11). */
    static AtlasException choiceNeeded(int toFile) {
        return choiceNeeded(toFile, List.of(JPEG));
    }

    /** {@link #choiceNeeded(int)} in the words of the import's kinds of pair (Phase 7b contract §1). */
    static AtlasException choiceNeeded(int toFile, List<String> kinds) {
        String words = pairWords(kinds);
        return new AtlasException(Kind.CONFLICT, ErrorCodes.PAIR_CHOICE_NEEDED, toFile == 1
                ? "Choose how to file the 1 " + words + " pair first."
                : "Choose how to file the " + String.format(Locale.ENGLISH, "%,d", toFile) + " " + words + " pairs first.");
    }

    /** 400 {@code invalid}: not one of {@link #CHOICES}. */
    static AtlasException unknownChoice(String choice) {
        return new AtlasException(Kind.INVALID, ErrorCodes.INVALID, "Unknown choice “" + choice + "”.");
    }

    /** 409 {@code no_pairs}: a choice for an import without pairs (fixes J4). */
    static AtlasException noPairs() {
        return new AtlasException(Kind.CONFLICT, "no_pairs", "This import has no RAW + JPEG or RAW + HEIC pairs.");
    }

    /** 409 {@code pair_choice_locked}: some pairs were filed as chosen. */
    static AtlasException choiceLocked() {
        return new AtlasException(Kind.CONFLICT, ErrorCodes.PAIR_CHOICE_LOCKED,
                "Some pairs were already filed as you chose. Start a new import to file them differently.");
    }

    /** The import's pairs, or null when it has none. */
    static ImportStore.Pairs of(Connection c, long runId) throws SQLException {
        String choice;
        try (PreparedStatement ps = c.prepareStatement("SELECT pair_choice FROM import_run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                choice = rs.next() ? rs.getString(1) : null;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), coalesce(SUM(CASE WHEN i.taxon_id IS NOT NULL"
                + " AND i.skipped = 0 AND i.pair_left = 0 AND r.pair_left = 0 AND (i.outcome IS NULL OR i.outcome = 'failed')"
                + " AND (r.outcome IS NULL OR r.outcome = 'failed') THEN 1 ELSE 0 END), 0),"
                + " coalesce(MAX(CASE WHEN i.outcome IN " + FILED_TYPE + " OR r.outcome IN " + FILED_TYPE
                + " THEN 1 ELSE 0 END), 0)"
                + " FROM import_item i JOIN import_item r ON r.id = i.pair_item_id WHERE i.run_id = ?"
                + " AND i.file_type IN " + PARTNER_TYPES)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    return null;
                }
                int count = rs.getInt(1);
                int toFile = rs.getInt(2);
                boolean locked = rs.getInt(3) == 1;
                return new ImportStore.Pairs(count, toFile, choice, locked, kinds(c, runId));
            }
        }
    }

    /** The items and their partners (the edits of one half apply to its partner too). */
    static List<Long> withPartners(Connection c, long runId, List<Long> ids) throws SQLException {
        Set<Long> all = new LinkedHashSet<>(ids);
        for (List<Long> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pair_item_id FROM import_item WHERE run_id = ? AND id IN ("
                    + SqlLists.marks(chunk.size()) + ") AND pair_item_id IS NOT NULL")) {
                ps.setLong(1, runId);
                SqlLists.bind(ps, 2, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        all.add(rs.getLong(1));
                    }
                }
            }
        }
        return List.copyOf(all);
    }

    /** The pairs among {@code ids}: each JPEG or HEIC half with its RAW half. */
    static Map<Long, Long> pairsAmong(Connection c, long runId, Collection<Long> ids) throws SQLException {
        Map<Long, Long> pairs = new LinkedHashMap<>();
        for (List<Long> chunk : SqlLists.chunks(List.copyOf(ids))) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, pair_item_id FROM import_item WHERE run_id = ? AND id IN ("
                    + SqlLists.marks(chunk.size()) + ") AND pair_item_id IS NOT NULL AND file_type IN " + PARTNER_TYPES)) {
                ps.setLong(1, runId);
                SqlLists.bind(ps, 2, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pairs.put(rs.getLong(1), rs.getLong(2));
                    }
                }
            }
        }
        return pairs;
    }

    /**
     * Works the pairs out again (after a walk or a reading), stamping every item whose pair changed with {@code seq};
     * returns how many items changed.
     */
    static int recompute(Connection c, long runId, long seq) throws SQLException {
        List<Row> rows = rows(c, runId);
        Map<Long, Row> byId = new HashMap<>();
        Map<String, List<Row>> byKey = new HashMap<>();
        for (Row row : rows) {
            byId.put(row.id(), row);
            byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
        }
        Map<Long, Long> wanted = new HashMap<>();
        // Pairs filing has touched stay as they are.
        for (Row row : rows) {
            Row partner = row.pairId() == null ? null : byId.get(row.pairId());
            if (partner != null && (row.touched() || partner.touched())) {
                wanted.put(row.id(), partner.id());
            }
        }
        Map<Long, Row> takesEditsFrom = new HashMap<>();
        for (List<Row> group : byKey.values()) {
            if (group.size() != 2) {
                continue;
            }
            Row a = group.get(0);
            Row b = group.get(1);
            if (wanted.containsKey(a.id()) || wanted.containsKey(b.id()) || !ImportStore.READ.equals(a.readState())
                    || !ImportStore.READ.equals(b.readState()) || a.touched() || b.touched()
                    || !(Set.of(RAW, JPEG).equals(Set.of(String.valueOf(a.fileType()), String.valueOf(b.fileType())))
                            || Set.of(RAW, HEIC).equals(Set.of(String.valueOf(a.fileType()), String.valueOf(b.fileType()))))) {
                continue;
            }
            boolean paired = Objects.equals(a.pairId(), b.id()) && Objects.equals(b.pairId(), a.id());
            if (!paired && !a.edits().equals(b.edits())) {
                if (a.edits().equals(Edits.NONE)) {
                    takesEditsFrom.put(a.id(), b);
                } else if (b.edits().equals(Edits.NONE)) {
                    takesEditsFrom.put(b.id(), a);
                } else {
                    continue; // both edited, differently: two tiles, so no choice is lost (audit 5)
                }
            }
            wanted.put(a.id(), b.id());
            wanted.put(b.id(), a.id());
        }
        int changed = 0;
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET pair_item_id = ?, seq = ? WHERE id = ?")) {
            for (Row row : rows) {
                Long pair = wanted.get(row.id());
                if (!Objects.equals(pair, row.pairId())) {
                    ps.setObject(1, pair);
                    ps.setLong(2, seq);
                    ps.setLong(3, row.id());
                    ps.executeUpdate();
                    changed++;
                }
            }
        }
        for (Map.Entry<Long, Row> copy : takesEditsFrom.entrySet()) {
            copyEdits(c, copy.getValue(), copy.getKey(), seq);
        }
        return changed;
    }

    private static void copyEdits(Connection c, Row from, long to, long seq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET taxon_id = ?, skipped = ?, user_place_set = ?,"
                + " user_place_id = ?, seq = ? WHERE id = ?")) {
            ps.setString(1, from.edits().taxonId());
            ps.setInt(2, from.edits().skipped() ? 1 : 0);
            ps.setInt(3, from.edits().placeSet() ? 1 : 0);
            ps.setString(4, from.edits().placeId());
            ps.setLong(5, seq);
            ps.setLong(6, to);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO import_item_tag (item_id, tag_id)"
                + " SELECT ?, tag_id FROM import_item_tag WHERE item_id = ?")) {
            ps.setLong(1, to);
            ps.setLong(2, from.id());
            ps.executeUpdate();
        }
    }

    private static List<Row> rows(Connection c, long runId) throws SQLException {
        Map<Long, Set<Long>> tags = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT t.item_id, t.tag_id FROM import_item_tag t"
                + " JOIN import_item i ON i.id = t.item_id WHERE i.run_id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tags.computeIfAbsent(rs.getLong(1), k -> new TreeSet<>()).add(rs.getLong(2));
                }
            }
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.id, i.rel_path, i.read_state, i.file_type, " + TOUCHED + ","
                + " i.pair_item_id, i.taxon_id, i.skipped, i.user_place_set, i.user_place_id FROM import_item i"
                + " WHERE i.run_id = ? ORDER BY i.id")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    long pair = rs.getLong(6);
                    Long pairId = rs.wasNull() ? null : pair;
                    boolean placeSet = rs.getInt(9) == 1;
                    rows.add(new Row(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) == 1, pairId,
                            new Edits(rs.getString(7), rs.getInt(8) == 1, Set.copyOf(tags.getOrDefault(id, Set.of())),
                                    placeSet, placeSet ? rs.getString(10) : null)));
                }
            }
        }
        return rows;
    }
}
