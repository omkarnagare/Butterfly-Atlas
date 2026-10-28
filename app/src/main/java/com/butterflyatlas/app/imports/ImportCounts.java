package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.PhotoStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * How far an import is: its items counted by category (contract §2 "ImportSummary.counts"), and its result once it has
 * filed (photos filed per species, new species, the milestone crossed, what was not filed and why).
 */
final class ImportCounts {

    /**
     * SQL: the one category of an item (aliased {@code i}, its pair's other item {@code p}), in the order the contract
     * checks them. Counts are per shot (7a contract §3): a pair counts once, by its JPEG, filed when either half is, and
     * a problem when either half failed (fixes J5).
     */
    static final String CATEGORY = "CASE WHEN i.read_state = 'pending' THEN 'reading'"
            + " WHEN i.read_state IN ('unsupported', 'failed') OR i.outcome = 'failed' OR p.outcome = 'failed' THEN 'problems'"
            + " WHEN i.read_state = 'missing' THEN 'missing'"
            + " WHEN coalesce(i.outcome, p.outcome) IS NOT NULL THEN 'filed'"
            + " WHEN i.skipped = 1 THEN 'skipped'"
            + " WHEN i.taxon_id IS NOT NULL THEN 'assigned'"
            + " WHEN i.suggested_taxon_id IS NOT NULL THEN 'suggested'"
            + " ELSE 'unassigned' END";

    /** SQL: the item stands for its shot (every item but the RAW half of a pair). */
    static final String SHOT = "NOT (i.pair_item_id IS NOT NULL AND i.file_type = 'raw')";

    private final Supplier<Library> library;

    ImportCounts(Supplier<Library> library) {
        this.library = library;
    }

    ImportStore.Counts counts(long runId) {
        return library.get().database().read(c -> counts(c, runId));
    }

