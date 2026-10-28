package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.places.PlaceRules;
import com.butterflyatlas.app.places.UserPlaces;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The user's choices on an import's items (design §4.3, B7; contract §2): assign a species, tag, skip, accept
 * suggestions. Each call is one transaction, saved at once. Items that are done with (filed, a duplicate, a copy),
 * missing, or that could not be read are left alone; the answer holds only the items that changed, all stamped with
 * one new change number. A decision on an item whose filing failed (assigning or clearing a species, skipping, accepting a suggestion) clears the failure,
 * so the next filing tries it again or leaves it. A choice made on one half of a RAW + JPEG pair applies to its partner
 * too (7a contract §3), and both are answered.
 */
public final class ImportEdits {

    /** SQL on {@code import_item} (not aliased): an item the user's choices still apply to. */
    static final String EDITABLE = "read_state IN ('pending', 'read') AND (outcome IS NULL OR outcome = 'failed')";

    private final Supplier<Library> library;
    private final ImportStore store;
    private final Clock clock;

    public ImportEdits(Supplier<Library> library, Clock clock) {
        this.library = library;
        this.store = new ImportStore(library, clock);
        this.clock = clock;
    }

    /** Assigns a species ({@code taxonId}, which must be a species) to the items and un-skips them, or clears it (null). */
    public ImportStore.ItemsPage assign(long runId, List<Long> rawIds, String taxonId) {
        return library.get().database().write(c -> {
            long seq = peek(c, runId);
            if (taxonId != null) {
                requireSpecies(c, taxonId);
            }
            List<Long> ids = ImportPairs.withPartners(c, runId, ids(rawIds));
            int changed = 0;
            for (List<Long> chunk : SqlLists.chunks(ids)) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET taxon_id = ?,"
                        + " skipped = CASE WHEN ? IS NULL THEN skipped ELSE 0 END,"
                        + " error = CASE WHEN outcome = 'failed' THEN NULL ELSE error END,"
                        + " outcome = CASE WHEN outcome = 'failed' THEN NULL ELSE outcome END, seq = ?"
                        + " WHERE run_id = ? AND id IN (" + SqlLists.marks(chunk.size()) + ") AND " + EDITABLE
                        + " AND (taxon_id IS NOT ? OR (? IS NOT NULL AND skipped = 1) OR outcome = 'failed')")) {
                    int at = 1;
                    for (int n = 0; n < 2; n++) {
                        ps.setString(at++, taxonId);
                    }
                    ps.setLong(at++, seq);
                    at = bindIds(ps, at, runId, chunk);
                    ps.setString(at++, taxonId);
                    ps.setString(at, taxonId);
                    changed += ps.executeUpdate();
                }
            }
            return answer(c, runId, seq, changed);
        });
    }

    /** Adds and removes tags (keys from {@code GET /api/tags}; an unknown key is 400 {@code unknown_tag}). */
    public ImportStore.ItemsPage tags(long runId, List<Long> rawIds, List<String> rawAdd, List<String> rawRemove) {
        List<String> add = keys(rawAdd);
        List<String> remove = keys(rawRemove);
        return library.get().database().write(c -> {
            long seq = peek(c, runId);
            if (add.stream().anyMatch(remove::contains)) {
                throw AtlasException.invalid("A tag cannot be added and removed at the same time.");
            }
            requireTags(c, add);
            requireTags(c, remove);
            List<Long> ids = ImportPairs.withPartners(c, runId, ids(rawIds));
            List<Long> editable = editable(c, runId, ids);
            List<Long> changed = new ArrayList<>();
            try (PreparedStatement adding = c.prepareStatement(
                    "INSERT OR IGNORE INTO import_item_tag (item_id, tag_id) SELECT ?, id FROM tag WHERE key = ?");
                 PreparedStatement removing = c.prepareStatement(
                    "DELETE FROM import_item_tag WHERE item_id = ? AND tag_id = (SELECT id FROM tag WHERE key = ?)")) {
                for (long id : editable) {
                    int rows = 0;
                    for (String key : add) {
                        adding.setLong(1, id);
                        adding.setString(2, key);
                        rows += adding.executeUpdate();
                    }
                    for (String key : remove) {
                        removing.setLong(1, id);
                        removing.setString(2, key);
                        rows += removing.executeUpdate();
                    }
                    if (rows > 0) {
                        changed.add(id);
                    }
                }
            }
            for (List<Long> chunk : SqlLists.chunks(changed)) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET seq = ? WHERE run_id = ? AND id IN ("
                        + SqlLists.marks(chunk.size()) + ")")) {
                    ps.setLong(1, seq);
                    bindIds(ps, 2, runId, chunk);
                    ps.executeUpdate();
                }
            }
            return answer(c, runId, seq, changed.size());
        });
    }

    /**
     * Chooses the place the items are filed with ({@code placeId}, as the user's), or that they are filed without one
     * ({@code remove}); the place becomes the newest recent place. An unknown or retired place is NOT_FOUND.
     */
    public ImportStore.ItemsPage place(long runId, List<Long> rawIds, String placeId, boolean remove) {
        return library.get().database().write(c -> {
            long seq = peek(c, runId);
            List<Long> ids = ImportPairs.withPartners(c, runId, PlaceRules.photoIds(rawIds));
            if ((placeId == null) != remove) {
                throw PlaceRules.invalid("Choose a place, or remove the place.");
            }
            if (placeId != null) {
                PlaceRules.usable(c, placeId);
            }
            int changed = 0;
            for (List<Long> chunk : SqlLists.chunks(ids)) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET user_place_id = ?, user_place_set = 1,"
                        + " seq = ? WHERE run_id = ? AND id IN (" + SqlLists.marks(chunk.size()) + ") AND " + EDITABLE
                        + " AND (user_place_set = 0 OR user_place_id IS NOT ?)")) {
                    ps.setString(1, placeId);
                    ps.setLong(2, seq);
                    int at = bindIds(ps, 3, runId, chunk);
                    ps.setString(at, placeId);
                    changed += ps.executeUpdate();
                }
            }
            if (placeId != null) {
                UserPlaces.used(c, placeId, clock.instant());
            }
            return answer(c, runId, seq, changed);
        });
    }

    /** Skips the items (they stay in the folder, never filed) or takes them back. */
    public ImportStore.ItemsPage skip(long runId, List<Long> rawIds, boolean skipped) {
        int flag = skipped ? 1 : 0;
        return library.get().database().write(c -> {
            long seq = peek(c, runId);
            List<Long> ids = ImportPairs.withPartners(c, runId, ids(rawIds));
            int changed = 0;
            for (List<Long> chunk : SqlLists.chunks(ids)) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET skipped = ?,"
                        + " error = CASE WHEN ? = 1 AND outcome = 'failed' THEN NULL ELSE error END,"
                        + " outcome = CASE WHEN ? = 1 AND outcome = 'failed' THEN NULL ELSE outcome END, seq = ?"
                        + " WHERE run_id = ? AND id IN (" + SqlLists.marks(chunk.size()) + ") AND " + EDITABLE
                        + " AND (skipped <> ? OR (? = 1 AND outcome = 'failed'))")) {
                    ps.setInt(1, flag);
                    ps.setInt(2, flag);
                    ps.setInt(3, flag);
                    ps.setLong(4, seq);
                    int at = bindIds(ps, 5, runId, chunk);
                    ps.setInt(at++, flag);
                    ps.setInt(at, flag);
                    changed += ps.executeUpdate();
                }
            }
            return answer(c, runId, seq, changed);
        });
    }

    /** Assigns each item its suggested species (and un-skips it); items without a suggestion are left alone. */
    public ImportStore.ItemsPage acceptSuggestions(long runId, List<Long> rawIds) {
        return library.get().database().write(c -> {
            long seq = peek(c, runId);
            List<Long> ids = ImportPairs.withPartners(c, runId, ids(rawIds));
            int changed = acceptPairs(c, runId, ids, seq);
            for (List<Long> chunk : SqlLists.chunks(ids)) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET taxon_id = suggested_taxon_id, skipped = 0,"
                        + " error = CASE WHEN outcome = 'failed' THEN NULL ELSE error END,"
                        + " outcome = CASE WHEN outcome = 'failed' THEN NULL ELSE outcome END, seq = ?"
                        + " WHERE run_id = ? AND id IN (" + SqlLists.marks(chunk.size()) + ") AND " + EDITABLE
                        + " AND suggested_taxon_id IS NOT NULL AND pair_item_id IS NULL"
                        + " AND (taxon_id IS NOT suggested_taxon_id OR skipped = 1 OR outcome = 'failed')")) {
                    ps.setLong(1, seq);
                    bindIds(ps, 2, runId, chunk);
                    changed += ps.executeUpdate();
                }
            }
            return answer(c, runId, seq, changed);
        });
    }

    /**
     * Accepts one suggestion for both halves of each pair among {@code ids}: the JPEG's when it has one, else the RAW's
     * (contract §3), so a pair is never split between two species. Returns how many items changed.
     */
    private static int acceptPairs(Connection c, long runId, List<Long> ids, long seq) throws SQLException {
        int changed = 0;
        try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET taxon_id = ?, skipped = 0,"
                + " error = CASE WHEN outcome = 'failed' THEN NULL ELSE error END,"
                + " outcome = CASE WHEN outcome = 'failed' THEN NULL ELSE outcome END, seq = ?"
                + " WHERE run_id = ? AND id IN (?, ?) AND " + EDITABLE
                + " AND (taxon_id IS NOT ? OR skipped = 1 OR outcome = 'failed')")) {
            for (Map.Entry<Long, Long> pair : ImportPairs.pairsAmong(c, runId, ids).entrySet()) {
                String suggestion = suggestion(c, pair.getKey());
                if (suggestion == null) {
                    suggestion = suggestion(c, pair.getValue());
                }
                if (suggestion == null) {
                    continue;
                }
                ps.setString(1, suggestion);
                ps.setLong(2, seq);
                ps.setLong(3, runId);
                ps.setLong(4, pair.getKey());
                ps.setLong(5, pair.getValue());
                ps.setString(6, suggestion);
                changed += ps.executeUpdate();
            }
        }
        return changed;
    }

    private static String suggestion(Connection c, long itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT suggested_taxon_id FROM import_item WHERE id = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * "Finish and leave the rest" (B10): every item still waiting (no outcome, not a problem) is skipped, left in the
     * folder. Returns how many.
     */
    public int skipRest(long runId) {
        return library.get().database().write(c -> {
            long seq = peek(c, runId);
            int changed;
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET skipped = 1, seq = ? WHERE run_id = ?"
                    + " AND outcome IS NULL AND skipped = 0 AND read_state IN ('pending', 'read', 'missing')")) {
                ps.setLong(1, seq);
                ps.setLong(2, runId);
                changed = ps.executeUpdate();
            }
            if (changed > 0) {
                long taken = ImportStore.stamp(c, runId, clock.instant());
                if (taken != seq) {
                    throw new IllegalStateException("Change number " + taken + " is not the one the items were stamped with: " + seq);
                }
            }
            return changed;
        });
    }

    /** The change number this transaction will stamp (taken for real only if something changes, {@link #answer}). */
    private static long peek(Connection c, long runId) throws SQLException {
        return ImportStore.version(c, runId) + 1;
    }

    /** Takes the change number if anything changed, and answers the changed items. */
    private ImportStore.ItemsPage answer(Connection c, long runId, long peeked, int changed) throws SQLException {
        Long seq = null;
        if (changed > 0) {
            seq = ImportStore.stamp(c, runId, clock.instant());
            if (seq != peeked) {
                throw new IllegalStateException("Change number " + seq + " is not the one the items were stamped with: " + peeked);
            }
        }
        return store.stamped(c, runId, seq);
    }

    private static List<Long> editable(Connection c, long runId, List<Long> ids) throws SQLException {
        List<Long> editable = new ArrayList<>();
        for (List<Long> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM import_item WHERE run_id = ? AND id IN ("
                    + SqlLists.marks(chunk.size()) + ") AND " + EDITABLE + " ORDER BY id")) {
                bindIds(ps, 1, runId, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        editable.add(rs.getLong(1));
                    }
                }
            }
        }
        return editable;
    }

    /** Binds the run id and then the item ids from {@code at}; returns the next position. */
    private static int bindIds(PreparedStatement ps, int at, long runId, List<Long> ids) throws SQLException {
        ps.setLong(at, runId);
        return SqlLists.bind(ps, at + 1, ids);
    }

    private static void requireSpecies(Connection c, String taxonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT rank FROM taxon WHERE id = ?")) {
            ps.setString(1, taxonId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AtlasException.noTaxon(taxonId);
                }
                if (!"species".equals(rs.getString(1))) {
                    throw ImportErrors.notASpecies();
                }
            }
        }
    }

    private static void requireTags(Connection c, List<String> keys) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM tag WHERE key = ?")) {
            for (String key : keys) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw ImportErrors.unknownTag(key);
                    }
                }
            }
        }
    }

    private static List<Long> ids(List<Long> raw) {
        List<Long> ids = raw == null ? List.of() : raw.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw AtlasException.invalid(AtlasException.NO_PHOTOS_CHOSEN);
        }
        return ids;
    }

    private static List<String> keys(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(raw.stream().filter(Objects::nonNull).map(String::strip)
                .filter(k -> !k.isEmpty()).toList()));
    }
}
