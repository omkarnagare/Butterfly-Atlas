package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.places.PlaceRef;
import com.butterflyatlas.app.places.PlaceSearch;
import com.butterflyatlas.app.places.UserPlaces;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.butterflyatlas.app.imports.ImportStoreTest.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The place chosen for import items (3c-2 contract §1 "Import items"). */
class ImportPlacesTest {

    private static final PlaceRef ALPHA_GPS = new PlaceRef("P4", List.of("Alpha", "Northstate", "Testland"),
            List.of("P4", "P2", "P1"), "gps", false, "district");
    private static final PlaceRef BETA_USER = new PlaceRef("P5", List.of("Beta", "Northstate", "Testland"),
            List.of("P5", "P2", "P1"), "user", false, "district");

    @TempDir
    Path dir;

    private Library library;
    private Database db;
    private ImportStore store;
    private ImportEdits edits;
    private UserPlaces places;
    private long run;

    @BeforeEach
    void setUp() throws Exception {
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        db = library.database();
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        edits = new ImportEdits(() -> library, TestBundles.CLOCK);
        places = new UserPlaces(() -> library.database(), TestBundles.CLOCK, new PlaceSearch(() -> library.database()),
                new ImportPlaceChoices());
        run = store.create(Files.createDirectories(dir.resolve("Photos"))).id();
    }

    private Map<Long, ImportStore.Item> byId() {
        return store.items(run, null).items().stream().collect(Collectors.toMap(ImportStore.Item::id, Function.identity()));
    }

    private static List<Long> ids(ImportStore.ItemsPage page) {
        return page.items().stream().map(ImportStore.Item::id).toList();
    }

    @Test
    void aChosenPlaceIsTheItemsPlaceAsTheUsersAndRemovingItLeavesNone() throws Exception {
        long gps = item(db, run, "gps.jpg", "read_state", "read", "gps_place_id", "P4");
        long none = item(db, run, "none.jpg", "read_state", "read");
        long pending = item(db, run, "pending.jpg");
        long filed = item(db, run, "filed.jpg", "read_state", "read", "outcome", "filed", "gps_place_id", "P4");
        long missing = item(db, run, "missing.jpg", "read_state", "missing");
        long other = item(db, store.create(Files.createDirectories(dir.resolve("Other"))).id(), "x.jpg", "read_state", "read");
        long before = store.items(run, null).version();

        ImportStore.ItemsPage page = edits.place(run, List.of(gps, none, pending, filed, missing, other), "P5", false);

        assertThat(ids(page)).containsExactly(gps, none, pending);
        assertThat(page.version()).isEqualTo(before + 1);
        assertThat(page.items()).extracting(ImportStore.Item::place).containsOnly(BETA_USER);
        Map<Long, ImportStore.Item> items = byId();
        assertThat(items.get(filed).place()).isEqualTo(ALPHA_GPS);
        assertThat(items.get(missing).place()).isNull();
        assertThat(places.recent()).extracting(PlaceSearch.Place::id).containsExactly("P5");

        ImportStore.ItemsPage removed = edits.place(run, List.of(gps), null, true);

        assertThat(ids(removed)).containsExactly(gps);
        assertThat(removed.items().get(0).place()).as("removed: no place, not the GPS one").isNull();
        assertThat(byId().get(none).place()).isEqualTo(BETA_USER);
        ImportStore.ItemsPage again = edits.place(run, List.of(gps), null, true);
        assertThat(again.items()).as("nothing changed").isEmpty();
        assertThat(again.version()).isEqualTo(before + 2);
        assertThat(edits.place(run, List.of(none), "P5", false).items()).isEmpty();
        assertThat(rowsOf("SELECT id || ' ' || coalesce(user_place_id, '-') || ' ' || user_place_set FROM import_item"
                + " WHERE id IN (" + gps + ", " + none + ", " + other + ") ORDER BY id"))
                .containsExactly(gps + " - 1", none + " P5 1", other + " - 0");
    }

    @Test
    void anItemWithoutAChoiceShowsItsGpsPlace() {
        long gps = item(db, run, "gps.jpg", "read_state", "read", "gps_place_id", "P4");

        assertThat(byId().get(gps).place()).isEqualTo(ALPHA_GPS);
    }

