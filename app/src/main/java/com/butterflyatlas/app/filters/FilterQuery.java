package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.filters.Taxa.Taxon;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoQueries;
import com.butterflyatlas.app.photos.TaxonRef;
import com.butterflyatlas.app.places.PlaceRef;
import com.butterflyatlas.app.places.PlaceSearch;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Answers the Export screen's filter (4a contract §2, §3): the count, the species, a page of photos and the places. Each
 * answer reads the taxon table whole and the photos in SQL (one statement grouped by species, plus the page itself),
 * all in one {@link Database#snapshot snapshot} so its statements see one state of the library.
 */
public final class FilterQuery {

    /** The life stages Missing life stages looks for, in the order {@code missing} lists them. */
    static final List<String> STAGES = PhotoQueries.Stages.EARLY_KEYS;
    /** The photos a page holds when the request names no {@code limit}. */
    public static final int DEFAULT_LIMIT = 200;
    /** The most photos one page may hold ({@code limit} is 1 to this). */
    public static final int MAX_LIMIT = 500;
    private static final String BAD_LIMIT = "The limit must be between 1 and " + MAX_LIMIT + ".";

    /**
     * A chosen place the filter could not use as it was (5c contract §6): {@code merged} into {@code into} (used
     * instead; {@code name} the merged place's), or {@code gone} (the library has no such place; {@code name} and
     * {@code into} null).
     */
    public record PlaceNote(String id, String kind, String name, PlaceSearch.Place into) {
    }

    /** A chosen taxon as the UI shows it. */
    public record TaxonChip(String id, String rank, String name, String common) {
    }

    /** A chosen tag as the UI shows it. */
    public record TagChip(String key, String label) {
    }

    /**
     * The filter as used: {@code query} is the normalised query string; {@code places} holds a place
     * ({@code PlaceSearch.Place}) per chosen place and {@code {"id": "unknown"}} for Unknown place; {@code placeNotes} the
     * chosen places merged by a place update (replaced by their survivor) or that the library does not have (5c contract
     * §6): {@code {id, kind: "merged"|"gone", name, into}}; {@code types} the file types chosen (7a contract §4).
     */
    public record Used(String query, List<TaxonChip> taxa, List<TagChip> tags, String tagMode, List<Object> places,
                       String status, String source, String stages, List<PlaceNote> placeNotes, List<String> types) {
    }

    /**
     * The count line: matching photos and their bytes, species in the result, the species of the result with at least
     * one matching photo ({@code photographedSpecies}: with status all, fewer than {@code species} when tags or places
     * narrow the photos; 0 with status not), and whether there are manual species.
     */
    public record FilterCount(int photos, int species, int photographedSpecies, long bytes, boolean manualSpecies,
                              Used filter) {
    }

    /**
     * A species of the result: {@code family}…{@code genus} are names (null where its line has no such rank),
     * {@code photos} its matching photos, {@code missing} the absent stages with Missing life stages (else empty).
     */
    public record SpeciesRow(String id, String name, String common, String family, String subfamily, String tribe,
                             String genus, int photos, List<String> missing, String source, boolean active) {
    }

    /** A matching photo: exactly the gallery's {@link Photo}, and its species. */
    public record FilterPhoto(long id, String taxonId, String fileName, String relPath, List<String> tags, String addedAt,
                              Integer width, Integer height, boolean isCover, String v, PlaceRef place, String fileType,
                              Photo.RawPreview rawPreview, Photo.Heic heic, boolean unreadable, TaxonRef species) {
    }

    /** A page of matching photos; {@code next} is the cursor of the next page, null on the last. */
    public record PhotoPage(List<FilterPhoto> photos, String next) {
    }

    /** A row of the Places view: a place, or Unknown place. */
    public sealed interface PlaceRow permits PlaceCount, UnknownCount {
    }

    /** A place with the species and matching photos at or below it. */
    public record PlaceCount(String id, String name, List<String> path, String level, boolean mine, int species, int photos)
            implements PlaceRow {
    }

    /** Matching photos with no place (or a place the library lacks), and their species. */
    public record UnknownCount(String id, int species, int photos) implements PlaceRow {
    }

    /**
     * A row of an export's species list (4b contract §4): the species with its family…genus names (null where its line
     * has none), the row's place names down to the chosen depth (all null without places, for Unknown place, or for a
     * species with no matching photo), its matching photos there and the labels of their tags in tag order, the missing
     * stages with Missing life stages (else empty), and the source.
     */
    public record ListRow(String id, String family, String subfamily, String tribe, String genus, String name,
                          String common, String country, String state, String district, int photos, List<String> tags,
                          List<String> missing, String source) {
    }

    /**
     * A matching photo to export: what its target name is made from (its file name and its species' names, family…genus
     * null where its line has none, and whether the species is a custom one) and its size; whether it is RAW or HEIC
     * (exported exactly, with any location it holds).
     */
    public record ExportPhoto(long id, String taxonId, String fileName, long bytes, String family,
                              String subfamily, String tribe, String genus, String species, boolean custom, boolean raw,
                              boolean heic) {
    }

    /**
     * What an export of a filter takes, all read in one snapshot so the list and the photos describe the same set: the
     * filter as used (its normalised query string), the species in the result and those with matching photos, the
     * matching photos and their bytes, the species list (when asked) and the photos in order (when asked).
     */
    /**
     * What an export of the filter takes. {@code rawPhotos}: the matching photos that are RAW, {@code rawWithGps} those
     * of them whose stored details have a GPS position (7a contract §5), {@code rawWithUnknown} those whose stored
     * details could not be read (none, or not readable), so whether they hold a GPS position is unknown (fixes J7);
     * {@code heicPhotos}, {@code heicWithGps} and {@code heicWithUnknown} the same of the HEIC photos (Phase 7b contract
     * §6).
     */
    public record ExportSet(String query, int species, int photographedSpecies, int photos, long bytes, List<ListRow> rows,
                            List<ExportPhoto> photoList, int rawPhotos, int rawWithGps, int rawWithUnknown,
                            int heicPhotos, int heicWithGps, int heicWithUnknown) {
    }

    /** A species in the result with its matching photos, their bytes and its missing stages (in taxonomic order). */
    record Hit(Taxon taxon, int photos, long bytes, List<String> missing) {
    }

    /** The species of a result in taxonomic order, from one grouped statement. */
    record Result(Resolved resolved, List<Hit> hits) {

        int photos() {
            return hits.stream().mapToInt(Hit::photos).sum();
        }

        long bytes() {
            return hits.stream().mapToLong(Hit::bytes).sum();
        }
    }

    /**
     * Work run on a request's connection between two of its reads (a photos page's order keys and its photos, an export's
     * species list and its photos): tests change the library there.
     */
    @FunctionalInterface
    interface BetweenReads {
        void run(Connection c) throws SQLException;
    }

    private final Supplier<Database> database;
    private final BetweenReads betweenReads;

    public FilterQuery(Supplier<Database> database) {
        this(database, c -> { });
    }

    FilterQuery(Supplier<Database> database, BetweenReads betweenReads) {
        this.database = database;
        this.betweenReads = betweenReads;
    }

    public FilterCount count(Filter filter) {
        return database.get().snapshot(c -> {
            Result result = result(c, filter);
            Resolved r = result.resolved();
            int photographed = (int) result.hits().stream().filter(h -> h.photos() > 0).count();
            return new FilterCount(result.photos(), result.hits().size(), photographed, result.bytes(), r.all().anyManual(),
                    used(r));
        });
    }

    /** Every species of the result, in taxonomic order. */
    public List<SpeciesRow> species(Filter filter) {
        return database.get().snapshot(c -> {
            Result result = result(c, filter);
            Taxa taxa = result.resolved().all();
            return result.hits().stream().map(hit -> {
                Taxon t = hit.taxon();
                Taxa.Names line = taxa.names(t);
                return new SpeciesRow(t.id(), t.name(), t.common(), line.family(), line.subfamily(), line.tribe(), line.genus(),
                        hit.photos(), hit.missing(), t.source(), t.active());
            }).toList();
        });
    }

    /**
     * The {@code limit} of a photos request: 1 to {@value #MAX_LIMIT}, {@value #DEFAULT_LIMIT} when absent (400
     * {@code invalid} otherwise).
     */
    public static int limit(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        try {
            return checkedLimit(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            throw Filter.invalid(BAD_LIMIT);
        }
    }

    private static int checkedLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw Filter.invalid(BAD_LIMIT);
        }
        return limit;
    }

    /**
     * Matching photos in order (their species' taxonomic order, then {@code number}, nulls first, then id), {@code limit}
     * after the photo {@code after} names (null: from the first). A cursor that cannot be read, or whose species is no
     * longer in the universe, is 400 {@code invalid}.
     */
    public PhotoPage photos(Filter filter, String after, int limit) {
        checkedLimit(limit);
        return database.get().snapshot(c -> FilterPhotos.page(c, result(c, filter), after, limit, betweenReads));
    }

    /** Where a page ends: the last photo's species, number and id; written as opaque base64url text. */
    record Cursor(String taxonId, Integer number, long id) {

        String encode() {
            String text = taxonId + "|" + (number == null ? "" : number) + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || parts[0].isEmpty()) {
                    throw outOfDate();
                }
                return new Cursor(parts[0], parts[1].isEmpty() ? null : Integer.valueOf(parts[1]), Long.parseLong(parts[2]));
            } catch (IllegalArgumentException e) {
                throw outOfDate();
            }
        }

        static RuntimeException outOfDate() {
            return Filter.invalid("That page is out of date. Reload the results.");
        }
    }

    /**
     * Counts per place (design D7): no place chosen → the countries with matching photos; exactly one place → its
     * children with matching photos, and the place itself for photos placed at it directly (a district: itself);
     * several → the chosen places themselves, each counting the photos at or below it. Unknown place comes last when
     * matching photos without a place exist. Sorted by photos (most first), then name. Empty with status not. Rolled up
     * in SQL: one grouped statement (one per chosen place when several are chosen).
     */
    public List<PlaceRow> places(Filter filter) {
        return database.get().snapshot(c -> FilterPlaces.places(c, Resolved.resolve(c, filter)));
    }

    /**
     * The species list of the filter, a row per species, or per species per place at {@code placeDepth} (0 none, 1
     * country, 2 state, 3 district), read in one snapshot.
     */
    public ExportSet exportSet(Filter filter, int placeDepth) {
        return exportSet(filter, placeDepth, true, false);
    }

    /**
     * What an export of the filter takes, in one snapshot: the counts, the species list (with {@code rows}; see
     * {@link #exportSet(Filter, int)}) and every matching photo in the Photos view's order (with {@code photos}).
     */
    public ExportSet exportSet(Filter filter, int placeDepth, boolean rows, boolean photos) {
        return database.get().snapshot(c -> {
            Result result = result(c, filter);
            int photographed = (int) result.hits().stream().filter(h -> h.photos() > 0).count();
            List<ListRow> list = rows ? FilterList.rows(c, result, placeDepth) : List.of();
            betweenReads.run(c); // tests change the library here: the photos still come from the list's state
            int[] raw = exact(c, result, "raw");
            int[] heic = exact(c, result, "heic");
            return new ExportSet(result.resolved().used().query(), result.hits().size(), photographed, result.photos(),
                    result.bytes(), list, photos ? FilterPhotos.all(c, result) : List.of(), raw[0], raw[1], raw[2],
                    heic[0], heic[1], heic[2]);
        });
    }

    /**
     * The matching photos of the result's species of this file type (RAW or HEIC, exported exactly), those of them whose
     * stored details hold a GPS position, and those whose stored details are missing or cannot be read:
     * {@code {photos, withGps, withUnknown}}.
     */
    private static int[] exact(Connection c, Result result, String fileType) throws SQLException {
        Set<String> species = new HashSet<>();
        result.hits().forEach(h -> species.add(h.taxon().id()));
        PhotoMatch match = PhotoMatch.of(result.resolved(), !result.resolved().used().missingStages());
        int[] raw = new int[3];
        try (PreparedStatement ps = c.prepareStatement("SELECT i.taxon_id, CASE WHEN m.json IS NOT NULL AND json_valid(m.json)"
                + " THEN json_type(m.json, '$.gps') END IS NOT NULL, m.json IS NULL OR NOT json_valid(m.json) FROM "
                + match.from()
                + " LEFT JOIN image_meta m ON m.image_id = i.id WHERE " + match.where() + " AND i.file_type = ?")) {
            int next = match.bind(ps, 1);
            ps.setString(next, fileType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (species.contains(rs.getString(1))) {
                        raw[0]++;
                        raw[1] += rs.getInt(2);
                        raw[2] += rs.getInt(3);
                    }
                }
            }
        }
        return raw;
    }

    /** A taxon's name; null for none. */
    static String name(Taxon taxon) {
        return taxon == null ? null : taxon.name();
    }

    /** The filter as the UI gets it back. */
    static Used used(Resolved r) {
        Filter f = r.used();
        return new Used(f.query(), r.taxa().stream().map(t -> new TaxonChip(t.id(), t.rank(), t.name(), t.common())).toList(),
                r.tags().stream().map(t -> new TagChip(t.key(), t.label())).toList(), f.tagMode(), r.echo(), f.status(),
                f.source(), f.stages(), r.notes(), f.types());
    }

    /** The species in the result (per status, or Missing life stages), each with its matching photos. */
    static Result result(Connection c, Filter filter) throws SQLException {
        return result(c, Resolved.resolve(c, filter));
    }

    /** As {@link #result(Connection, Filter)}, for a filter already resolved on this connection. */
    static Result result(Connection c, Resolved r) throws SQLException {
        boolean missingStages = r.used().missingStages();
        Map<String, long[]> tallies = tallies(c, r, missingStages);
        List<Hit> hits = new ArrayList<>();
        for (Taxon species : r.universe()) {
            long[] tally = tallies.get(species.id());
            if (missingStages) {
                List<String> missing = new ArrayList<>();
                for (int n = 0; tally != null && n < STAGES.size(); n++) {
                    if (tally[2 + n] == 0) {
                        missing.add(STAGES.get(n));
                    }
                }
                if (!missing.isEmpty()) {
                    hits.add(new Hit(species, (int) tally[0], tally[1], List.copyOf(missing)));
                }
                continue;
            }
            switch (r.used().status()) {
                case Filter.PHOTOGRAPHED -> {
                    if (tally != null) {
                        hits.add(new Hit(species, (int) tally[0], tally[1], List.of()));
                    }
                }
                case Filter.NOT -> {
                    if (tally == null) {
                        hits.add(new Hit(species, 0, 0, List.of()));
                    }
                }
                default -> hits.add(tally == null ? new Hit(species, 0, 0, List.of())
                        : new Hit(species, (int) tally[0], tally[1], List.of()));
            }
        }
        return new Result(r, List.copyOf(hits));
    }

    /**
     * Matching photos grouped by species: {@code [photos, bytes]}, and with {@code stages} (tags left out) whether any of
     * them carries egg, larva and pupa.
     */
    private static Map<String, long[]> tallies(Connection c, Resolved r, boolean stages) throws SQLException {
        PhotoMatch match = PhotoMatch.of(r, !stages);
        StringBuilder sql = new StringBuilder("SELECT i.taxon_id, COUNT(*), SUM(i.file_size)");
        if (stages) {
            for (int n = 0; n < STAGES.size(); n++) {
                sql.append(", MAX(EXISTS (SELECT 1 FROM image_tag s JOIN tag k ON k.id = s.tag_id WHERE s.image_id = i.id"
                        + " AND k.key = ?))");
            }
        }
        sql.append(" FROM ").append(match.from()).append(" WHERE ").append(match.where()).append(" GROUP BY i.taxon_id");
        Map<String, long[]> tallies = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            match.bind(ps, SqlLists.bind(ps, 1, stages ? STAGES : List.of()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long[] tally = new long[2 + STAGES.size()];
                    tally[0] = rs.getLong(2);
                    tally[1] = rs.getLong(3);
                    for (int n = 0; stages && n < STAGES.size(); n++) {
                        tally[2 + n] = rs.getLong(4 + n);
                    }
                    tallies.put(rs.getString(1), tally);
                }
            }
        }
        return tallies;
    }
}