    /** The counts of every import, in one query (for the list of imports). */
    Map<Long, ImportStore.Counts> countsByRun() {
        return library.get().database().read(c -> {
            Map<Long, Map<String, Integer>> byRun = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT r.id, " + CATEGORY + ", COUNT(i.id) FROM import_run r"
                    + " LEFT JOIN import_item i ON i.run_id = r.id AND " + SHOT + " LEFT JOIN import_item p"
                    + " ON p.id = i.pair_item_id GROUP BY r.id, 2");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Integer> categories = byRun.computeIfAbsent(rs.getLong(1), id -> new HashMap<>());
                    if (rs.getInt(3) > 0) {
                        categories.put(rs.getString(2), rs.getInt(3));
                    }
                }
            }
            Map<Long, ImportStore.Counts> counts = new HashMap<>();
            byRun.forEach((id, categories) -> counts.put(id, counts(categories)));
            return counts;
        });
    }

    /**
     * The import's result so far: photos filed (also those filed under another species too) per species, new species
     * (photographed first by this import), the milestone this import crossed ({@link PhotoStore#MILESTONES}, counted from
     * the species photographed when it first filed), duplicates, copies, skipped, failed, problems by reason, and how
     * many shots are still waiting ({@link #left}).
     */
    ImportStore.Result result(long runId) {
        return library.get().database().read(c -> {
            ImportStore.run(c, runId);
            Map<String, Integer> outcomes = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(outcome, CASE WHEN pair_left = 1 THEN 'pair_left'"
                    + " WHEN read_state IN ('unsupported', 'failed') THEN 'problem' WHEN skipped = 1 THEN 'skipped' ELSE 'left' END),"
                    + " COUNT(*) FROM import_item WHERE run_id = ? GROUP BY 1")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        outcomes.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            List<ImportStore.SpeciesFiled> bySpecies = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT t.id, t.name, t.common_name, COUNT(*), MAX(i.first_for_species)"
                    + " FROM import_item i JOIN taxon t ON t.id = i.taxon_id WHERE i.run_id = ?"
                    + " AND i.outcome IN ('filed', 'also_elsewhere') GROUP BY t.id ORDER BY COUNT(*) DESC, t.name")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        bySpecies.add(new ImportStore.SpeciesFiled(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                                rs.getInt(5) == 1));
                    }
                }
            }
            List<ImportStore.Problem> problems = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT problem, COUNT(*) FROM import_item WHERE run_id = ?"
                    + " AND read_state IN ('unsupported', 'failed') AND outcome IS NULL GROUP BY problem"
                    + " ORDER BY COUNT(*) DESC, problem")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        problems.add(new ImportStore.Problem(rs.getString(1), rs.getInt(2)));
                    }
                }
            }
            int speciesCount;
            Integer before;
            try (PreparedStatement ps = c.prepareStatement("SELECT (SELECT COUNT(DISTINCT taxon_id) FROM image"
                    + " WHERE deleted_at IS NULL), species_before FROM import_run WHERE id = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    speciesCount = rs.getInt(1);
                    int value = rs.getInt(2);
                    before = rs.wasNull() ? null : value;
                }
            }
            int filed = outcomes.getOrDefault(ImportStore.FILED, 0) + outcomes.getOrDefault(ImportStore.ALSO_ELSEWHERE, 0);
            int newSpecies = (int) bySpecies.stream().filter(ImportStore.SpeciesFiled::isNew).count();
            return new ImportStore.Result(filed, newSpecies, speciesCount, milestone(before, speciesCount), bySpecies,
                    outcomes.getOrDefault(ImportStore.DUPLICATE, 0), outcomes.getOrDefault(ImportStore.ALSO_ELSEWHERE, 0),
                    outcomes.getOrDefault(ImportStore.COPY_OF, 0), outcomes.getOrDefault("skipped", 0),
                    outcomes.getOrDefault(ImportStore.OUT_FAILED, 0), problems, left(c, runId),
                    lastFiling(c, runId, speciesCount), outcomes.getOrDefault(ImportStore.PAIR_LEFT, 0), pairChoice(c, runId));
        });
    }

    /**
     * The shots still waiting (fixes J5: shots, like the grid): no outcome on either half, not left out, not skipped and
     * not a problem.
     */
    private static int left(Connection c, long runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM import_item i LEFT JOIN import_item p"
                + " ON p.id = i.pair_item_id WHERE i.run_id = ? AND " + SHOT + " AND i.outcome IS NULL AND p.outcome IS NULL"
                + " AND i.pair_left = 0 AND coalesce(p.pair_left, 0) = 0 AND i.skipped = 0"
                + " AND i.read_state NOT IN ('unsupported', 'failed')")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String pairChoice(Connection c, long runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pair_choice FROM import_run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The largest of {@link PhotoStore#MILESTONES} crossed going from {@code before} to {@code now} species, or null. */
    private static Integer milestone(Integer before, int now) {
        Integer milestone = null;
        if (before != null) {
            for (int m : PhotoStore.MILESTONES) {
                if (before < m && m <= now) {
                    milestone = m;
                }
            }
        }
        return milestone;
    }

    private static ImportStore.LastFiling lastFiling(Connection c, long runId, int speciesCount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT r.filings, r.last_species_before,"
                + " (SELECT COUNT(*) FROM import_item i WHERE i.run_id = r.id AND i.filing_no = r.filings"
                + " AND i.outcome IN ('filed', 'also_elsewhere')),"
                + " (SELECT COUNT(DISTINCT i.taxon_id) FROM import_item i WHERE i.run_id = r.id AND i.filing_no = r.filings"
                + " AND i.first_for_species = 1 AND i.outcome IN ('filed', 'also_elsewhere'))"
                + " FROM import_run r WHERE r.id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    return null;
                }
                int value = rs.getInt(2);
                Integer before = rs.wasNull() ? null : value;
                return new ImportStore.LastFiling(rs.getInt(3), rs.getInt(4), milestone(before, speciesCount), speciesCount);
            }
        }
    }

    static ImportStore.Counts counts(Connection c, long runId) throws SQLException {
        Map<String, Integer> byCategory = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + CATEGORY + ", COUNT(*) FROM import_item i"
                + " LEFT JOIN import_item p ON p.id = i.pair_item_id WHERE i.run_id = ? AND " + SHOT + " GROUP BY 1")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byCategory.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return counts(byCategory);
    }

    private static ImportStore.Counts counts(Map<String, Integer> byCategory) {
        int total = byCategory.values().stream().mapToInt(Integer::intValue).sum();
        int problems = byCategory.getOrDefault("problems", 0);
        int missing = byCategory.getOrDefault("missing", 0);
        return new ImportStore.Counts(total, total - problems - missing, byCategory.getOrDefault("reading", 0),
                byCategory.getOrDefault("unassigned", 0), byCategory.getOrDefault("suggested", 0),
                byCategory.getOrDefault("assigned", 0), byCategory.getOrDefault("skipped", 0), problems,
                byCategory.getOrDefault("filed", 0), missing);
    }
}
