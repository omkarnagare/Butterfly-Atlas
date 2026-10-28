package com.butterflyatlas.app.search;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.taxa.TaxonSnapshot.Row;
import com.butterflyatlas.app.taxa.TaxonViews.Count;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Search-as-you-type over scientific names, common names, synonyms and user search words (FTS5). */
public final class SearchService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private static final Pattern ATLAS_ID = Pattern.compile("(?i)[au]\\d+");
    private static final Map<String, Integer> KIND_ORDER = Map.of("id", 0, "name", 1, "common", 1, "synonym", 2, "word", 3);
    private static final String QUERY = "SELECT taxon_id, kind, text, bm25(taxon_fts) FROM taxon_fts"
            + " WHERE taxon_fts MATCH ? ORDER BY CASE kind WHEN 'name' THEN 1 WHEN 'common' THEN 1 WHEN 'synonym' THEN 2 ELSE 3 END,"
            + " bm25(taxon_fts) LIMIT 1000";

    /**
     * One result. {@code matched} is the text that matched and {@code matchKind} says which kind
     * of name it is (id, name, common, synonym, word); {@code path} is the ancestors below the group;
     * {@code source} official, manual (a custom species, {@code family} the one it was given) or system; {@code toSort}: a
     * split species' live photos still to sort, else 0.
     */
    public record Hit(String id, String rank, String name, String common, String family, boolean active, String source,
                      Count species, String path, String matched, String matchKind, int toSort) {
    }

    private record Candidate(Row row, String kind, String text, boolean exact, double score) {
    }

    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparing((Candidate c) -> !c.exact())
            .thenComparingInt(c -> KIND_ORDER.getOrDefault(c.kind(), 9))
            .thenComparingDouble(Candidate::score)
            .thenComparingInt(c -> TaxonSnapshot.depth(c.row().rank()))
            .thenComparing(c -> c.row().name())
            .thenComparing(c -> c.row().id());

    private final Supplier<Database> database;

    public SearchService(Supplier<Database> database) {
        this.database = database;
    }

    public List<Hit> search(String query, int limit) {
        int max = Math.max(1, Math.min(limit, MAX_LIMIT));
        String typed = query == null ? "" : query.strip();
        String wanted = SearchText.normalize(typed);
        Optional<String> expression = SearchText.matchExpression(typed);
        boolean idShaped = ATLAS_ID.matcher(typed).matches();
        if (expression.isEmpty() && !idShaped) {
            return List.of();
        }
        return database.get().read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            Map<String, Candidate> best = new HashMap<>();
            if (idShaped) {
                snapshot.find(typed.toUpperCase(Locale.ROOT)).filter(snapshot::visible)
                        .ifPresent(row -> best.put(row.id(), new Candidate(row, "id", row.id(), true, Double.NEGATIVE_INFINITY)));
            }
            if (expression.isPresent()) {
                try (PreparedStatement ps = c.prepareStatement(QUERY)) {
                    ps.setString(1, expression.get());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Optional<Row> row = snapshot.find(rs.getString(1)).filter(snapshot::visible);
                            if (row.isEmpty()) {
                                continue;
                            }
                            String text = rs.getString(3);
                            Candidate candidate = new Candidate(row.get(), rs.getString(2), text,
                                    SearchText.normalize(text).equals(wanted), rs.getDouble(4));
                            best.merge(row.get().id(), candidate, (a, b) -> BEST_FIRST.compare(a, b) <= 0 ? a : b);
                        }
                    }
                }
            }
            return best.values().stream().sorted(BEST_FIRST).limit(max).map(cand -> hit(snapshot, cand)).toList();
        });
    }

    private static Hit hit(TaxonSnapshot snapshot, Candidate candidate) {
        Row row = candidate.row();
        List<Row> ancestors = snapshot.ancestors(row);
        String path = ancestors.stream().skip(1).map(Row::name).collect(Collectors.joining(" › "));
        return new Hit(row.id(), row.rank(), row.name(), row.common(), snapshot.family(row), row.active(), row.source(),
                snapshot.count(row.id()), path, candidate.text(), candidate.kind(), row.toSort());
    }
}
