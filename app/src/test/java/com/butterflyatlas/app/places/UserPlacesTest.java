package com.butterflyatlas.app.places;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.imports.ImportPlaceChoices;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPlacesTest {

    @TempDir
    Path dir;

    private Library library;
    private final TestClock clock = new TestClock();
    private PlaceSearch search;
    private UserPlaces places;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        search = new PlaceSearch(() -> library.database());
        places = new UserPlaces(() -> library.database(), clock, search, new ImportPlaceChoices());
    }

    /** A bundled place written straight into the library, under {@code parent} (null for a country). */
    static void bundled(Database db, String id, String parent, String level, String name, String... aliases) {
        db.write(c -> {
            String path = "/";
            if (parent != null) {
                try (PreparedStatement ps = c.prepareStatement("SELECT ancestor_path FROM place WHERE id = ?")) {
                    ps.setString(1, parent);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        path = rs.getString(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO place (id, parent_id, level, name, source,"
                    + " ancestor_path, sort_key, active) VALUES (?, ?, ?, ?, 'bundled', ?, lower(?), 1)")) {
                ps.setString(1, id);
                ps.setString(2, parent);
                ps.setString(3, level);
                ps.setString(4, name);
                ps.setString(5, path + id + "/");
                ps.setString(6, name);
                ps.executeUpdate();
            }
            for (String alias : aliases) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO place_alias (place_id, name) VALUES (?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, alias);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    static void sql(Database db, String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    static List<String> strings(Database db, String sql) {
        return db.read(c -> {
            List<String> values = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            return values;
        });
    }

    /** India › Maharashtra (Pune, Mumbai, East Punta, Kalyan), Punjab; Pakistan › Punjab (Kapunda), Púnchal. */
    static void india(Database db) {
        bundled(db, "P20", null, "country", "India", "Bharat");
        bundled(db, "P21", "P20", "state", "Maharashtra");
        bundled(db, "P22", "P21", "district", "Pune", "Poona");
        bundled(db, "P23", "P21", "district", "Mumbai", "Bombay");
        bundled(db, "P24", "P20", "state", "Punjab");
        bundled(db, "P25", null, "country", "Pakistan");
        bundled(db, "P26", "P25", "state", "Punjab", "Panjab");
        bundled(db, "P27", "P21", "district", "East Punta");
        bundled(db, "P28", "P21", "district", "Kalyan", "Punya Nagar");
        bundled(db, "P30", "P26", "district", "Kapunda");
        bundled(db, "P31", "P25", "state", "Púnchal");
    }

    static List<String> ids(List<PlaceSearch.Place> found) {
        return found.stream().map(PlaceSearch.Place::id).toList();
    }

    @Test
    void addMakesAUserDistrictUnderAStateWithTheNextIdNeverReused() {
        PlaceSearch.Place added = places.add("  Tamhini   Ghat ", "P2");

        assertThat(added).isEqualTo(new PlaceSearch.Place("PU1", "Tamhini Ghat",
                List.of("Tamhini Ghat", "Northstate", "Testland"), "district", true));
        assertThat(strings(library.database(), "SELECT id || ' ' || parent_id || ' ' || level || ' ' || source || ' '"
                + " || ancestor_path || ' ' || sort_key || ' ' || active FROM place WHERE id = 'PU1'"))
                .containsExactly("PU1 P2 district user /P1/P2/PU1/ tamhini ghat 1");
        assertThat(ids(search.search("tamh", null))).as("search sees it at once").containsExactly("PU1");
        assertThat(search.search("tamh", null).get(0).mine()).isTrue();

        places.delete("PU1");
        assertThat(places.add("Tamhini", "P2").id()).isEqualTo("PU2");
        assertThat(places.add("Mulshi", "P3").id()).isEqualTo("PU3");
        assertThat(strings(library.database(), "SELECT kind || ' ' || payload_json FROM change_log WHERE kind LIKE 'place.%'"
                + " ORDER BY id")).containsExactly(
                "place.add {\"id\":\"PU1\",\"name\":\"Tamhini Ghat\",\"parentId\":\"P2\"}",
                "place.delete {\"id\":\"PU1\",\"name\":\"Tamhini Ghat\",\"parentId\":\"P2\",\"photos\":[]}",
                "place.add {\"id\":\"PU2\",\"name\":\"Tamhini\",\"parentId\":\"P2\"}",
                "place.add {\"id\":\"PU3\",\"name\":\"Mulshi\",\"parentId\":\"P3\"}");
    }

    @Test
    void addRefusesABlankOrLongNameAParentThatIsNoStateAndASiblingsName() {
        assertError(() -> places.add("   ", "P2"), AtlasException.Kind.INVALID, "invalid", "Give the place a name.");
        assertError(() -> places.add(null, "P2"), AtlasException.Kind.INVALID, "invalid", "Give the place a name.");
        assertError(() -> places.add("x".repeat(81), "P2"), AtlasException.Kind.INVALID, "invalid",
                "Keep place names to 80 characters or fewer.");
        assertThat(places.add("y".repeat(80), "P2").name()).hasSize(80);
        for (String parent : new String[] {null, "P404", "P1", "P4", "P8"}) {
            assertError(() -> places.add("Tamhini", parent), AtlasException.Kind.INVALID, "invalid", "Choose the state it is in.");
        }
        sql(library.database(), "UPDATE place SET active = 0 WHERE id = 'P3'");
        assertError(() -> places.add("Tamhini", "P3"), AtlasException.Kind.INVALID, "invalid", "Choose the state it is in.");
        assertError(() -> places.add("ALPHA", "P2"), AtlasException.Kind.CONFLICT, "place_exists",
                "Alpha is already in Northstate.");
        places.add("Tamhini", "P2");
        assertError(() -> places.add("tamhini", "P2"), AtlasException.Kind.CONFLICT, "place_exists",
                "Tamhini is already in Northstate.");
    }

    @Test
    void userPlaceNamesAreComparedAndSortedAsSearchComparesThem() {
        assertError(() -> places.add("Álpha", "P2"), AtlasException.Kind.CONFLICT, "place_exists",
                "Alpha is already in Northstate.");
        places.add("Pune", "P2");
        assertError(() -> places.add("Puné", "P2"), AtlasException.Kind.CONFLICT, "place_exists",
                "Pune is already in Northstate.");
        assertThat(places.rename("PU1", "PUNÉ").name()).as("its own name with other accents").isEqualTo("PUNÉ");
        places.add("Éden", "P2");
        places.add("Fort", "P2");
        places.add("Dapoli", "P2");

        assertThat(places.mine()).extracting(UserPlaces.MyPlace::name).containsExactly("Dapoli", "Éden", "Fort", "PUNÉ");
        assertThat(strings(library.database(), "SELECT sort_key FROM place WHERE source = 'user' ORDER BY id"))
                .containsExactly("pune", "eden", "fort", "dapoli");
    }

    @Test
    void renameChangesAUserPlacesNameOnly() {
        places.add("Tamhini", "P2");

        PlaceSearch.Place renamed = places.rename("PU1", " Tamhini Ghat ");

        assertThat(renamed).isEqualTo(new PlaceSearch.Place("PU1", "Tamhini Ghat",
                List.of("Tamhini Ghat", "Northstate", "Testland"), "district", true));
        assertThat(places.rename("PU1", "TAMHINI GHAT").name()).as("its own name in other capitals").isEqualTo("TAMHINI GHAT");
        assertThat(ids(search.search("tamhini g", null))).containsExactly("PU1");
        assertThat(strings(library.database(), "SELECT sort_key FROM place WHERE id = 'PU1'")).containsExactly("tamhini ghat");
        assertThat(strings(library.database(), "SELECT payload_json FROM change_log WHERE kind = 'place.rename' ORDER BY id"))
                .containsExactly("{\"id\":\"PU1\",\"from\":\"Tamhini\",\"to\":\"Tamhini Ghat\"}",
                        "{\"id\":\"PU1\",\"from\":\"Tamhini Ghat\",\"to\":\"TAMHINI GHAT\"}");
    }

    @Test
    void renameRefusesBundledAndUnknownPlacesAndASiblingsName() {
        places.add("Tamhini", "P2");

        assertError(() -> places.rename("P4", "Alpha Nagar"), AtlasException.Kind.CONFLICT, "bundled_place",
                "Places that come with Butterfly Atlas can't be renamed.");
        assertError(() -> places.rename("PU9", "X"), AtlasException.Kind.NOT_FOUND, "not_found", "There is no such place.");
        assertError(() -> places.rename("PU1", "beta"), AtlasException.Kind.CONFLICT, "place_exists",
                "Beta is already in Northstate.");
        assertError(() -> places.rename("PU1", ""), AtlasException.Kind.INVALID, "invalid", "Give the place a name.");
    }

    @Test
    void deleteRemovesAUserPlaceAndItsPhotosBecomeUnknownPlaceAsTheUsers() {
        places.add("Tamhini", "P2");
        long a = TestBundles.addImage(library.database(), "A5");
        long b = TestBundles.addImage(library.database(), "A5");
        long c = TestBundles.addImage(library.database(), "A5");
        long gone = TestBundles.addImage(library.database(), "A5");
        sql(library.database(), "UPDATE image SET place_id = 'PU1', place_source = 'user' WHERE id IN (" + a + ", " + b
                + ", " + gone + ")");
        sql(library.database(), "UPDATE image SET place_id = 'P4', place_source = 'gps' WHERE id = " + c);
        TestBundles.markDeleted(library.database(), gone);
        sql(library.database(), "INSERT INTO place_recent (place_id, used_at) VALUES ('PU1', 't'), ('P4', 't')");

        assertThat(places.delete("PU1")).as("live photos cleared").isEqualTo(2);

        assertThat(strings(library.database(), "SELECT id || ' ' || coalesce(place_id, '-') || ' ' || place_source FROM image"
                + " ORDER BY id")).containsExactly(a + " - user", b + " - user", c + " P4 gps", gone + " - user");
        assertThat(strings(library.database(), "SELECT COUNT(*) FROM place WHERE id = 'PU1'")).containsExactly("0");
        assertThat(strings(library.database(), "SELECT place_id FROM place_recent")).containsExactly("P4");
        assertThat(strings(library.database(), "SELECT payload_json FROM change_log WHERE kind = 'place.delete'"))
                .containsExactly("{\"id\":\"PU1\",\"name\":\"Tamhini\",\"parentId\":\"P2\",\"photos\":[" + a + "," + b + ","
                        + gone + "]}");
        assertThat(search.search("tamhini", null)).isEmpty();
    }

    @Test
    void deleteRefusesBundledAndUnknownPlaces() {
        assertError(() -> places.delete("P4"), AtlasException.Kind.CONFLICT, "bundled_place",
                "Places that come with Butterfly Atlas can't be deleted.");
        assertError(() -> places.delete("PU1"), AtlasException.Kind.NOT_FOUND, "not_found", "There is no such place.");
    }

    @Test
    void minePlacesAreTheUsersByNameWithTheirLivePhotos() {
        places.add("Tamhini", "P2");
        places.add("Mulshi", "P3");
        places.add("alibag", "P2");
        long a = TestBundles.addImage(library.database(), "A5");
        long b = TestBundles.addImage(library.database(), "A5");
        long gone = TestBundles.addImage(library.database(), "A5");
        sql(library.database(), "UPDATE image SET place_id = 'PU1', place_source = 'user' WHERE id IN (" + a + ", " + b
                + ", " + gone + ")");
        TestBundles.markDeleted(library.database(), gone);

        assertThat(places.mine()).containsExactly(
                new UserPlaces.MyPlace("PU3", "alibag", List.of("alibag", "Northstate", "Testland"), "district", true, 0),
                new UserPlaces.MyPlace("PU2", "Mulshi", List.of("Mulshi", "Leafstate", "Testland"), "district", true, 0),
                new UserPlaces.MyPlace("PU1", "Tamhini", List.of("Tamhini", "Northstate", "Testland"), "district", true, 2));
    }

    @Test
    void recentPlacesAreTheLastFiveUsedNewestFirstStillThere() {
        assertThat(places.recent()).isEmpty();
        places.add("Tamhini", "P2");
        for (String id : new String[] {"P4", "P5", "PU1", "P6", "P7", "P8", "P5"}) {
            library.database().write(c -> {
                UserPlaces.used(c, id, clock.instant());
                return null;
            });
        }
        sql(library.database(), "UPDATE place SET active = 0 WHERE id = 'P7'");

        assertThat(ids(places.recent())).containsExactly("P5", "P8", "P6", "PU1", "P4");
        places.delete("PU1");
        assertThat(ids(places.recent())).containsExactly("P5", "P8", "P6", "P4");
        assertThat(places.recent().get(0)).isEqualTo(
                new PlaceSearch.Place("P5", "Beta", List.of("Beta", "Northstate", "Testland"), "district", false));
    }

    @Test
    void aPlaceToApplyMustExistAndNotBeRetired() {
        sql(library.database(), "UPDATE place SET active = 0 WHERE id = 'P7'");

        String district = library.database().read(c -> PlaceRules.usable(c, "P4"));
        String country = library.database().read(c -> PlaceRules.usable(c, "P1"));
        assertThat(district).isEqualTo("P4");
        assertThat(country).as("a country with states too").isEqualTo("P1");
        for (String id : new String[] {"P7", "P404", ""}) {
            assertError(() -> library.database().read(c -> PlaceRules.usable(c, id)), AtlasException.Kind.NOT_FOUND,
                    "not_found", "There is no such place.");
        }
    }

    static void assertError(Runnable call, AtlasException.Kind kind, String code, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }
}
