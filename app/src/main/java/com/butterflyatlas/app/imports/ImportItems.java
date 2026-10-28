package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.SqlLists;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.places.PlaceRef;
import com.butterflyatlas.app.places.PlaceStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The items of an import as the grid reads them (contract §2 "ItemsPage"): all of them or those changed since a
 * version, each with its tags, place and species names, read as one state of the database; and one item's files.
 */
final class ImportItems {

    private static final String ITEM_COLUMNS = "i.id, i.rel_path, i.file_size, i.modified_at, i.captured_at, i.read_state,"
            + " i.problem, i.suggested_taxon_id, i.library_taxon_id, i.copy_of_item_id, i.taxon_id, i.skipped, i.outcome,"
            + " i.image_id, i.error, i.v, json_extract(i.meta_json, '$.width'), json_extract(i.meta_json, '$.height'),"
            + " i.gps_place_id, i.user_place_set, i.user_place_id, i.file_type, i.pair_item_id, i.pair_left,"
            + " json_extract(i.meta_json, '$.orientation'), i.preview_failed,"
            + " CASE WHEN i.file_type = 'heic' AND json_valid(i.meta_json) THEN json_extract(i.meta_json, '$.heif') END";

    private final Supplier<Library> library;
    /** Runs between the version read and the items read of {@link #items} (tests commit a change there). */
    private final Runnable betweenReads;

    ImportItems(Supplier<Library> library, Runnable betweenReads) {
        this.library = library;
        this.betweenReads = betweenReads;
    }

    /** All items ({@code since} null), or those changed after version {@code since}. */
    ImportStore.ItemsPage items(long runId, Long since) {
        // One state of the database: the version and the items it covers.
        return library.get().database().snapshot(c -> {
            long version = ImportStore.version(c, runId);
            betweenReads.run();
            return page(c, runId, version, since == null ? "" : " AND i.seq > ?", since == null ? List.of() : List.of(since));
        });
    }

    /** The items stamped with exactly {@code seq} (what one change touched), with the version as it stands. */
    static ImportStore.ItemsPage stamped(Connection c, long runId, Long seq) throws SQLException {
        long version = ImportStore.version(c, runId);
        if (seq == null) {
            return new ImportStore.ItemsPage(version, List.of(), Map.of());
        }
        return page(c, runId, version, " AND i.seq = ?", List.of(seq));
    }

