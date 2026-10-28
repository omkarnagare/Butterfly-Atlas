package com.butterflyatlas.app.places;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;
import com.butterflyatlas.app.db.SqlLists;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The reads and checks every place change shares, inside the caller's transaction: a place's row, the places of some
 * ids, whether a place may be applied, the photo ids of a request, and the errors (3c-2 contract §1).
 */
public final class PlaceRules {

    /** The most photos one place change may cover. */
    public static final int MAX_PHOTOS = 10_000;
    public static final String BUNDLED = "bundled";
    public static final String USER = "user";

    private PlaceRules() {
    }

    /** A {@code place} row. */
    record Row(String id, String parentId, String level, String name, String source, String path, boolean active) {
    }

    static Row row(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, parent_id, level, name, source, ancestor_path, active"
                + " FROM place WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7) == 1) : null;
            }
        }
    }

    /** The ids once each; at least one and at most {@value #MAX_PHOTOS} (400 {@code invalid}). */
    public static List<Long> photoIds(List<Long> raw) {
        List<Long> ids = raw == null ? List.of() : raw.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw invalid(AtlasException.NO_PHOTOS_CHOSEN);
        }
        if (ids.size() > MAX_PHOTOS) {
            throw invalid("Choose at most 10,000 photos at a time.");
        }
        return ids;
    }

    /** The id of a place that may be applied to photos (it exists and is not retired), or NOT_FOUND. */
    public static String usable(Connection c, String placeId) throws SQLException {
        Row row = placeId == null ? null : row(c, placeId);
        if (row == null || !row.active()) {
            throw notFound();
        }
        return placeId;
    }

    /** The places with these ids (those the library has), with their paths; an inactive one's name labelled. */
    public static Map<String, PlaceSearch.Place> places(Connection c, Collection<String> ids) throws SQLException {
        Map<String, PlaceSearch.Place> places = new HashMap<>();
        if (ids.isEmpty()) {
            return places;
        }
        Map<String, PlaceStore.PlaceView> views = PlaceStore.views(c, ids);
        List<String> list = new ArrayList<>(new HashSet<>(ids));
        for (List<String> chunk : SqlLists.chunks(list)) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, level, source, active FROM place WHERE id IN ("
                    + SqlLists.marks(chunk.size()) + ")")) {
                SqlLists.bind(ps, 1, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        // Its name as its path shows it: labelled when it is inactive.
                        places.put(id, new PlaceSearch.Place(id, views.get(id).path().getFirst(), views.get(id).path(),
                                rs.getString(2), USER.equals(rs.getString(3)), rs.getInt(4) == 0));
                    }
                }
            }
        }
        return places;
    }

    /** 400 with the code {@code invalid} (contract §1). */
    public static AtlasException invalid(String message) {
        return new AtlasException(AtlasException.Kind.INVALID, ErrorCodes.INVALID, message);
    }

    public static AtlasException notFound() {
        return new AtlasException(AtlasException.Kind.NOT_FOUND, ErrorCodes.NOT_FOUND, "There is no such place.");
    }
}
