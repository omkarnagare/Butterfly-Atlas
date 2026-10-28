package com.butterflyatlas.app.taxonomy;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleLoaderTest {

    @TempDir
    Path dir;

    private static String one(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String str(Database db, String sql) {
        return db.read(c -> one(c, sql));
    }

    private static int count(Database db, String sql) {
        return db.read(c -> Integer.parseInt(one(c, sql)));
    }

    @Test
    void insertsTheGroupRootAndCustomAdditions() {
        Database db = TestBundles.fixtureDatabase(dir);

        assertThat(str(db, "SELECT rank || '|' || name || '|' || coalesce(parent_id, '-') || '|' || ancestor_path || '|' || source"
                + " FROM taxon WHERE id = 'G:butterflies'"))
                .isEqualTo("group|Butterflies|-|/G:butterflies/|system");
        assertThat(str(db, "SELECT rank || '|' || name || '|' || parent_id || '|' || ancestor_path"
                + " FROM taxon WHERE id = 'U:butterflies'"))
                .isEqualTo("additions|Custom additions|G:butterflies|/G:butterflies/U:butterflies/");
    }

    @Test
    void buildsAncestorPathsAndFolderNames() {
        Database db = TestBundles.fixtureDatabase(dir);

        assertThat(str(db, "SELECT ancestor_path || '|' || folder_name FROM taxon WHERE id = 'A5'"))
                .isEqualTo("/G:butterflies/A1/A2/A3/A4/A5/|Danaus_chrysippus_[A5]");
        assertThat(str(db, "SELECT ancestor_path || '|' || folder_name FROM taxon WHERE id = 'A11'"))
                .isEqualTo("/G:butterflies/A9/A10/A11/|Papilio");
        assertThat(str(db, "SELECT folder_name FROM taxon WHERE id = 'U:butterflies'")).isEqualTo("Custom_additions");
        assertThat(str(db, "SELECT parent_id FROM taxon WHERE id = 'A1'")).isEqualTo("G:butterflies");
        assertThat(str(db, "SELECT sort_key || '|' || source || '|' || inat_id FROM taxon WHERE id = 'A5'"))
                .isEqualTo("danaus chrysippus|official|1005");
    }

    @Test
    void storesBundleSynonymsAndSearchRows() {
        Database db = TestBundles.fixtureDatabase(dir);

        assertThat(str(db, "SELECT name || '|' || source FROM taxon_synonym WHERE taxon_id = 'A8'"))
                .isEqualTo("Danaus limniace|bundle");
        assertThat(count(db, "SELECT COUNT(*) FROM taxon_synonym")).isEqualTo(4);
        assertThat(count(db, "SELECT COUNT(*) FROM taxon_fts")).isEqualTo(29);
        assertThat(count(db, "SELECT COUNT(*) FROM taxon_fts WHERE taxon_id = 'U:butterflies'")).isZero();
    }

    @Test
    void recordsTheBundleVersionAndChecksum() {
        Database db = TestBundles.fixtureDatabase(dir);
        String sha = TaxonomyBundles.sha256(TestBundles.fixtureBytes());

        assertThat(str(db, "SELECT bundle_version || '|' || bundle_sha256 || '|' || applied_at FROM taxonomy_state"))
                .isEqualTo("2026-01-01|" + sha + "|2026-10-09T08:00:00Z");
    }

    @Test
    void refusesToLoadIntoALibraryThatAlreadyHasATaxonomy() {
        Database db = TestBundles.fixtureDatabase(dir);

        assertThatThrownBy(() -> BundleLoader.load(db, TestBundles.fixture().newest(), TestBundles.CLOCK))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(db, "SELECT COUNT(*) FROM taxon")).isEqualTo(16);
    }

    @Test
    void loadsTheRealIndiaBundle() {
        Database db = Database.open(dir.resolve(Database.FILE_NAME));
        BundleLoader.load(db, TaxonomyBundles.fromClasspath().newest(), TestBundles.CLOCK);

        Map<String, Integer> byRank = db.read(c -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT rank, COUNT(*) FROM taxon GROUP BY rank");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getInt(2));
                }
            }
            return counts;
        });
        assertThat(byRank).containsOnly(Map.entry("group", 1), Map.entry("additions", 1), Map.entry("family", 6),
                Map.entry("subfamily", 31), Map.entry("tribe", 77), Map.entry("genus", 351), Map.entry("species", 1139));
        assertThat(count(db, "SELECT COUNT(*) FROM taxon_synonym")).isEqualTo(349);
        assertThat(count(db, "SELECT COUNT(*) FROM taxon_fts")).isEqualTo(1604 + 1 + 1265 + 349);
        assertThat(count(db, "SELECT COUNT(*) FROM taxon c JOIN taxon p ON c.parent_id = p.id"
                + " WHERE c.ancestor_path <> p.ancestor_path || c.id || '/'")).isZero();
    }

    @Test
    void aMalformedBundleIsRefusedAndLeavesTheLibraryEmpty() throws Exception {
        Bundle good = TestBundles.fixture().newest().bundle();
        Bundle bundle = new Bundle(good.format(), good.version(), good.basedOn(), good.generatedAt(), good.ranks(),
                good.region(), good.group(), good.source(), List.of(
                new BundleTaxon("A1", null, "family", "Nymphalidae", null, 1001, List.of()),
                new BundleTaxon("A2", "A99", "genus", "Danaus", null, 1004, List.of())), List.of());
        TaxonomyBundles.Shipped broken = new TaxonomyBundles.Shipped("broken.json", "0".repeat(64), bundle);
        Path sub = Files.createDirectory(dir.resolve("broken"));
        Database db = Database.open(sub.resolve(Database.FILE_NAME));

        assertThatThrownBy(() -> BundleLoader.load(db, broken, TestBundles.CLOCK))
                .isInstanceOf(BundleException.class)
                .hasMessageContaining("unknown parent A99");
        assertThat(count(db, "SELECT COUNT(*) FROM taxon")).isZero();
        assertThat(count(db, "SELECT COUNT(*) FROM taxonomy_state")).isZero();
    }
}
