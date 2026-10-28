package com.butterflyatlas.app.custom;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.photos.TaxonRef;
import com.butterflyatlas.app.search.SearchService;
import com.butterflyatlas.app.taxa.TaxonService;
import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.taxa.TaxonViews.TaxonDetail;
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

class CustomSpeciesTest {

    @TempDir
    Path dir;

    private Library library;
    private final TestClock clock = new TestClock();
    private TaxonService taxa;
    private CustomSpecies custom;

    @BeforeEach
    void setUp() {
        library = TestBundles.fixtureLibrary(dir);
        taxa = new TaxonService(() -> library.database());
        custom = new CustomSpecies(() -> library, taxa, clock);
    }

    static List<String> rows(Database db, String sql) {
        return db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        row.append(i > 1 ? "|" : "").append(rs.getString(i));
                    }
                    rows.add(row.toString());
                }
            }
            return rows;
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

    private static AtlasException invalid(Runnable r) {
        try {
            r.run();
        } catch (AtlasException e) {
            return e;
        }
        throw new AssertionError("not refused");
    }

    @Test
    void addsARowUnderCustomAdditionsWithItsFamilyAndSearchRows() {
        TaxonDetail added = custom.add("A1", "  neptis   SANKARA ", " Broad-banded   Sailer ");

        assertThat(added.id()).isEqualTo("U1");
        assertThat(added.name()).isEqualTo("Neptis sankara");
        assertThat(added.common()).isEqualTo("Broad-banded Sailer");
        assertThat(added.family()).isEqualTo("Nymphalidae");
        assertThat(added.familyId()).isEqualTo("A1");
        assertThat(added.source()).isEqualTo("manual");
        assertThat(added.folder()).isEqualTo("images/Custom_additions/Neptis_sankara_[U1]/");
        assertThat(rows(library.database(), "SELECT parent_id, rank, source, group_key, family_id, inat_id, ancestor_path,"
                + " folder_name, active, sort_key, next_photo_number, cover_image_id FROM taxon WHERE id = 'U1'"))
                .containsExactly("U:butterflies|species|manual|butterflies|A1|null|/G:butterflies/U:butterflies/U1/"
                        + "|Neptis_sankara_[U1]|1|neptis sankara|1|null");
        assertThat(rows(library.database(), "SELECT kind, text FROM taxon_fts WHERE taxon_id = 'U1' ORDER BY kind"))
                .containsExactly("common|Broad-banded Sailer", "name|Neptis sankara");
        assertThat(rows(library.database(), "SELECT at, kind, payload_json FROM change_log WHERE kind LIKE 'taxon.%'"))
                .containsExactly(clock.instant() + "|taxon.add|{\"id\":\"U1\",\"name\":\"Neptis sankara\","
                        + "\"common\":\"Broad-banded Sailer\",\"familyId\":\"A1\"}");
    }

    @Test
    void withoutACommonNameThereIsOneSearchRow() {
        TaxonDetail added = custom.add("A9", "Graphium eurypylus", "   ");

        assertThat(added.common()).isNull();
        assertThat(rows(library.database(), "SELECT kind FROM taxon_fts WHERE taxon_id = 'U1'")).containsExactly("name");
    }

    @Test
    void searchFindsItWithItsFamilyAndSource() {
        custom.add("A1", "Neptis sankara", "Broad-banded Sailer");
        SearchService search = new SearchService(() -> library.database());

        assertThat(search.search("neptis", 20)).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo("U1");
            assertThat(hit.family()).isEqualTo("Nymphalidae");
            assertThat(hit.source()).isEqualTo("manual");
            assertThat(hit.matchKind()).isEqualTo("name");
        });
        assertThat(search.search("broad-banded", 20)).extracting(SearchService.Hit::id).containsExactly("U1");
    }

    @Test
    void itCountsInTheGroupButNotInItsFamily() {
        custom.add("A1", "Neptis sankara", null);

        assertThat(taxa.summary().group().species()).isEqualTo(new Count(0, 7));
        assertThat(taxa.taxon("U:butterflies").species()).isEqualTo(new Count(0, 1));
        assertThat(taxa.taxon("A1").species()).isEqualTo(new Count(0, 3));
    }

    @Test
    void theScientificNameMustBeABinomial() {
        for (String bad : List.of("", "   ", "Neptis", "Neptis sankara major", "N sankara", "Neptis s4nkara", "Neptis sankara-",
                "Neptis -sankara", "Neptis sank ara", "Néptis sankara", "Neptis sankara." , "Ne" + "p".repeat(95) + " sankara")) {
            AtlasException e = invalid(() -> custom.add("A1", bad, null));
            assertThat(e.code()).as(bad).isEqualTo("invalid");
            assertThat(e.getMessage()).isEqualTo("Enter the scientific name as genus and species, e.g. Neptis sankara.");
        }
        assertThat(custom.add("A1", "neptis sankara-major", null).name()).isEqualTo("Neptis sankara-major");
        assertThat(custom.add("A1", "NEPTIS\tSOMA", null).name()).isEqualTo("Neptis soma");
        assertThat(custom.add("A1", "Ab " + "c".repeat(97), null).name()).hasSize(100);
    }

    @Test
    void theCommonNameIsAtMostAHundredCharacters() {
        AtlasException e = invalid(() -> custom.add("A1", "Neptis sankara", "x".repeat(101)));
        assertThat(e.code()).isEqualTo("invalid");
        assertThat(e.getMessage()).isEqualTo("The common name can be at most 100 characters.");
        assertThat(custom.add("A1", "Neptis sankara", "x".repeat(100)).common()).hasSize(100);
    }

    @Test
    void theFamilyMustBeAnActiveOfficialFamily() {
        TestBundles.retire(library.database(), "A9");
        for (String bad : new String[] {null, "", "A2", "A5", "A9", "U:butterflies", "G:butterflies", "A99"}) {
            AtlasException e = invalid(() -> custom.add(bad, "Neptis sankara", null));
            assertThat(e.code()).as(bad).isEqualTo("invalid");
            assertThat(e.getMessage()).isEqualTo("Choose the species' family.");
        }
        assertThat(rows(library.database(), "SELECT COUNT(*) FROM taxon WHERE source = 'manual'")).containsExactly("0");
    }

    @Test
    void anOfficialSpeciesNameIsBlockedWithTheSpeciesToOpen() {
        assertThatThrownBy(() -> custom.add("A1", "danaus CHRYSIPPUS", null)).isInstanceOfSatisfying(NameBlocked.class, e -> {
            assertThat(e.code()).isEqualTo("in_list");
            assertThat(e.getMessage()).isEqualTo("Danaus chrysippus is already in the list as Plain Tiger.");
            assertThat(e.taxon()).isEqualTo(new TaxonRef("A5", "Danaus chrysippus", "Plain Tiger"));
        });
        assertThatThrownBy(() -> custom.add("A1", "Papilio demoleus", null)).isInstanceOfSatisfying(NameBlocked.class,
                e -> assertThat(e.getMessage()).isEqualTo("Papilio demoleus is already in the list."));
    }

    @Test
    void aBundledSynonymIsBlockedNamingTheSpeciesItBelongsTo() {
        assertThatThrownBy(() -> custom.add("A1", "limnas klugii", null)).isInstanceOfSatisfying(NameBlocked.class, e -> {
            assertThat(e.code()).isEqualTo("in_list");
            assertThat(e.getMessage()).isEqualTo("Limnas klugii is already in the list as Danaus chrysippus (Plain Tiger).");
            assertThat(e.taxon().id()).isEqualTo("A5");
        });
        assertThatThrownBy(() -> custom.add("A1", "Princeps demoleus", null)).isInstanceOfSatisfying(NameBlocked.class,
                e -> assertThat(e.getMessage()).isEqualTo("Princeps demoleus is already in the list as Papilio demoleus."));
    }

    @Test
    void searchWordsAndInactiveSpeciesNeverBlock() {
        sql(library.database(), "INSERT INTO taxon_synonym (taxon_id, name, source, added_at) VALUES ('A6', 'Neptis hylas',"
                + " 'user', '2026-10-15T00:00:00Z')");
        TestBundles.retire(library.database(), "A12");

        assertThat(custom.add("A1", "Neptis hylas", null).id()).isEqualTo("U1");
        assertThat(custom.add("A9", "Papilio polytes", null).id()).isEqualTo("U2");
    }

    @Test
    void anotherCustomSpeciesNameIsBlockedWithoutCase() {
        custom.add("A1", "Neptis sankara", "Broad-banded Sailer");

        assertThatThrownBy(() -> custom.add("A9", "NEPTIS SANKARA", null)).isInstanceOfSatisfying(NameBlocked.class, e -> {
            assertThat(e.code()).isEqualTo("name_exists");
            assertThat(e.getMessage()).isEqualTo("You already added Neptis sankara.");
            assertThat(e.taxon()).isEqualTo(new TaxonRef("U1", "Neptis sankara", "Broad-banded Sailer"));
        });
    }

    @Test
    void idsAreNeverReusedEvenAfterADelete() {
        custom.add("A1", "Neptis sankara", null);
        custom.add("A1", "Neptis soma", null);
        sql(library.database(), "DELETE FROM taxon_fts WHERE taxon_id = 'U2'");
        sql(library.database(), "DELETE FROM taxon WHERE id = 'U2'");

        assertThat(custom.add("A1", "Neptis nata", null).id()).isEqualTo("U3");
        TestBundles.addManualSpecies(library.database(), "U9", "Something newus");
        assertThat(custom.add("A1", "Neptis clinia", null).id()).isEqualTo("U10");
    }

    @Test
    void theCheckAnswersWithTheNormalisedNameOrTheReason() {
        custom.add("A1", "Neptis sankara", null);

        assertThat(custom.check(" neptis  soma ", null)).isEqualTo(new CustomSpecies.Check(true, "Neptis soma", null, null, null));
        assertThat(custom.check("Neptis", null)).isEqualTo(new CustomSpecies.Check(false, null, "invalid",
                "Enter the scientific name as genus and species, e.g. Neptis sankara.", null));
        assertThat(custom.check(null, null).code()).isEqualTo("invalid");
        assertThat(custom.check("danaus chrysippus", null)).isEqualTo(new CustomSpecies.Check(false, null, "in_list",
                "Danaus chrysippus is already in the list as Plain Tiger.", new TaxonRef("A5", "Danaus chrysippus", "Plain Tiger")));
        assertThat(custom.check("neptis sankara", null)).isEqualTo(new CustomSpecies.Check(false, null, "name_exists",
                "You already added Neptis sankara.", new TaxonRef("U1", "Neptis sankara", null)));
        assertThat(custom.check("neptis sankara", "U1")).as("its own name, on rename")
                .isEqualTo(new CustomSpecies.Check(true, "Neptis sankara", null, null, null));
    }
}
