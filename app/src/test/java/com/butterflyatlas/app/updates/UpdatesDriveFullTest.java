package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A full drive is never a generic error in an update (review J20): an Apply, Merge or Retry that cannot record itself is
 * 409 {@code drive_full} with nothing started; the plan, a batch of moves or the switch-over that cannot be written
 * pauses {@code drive_full}, and Retry carries on once space is freed.
 */
class UpdatesDriveFullTest {

    private static final String FULL = "The drive with your library is full. Free some space, then choose Retry.";

    @TempDir
    Path dir;

    private UpdateFixture f;
    private Updates updates;
    /** The methods ("Class.method") during which a commit fails as SQLite does on a full drive; empty: none. */
    private volatile Set<String> fullIn = Set.of();

    /** The usual v2, and Neptis sankara added (the custom U1 then matches it). */
    private static Bundle v2() {
        return UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").rename("A6", "Danaus melanippus")
                .add("A15", "A11", "species", "Papilio agestor", null).merge("A15", "A14")
                .add("A20", "A3", "genus", "Neptis", null).add("A21", "A20", "species", "Neptis sankara", null).build();
    }

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
        TestDatabases.failCommits(f.db, () -> Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> fullIn.contains(frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                        + "." + frame.getMethodName()))
                ? new SQLException("[SQLITE_FULL] Insertion failed because database is full (database or disk is full)") : null);
        updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), v2()), f.clock);
        updates.startup();
    }

    private void idle() throws InterruptedException {
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
    }

    private static void refusedFull(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String again) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("drive_full");
            assertThat(e.getMessage()).isEqualTo("The drive with your library is full. Free some space, then choose " + again
                    + " again.");
        });
    }

    /** Paused drive_full, then Retry with space freed: done, every photo where its row says. */
    private void pausedFullThenRetried() throws InterruptedException {
        idle();
        assertThat(updates.update().state()).isEqualTo("paused");
        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("drive_full", FULL));
        assertThat(updates.status().orElseThrow().notice().code()).isEqualTo("drive_full");
        fullIn = Set.of();
        updates.retry();
        idle();
        assertThat(updates.update().state()).isEqualTo("done");
        Map<String, String> folders = f.db.read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            Map<String, String> byId = new HashMap<>();
            for (String id : f.rows("SELECT DISTINCT taxon_id FROM image")) {
                byId.put(id, snapshot.folder(snapshot.find(id).orElseThrow()));
            }
            return byId;
        });
        for (String row : f.rows("SELECT taxon_id, rel_path, file_name FROM image WHERE deleted_at IS NULL")) {
            String[] r = row.split("\\|");
            assertThat(r[1]).as(row).isEqualTo(folders.get(r[0]) + r[2]);
            assertThat(f.file(r[1])).as(row).isRegularFile();
        }
    }

    @Test
    void anApplyThatCannotBeRecordedIsDriveFullAndStartsNothing() throws Exception {
        fullIn = Set.of("Updates.apply");

        refusedFull(updates::apply, "Apply now");

        assertThat(updates.update().state()).isEqualTo("pending");
        assertThat(f.library.gate().isClosed()).isFalse();
        assertThat(f.rows("SELECT COUNT(*) FROM taxonomy_update")).containsExactly("0");
        fullIn = Set.of();
        updates.apply();
        idle();
        assertThat(updates.update().state()).isEqualTo("done");
    }

    @Test
    void aFullDriveAtThePlanPausesTheUpdate() throws Exception {
        fullIn = Set.of("Updates.plan");
        updates.apply();
        pausedFullThenRetried();
    }

    @Test
    void aBatchThatCannotBeRecordedPausesTheUpdateEvenWhenThePauseCannotBeRecordedEither() throws Exception {
        fullIn = Set.of("UpdateMoves.record", "Updates.pause");
        updates.apply();
        idle();
        assertThat(f.rows("SELECT state FROM taxonomy_update")).as("kept in memory only").containsExactly("running");
        pausedFullThenRetried();
    }

    @Test
    void aFullDriveAtTheSwitchOverPausesTheUpdate() throws Exception {
        fullIn = Set.of("Updates.switchOver");
        updates.apply();
        idle();
        assertThat(f.rows("SELECT stage FROM taxonomy_update")).containsExactly("switch");
        pausedFullThenRetried();
    }

    @Test
    void aRetryThatCannotBeRecordedIsDriveFullAndStaysPaused() throws Exception {
        fullIn = Set.of("Updates.plan");
        updates.apply();
        idle();
        fullIn = Set.of("Updates.retry");

        refusedFull(updates::retry, "Retry");

        assertThat(updates.update().state()).isEqualTo("paused");
        pausedFullThenRetried();
    }

    @Test
    void aMatchMergeOnAFullDriveIsDriveFullOrPausesAsAnUpdate() throws Exception {
        updates.apply();
        idle();
        fullIn = Set.of("Updates.merge");

        refusedFull(() -> updates.merge("U1", "A21"), "Merge");

        assertThat(f.library.gate().isClosed()).isFalse();
        assertThat(f.rows("SELECT COUNT(*) FROM taxonomy_update WHERE kind = 'match'")).containsExactly("0");
        fullIn = Set.of("Updates.switchOver");
        updates.merge("U1", "A21");
        pausedFullThenRetried();
        assertThat(updates.update().kind()).isEqualTo("match");
        assertThat(f.rows("SELECT COUNT(*) FROM taxon WHERE id = 'U1'")).containsExactly("0");
    }
}
