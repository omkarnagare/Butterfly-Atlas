package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.taxa.TaxonViews.Count;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Item;
import com.butterflyatlas.app.updates.UpdateViews.Kind;
import com.butterflyatlas.app.updates.UpdateViews.Ref;
import com.butterflyatlas.app.updates.UpdateViews.Summary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Detecting an update and its dry-run summary (5b contract §2, §5). */
class PendingUpdateTest {

    @TempDir
    java.nio.file.Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
    }

    /** "kind count photos" then each item as "rank from→to photos detail". */
    private static List<String> lines(Summary summary) {
        return summary.kinds().stream().flatMap(k -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(k.kind() + " " + k.count() + " " + k.photos()),
                k.items().stream().map(PendingUpdateTest::line))).toList();
    }

    private static String line(Item i) {
        return "  " + i.rank() + " " + i.from().stream().map(Ref::id).toList() + "→" + i.to().stream().map(Ref::id).toList()
                + " " + i.photos() + " " + i.detail();
    }

    @Test
    void nothingIsPendingWhenTheLibraryHasTheNewestBundle() {
        PendingUpdate pending = PendingUpdate.of(f.db, UpdateBundles.bundles(UpdateFixture.v1()));

        assertThat(pending.state()).isEqualTo(PendingUpdate.State.NONE);
        assertThat(pending.from()).isEqualTo("2026-01-01");
        assertThat(pending.to()).isEqualTo("2026-01-01");
        assertThat(pending.steps()).isEmpty();
        assertThat(pending.summary(f.db)).isNull();
        assertThat(pending.notice()).isNull();
    }

    @Test
    void theChainFollowsBasedOnFromTheLibrarysVersionToTheNewest() {
        Bundle v2 = UpdateFixture.v2();
        Bundle v3 = UpdateBundles.derive(v2, "2026-03-01").rename("A12", "Papilio romulus").build();

        PendingUpdate pending = PendingUpdate.of(f.db, UpdateBundles.bundles(UpdateFixture.v1(), v2, v3));

        assertThat(pending.state()).isEqualTo(PendingUpdate.State.PENDING);
        assertThat(pending.from()).isEqualTo("2026-01-01");
        assertThat(pending.to()).isEqualTo("2026-03-01");
        assertThat(pending.versions()).containsExactly("2026-02-01", "2026-03-01");

        f.sql("INSERT INTO taxonomy_state (bundle_version, bundle_sha256, applied_at) VALUES ('2026-02-01', 'x', 't')");
        PendingUpdate later = PendingUpdate.of(f.db, UpdateBundles.bundles(UpdateFixture.v1(), v2, v3));
        assertThat(later.from()).as("the highest version applied").isEqualTo("2026-02-01");
        assertThat(later.versions()).containsExactly("2026-03-01");
    }

    @Test
    void aLibraryOnAVersionNotShippedCannotBeUpdated() {
        Bundle other = UpdateBundles.derive(UpdateFixture.v1(), "2026-05-01").build();
        Bundle alone = new Bundle(other.format(), other.version(), null, other.generatedAt(), other.ranks(), other.region(),
                other.group(), other.source(), other.taxa(), List.of());

        PendingUpdate pending = PendingUpdate.of(f.db, UpdateBundles.bundles(alone));

        assertThat(pending.state()).isEqualTo(PendingUpdate.State.UNAVAILABLE);
        assertThat(pending.notice()).isEqualTo("This library was last used with a newer version of Butterfly Atlas (its"
                + " species list is from 2026-01-01). Your photos and folders are safe and everything else works as usual."
                + " Install that version or a newer one to keep the species list up to date.");
        assertThat(pending.steps()).isEmpty();
        assertThat(pending.summary(f.db)).isNull();
    }

    @Test
    void aLibraryThatRecordsNoVersionSaysSoWithoutANull() {
        f.sql("DELETE FROM taxonomy_state");

        PendingUpdate pending = PendingUpdate.of(f.db, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()));

        assertThat(pending.state()).isEqualTo(PendingUpdate.State.UNAVAILABLE);
        assertThat(pending.notice()).isEqualTo("This library doesn't record which species list it uses, so it can't be"
                + " updated. Your photos and folders are safe and everything else works as usual.");
    }

    @Test
    void theSummaryCountsEveryKindWithTheUsersPhotosAndTheSpeciesBeforeAndAfter() {
        f.populated();
        List<String> before = f.rows("SELECT id, taxon_id, rel_path, file_name, number FROM image ORDER BY id");

        Summary summary = PendingUpdate.of(f.db, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2())).summary(f.db);

        assertThat(summary.photos()).as("2 renamed, 1 moved with its genus, 2 merged, 3 split, 1 of a removed species")
                .isEqualTo(9);
        assertThat(summary.species().before()).isEqualTo(new Count(7, 7));
        assertThat(summary.species().after()).as("the removed species with a photo still counts; the merged one goes")
                .isEqualTo(new Count(7, 9));
        assertThat(lines(summary)).containsExactly(
                "rename 1 2",
                "  species [A6]→[A6] 2 Danaus genutia → Danaus melanippus",
                "move 1 1",
                "  genus [A7]→[A7] 1 Tirumala: Danaini → Danainae",
                "merge 1 2",
                "  species [A14]→[A15] 2 Papilio clytia merged into Papilio agestor",
                "split 1 3",
                "  species [A5]→[A5, A16] 3 Danaus chrysippus split into Danaus chrysippus, Danaus petilia",
                "add 1 0",
                "  species []→[A17] 0 Papilio memnon added",
                "remove 1 1",
                "  species [A13]→[] 1 Papilio demoleus removed");
        Kind rename = summary.kinds().getFirst();
        assertThat(rename.items().getFirst().from()).containsExactly(new Ref("A6", "Danaus genutia", "Striped Tiger"));
        assertThat(rename.items().getFirst().to()).containsExactly(new Ref("A6", "Danaus melanippus", "Striped Tiger"));
        assertThat(f.rows("SELECT id, taxon_id, rel_path, file_name, number FROM image ORDER BY id")).as("nothing written")
                .isEqualTo(before);
        assertThat(f.rows("SELECT COUNT(*) FROM taxon WHERE id IN ('A15', 'A16', 'A17')")).containsExactly("0");
    }

    @Test
    void skippedReleasesMakeOneSummaryWithTheNamesBeforeTheFirstStepAndAfterTheLast() {
        f.populated();
        Bundle v2 = UpdateFixture.v2();
        Bundle v3 = UpdateBundles.derive(v2, "2026-03-01").rename("A6", "Danaus genutius")
                .readd(UpdateFixture.v1Taxon("A13")).rename("A7", "Tirumalia").build();

        Summary summary = PendingUpdate.of(f.db, UpdateBundles.bundles(UpdateFixture.v1(), v2, v3)).summary(f.db);

        assertThat(lines(summary)).containsExactly(
                "rename 2 3",
                "  species [A6]→[A6] 2 Danaus genutia → Danaus genutius",
                "  genus [A7]→[A7] 1 Tirumala → Tirumalia",
                "move 1 1",
                "  genus [A7]→[A7] 1 Tirumalia: Danaini → Danainae",
                "merge 1 2",
                "  species [A14]→[A15] 2 Papilio clytia merged into Papilio agestor",
                "split 1 3",
                "  species [A5]→[A5, A16] 3 Danaus chrysippus split into Danaus chrysippus, Danaus petilia",
                "add 2 1",
                "  species []→[A13] 1 Papilio demoleus added",
                "  species []→[A17] 0 Papilio memnon added",
                "remove 1 1",
                "  species [A13]→[] 1 Papilio demoleus removed");
        assertThat(summary.species().after()).isEqualTo(new Count(7, 9));
        assertThat(summary.photos()).isEqualTo(9);
    }
}