    @Test
    void anUnknownOrRetiredPlaceOrABadRequestChangesNothing() {
        long a = item(db, run, "a.jpg", "read_state", "read", "gps_place_id", "P4");
        exec("UPDATE place SET active = 0 WHERE id = 'P7'");

        for (String id : new String[] {"P404", "P7"}) {
            assertThatThrownBy(() -> edits.place(run, List.of(a), id, false)).isInstanceOfSatisfying(AtlasException.class, e -> {
                assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND);
                assertThat(e.code()).isEqualTo("not_found");
            });
        }
        assertThatThrownBy(() -> edits.place(run, List.of(a), "P5", true)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("invalid"));
        assertThatThrownBy(() -> edits.place(run, List.of(), "P5", false)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("invalid"));
        assertThatThrownBy(() -> edits.place(404, List.of(a), "P5", false)).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("import_not_found"));
        assertThat(byId().get(a).place()).isEqualTo(ALPHA_GPS);
        assertThat(places.recent()).isEmpty();
    }

    @Test
    void deletingAUserPlaceSendsItsItemsBackToTheirGpsPlace() {
        long a = item(db, run, "a.jpg", "read_state", "read", "gps_place_id", "P4");
        long b = item(db, run, "b.jpg", "read_state", "read");
        places.add("Tamhini", "P2");
        edits.place(run, List.of(a, b), "PU1", false);
        assertThat(byId().get(a).place()).isEqualTo(new PlaceRef("PU1", List.of("Tamhini", "Northstate", "Testland"),
                List.of("PU1", "P2", "P1"), "user", false, "district"));
        long version = store.items(run, null).version();

        places.delete("PU1");

        ImportStore.ItemsPage changed = store.items(run, version);
        assertThat(ids(changed)).as("the grid hears of it").containsExactly(a, b);
        assertThat(changed.items()).extracting(ImportStore.Item::place).containsExactly(ALPHA_GPS, null);
        assertThat(rowsOf("SELECT COUNT(*) FROM import_item WHERE user_place_set = 1 OR user_place_id IS NOT NULL"))
                .containsExactly("0");
    }

    @Test
    void renamingAUserPlaceStampsItsItemsSoTheGridHearsOfIt() {
        long a = item(db, run, "a.jpg", "read_state", "read");
        long b = item(db, run, "b.jpg", "read_state", "read");
        places.add("Tamhini", "P2");
        edits.place(run, List.of(a), "PU1", false);
        long version = store.items(run, null).version();

        places.rename("PU1", "Tamhini Ghat");

        ImportStore.ItemsPage changed = store.items(run, version);
        assertThat(ids(changed)).containsExactly(a);
        assertThat(changed.version()).isEqualTo(version + 1);
        assertThat(changed.items().get(0).place())
                .isEqualTo(new PlaceRef("PU1", List.of("Tamhini Ghat", "Northstate", "Testland"),
                        List.of("PU1", "P2", "P1"), "user", false, "district"));
        assertThat(byId().get(b).place()).isNull();
    }

    @Test
    void renamingOrDeletingAUserPlaceStampsOnlyOpenImports() throws Exception {
        long done = store.create(Files.createDirectories(dir.resolve("Done"))).id();
        long a = item(db, run, "a.jpg", "read_state", "read");
        long d = item(db, done, "d.jpg", "read_state", "read");
        places.add("Tamhini", "P2");
        edits.place(run, List.of(a), "PU1", false);
        edits.place(done, List.of(d), "PU1", false);
        exec("UPDATE import_run SET state = 'finished', updated_at = '2020-01-01T00:00:00Z' WHERE id = " + done);
        List<String> finished = rowsOf("SELECT next_seq || ' ' || updated_at FROM import_run WHERE id = " + done);
        long dSeq = Long.parseLong(rowsOf("SELECT seq FROM import_item WHERE id = " + d).get(0));
        long version = store.items(run, null).version();

        places.rename("PU1", "Tamhini Ghat");
        places.delete("PU1");

        assertThat(store.items(run, null).version()).as("the open import hears of both").isEqualTo(version + 2);
        assertThat(rowsOf("SELECT next_seq || ' ' || updated_at FROM import_run WHERE id = " + done))
                .as("a finished import is left alone").isEqualTo(finished);
        assertThat(rowsOf("SELECT seq || ' ' || coalesce(user_place_id, '-') || ' ' || user_place_set FROM import_item WHERE id = " + d))
                .as("its item forgets the place, unstamped").containsExactly(dSeq + " - 0");
    }

    private void exec(String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    private List<String> rowsOf(String sql) {
        return db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        });
    }
}
