package com.butterflyatlas.app.taxa;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchWordServiceTest {

    @TempDir
    Path dir;

    private Database db;
    private SearchWordService words;

    @BeforeEach
    void setUp() {
        db = TestBundles.fixtureDatabase(dir);
        words = new SearchWordService(() -> db, TestBundles.CLOCK);
    }

    private int count(String sql, String... args) {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Test
    void addsAWordTidiesItAndListsIt() {
        assertThat(words.add("A5", "  Kaner   tiger ")).containsExactly("Kaner tiger");

        assertThat(count("SELECT COUNT(*) FROM taxon_synonym WHERE taxon_id = 'A5' AND source = 'user'"
                + " AND name = 'Kaner tiger' AND added_at = '2026-10-09T08:00:00Z'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM taxon_fts WHERE taxon_id = 'A5' AND kind = 'word'")).isEqualTo(1);
        assertThat(new TaxonService(() -> db).taxon("A5").searchWords()).containsExactly("Kaner tiger");
    }

    @Test
    void ignoresTheSameWordInAnotherCase() {
        words.add("A5", "Kaner tiger");

        assertThat(words.add("A5", "KANER TIGER")).containsExactly("Kaner tiger");
        assertThat(count("SELECT COUNT(*) FROM taxon_fts WHERE kind = 'word'")).isEqualTo(1);
    }

    @Test
    void ignoresWordsThatAreAlreadyTheTaxonsNames() {
        assertThat(words.add("A5", "plain tiger")).isEmpty();
        assertThat(words.add("A5", "danaus CHRYSIPPUS")).isEmpty();
        assertThat(words.add("A5", "Limnas klugii")).isEmpty();
    }

    @Test
    void theSameWordCanBelongToSeveralTaxa() {
        words.add("A5", "tiger");

        assertThat(words.add("A6", "tiger")).containsExactly("tiger");
        assertThat(words.list("A5")).containsExactly("tiger");
    }

    @Test
    void removesOnlyYourOwnWords() {
        words.add("A5", "Kaner tiger");

        assertThat(words.remove("A5", "kaner TIGER")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM taxon_fts WHERE kind = 'word'")).isZero();

        assertThat(words.remove("A5", "Limnas klugii")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM taxon_synonym WHERE taxon_id = 'A5' AND source = 'bundle'")).isEqualTo(1);
    }

    @Test
    void rejectsBlankAndOverlongWords() {
        assertThatThrownBy(() -> words.add("A5", "   ")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.getMessage()).isEqualTo("Type a search word.");
        });
        assertThatThrownBy(() -> words.add("A5", "x".repeat(81))).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Keep search words to 80 characters or fewer."));
        assertThat(words.add("A5", "x".repeat(80))).hasSize(1);
    }

    @Test
    void unknownTaxaAreNotFound() {
        assertThatThrownBy(() -> words.add("A999", "x")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
    }

    @Test
    void worksAtAnyRank() {
        assertThat(words.add("G:butterflies", "Titli")).containsExactly("Titli");
        assertThat(words.add("A3", "Tigers & Crows")).containsExactly("Tigers & Crows");
    }
}
