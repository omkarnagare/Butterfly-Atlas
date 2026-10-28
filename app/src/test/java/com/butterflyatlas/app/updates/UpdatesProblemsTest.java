package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.taxonomy.bundle.BundleTaxon;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A summary that cannot be worked out never fails the answer, and an unfinished update whose bundle is not shipped
 * pauses with what to do (5b contract §7).
 */
class UpdatesProblemsTest {

    @TempDir
    Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
    }

    @Test
    void aSummaryThatCannotBeWorkedOutIsAProblemNotAFailure() {
        Bundle v2 = UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").rename("A6", "Danaus melanippus").build();
        List<BundleTaxon> taxa = new ArrayList<>(v2.taxa());
        taxa.add(new BundleTaxon("A30", "A99", "species", "Danaus orphanus", null, 9999, List.of()));
        Bundle broken = new Bundle(v2.format(), v2.version(), v2.basedOn(), v2.generatedAt(), v2.ranks(), v2.region(),
                v2.group(), v2.source(), taxa, v2.changes());
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), broken), f.clock);
        updates.startup();

        Update pending = updates.update();

        assertThat(pending.state()).isEqualTo("pending");
        assertThat(pending.summary()).isNull();
        assertThat(pending.summaryProblem()).isEqualTo("The list of changes couldn't be worked out (The taxonomy file is"
                + " inconsistent (taxon A30 has unknown parent A99)). You can still apply the update, or choose Later.");
        assertThat(updates.status().orElseThrow().state()).isEqualTo("pending");
    }

    @Test
    void aSummaryThatFailedIsWorkedOutAgainOnTheNextRead() {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        updates.startup();
        AtomicBoolean failing = new AtomicBoolean(true);
        TestDatabases.failStatements(f.db, failing::get);

        assertThat(updates.update().summaryProblem()).contains("disk I/O error");
        failing.set(false);
        Update again = updates.update();

        assertThat(again.summaryProblem()).isNull();
        assertThat(again.summary()).isNotNull();
    }

    @Test
    void anUnfinishedUpdateWhoseBundleIsNotShippedPausesNeedsNewer() throws Exception {
        f.sql("INSERT INTO taxonomy_update (kind, from_version, to_version, step_version, stage, state, started_at)"
                + " VALUES ('bundle', '2026-01-01', '2027-01-01', '2027-01-01', 'plan', 'running', 't')");
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1()), f.clock);

        updates.startup();
        updates.awaitContinuation();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().state()).isEqualTo("paused");
        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("needs_newer", "This update needs a newer"
                + " version of Butterfly Atlas. Install it, then open the library again."));
        assertThat(f.rows("SELECT state, notice_code FROM taxonomy_update")).containsExactly("paused|needs_newer");
        assertThat(f.library.gate().isClosed()).isTrue();
    }
}
