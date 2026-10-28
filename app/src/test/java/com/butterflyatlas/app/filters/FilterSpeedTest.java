package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A synthetic library of 50,000 photos (rows only, no files) over 1,200 species and 798 places: every filter answer
 * must come within the CI budget of 1 s; the time (the best of two runs) is logged against the 200 ms target (design
 * §3.2). A second library holds one species with 20,000 photos, paged from the middle.
 */
class FilterSpeedTest {

    /** No filter: the whole group, any photo, any place. */
    private static final Filter NONE = Filter.parse(null);

    private static final int PHOTOS = 50_000;
    private static final long BUDGET_MS = 1_000;
    private static final String[] TAGS = {"egg", "larva", "pupa", "upper_wing", "under_wing", "male", "female", "dsf", "wsf"};

    @TempDir
    static Path dir;

    private static Library library;
    private static FilterQuery query;

    @BeforeAll
    static void build() {
        library = TestBundles.fixtureLibrary(dir);
        Database db = library.database();
        Random random = new Random(42);
        List<String> species = new ArrayList<>();
        List<String> places = new ArrayList<>();
        db.write(c -> {
            try (PreparedStatement taxon = c.prepareStatement("INSERT INTO taxon (id, parent_id, rank, name, common_name,"
                    + " group_key, source, ancestor_path, folder_name, active, sort_key)"
                    + " VALUES (?, ?, ?, ?, ?, 'butterflies', 'official', ?, ?, 1, ?)")) {
                int n = 1000;
                // 6 families × 4 subfamilies × 5 genera (two in each of two tribes, one with no tribe) × 10 species = 1,200
                for (int fa = 0; fa < 6; fa++) {
                    String family = "S" + n++;
                    String familyPath = add(taxon, family, "G:butterflies", "family", "Familia" + fa, "/G:butterflies/");
                    for (int sf = 0; sf < 4; sf++) {
                        String sub = "S" + n++;
                        String subPath = add(taxon, sub, family, "subfamily", "Subfam" + fa + "x" + sf, familyPath);
                        for (int tr = 0; tr < 3; tr++) {
                            String parent = sub;
                            String parentPath = subPath;
                            if (tr < 2) {
                                parent = "S" + n++;
                                parentPath = add(taxon, parent, sub, "tribe", "Tribus" + fa + sf + tr, subPath);
                            }
                            for (int ge = 0; ge < (tr < 2 ? 2 : 1); ge++) {
                                String genus = "S" + n++;
                                String genusPath = add(taxon, genus, parent, "genus", "Genus" + fa + sf + tr + ge, parentPath);
                                for (int sp = 0; sp < 10; sp++) {
                                    String id = "S" + n++;
                                    add(taxon, id, genus, "species", "Genus" + fa + sf + tr + ge + " species" + sp, genusPath);
                                    species.add(id);
                                }
                            }
                        }
                    }
                }
            }
            try (PreparedStatement place = c.prepareStatement("INSERT INTO place (id, parent_id, level, name, source,"
                    + " ancestor_path, sort_key, active) VALUES (?, ?, ?, ?, 'bundled', ?, lower(?), 1)")) {
                // 38 states of Testland with 20 districts each
                for (int st = 0; st < 38; st++) {
                    String state = "P" + (100 + st);
                    insertPlace(place, state, "P1", "state", "State " + st, "/P1/" + state + "/");
                    places.add(state);
                    for (int di = 0; di < 20; di++) {
                        String district = "P" + (1000 + st * 20 + di);
                        insertPlace(place, district, state, "district", "District " + st + "-" + di,
                                "/P1/" + state + "/" + district + "/");
                        places.add(district);
                    }
                }
            }
            try (PreparedStatement image = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, number,"
                    + " file_size, modified_at, sha256, added_at, source, place_id, place_source)"
                    + " VALUES (?, ?, ?, ?, ?, '2026-10-09T08:00:00Z', ?, '2026-10-09T08:00:00Z', 'app', ?, ?)");
                 PreparedStatement tag = c.prepareStatement("INSERT OR IGNORE INTO image_tag (image_id, tag_id)"
                         + " SELECT ?, id FROM tag WHERE key = ?")) {
                for (int i = 1; i <= PHOTOS; i++) {
                    // most photos fall on the first 900 species, as a real library leaves many species unphotographed
                    String taxonId = species.get(random.nextInt(10) < 9 ? random.nextInt(900) : random.nextInt(species.size()));
                    image.setString(1, taxonId);
                    image.setString(2, "images/x/photo_" + i + ".jpg");
                    image.setString(3, "photo_" + i + ".jpg");
                    image.setInt(4, i);
                    image.setLong(5, 2_000_000 + random.nextInt(8_000_000));
                    image.setString(6, String.format("%064x", i));
                    String placeId = random.nextInt(10) == 0 ? null : places.get(random.nextInt(places.size()));
                    image.setString(7, placeId);
                    image.setString(8, placeId == null ? null : "gps");
                    image.addBatch();
                    if (i % 1_000 == 0) {
                        image.executeBatch();
                    }
                }
                for (int i = 1; i <= PHOTOS; i++) {
                    for (int t = random.nextInt(3); t > 0; t--) {
                        tag.setLong(1, i);
                        tag.setString(2, TAGS[random.nextInt(TAGS.length)]);
                        tag.addBatch();
                    }
                    if (i % 1_000 == 0) {
                        tag.executeBatch();
                    }
                }
            }
            return null;
        });
        query = new FilterQuery(library::database);
        query.count(NONE); // warm up: classes loaded, the database file in the page cache
    }

    private static String add(PreparedStatement ps, String id, String parent, String rank, String name, String parentPath)
            throws java.sql.SQLException {
        String path = parentPath + id + "/";
        ps.setString(1, id);
        ps.setString(2, parent);
        ps.setString(3, rank);
        ps.setString(4, name);
        ps.setString(5, null);
        ps.setString(6, path);
        ps.setString(7, name.replace(' ', '_') + "_[" + id + "]");
        ps.setString(8, name.toLowerCase(java.util.Locale.ROOT));
        ps.executeUpdate();
        return path;
    }

    private static void insertPlace(PreparedStatement ps, String id, String parent, String level, String name, String path)
            throws java.sql.SQLException {
        ps.setString(1, id);
        ps.setString(2, parent);
        ps.setString(3, level);
        ps.setString(4, name);
        ps.setString(5, path);
        ps.setString(6, name);
        ps.executeUpdate();
    }

    /** Runs {@code call} twice, logs the better time and checks it is within the budget; returns its answer. */
    private static <T> T timed(String what, Supplier<T> call) {
        T answer = null;
        long ms = Long.MAX_VALUE;
        for (int run = 0; run < 2; run++) {
            long start = System.nanoTime();
            answer = call.get();
            ms = Math.min(ms, (System.nanoTime() - start) / 1_000_000);
        }
        System.out.printf("filter speed: %-71s %4d ms (target 200 ms)%n", what, ms);
        assertThat(ms).as(what + " took " + ms + " ms").isLessThan(BUDGET_MS);
        return answer;
    }

    @Test
    void theLibraryHas50000LivePhotos() {
        assertThat(timed("count, no filter", () -> query.count(NONE)).photos()).isEqualTo(PHOTOS);
    }

    @Test
    void everyAnswerComesWithinTheBudget() {
        String[] filters = {
                "",
                "taxa=S1000&tags=larva,pupa&places=P101,unknown&status=photographed",
                "taxa=S1000,S1100,S1300&tags=male,female&tagMode=all",
                "places=P1005,P1203,P137",
                "places=P1",
                "places=P110&tags=larva",
                "status=not&places=P120",
                "stages=missing",
                "stages=missing&places=P110",
                "source=official&tags=egg",
        };
        for (String filter : filters) {
            Filter f = Filter.parse(filter);
            FilterQuery.FilterCount count = timed("count   " + filter, () -> query.count(f));
            List<FilterQuery.SpeciesRow> species = timed("species " + filter, () -> query.species(f));
            FilterQuery.PhotoPage page = timed("photos  " + filter, () -> query.photos(f, null, FilterQuery.DEFAULT_LIMIT));
            List<FilterQuery.PlaceRow> places = timed("places  " + filter, () -> query.places(f));
            assertThat(species).hasSize(count.species());
            assertThat(page.photos()).hasSize(Math.min(count.photos(), FilterQuery.DEFAULT_LIMIT));
            if (page.next() != null) {
                timed("photos page 2 " + filter, () -> query.photos(f, page.next(), FilterQuery.DEFAULT_LIMIT));
            }
            assertThat(places.isEmpty()).isEqualTo(count.photos() == 0);
        }
    }

    @Test
    void homeAndAFamilysCardsComeWithinTheBudget() {
        TaxonService taxa = new TaxonService(library::database);
        assertThat(timed("home summary", taxa::summary).photoCount()).isEqualTo(PHOTOS);
        assertThat(timed("a family's cards", () -> taxa.children("S1000"))).hasSize(4);
    }

    @Test
    void pagingThroughEverythingTakes100PagesAndTheLastComesWithinTheBudget() {
        Filter all = NONE;
        FilterQuery.PhotoPage page = query.photos(all, null, FilterQuery.MAX_LIMIT);
        String last = null;
        int pages = 1;
        while (page.next() != null) {
            last = page.next();
            page = query.photos(all, last, FilterQuery.MAX_LIMIT);
            pages++;
        }
        assertThat(pages).isEqualTo(PHOTOS / FilterQuery.MAX_LIMIT);
        String cursor = last;
        timed("photos, the last page of 500", () -> query.photos(all, cursor, FilterQuery.MAX_LIMIT));
    }

    @Test
    void oneSpeciesOf20000PhotosPagesFromTheMiddleWithinTheBudget(@TempDir Path own) {
        Library big = TestBundles.fixtureLibrary(own);
        int photos = 20_000;
        big.database().write(c -> {
            try (PreparedStatement image = c.prepareStatement("INSERT INTO image (taxon_id, rel_path, file_name, number,"
                    + " file_size, modified_at, sha256, added_at, source) VALUES (?, ?, ?, ?, 1, '2026-10-09T08:00:00Z', ?,"
                    + " '2026-10-09T08:00:00Z', 'app')")) {
                for (int i = 1; i <= photos + 10; i++) {
                    // A5 first in taxonomic order with 20,000 photos (numbered backwards), then 10 of A6
                    image.setString(1, i <= photos ? "A5" : "A6");
                    image.setString(2, "images/x/photo_" + i + ".jpg");
                    image.setString(3, "photo_" + i + ".jpg");
                    image.setInt(4, photos + 1 - i);
                    image.setString(5, String.format("%064x", i));
                    image.addBatch();
                }
                image.executeBatch();
            }
            return null;
        });
        FilterQuery bigQuery = new FilterQuery(big::database);
        bigQuery.count(NONE); // warm up
        // the photo numbered 10,000 is id 10,001
        String middle = new FilterQuery.Cursor("A5", 10_000, 10_001).encode();

        FilterQuery.PhotoPage page = timed("photos, one species of 20,000, a page of 500 from the middle",
                () -> bigQuery.photos(NONE, middle, FilterQuery.MAX_LIMIT));
        assertThat(page.photos()).hasSize(FilterQuery.MAX_LIMIT);
        assertThat(page.photos().get(0).id()).isEqualTo(10_000);
        assertThat(page.photos().get(FilterQuery.MAX_LIMIT - 1).id()).isEqualTo(9_501);
        FilterQuery.PhotoPage first = timed("photos, one species of 20,000, the first page of 500",
                () -> bigQuery.photos(NONE, null, FilterQuery.MAX_LIMIT));
        assertThat(first.photos().get(0).id()).isEqualTo(photos);
        String nearEnd = new FilterQuery.Cursor("A5", photos - 5, 6).encode();
        FilterQuery.PhotoPage across = timed("photos, one species of 20,000, a page across into the next",
                () -> bigQuery.photos(NONE, nearEnd, FilterQuery.MAX_LIMIT));
        assertThat(across.photos()).hasSize(15);
        assertThat(across.next()).isNull();
    }
}
