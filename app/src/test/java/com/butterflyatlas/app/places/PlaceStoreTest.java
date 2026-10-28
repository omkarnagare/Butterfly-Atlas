package com.butterflyatlas.app.places;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.DatabaseException;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.UpdatePlaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceStoreTest {

    @TempDir
    Path dir;

    private final PlaceStore store = new PlaceStore(TestBundles.CLOCK);

    private Database database() {
        return Database.open(dir.resolve(Database.FILE_NAME));
    }

    private static List<String> strings(Database db, String sql) {
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

    private PlaceStore.PlaceView view(Database db, String id) {
        return db.read(c -> store.view(c, id));
    }

    private static Map<String, PlaceStore.PlaceView> views(Database db, List<String> ids) {
        return db.read(c -> PlaceStore.views(c, ids));
    }

    private void sync(Database db, PlaceBundles bundles) {
        db.write(c -> {
            store.syncBundle(c, bundles);
            return null;
        });
    }

    @Test
    void theFirstSyncLoadsEveryPlaceWithItsPathAliasesAndTheBundleItCameFrom() {
        Database db = database();

        sync(db, TestPlaces.bundles());

        assertThat(strings(db, "SELECT id || ' ' || coalesce(parent_id, '-') || ' ' || level || ' ' || name || ' ' || source"
                + " || ' ' || ancestor_path || ' ' || sort_key || ' ' || active FROM place ORDER BY CAST(substr(id, 2) AS INTEGER)"))
                .containsExactly(
                        "P1 - country Testland bundled /P1/ testland 1",
                        "P2 P1 state Northstate bundled /P1/P2/ northstate 1",
                        "P3 P1 state Leafstate bundled /P1/P3/ leafstate 1",
                        "P4 P2 district Alpha bundled /P1/P2/P4/ alpha 1",
                        "P5 P2 district Beta bundled /P1/P2/P5/ beta 1",
                        "P6 P2 district Lakeland bundled /P1/P2/P6/ lakeland 1",
                        "P7 P2 district Islet bundled /P1/P2/P7/ islet 1",
                        "P8 - country Farland bundled /P8/ farland 1",
                        "P9 - country Westfar bundled /P9/ westfar 1");
        assertThat(strings(db, "SELECT place_id || ' ' || name FROM place_alias ORDER BY place_id"))
                .containsExactly("P1 Testia", "P4 Alfa");
        assertThat(strings(db, "SELECT bundle_version || ' ' || bundle_sha256 || ' ' || applied_at FROM place_state"))
                .containsExactly("2026-10-13 " + TestPlaces.bundles().sha256() + " 2026-10-09T08:00:00Z");
    }

    @Test
    void syncingTheSameBundleAgainChangesNothing() {
        Database db = database();
        sync(db, TestPlaces.bundles());

        sync(db, TestPlaces.bundles());

        assertThat(strings(db, "SELECT COUNT(*) FROM place")).containsExactly("9");
        assertThat(strings(db, "SELECT COUNT(*) FROM place_alias")).containsExactly("2");
        assertThat(strings(db, "SELECT COUNT(*) FROM place_state")).containsExactly("1");
    }

    @Test
    void anotherBundleAddsMissingPlacesAndRenamesBundledOnesButNeverTouchesUserPlaces() {
        Database db = database();
        sync(db, TestPlaces.bundles());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM place WHERE id = 'P5'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO place (id, parent_id, level, name, source, ancestor_path,"
                    + " sort_key, active) VALUES ('PU1', 'P2', 'district', 'Alpha', 'user', '/P1/P2/PU1/', 'alpha', 1)")) {
                ps.executeUpdate();
            }
            return null;
        });
        UpdatePlaces.Built next = UpdatePlaces.derive(UpdatePlaces.fixture(), "2026-11-01").rename("P4", "Alpha Prime")
                .aliases("P1").build();

        sync(db, UpdatePlaces.bundles(UpdatePlaces.fixture(), next));

        assertThat(strings(db, "SELECT id || ' ' || name || ' ' || sort_key || ' ' || source || ' ' || ancestor_path FROM place"
                + " WHERE id IN ('P4', 'P5', 'PU1') ORDER BY id")).containsExactly(
                "P4 Alpha Prime alpha prime bundled /P1/P2/P4/",
                "P5 Beta beta bundled /P1/P2/P5/",
                "PU1 Alpha alpha user /P1/P2/PU1/");
        assertThat(strings(db, "SELECT place_id || ' ' || name FROM place_alias ORDER BY place_id, name"))
                .containsExactly("P4 Alfa", "P4 Alpha");
        assertThat(strings(db, "SELECT bundle_version FROM place_state ORDER BY bundle_version"))
                .containsExactly("2026-10-13", "2026-11-01");
    }

    @Test
    void theSameVersionWithAnotherChecksumIsAppliedToo() {
        Database db = database();
        sync(db, TestPlaces.bundles());
        Map<String, byte[]> files = new LinkedHashMap<>(TestPlaces.files());
        files.put(TestPlaces.JSON, TestPlaces.json().replace("\"name\": \"Beta\"", "\"name\": \"Béta  Nagar\"")
                .getBytes(StandardCharsets.UTF_8));
        files.put(PlaceBundles.CHECKSUMS, TestPlaces.checksums(files));
        PlaceBundles rebuilt = PlaceBundles.load(files);

        sync(db, rebuilt);
        sync(db, rebuilt);

        assertThat(strings(db, "SELECT name || '|' || sort_key FROM place WHERE id = 'P5'")).as("sort_key folded like a user place's").containsExactly("Béta  Nagar|beta nagar");
        assertThat(strings(db, "SELECT place_id || ' ' || name FROM place_alias ORDER BY place_id")).containsExactly("P1 Testia", "P4 Alfa");
        assertThat(strings(db, "SELECT bundle_version || ' ' || bundle_sha256 FROM place_state ORDER BY rowid")).containsExactly(
                "2026-10-13 " + TestPlaces.bundles().sha256(), "2026-10-13 " + rebuilt.sha256());
    }

    @Test
    void aPlaceReadsAsItsPathUpToItsCountry() {
        Database db = database();
        sync(db, TestPlaces.bundles());

        assertThat(view(db, "P4")).isEqualTo(new PlaceStore.PlaceView("P4", List.of("Alpha", "Northstate", "Testland"),
                List.of("P4", "P2", "P1"), false, null, "district"));
        assertThat(view(db, "P3")).isEqualTo(new PlaceStore.PlaceView("P3", List.of("Leafstate", "Testland"), List.of("P3", "P1"), false, null, "state"));
        assertThat(view(db, "P8")).isEqualTo(new PlaceStore.PlaceView("P8", List.of("Farland"), List.of("P8"), false, null, "country"));
        assertThat(view(db, "P404")).isNull();
        assertThat(views(db, List.of("P5", "P404", "P9", "P5"))).containsOnlyKeys("P5", "P9")
                .containsEntry("P5", new PlaceStore.PlaceView("P5", List.of("Beta", "Northstate", "Testland"),
                        List.of("P5", "P2", "P1"), false, null, "district"));
        assertThat(views(db, List.of())).isEmpty();
    }

    @Test
    void openingALibraryLoadsTheBundledPlacesOnce() {
        Library library = Library.open(dir, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
        assertThat(strings(library.database(), "SELECT COUNT(*) FROM place")).containsExactly("9");

        Library again = Library.open(dir, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);

        assertThat(strings(again.database(), "SELECT COUNT(*) FROM place")).containsExactly("9");
        assertThat(strings(again.database(), "SELECT COUNT(*) FROM place_state")).containsExactly("1");
        assertThat(strings(TestBundles.fixtureLibrary(dir.resolve("other")).database(), "SELECT COUNT(*) FROM place"))
                .as("test libraries have the fixture places").containsExactly("9");
    }

    @Test
    void openingALibraryReadsTheBoundariesBeforeAnyPhotoIsWritten() {
        // So the first photo's place lookup never reads them inside a write transaction (holding the write lock).
        PlaceBundles places = PlaceBundles.load(TestPlaces.files());
        assertThat(places.index().decoded()).isFalse();

        Library library = Library.open(dir, TestBundles.fixture(), places, TestBundles.CLOCK);

        assertThat(library.placeIndex()).isSameAs(places.index());
        assertThat(places.index().decoded()).isTrue();
    }

    @Test
    void aLibraryThatFailsToOpenLeavesNoConnectionOpen() throws Exception {
        Library.open(dir, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK).database()
                .write(c -> c.createStatement().executeUpdate("DROP TABLE place_state"));

        assertThatThrownBy(
                () -> Library.open(dir, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK))
                .isInstanceOf(DatabaseException.class);

        // SQLite removes the write-ahead log when the last connection to the file closes.
        assertThat(Files.exists(dir.resolve(Database.FILE_NAME + "-wal"))).as("every connection closed").isFalse();
        Files.delete(dir.resolve(Database.FILE_NAME));
    }
}
