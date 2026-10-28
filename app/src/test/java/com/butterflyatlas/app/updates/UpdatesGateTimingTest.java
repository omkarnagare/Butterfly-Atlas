package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Apply and Merge close the gate before they answer, not when their run begins; a merge whose custom species is gone
 * meanwhile merges nothing; a match being merged cannot be declined (review J14).
 */
class UpdatesGateTimingTest {

    @TempDir
    Path dir;

    private UpdateFixture f;
    private final CountDownLatch go = new CountDownLatch(1);
    private Updates updates;

    /** Neptis sankara (A21, the custom U1 has a photo) and Neptis soma (A22, the custom U2 has none) added. */
    private static Bundle v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").add("A20", "A3", "genus", "Neptis", null)
                .add("A21", "A20", "species", "Neptis sankara", null).add("A22", "A20", "species", "Neptis soma", null).build();
    }

    @BeforeEach
    void setUp() throws Exception {
        f = new UpdateFixture(dir);
        f.populated();
        f.custom.add("A1", "Neptis soma", null);
        updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2()), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        updates.beforeWork = () -> {
            try {
                assertThat(go.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @AfterEach
    void tearDown() {
        go.countDown();
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("taxonomy_updating"));
    }

    @Test
    void aMergeClosesTheGateBeforeItAnswersSoTheCustomSpeciesCannotBeDeletedUnderIt() throws Exception {
        updates.merge("U2", "A22");

        assertThat(f.library.gate().isClosed()).isTrue();
        refused(() -> f.custom.delete("U2"));
        refused(() -> updates.decline("U2", "A22"));
        go.countDown();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(f.rows("SELECT COUNT(*) FROM taxon WHERE id = 'U2'")).containsExactly("0");
        assertThat(f.rows("SELECT state FROM custom_match WHERE custom_id = 'U2'")).containsExactly("merged");
        assertThatThrownBy(() -> updates.decline("U2", "A22")).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.kind()).isEqualTo(AtlasException.Kind.NOT_FOUND));
        assertThat(f.library.gate().isClosed()).isFalse();
    }

    @Test
    void aMergeWhoseCustomSpeciesIsGoneMeanwhileMergesNothing() throws Exception {
        updates.merge("U2", "A22");
        f.sql("DELETE FROM taxon_fts WHERE taxon_id = 'U2'");
        f.sql("DELETE FROM taxon WHERE id = 'U2'"); // as if deleted before the gate closed

        go.countDown();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(updates.update().notice()).isNull();
        assertThat(updates.matches()).extracting(m -> m.custom().id()).containsExactly("U1");
    }

    @Test
    void applyClosesTheGateBeforeItAnswersAndAFailedStartOpensItAgain() throws Exception {
        Bundle v3 = UpdateBundles.derive(v2(), "2026-03-01").rename("A6", "Danaus melanippus").build();
        Updates next = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2(), v3), f.clock);
        next.startup();
        next.beforeWork = updates.beforeWork;
        f.sql("INSERT INTO taxonomy_update (kind, from_version, to_version, step_version, stage, state, started_at)"
                + " VALUES ('match', 'x', 'x', 'x', 'plan', 'paused', 't')");

        assertThatThrownBy(next::apply).isInstanceOfSatisfying(AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("update_running"));
        assertThat(f.library.gate().isClosed()).as("a start that failed").isFalse();

        f.sql("DELETE FROM taxonomy_update WHERE state = 'paused'");
        next.apply();
        assertThat(f.library.gate().isClosed()).isTrue();
        refused(() -> f.add("A5"));
        go.countDown();
        assertThat(next.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(next.update().state()).isEqualTo("done");
    }
}
