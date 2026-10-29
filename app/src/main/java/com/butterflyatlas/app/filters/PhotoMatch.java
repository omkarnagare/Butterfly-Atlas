package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.filters.Taxa.Taxon;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The SQL of "matching photos" (4a contract §2): live photos of the chosen taxa, source and file types (7a contract §4),
 * with place match and, unless left out, tag match. {@code ancestor_path} prefixes are written as ranges ({@code >= path AND <} the path with its
 * last character, {@code /}, raised to {@code 0}): on {@code place} the index {@code place_path} serves them; {@code taxon}
 * has no {@code ancestor_path} index, so its ranges are checked row by row (each photo's species, ≈1,600 taxa).
 *
 * @param from   {@code image i}, joined to {@code taxon t} when taxa or a source are chosen
 * @param where  the conditions, starting with {@code i.deleted_at IS NULL}
 * @param params the values of the question marks of {@code from} and {@code where}, in order
 */
record PhotoMatch(String from, String where, List<Object> params) {

    /**
     * Unknown place, the one definition used everywhere: a photo with no place, or with a {@code place_id} the library
     * has no place for ({@code place_id} has no foreign key), as photos show it.
     */
    static final String UNKNOWN_PLACE = "(i.place_id IS NULL OR NOT EXISTS (SELECT 1 FROM place u WHERE u.id = i.place_id))";

    /** Matching photos of a resolved filter; {@code withTags} false leaves the tags out (Life stages). */
    static PhotoMatch of(Resolved r, boolean withTags) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("i.deleted_at IS NULL");
        boolean bySource = !Filter.ALL.equals(r.used().source());
        if (!r.taxa().isEmpty()) {
            where.append(" AND (");
            for (int n = 0; n < r.taxa().size(); n++) {
                Taxon t = r.taxa().get(n);
                where.append(n == 0 ? "" : " OR ").append("(t.ancestor_path >= ? AND t.ancestor_path < ?)");
                params.add(t.path());
                params.add(upper(t.path()));
            }
            where.append(')');
        }
        if (bySource) {
            where.append(" AND t.source = ?");
            params.add(r.used().source());
        }
        if (r.byPlace()) {
            List<String> conditions = new ArrayList<>();
            if (!r.places().isEmpty()) {
                StringBuilder in = new StringBuilder("i.place_id IN (SELECT p.id FROM place p WHERE ");
                for (int n = 0; n < r.places().size(); n++) {
                    Resolved.Place p = r.places().get(n);
                    in.append(n == 0 ? "" : " OR ").append("(p.ancestor_path >= ? AND p.ancestor_path < ?)");
                    params.add(p.path());
                    params.add(upper(p.path()));
                }
                conditions.add(in.append(')').toString());
            }
            if (r.unknown()) {
                conditions.add(UNKNOWN_PLACE);
            }
            where.append(" AND (").append(String.join(" OR ", conditions)).append(')');
        }
        if (!r.used().types().isEmpty()) {
            // 7a contract §4: like tags, per photo; a photo of an older library not read yet (no file type) matches none.
            where.append(" AND i.file_type IN (").append(SqlLists.marks(r.used().types().size())).append(')');
            params.addAll(r.used().types());
        }
        if (withTags && !r.tags().isEmpty()) {
            List<Long> ids = r.tags().stream().map(Resolved.Tag::id).toList();
            String in = "it.tag_id IN (" + SqlLists.marks(ids.size()) + ")";
            if (Filter.ALL.equals(r.used().tagMode())) {
                where.append(" AND (SELECT COUNT(*) FROM image_tag it WHERE it.image_id = i.id AND ").append(in)
                        .append(") = ?");
                params.addAll(ids);
                params.add((long) ids.size());
            } else {
                where.append(" AND EXISTS (SELECT 1 FROM image_tag it WHERE it.image_id = i.id AND ").append(in).append(')');
                params.addAll(ids);
            }
        }
        String from = r.taxa().isEmpty() && !bySource ? "image i" : "image i JOIN taxon t ON t.id = i.taxon_id";
        return new PhotoMatch(from, where.toString(), List.copyOf(params));
    }

    /** Binds the parameters from position {@code at}; returns the next position. */
    int bind(PreparedStatement ps, int at) throws SQLException {
        return SqlLists.bind(ps, at, params);
    }

    /** The first path after every path that starts with {@code path} ({@code /A1/} → {@code /A10}). */
    static String upper(String path) {
        return path.substring(0, path.length() - 1) + (char) (path.charAt(path.length() - 1) + 1);
    }
}
