package com.butterflyatlas.app.places;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.ChangeLog;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.places.PlaceRules.Row;
import com.butterflyatlas.app.places.PlaceSearch.Place;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The user's own places and the places they used last (3c-2 contract §1 "Places"; design C9): adding, renaming and
 * deleting user districts, listing them, and the recent places. User places are districts under a state, with ids
 * {@code PU1}, {@code PU2}, … never reused; places that come with Butterfly Atlas cannot be renamed or deleted here.
 */
public final class UserPlaces {

    public static final int MAX_NAME = 80;
    public static final int RECENT_LIMIT = 5;
    static final String ADD = "place.add";
    static final String RENAME = "place.rename";
    static final String DELETE = "place.delete";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** A user place with how many live photos (not those in Recently deleted) have it. */
    public record MyPlace(String id, String name, List<String> path, String level, boolean mine, int photos) {

        public MyPlace {
            path = List.copyOf(path);
        }
    }

    /** The open library's database. */
    private final Supplier<Database> database;
    private final Clock clock;
    private final PlaceSearch search;
    private final ImportChoices imports;

    public UserPlaces(Supplier<Database> database, Clock clock, PlaceSearch search, ImportChoices imports) {
        this.database = database;
        this.clock = clock;
        this.search = search;
        this.imports = imports;
    }

