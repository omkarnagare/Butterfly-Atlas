package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.taxonomy.TaxonomyBundles;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;
import com.butterflyatlas.app.taxonomy.bundle.Change;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The test-scope generator of taxonomy update chains (design D10). */
class UpdateBundlesTest {

    @TempDir
    Path dir;

    private static List<String> changes(Bundle bundle) {
        return bundle.changes().stream()
                .map(c -> c.type().json() + " " + c.from() + " " + c.to() + " " + c.detail()).toList();
    }

    @Test
    void aDerivedBundleListsItsChangesAsTheImporterDoes() {
        Bundle v2 = UpdateBundles.derive(UpdateBundles.fixture(), "2026-02-01")
                .rename("A6", "Danaus melanippus")
                .move("A7", "A2")
                .add("A15", "A11", "species", "Papilio agestor", "Tawny Mime")
                .merge("A15", "A14")
                .add("A16", "A4", "species", "Danaus petilia", null)
                .split("A5", "A5", "A16")
                .remove("A13")
                .add("A17", "A11", "species", "Papilio memnon", "Great Mormon")
                .build();

        assertThat(v2.version()).isEqualTo("2026-02-01");
        assertThat(v2.basedOn()).isEqualTo("2026-01-01");
        assertThat(changes(v2)).containsExactly(
                "add [] [A17] Papilio memnon added",
                "remove [A13] [] Papilio demoleus removed",
                "rename [A6] [A6] Danaus genutia → Danaus melanippus",
                "move [A7] [A7] Tirumala: Danaini → Danainae",
                "merge [A14] [A15] Papilio clytia merged into Papilio agestor",
                "split [A5] [A5, A16] Danaus chrysippus split into Danaus chrysippus, Danaus petilia");
        assertThat(v2.taxa()).extracting(BundleTaxon::id).contains("A5", "A15", "A16", "A17").doesNotContain("A13", "A14");
    }

    @Test
    void aHigherRankMergeTakesItsChildrenAndARemovedTaxonCanComeBack() {
        Bundle v1 = UpdateBundles.fixture();
        Bundle v2 = UpdateBundles.derive(v1, "2026-02-01").merge("A4", "A7").remove("A12").build();
        Bundle v3 = UpdateBundles.derive(v2, "2026-03-01")
                .readd(v1.taxa().stream().filter(t -> t.id().equals("A12")).findFirst().orElseThrow()).build();

        assertThat(changes(v2)).containsExactly("remove [A12] [] Papilio polytes removed",
                "move [A8] [A8] Tirumala limniace: Tirumala → Danaus", "merge [A7] [A4] Tirumala merged into Danaus");
        assertThat(changes(v3)).containsExactly("add [] [A12] Papilio polytes added");

        TaxonomyBundles chain = UpdateBundles.bundles(v1, v2, v3);
        assertThat(chain.all()).extracting(s -> s.bundle().version()).containsExactly("2026-01-01", "2026-02-01", "2026-03-01");
        assertThat(chain.newest().bundle().changes()).extracting(Change::type).containsExactly(Change.Type.ADD);
    }

    @Test
    void aWrittenChainLoadsFromItsFolder() throws Exception {
        Bundle v1 = UpdateBundles.fixture();
        Bundle v2 = UpdateBundles.derive(v1, "2026-02-01").rename("A12", "Papilio romulus").build();

        UpdateBundles.write(dir.resolve("chain"), v1, v2);

        TaxonomyBundles chain = TaxonomyBundles.fromDirectory(dir.resolve("chain"));
        assertThat(chain.all()).extracting(TaxonomyBundles.Shipped::fileName)
                .containsExactly("taxonomy-india-2026-01-01.json", "taxonomy-india-2026-02-01.json");
        assertThat(chain.newest().bundle().taxa()).filteredOn(t -> t.id().equals("A12")).extracting(BundleTaxon::name)
                .containsExactly("Papilio romulus");
    }

    @Test
    void theRealBundlesV2HasARenameAGenusMoveAMergeASplitAndTheAddedSpecies() throws Exception {
        UpdateBundles.main(new String[] {dir.resolve("real").toString(), "Neptis harita"});

        TaxonomyBundles chain = TaxonomyBundles.fromDirectory(dir.resolve("real"));
        assertThat(chain.all()).hasSize(2);
        assertThat(chain.all().getFirst().sha256()).as("the shipped bundle as it is")
                .isEqualTo(TaxonomyBundles.fromClasspath().newest().sha256());
        Bundle v2 = chain.newest().bundle();
        assertThat(v2.version()).isEqualTo(UpdateBundles.REAL_V2);
        assertThat(changes(v2)).containsExactly(
                "add [] [A1606] Neptis harita added",
                "rename [A21] [A21] Euploea core → Euploea coreta",
                "move [A102] [A102] Pachliopta: Troidini → Papilionini",
                "merge [A124] [A43] Tirumala septentrionis merged into Tirumala limniace",
                "split [A5] [A5, A1605] Danaus chrysippus split into Danaus chrysippus, Danaus petilia");
        assertThat(v2.taxa()).filteredOn(t -> t.id().equals("A1606")).extracting(BundleTaxon::parent)
                .as("in the genus Neptis").containsExactly("A46");
        assertThat(Files.readString(dir.resolve("real").resolve(TaxonomyBundles.CHECKSUMS)).lines()).hasSize(2);
    }
}
