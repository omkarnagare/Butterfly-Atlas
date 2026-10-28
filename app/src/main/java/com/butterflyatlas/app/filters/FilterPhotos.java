package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.filters.FilterQuery.BetweenReads;
import com.butterflyatlas.app.filters.FilterQuery.Cursor;
import com.butterflyatlas.app.filters.FilterQuery.ExportPhoto;
import com.butterflyatlas.app.filters.FilterQuery.FilterPhoto;
import com.butterflyatlas.app.filters.FilterQuery.Hit;
import com.butterflyatlas.app.filters.FilterQuery.PhotoPage;
import com.butterflyatlas.app.filters.FilterQuery.Result;
import com.butterflyatlas.app.filters.Taxa.Taxon;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.TaxonRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A page of the filter's matching photos ({@link FilterQuery#photos}): the order keys from the cursor on, read in SQL a
 * species at a time where one does not fit whole, then the page's photos, on the caller's snapshot; and every matching
 * photo in the same order for an export ({@link #all}).
 */
final class FilterPhotos {

    private FilterPhotos() {
    }

    /** The page of {@code limit} photos after {@code after} (null: from the first) of a result read on {@code c}. */
    static PhotoPage page(Connection c, Result result, String after, int limit, BetweenReads betweenReads)
            throws SQLException {
        Resolved r = result.resolved();
        Map<String, Integer> ordinal = new HashMap<>();
        for (Taxon species : r.universe()) {
            ordinal.put(species.id(), ordinal.size());
        }
        Cursor cursor = after == null || after.isEmpty() ? null : Cursor.decode(after);
        if (cursor != null && !ordinal.containsKey(cursor.taxonId())) {
            throw Cursor.outOfDate();
        }
        List<Hit> hits = result.hits().stream().filter(h -> h.photos() > 0).toList();
        int at = 0;
        while (cursor != null && at < hits.size() && ordinal.get(hits.get(at).taxon().id()) < ordinal.get(cursor.taxonId())) {
            at++;
        }
        // limit + 1 order keys from the cursor on, each species' keys ordered and cut in SQL: what is left of the
        // cursor's species, then whole species while they fit, then the first keys of the species that does not.
        PhotoMatch match = PhotoMatch.of(r, !r.used().missingStages());
        int wanted = limit + 1;
        List<Cursor> keys = new ArrayList<>(wanted);
        if (cursor != null && at < hits.size() && hits.get(at).taxon().id().equals(cursor.taxonId())) {
            keys.addAll(keys(c, match, cursor.taxonId(), cursor, wanted));
            at++;
        }
        List<String> whole = new ArrayList<>();
        int inWhole = 0;
        for (; at < hits.size() && keys.size() + inWhole < wanted; at++) {
            Hit hit = hits.get(at);
            if (keys.size() + inWhole + hit.photos() <= wanted) {
                whole.add(hit.taxon().id());
                inWhole += hit.photos();
            } else {
                keys.addAll(keys(c, match, whole, ordinal));
                whole.clear();
                inWhole = 0;
                keys.addAll(keys(c, match, hit.taxon().id(), null, wanted - keys.size()));
            }
        }
        keys.addAll(keys(c, match, whole, ordinal));
        boolean more = keys.size() > limit;
        List<Cursor> page = more ? keys.subList(0, limit) : keys;
        betweenReads.run(c);
        Map<String, Taxon> species = new HashMap<>();
        r.universe().forEach(t -> species.put(t.id(), t));
        List<FilterPhoto> photos = new ArrayList<>(page.size());
        // One snapshot: every key is still a live photo of its species. Should one not be, it is left out.
        for (Photo p : PhotoQueries.stillLive(c, page.stream().map(Cursor::id).toList())) {
            Taxon t = species.get(p.taxonId());
            if (t == null) {
                continue;
            }
            photos.add(new FilterPhoto(p.id(), p.taxonId(), p.fileName(), p.relPath(), p.tags(), p.addedAt(), p.width(),
                    p.height(), p.isCover(), p.v(), p.place(), p.fileType(), p.rawPreview(), p.heic(), p.unreadable(),
                    new TaxonRef(t.id(), t.name(), t.common())));
        }
        return new PhotoPage(photos, more ? page.get(page.size() - 1).encode() : null);
    }

    /**
     * Every matching photo of a result in the page order (species in taxonomic order, then {@code number}, nulls first,
     * then id), with its species' names: one statement.
     */
    static List<ExportPhoto> all(Connection c, Result result) throws SQLException {
        Resolved r = result.resolved();
        Map<String, Integer> ordinal = new HashMap<>();
        Map<String, Taxon> species = new HashMap<>();
        for (Hit hit : result.hits()) {
            if (hit.photos() > 0) {
                ordinal.put(hit.taxon().id(), ordinal.size());
                species.put(hit.taxon().id(), hit.taxon());
            }
        }
        if (species.isEmpty()) {
            return List.of();
        }
        PhotoMatch match = PhotoMatch.of(r, !r.used().missingStages());
        record Row(Cursor key, String fileName, long bytes, String fileType) {
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.taxon_id, i.number, i.id, i.file_name, i.file_size,"
                + " i.file_type FROM " + match.from() + " WHERE " + match.where())) {
            match.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String taxonId = rs.getString(1);
                    int number = rs.getInt(2);
                    Integer numbered = rs.wasNull() ? null : number; // wasNull right after getInt: no other read between
                    if (species.containsKey(taxonId)) {
                        rows.add(new Row(new Cursor(taxonId, numbered, rs.getLong(3)), rs.getString(4), rs.getLong(5),
                                rs.getString(6)));
                    }
                }
            }
        }
        rows.sort(Comparator.<Row>comparingInt(row -> ordinal.get(row.key().taxonId()))
                .thenComparing(row -> row.key().number(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(row -> row.key().id()));
        Taxa taxa = r.all();
        List<ExportPhoto> photos = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Taxon t = species.get(row.key().taxonId());
            Taxa.Names line = taxa.names(t);
            photos.add(new ExportPhoto(row.key().id(), t.id(), row.fileName(), row.bytes(), line.family(), line.subfamily(),
                    line.tribe(), line.genus(), t.name(), Filter.MANUAL.equals(t.source()), "raw".equals(row.fileType()),
                    "heic".equals(row.fileType())));
        }
        return photos;
    }

    /** The order keys of the matching photos of these species (whole), in order. */
    private static List<Cursor> keys(Connection c, PhotoMatch match, List<String> taxonIds, Map<String, Integer> ordinal)
            throws SQLException {
        List<Cursor> keys = new ArrayList<>();
        for (List<String> chunk : SqlLists.chunks(taxonIds)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT i.taxon_id, i.number, i.id FROM " + match.from()
                    + " WHERE " + match.where() + " AND i.taxon_id IN (" + SqlLists.marks(chunk.size()) + ")")) {
                SqlLists.bind(ps, match.bind(ps, 1), chunk);
                read(ps, keys);
            }
        }
        keys.sort(Comparator.<Cursor>comparingInt(k -> ordinal.get(k.taxonId()))
                .thenComparing(Cursor::number, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(Cursor::id));
        return keys;
    }

    /**
     * The first {@code max} order keys of one species' matching photos ({@code number}, nulls first, then id), after
     * {@code after} when given (a position in this species).
     */
    private static List<Cursor> keys(Connection c, PhotoMatch match, String taxonId, Cursor after, int max)
            throws SQLException {
        List<Object> params = new ArrayList<>();
        params.add(taxonId);
        String from = "";
        if (after != null && after.number() == null) {
            from = " AND (i.number IS NOT NULL OR i.id > ?)";
            params.add(after.id());
        } else if (after != null) {
            from = " AND (i.number > ? OR (i.number = ? AND i.id > ?))";
            params.addAll(List.of((long) after.number(), (long) after.number(), after.id()));
        }
        params.add((long) max);
        List<Cursor> keys = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.taxon_id, i.number, i.id FROM " + match.from() + " WHERE "
                + match.where() + " AND i.taxon_id = ?" + from + " ORDER BY i.number, i.id LIMIT ?")) {
            SqlLists.bind(ps, match.bind(ps, 1), params);
            read(ps, keys);
        }
        return keys;
    }

    private static void read(PreparedStatement ps, List<Cursor> keys) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String taxonId = rs.getString(1);
                int number = rs.getInt(2);
                Integer numbered = rs.wasNull() ? null : number; // wasNull right after getInt: no other read between
                keys.add(new Cursor(taxonId, numbered, rs.getLong(3)));
            }
        }
    }
}