    /** One item of this import, or 404 when the import has no such item. */
    ImportStore.ItemFile itemFile(long runId, long itemId) {
        return library.get().database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT rel_path, read_state, v, format, meta_json, image_id,"
                    + " file_size, modified_at, preview_failed FROM import_item"
                    + " WHERE run_id = ? AND id = ?")) {
                ps.setLong(1, runId);
                ps.setLong(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ImportStore.version(c, runId); // 404 import_not_found when the import itself is unknown
                        throw AtlasException.notFound("That photo is not part of this import.");
                    }
                    int orientation = 1;
                    if (rs.getString(5) != null) {
                        try {
                            orientation = PhotoFacts.fromJson(rs.getString(5)).orientation();
                        } catch (IllegalArgumentException e) {
                            orientation = 1;
                        }
                    }
                    long imageId = rs.getLong(6);
                    Long image = rs.wasNull() ? null : imageId;
                    return new ImportStore.ItemFile(itemId, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), orientation,
                            image, rs.getLong(7), rs.getString(8), PhotoFacts.rawPreview(rs.getString(5)), rs.getInt(9));
                }
            }
        });
    }

    private static ImportStore.ItemsPage page(Connection c, long runId, long version, String where, List<Long> values)
            throws SQLException {
        Map<Long, List<String>> tags = tagsOf(c, runId, where, values);
        // Each item is made once its place is known: the places of the whole page are read in one query below.
        record Pending(long id, Function<PlaceRef, ImportStore.Item> make) {
        }
        List<Pending> items = new ArrayList<>();
        Map<Long, String[]> placeOf = new HashMap<>();   // item → {place id, source}
        Set<String> taxonIds = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ITEM_COLUMNS + " FROM import_item i WHERE i.run_id = ?"
                + where + " ORDER BY i.id")) {
            bind(ps, runId, values);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    String relPath = rs.getString(2);
                    long size = rs.getLong(3);
                    String modifiedAt = rs.getString(4);
                    String capturedAt = rs.getString(5);
                    String state = rs.getString(6);
                    String problem = rs.getString(7);
                    String suggested = rs.getString(8);
                    String inLibrary = rs.getString(9);
                    Long copyOf = longOrNull(rs, 10);
                    String taxonId = rs.getString(11);
                    boolean skipped = rs.getInt(12) == 1;
                    String outcome = rs.getString(13);
                    Long imageId = longOrNull(rs, 14);
                    String error = rs.getString(15);
                    String v = rs.getString(16);
                    Integer width = intOrNull(rs, 17);
                    Integer height = intOrNull(rs, 18);
                    List<String> itemTags = tags.getOrDefault(id, List.of());
                    String fileType = rs.getString(22);
                    Long pairId = longOrNull(rs, 23);
                    String shownOutcome = rs.getInt(24) == 1 ? ImportStore.PAIR_LEFT : outcome;
                    Integer orientation = intOrNull(rs, 25);
                    Photo.RawPreview rawPreview = PhotoFormat.RAW.fileType().equals(fileType) && width != null
                            && height != null ? new Photo.RawPreview(width, height, orientation == null ? 1 : orientation)
                            : null;
                    // only a preview that could not be made; one that took too long is made again when asked (fixes J9)
                    boolean previewFailed = rs.getInt(26) == ImportReader.PREVIEW_FAILED;
                    Photo.Heic heic = Photo.Heic.of(PhotoFacts.heifObject(rs.getString(27)));
                    items.add(new Pending(id, place -> new ImportStore.Item(id, relPath, ImportStore.folderOf(relPath), ImportStore.nameOf(relPath), size,
                            modifiedAt, capturedAt, width, height, state, problem, suggested, inLibrary, copyOf, taxonId,
                            itemTags, skipped, shownOutcome, imageId, error, v, place, fileType, rawPreview, pairId, heic,
                            previewFailed)));
                    // The user's choice when made (none when removed), else the place its GPS lies in.
                    boolean chosen = rs.getInt(20) == 1;
                    String placeId = chosen ? rs.getString(21) : rs.getString(19);
                    if (placeId != null) {
                        placeOf.put(id, new String[] {placeId, chosen ? "user" : "gps"});
                    }
                    for (String taxon : new String[] {suggested, inLibrary, taxonId}) {
                        if (taxon != null) {
                            taxonIds.add(taxon);
                        }
                    }
                }
            }
        }
        Map<String, PlaceStore.PlaceView> places = PlaceStore.views(c, placeOf.values().stream().map(p -> p[0]).toList());
        List<ImportStore.Item> made = new ArrayList<>(items.size());
        for (Pending item : items) {
            String[] place = placeOf.get(item.id());
            PlaceStore.PlaceView view = place == null ? null : places.get(place[0]);
            made.add(item.make().apply(view == null ? null : PlaceRef.of(view, place[1])));
        }
        return new ImportStore.ItemsPage(version, made, taxa(c, List.copyOf(taxonIds)));
    }

    private static void bind(PreparedStatement ps, long runId, List<Long> values) throws SQLException {
        ps.setLong(1, runId);
        for (int n = 0; n < values.size(); n++) {
            ps.setLong(n + 2, values.get(n));
        }
    }

    /**
     * The tag keys of the page's items, in one query over the same rows (built-in tags in their order, then the user's
     * by label).
     */
    private static Map<Long, List<String>> tagsOf(Connection c, long runId, String where, List<Long> values)
            throws SQLException {
        Map<Long, List<String>> tags = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT it.item_id, t.key FROM import_item i"
                + " JOIN import_item_tag it ON it.item_id = i.id JOIN tag t ON t.id = it.tag_id WHERE i.run_id = ?" + where
                + " ORDER BY t.source = 'user', t.sort_order, t.label COLLATE NOCASE")) {
            bind(ps, runId, values);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tags.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                }
            }
        }
        return tags;
    }

    private static Map<String, ImportStore.TaxonName> taxa(Connection c, List<String> ids) throws SQLException {
        Map<String, ImportStore.TaxonName> taxa = new LinkedHashMap<>();
        for (List<String> chunk : SqlLists.chunks(ids)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, common_name FROM taxon WHERE id IN ("
                    + SqlLists.marks(chunk.size()) + ")")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        taxa.put(rs.getString(1), new ImportStore.TaxonName(rs.getString(2), rs.getString(3)));
                    }
                }
            }
        }
        return taxa;
    }

    private static Integer intOrNull(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
