package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.library.Library;
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

import static com.butterflyatlas.app.imports.ImportStoreTest.item;
import static org.assertj.core.api.Assertions.assertThat;

/** How RAW + JPEG pairs form and come apart (7a contract §3, audit 5). */
class ImportPairsTest {

    @TempDir
    Path dir;

    private Library library;
    private ImportStore store;
    private long run;

    @BeforeEach
    void open() throws Exception {
        library = TestBundles.fixtureLibrary(dir.resolve("Atlas"));
        store = new ImportStore(() -> library, TestBundles.CLOCK);
        run = store.create(Files.createDirectories(dir.resolve("Card"))).id();
    }

    private long read(String relPath, String fileType, Object... more) {
        Object[] columns = new Object[4 + more.length];
        columns[0] = "read_state";
        columns[1] = "read";
        columns[2] = "file_type";
        columns[3] = fileType;
        System.arraycopy(more, 0, columns, 4, more.length);
        return item(library.database(), run, relPath, columns);
    }

    private void recompute() {
        library.database().write(c -> ImportPairs.recompute(c, run, ImportStore.stamp(c, run, TestBundles.CLOCK.instant())));
    }

    private Long pairOf(long id) {
        return library.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT pair_item_id FROM import_item WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    long pair = rs.getLong(1);
                    return rs.wasNull() ? null : pair;
                }
            }
        });
    }

    private List<String> rows(String sql) {
        return library.database().read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        });
    }

    @Test
    void aRawAndAJpegOfOneNameInOneFolderArePaired() {
        long raw = read("Day 1/DSC_0042.NEF", "raw");
        long jpeg = read("Day 1/dsc_0042.jpg", "jpeg");
        long elsewhere = read("Day 2/DSC_0042.JPG", "jpeg");
        long alone = read("Day 1/DSC_0043.NEF", "raw");
        long twoJpegs1 = read("Day 1/IMG_1.JPG", "jpeg");
        long twoJpegs2 = read("Day 1/IMG_1.png", "png");

        recompute();

        assertThat(pairOf(raw)).isEqualTo(jpeg);
        assertThat(pairOf(jpeg)).isEqualTo(raw);
        assertThat(List.of(elsewhere, alone, twoJpegs1, twoJpegs2)).allSatisfy(id -> assertThat(pairOf(id)).isNull());
        ImportStore.Pairs pairs = store.pairs(run);
        assertThat(pairs).isEqualTo(new ImportStore.Pairs(1, 0, null, false, List.of("jpeg")));
        assertThat(store.counts(run).total()).as("per shot").isEqualTo(5);
    }

    @Test
    void threeFilesOfOneNameAreNoPair() {
        long raw = read("A.NEF", "raw");
        long jpg = read("A.JPG", "jpeg");
        read("A.jpeg", "jpeg");

        recompute();

        assertThat(pairOf(raw)).isNull();
        assertThat(pairOf(jpg)).isNull();
        assertThat(store.pairs(run)).isNull();
    }

    @Test
    void aHalfThatIsNotReadOrBecomesMissingLeavesItsPair() {
        long raw = read("B.NEF", "raw");
        long jpeg = read("B.JPG", "jpeg");
        recompute();
        assertThat(pairOf(raw)).isEqualTo(jpeg);

        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET read_state = 'missing' WHERE id = ?")) {
                ps.setLong(1, raw);
                return ps.executeUpdate();
            }
        });
        recompute();

        assertThat(pairOf(raw)).isNull();
        assertThat(pairOf(jpeg)).isNull();
    }

    @Test
    void aHalfWithoutEditsTakesItsPartnersAndDifferentEditsKeepThemApart() {
        long rawC = read("C.NEF", "raw");
        long jpegC = read("C.JPG", "jpeg", "taxon_id", "A5", "skipped", 0, "user_place_set", 1, "user_place_id", "P4");
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO import_item_tag (item_id, tag_id)"
                    + " SELECT ?, id FROM tag WHERE key = 'male'")) {
                ps.setLong(1, jpegC);
                return ps.executeUpdate();
            }
        });
        long rawD = read("D.NEF", "raw", "taxon_id", "A6");
        long jpegD = read("D.JPG", "jpeg", "taxon_id", "A5");
        long rawE = read("E.NEF", "raw", "taxon_id", "A6");
        long jpegE = read("E.JPG", "jpeg", "taxon_id", "A6");

        recompute();

        assertThat(pairOf(rawC)).isEqualTo(jpegC);
        assertThat(rows("SELECT taxon_id || '|' || user_place_set || '|' || user_place_id FROM import_item WHERE id = " + rawC))
                .containsExactly("A5|1|P4");
        assertThat(rows("SELECT t.key FROM import_item_tag it JOIN tag t ON t.id = it.tag_id WHERE it.item_id = " + rawC))
                .containsExactly("male");
        assertThat(pairOf(rawD)).as("both edited, differently: two tiles").isNull();
        assertThat(pairOf(jpegD)).isNull();
        assertThat(pairOf(rawE)).as("the same edits").isEqualTo(jpegE);
    }

    @Test
    void acceptingSuggestionsOnAPairTakesTheJpegsAndSkippingOneHalfSkipsBoth() {
        long raw = read("G.NEF", "raw", "suggested_taxon_id", "A6");
        long jpeg = read("G.JPG", "jpeg", "suggested_taxon_id", "A5");
        recompute();
        ImportEdits edits = new ImportEdits(() -> library, TestBundles.CLOCK);

        ImportStore.ItemsPage page = edits.acceptSuggestions(run, List.of(raw));

        assertThat(page.items()).extracting(ImportStore.Item::id).containsExactlyInAnyOrder(raw, jpeg);
        assertThat(page.items()).extracting(ImportStore.Item::taxonId).containsOnly("A5");
        assertThat(edits.skip(run, List.of(jpeg), true).items()).extracting(ImportStore.Item::skipped).containsOnly(true)
                .hasSize(2);
    }

    @Test
    void aPairFilingHasTouchedNeverChanges() {
        long raw = read("F.NEF", "raw");
        long jpeg = read("F.JPG", "jpeg");
        recompute();
        library.database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET outcome = 'filed' WHERE id = ?")) {
                ps.setLong(1, jpeg);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET read_state = 'missing' WHERE id = ?")) {
                ps.setLong(1, raw);
                return ps.executeUpdate();
            }
        });

        recompute();

        assertThat(pairOf(raw)).isEqualTo(jpeg);
        assertThat(store.pairs(run).locked()).isTrue();
    }

    @Test
    void onlyAFiledTypeOutcomeLocksTheChoice() {
        long raw = read("G.NEF", "raw");
        long jpeg = read("G.JPG", "jpeg");
        recompute();
        for (String outcome : List.of("failed", "duplicate", "also_elsewhere", "copy_of", "filed")) {
            library.database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_item SET outcome = ? WHERE id = ?")) {
                    ps.setString(1, outcome);
                    ps.setLong(2, jpeg);
                    return ps.executeUpdate();
                }
            });
            assertThat(store.pairs(run).locked()).as(outcome).isEqualTo(!outcome.equals("failed"));
        }
        assertThat(pairOf(raw)).isEqualTo(jpeg);
    }

    @Test
    void theChoiceNeededMessageCountsWithThousandsSeparators() {
        assertThat(ImportPairs.choiceNeeded(1340).getMessage()).as("fixes J11")
                .isEqualTo("Choose how to file the 1,340 RAW + JPEG pairs first.");
        assertThat(ImportPairs.choiceNeeded(340).getMessage()).isEqualTo("Choose how to file the 340 RAW + JPEG pairs first.");
        assertThat(ImportPairs.choiceNeeded(1).getMessage()).isEqualTo("Choose how to file the 1 RAW + JPEG pair first.");
    }
}
