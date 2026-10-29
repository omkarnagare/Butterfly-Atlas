package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.filters.FilterQuery.Hit;
import com.butterflyatlas.app.filters.FilterQuery.ListRow;
import com.butterflyatlas.app.filters.FilterQuery.Result;
import com.butterflyatlas.app.filters.Taxa.Taxon;
import com.butterflyatlas.app.photos.TagService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The species list of an export (4b contract §4) from a result read on the caller's snapshot: a row per species in
 * taxonomic order, or per species per place at the chosen depth ({@link FilterPlaces#atDepth}); each with the photo
 * count and the labels of the tags on the row's matching photos (in tag order), the missing stages with Missing life
 * stages, and the source.
 */
final class FilterList {

    private FilterList() {
    }

    static List<ListRow> rows(Connection c, Result result, int depth) throws SQLException {
        Resolved r = result.resolved();
        PhotoMatch match = PhotoMatch.of(r, !r.used().byStages());
        Map<String, List<FilterPlaces.AtDepth>> places = FilterPlaces.atDepth(c, match, depth);
        Map<Long, String> labels = labels(c);
        Taxa taxa = r.all();
        List<ListRow> rows = new ArrayList<>();
        for (Hit hit : result.hits()) {
            Taxon t = hit.taxon();
            List<FilterPlaces.AtDepth> at = hit.photos() == 0 ? List.of() : places.getOrDefault(t.id(), List.of());
            if (at.isEmpty()) {
                at = List.of(new FilterPlaces.AtDepth(null, null, null, 0, Set.of()));
            }
            for (FilterPlaces.AtDepth place : at) {
                List<String> tags = labels.entrySet().stream().filter(e -> place.tags().contains(e.getKey()))
                        .map(Map.Entry::getValue).toList();
                Taxa.Names line = taxa.names(t);
                rows.add(new ListRow(t.id(), line.family(), line.subfamily(), line.tribe(), line.genus(), t.name(), t.common(),
                        place.country(), place.state(), place.district(), place.photos(), tags, hit.missing(), t.source()));
            }
        }
        return rows;
    }

    /** Every tag's label by id, in tag order (built-in first, by their order, then the user's by label). */
    private static Map<Long, String> labels(Connection c) throws SQLException {
        Map<Long, String> labels = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT t.id, t.label FROM tag t ORDER BY " + TagService.ORDER);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                labels.put(rs.getLong(1), rs.getString(2));
            }
        }
        return labels;
    }
}