    /** The places last applied to photos or import items, newest first, at most {@value #RECENT_LIMIT}. */
    public List<Place> recent() {
        return database.get().read(c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT r.place_id FROM place_recent r JOIN place p"
                    + " ON p.id = r.place_id WHERE p.active = 1 ORDER BY r.used_at DESC, r.rowid DESC LIMIT ?")) {
                ps.setInt(1, RECENT_LIMIT);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            Map<String, Place> places = PlaceRules.places(c, ids);
            return ids.stream().map(places::get).toList();
        });
    }

    /** Makes {@code placeId} the newest recent place (inside the caller's transaction). */
    public static void used(Connection c, String placeId, Instant at) throws SQLException {
        // INSERT OR REPLACE gives the row a new rowid, which orders places used at the same instant.
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO place_recent (place_id, used_at) VALUES (?, ?)")) {
            ps.setString(1, placeId);
            ps.setString(2, at.toString());
            ps.executeUpdate();
        }
    }

    /** The user's places by name, each with its live photos. */
    public List<MyPlace> mine() {
        return database.get().read(c -> {
            Map<String, Integer> photos = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT p.id, (SELECT COUNT(*) FROM image i WHERE i.place_id = p.id"
                    + " AND i.deleted_at IS NULL) FROM place p WHERE p.source = 'user' ORDER BY p.sort_key, p.id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    photos.put(rs.getString(1), rs.getInt(2));
                }
            }
            Map<String, Place> places = PlaceRules.places(c, photos.keySet());
            return photos.entrySet().stream().map(e -> {
                Place p = places.get(e.getKey());
                return new MyPlace(p.id(), p.name(), p.path(), p.level(), p.mine(), e.getValue());
            }).toList();
        });
    }

    /** Adds a user district under the state {@code parentId}. */
    public Place add(String rawName, String parentId) {
        String name = cleanName(rawName);
        Place added = database.get().write(c -> {
            Row parent = parentId == null ? null : PlaceRules.row(c, parentId);
            if (parent == null || !parent.level().equals("state") || !parent.active()) {
                throw PlaceRules.invalid("Choose the state it is in.");
            }
            requireFreeName(c, parent, name, null);
            String id = "PU" + (lastUserNumber(c) + 1);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO place (id, parent_id, level, name, source,"
                    + " ancestor_path, sort_key, active) VALUES (?, ?, 'district', ?, 'user', ?, ?, 1)")) {
                ps.setString(1, id);
                ps.setString(2, parentId);
                ps.setString(3, name);
                ps.setString(4, parent.path() + id + "/");
                ps.setString(5, sortKey(name));
                ps.executeUpdate();
            }
            ChangeLog.record(c, clock.instant(), ADD, ChangeLog.payload("id", id, "name", name, "parentId", parentId));
            return PlaceRules.places(c, List.of(id)).get(id);
        });
        search.changed();
        return added;
    }

    /** Renames a user place (its id, parent and photos stay; import items that chose it are stamped as changed). */
    public Place rename(String id, String rawName) {
        Place renamed = database.get().write(c -> {
            Row place = userPlace(c, id, "renamed");
            String name = cleanName(rawName);
            requireFreeName(c, PlaceRules.row(c, place.parentId()), name, id);
            try (PreparedStatement ps = c.prepareStatement("UPDATE place SET name = ?, sort_key = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, sortKey(name));
                ps.setString(3, id);
                ps.executeUpdate();
            }
            ChangeLog.record(c, clock.instant(), RENAME, ChangeLog.payload("id", id, "from", place.name(), "to", name));
            imports.renamed(c, id, clock.instant());   // import items that chose it show the new name
            return PlaceRules.places(c, List.of(id)).get(id);
        });
        search.changed();
        return renamed;
    }

    /**
     * Deletes a user place. Photos that had it (live or in Recently deleted) become Unknown place as the user's
     * ({@code place_source 'user'}, never refilled from GPS); import items that chose it go back to their GPS place; it
     * leaves the recent places. Returns how many live photos had it.
     */
    public int delete(String id) {
        ImportChoices.Deleted[] told = {ImportChoices.Deleted.NONE};
        boolean committed = false;
        int cleared;
        try {
            cleared = deleteRow(id, told);
            committed = true;
        } finally {
            told[0].over(committed); // the imports' revisions go up only once the delete committed
        }
        search.changed();
        return cleared;
    }

    private int deleteRow(String id, ImportChoices.Deleted[] told) {
        return database.get().write(c -> {
            Row place = userPlace(c, id, "deleted");
            List<Long> photos = new ArrayList<>();
            int live = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT id, deleted_at IS NULL FROM image WHERE place_id = ? ORDER BY id")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        photos.add(rs.getLong(1));
                        live += rs.getInt(2);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET place_id = NULL, place_source = 'user' WHERE place_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            told[0] = imports.deleted(c, id, clock.instant()); // import items that chose it go back to their GPS place
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM place WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate(); // its place_recent row goes with it (ON DELETE CASCADE)
            }
            ChangeLog.record(c, clock.instant(), DELETE, ChangeLog.payload("id", id, "name", place.name(),
                    "parentId", place.parentId(), "photos", photos));
            return live;
        });
    }

    /** A user place, or NOT_FOUND / 409 {@code bundled_place} ("… can't be {@code done}."). */
    private static Row userPlace(Connection c, String id, String done) throws SQLException {
        Row place = id == null ? null : PlaceRules.row(c, id);
        if (place == null) {
            throw PlaceRules.notFound();
        }
        if (!PlaceRules.USER.equals(place.source())) {
            throw new AtlasException(AtlasException.Kind.CONFLICT, "bundled_place",
                    "Places that come with Butterfly Atlas can't be " + done + ".");
        }
        return place;
    }

    /**
     * 409 {@code place_exists} when another active place under {@code parent} has this name, ignoring case and accents
     * as search does ("Puné" is Pune).
     */
    private static void requireFreeName(Connection c, Row parent, String name, String except) throws SQLException {
        String wanted = PlaceSearch.fold(name);
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name FROM place WHERE parent_id = ? AND active = 1")) {
            ps.setString(1, parent.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!rs.getString(1).equals(except) && PlaceSearch.fold(rs.getString(2)).equals(wanted)) {
                        throw new AtlasException(AtlasException.Kind.CONFLICT, "place_exists",
                                rs.getString(2) + " is already in " + parent.name() + ".");
                    }
                }
            }
        }
    }

    /** The highest user place number ever given: those in the library and those added and deleted since (change_log). */
    private static long lastUserNumber(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT max(n) FROM ("
                + "SELECT CAST(substr(id, 3) AS INTEGER) AS n FROM place WHERE id LIKE 'PU%'"
                + " UNION ALL SELECT CAST(substr(json_extract(payload_json, '$.id'), 3) AS INTEGER) FROM change_log"
                + " WHERE kind = ?)")) {
            ps.setString(1, ADD);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The name as typed: NFC, trimmed, single spaces, 1–{@value #MAX_NAME} characters. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).strip()).replaceAll(" ");
        if (name.isEmpty()) {
            throw PlaceRules.invalid("Give the place a name.");
        }
        if (name.length() > MAX_NAME) {
            throw PlaceRules.invalid("Keep place names to " + MAX_NAME + " characters or fewer.");
        }
        return name;
    }

    /** Folded as search folds names, so "Éden" sorts with E. */
    private static String sortKey(String name) {
        return PlaceSearch.fold(name);
    }
}
