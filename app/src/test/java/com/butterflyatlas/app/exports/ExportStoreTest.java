package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.db.DatabaseException;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.testing.TestBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exports as the library records them (4b contract §2, §3): the run, its fixed photo list, progress, history. */
class ExportStoreTest {

    @TempDir
    Path dir;

    private Database db;
    private ExportStore store;

    @BeforeEach
    void open() {
        db = TestBundles.fixtureLibrary(dir).database();
        store = new ExportStore(() -> db, TestBundles.CLOCK);
    }

    private static ExportStore.NewRun run(String what, String state) {
        return new ExportStore.NewRun("/Volumes/Backup", "Butterfly_Atlas_export_2026-10-13", what, "species", true, "state",
                "taxa=A5", what.equals("photos") ? null : ExportNames.CSV_IN_FOLDER, 48, state, null, null);
    }

    private static List<ExportStore.NewItem> items(int n) {
        List<ExportStore.NewItem> items = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            items.add(new ExportStore.NewItem(100 + i, "A5", "Danaus_chrysippus_0" + i + ".jpg",
                    "Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_0" + i + ".jpg", i == 3, 1000L * i));
        }
        return items;
    }

    private long create(String what, String state, int n) {
        return store.record(run(what, state), items(n));
    }

    @Test
    void aNewExportIsRecordedWithItsPhotosInOrder() {
        long id = create("both", ExportStore.RUNNING, 4);

        ExportStore.Run run = store.run(id);
        assertThat(run).isEqualTo(new ExportStore.Run(id, "/Volumes/Backup", "Butterfly_Atlas_export_2026-10-13", "both",
                "species", true, "state", "taxa=A5", "species-list.csv", 48, "running", null, null, null, 0, 4, 0, 0, 0, 0,
                10_000, 0, "2026-10-09T08:00:00Z", null, false, 0, 0, List.of()));
        assertThat(run.folder()).isEqualTo(Path.of("/Volumes/Backup", "Butterfly_Atlas_export_2026-10-13"));
        assertThat(store.pending(id, 0, 10)).extracting(ExportStore.Item::seq, ExportStore.Item::imageId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 101L), org.assertj.core.groups.Tuple.tuple(2, 102L),
                        org.assertj.core.groups.Tuple.tuple(3, 103L), org.assertj.core.groups.Tuple.tuple(4, 104L));
        assertThat(store.pending(id, 2, 1)).extracting(ExportStore.Item::targetRel)
                .containsExactly("Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_03.jpg");
        assertThat(store.current()).contains(run);
        assertThat(store.past()).isEmpty();
    }

    @Test
    void theSpeciesListsSizeAndTimeAreRecordedAtTheStart() {
        long with = store.record(new ExportStore.NewRun("/Volumes/Backup", "Butterfly_Atlas_export_2026-10-13",
                "both", "species", true, "none", "", ExportNames.CSV_IN_FOLDER, 3, ExportStore.RUNNING, 512L, 1_791_890_603_026L),
                List.of());
        store.finish(with);
        long without = create("photos", ExportStore.RUNNING, 1);

        assertThat(store.csvWritten(with)).isEqualTo(new ExportStore.Written(ExportNames.CSV_IN_FOLDER, 512, 1_791_890_603_026L));
        assertThat(store.csvWritten(without)).isNull();
        long unknown = store.record(new ExportStore.NewRun("/Volumes/Backup",
                "Butterfly_Atlas_species_list_2026-10-13.csv", "csv", "species", true, "none", "",
                "Butterfly_Atlas_species_list_2026-10-13.csv", 3, ExportStore.DONE, null, null), List.of());
        assertThat(store.csvWritten(unknown)).as("no time: Remove keeps it")
                .isEqualTo(new ExportStore.Written("Butterfly_Atlas_species_list_2026-10-13.csv", 0, null));
    }

    @Test
    void aCsvOnlyExportIsDoneAtOnce() {
        long id = store.record(new ExportStore.NewRun("/Volumes/Backup",
                "Butterfly_Atlas_species_list_2026-10-13.csv", "csv", "species", true, "none", "",
                "Butterfly_Atlas_species_list_2026-10-13.csv", 3, ExportStore.DONE, null, null),
                List.of());

        ExportStore.Run run = store.run(id);
        assertThat(run.state()).isEqualTo("done");
        assertThat(run.csvName()).isEqualTo("Butterfly_Atlas_species_list_2026-10-13.csv");
        assertThat(run.finishedAt()).isEqualTo("2026-10-09T08:00:00Z");
        assertThat(store.current()).isEmpty();
        assertThat(store.past()).containsExactly(run);
    }

    @Test
    void progressIsSavedInBatchesWithTheRunsCounts() {
        long id = create("photos", ExportStore.RUNNING, 4);

        store.save(id, List.of(new ExportStore.Outcome(1, ExportStore.ITEM_DONE, null, 1000),
                new ExportStore.Outcome(2, ExportStore.SKIPPED, "The photo's file is missing.", 0),
                new ExportStore.Outcome(3, ExportStore.FAILED, "The copy could not be written.", 0)));

        ExportStore.Run run = store.run(id);
        assertThat(List.of(run.copied(), run.skipped(), run.failed(), run.done())).containsExactly(1, 1, 1, 3);
        assertThat(run.bytesDone()).as("the originals' sizes of the photos dealt with").isEqualTo(1000 + 2000 + 3000);
        assertThat(run.renamed()).as("photo 3 was renamed but not copied").isZero();
        assertThat(store.pending(id, 0, 10)).extracting(ExportStore.Item::seq).containsExactly(4);
        assertThat(store.writtenFiles(id)).containsExactly(new ExportStore.Written(
                "Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_01.jpg", 1000, null));
        store.save(id, List.of(new ExportStore.Outcome(4, ExportStore.ITEM_DONE, null, 4321, 1_791_890_603_026L)));
        assertThat(store.writtenFiles(id)).last().isEqualTo(new ExportStore.Written(
                "Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_04.jpg", 4321, 1_791_890_603_026L));
        assertThat(store.pendingTargets(id)).isEmpty();
        assertThat(store.targets(id)).hasSize(4);
    }

    @Test
    void eachChangeOfStateHappensOnlyFromTheStatesItComesFrom() {
        long id = create("photos", ExportStore.RUNNING, 2);

        assertThat(store.resume(id)).as("running already").isFalse();
        assertThat(store.pause(id, "you", null)).isTrue();
        assertThat(store.pause(id, "drive_full", "x")).as("paused already: as it is").isFalse();
        assertThat(store.run(id).pausedBy()).isEqualTo("you");
        assertThat(store.finish(id)).as("finish only from running").isFalse();
        assertThat(store.resume(id)).isTrue();
        assertThat(store.finish(id)).isTrue();
        assertThat(store.cancel(id, "keep", 0)).as("done already").isFalse();
        assertThat(store.pause(id, "you", null)).isFalse();
        assertThat(store.resume(id)).isFalse();
        assertThat(store.run(id).state()).isEqualTo("done");
    }

    @Test
    void theDatabaseItselfHoldsOneExportAtATimeAndNoTwoTargetsEqualWithoutCase() {
        long first = create("photos", ExportStore.RUNNING, 1);

        long list = create("csv", ExportStore.DONE, 0);
        assertThatThrownBy(() -> create("photos", ExportStore.RUNNING, 1)).isInstanceOf(DatabaseException.class)
                .hasStackTraceContaining("export_run_one");
        assertThatThrownBy(() -> store.record(run("both", ExportStore.DONE),
                List.of(new ExportStore.NewItem(1, "A5", "a.jpg", "Folder/A.jpg", false, 1),
                        new ExportStore.NewItem(2, "A5", "a.jpg", "folder/a.JPG", false, 1))))
                .isInstanceOf(DatabaseException.class).hasStackTraceContaining("export_item.target_rel");
        assertThat(store.current()).map(ExportStore.Run::id).contains(first);
        assertThat(store.past()).extracting(ExportStore.Run::id).as("no row of the refused ones").containsExactly(list);
    }

    @Test
    void anUnknownOutcomeIsNeverCountedAsAFailure() {
        long id = create("photos", ExportStore.RUNNING, 1);

        assertThatThrownBy(() -> store.save(id, List.of(new ExportStore.Outcome(1, ExportStore.PENDING, null, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.run(id).done()).isZero();
        assertThat(store.pending(id, 0, 10)).hasSize(1);
    }

    @Test
    void theHistoryAndAnExportsDetailsHoldTheCurrentThePastAndTheProblems() {
        long done = create("photos", ExportStore.RUNNING, 3);
        store.save(done, List.of(new ExportStore.Outcome(1, ExportStore.FAILED, "The copy could not be written.", 0)));
        store.finish(done);
        long running = create("photos", ExportStore.RUNNING, 1);

        ExportStore.History history = store.history();
        ExportStore.Details details = store.details(done, 10);

        assertThat(history.current().id()).isEqualTo(running);
        assertThat(history.past()).extracting(ExportStore.Run::id).containsExactly(done);
        assertThat(details.run().id()).isEqualTo(done);
        assertThat(details.problems()).extracting(ExportStore.Problem::kind).containsExactly("failed");
        assertThat(details.count()).isEqualTo(1);
        assertThatThrownBy(() -> store.details(404, 10)).isInstanceOf(AtlasException.class).hasMessage(
                "That export can't be found.");
    }

    @Test
    void pauseResumeFinishAndCancelChangeTheState() {
        long id = create("photos", ExportStore.RUNNING, 2);

        store.pause(id, "drive_full", "The drive is full: about 2.1 GB more is needed.");
        assertThat(store.run(id)).extracting(ExportStore.Run::state, ExportStore.Run::pausedBy, ExportStore.Run::notice)
                .containsExactly("paused", "drive_full", "The drive is full: about 2.1 GB more is needed.");
        store.resume(id);
        assertThat(store.run(id)).extracting(ExportStore.Run::state, ExportStore.Run::pausedBy, ExportStore.Run::notice)
                .containsExactly("running", null, null);
        store.cancel(id, "remove", 3);
        ExportStore.Run cancelled = store.run(id);
        assertThat(cancelled).extracting(ExportStore.Run::state, ExportStore.Run::cancel, ExportStore.Run::notRemoved,
                ExportStore.Run::finishedAt).containsExactly("cancelled", "remove", 3, "2026-10-09T08:00:00Z");
        long other = create("photos", ExportStore.RUNNING, 1);
        assertThat(store.finish(other)).isTrue();
        assertThat(store.run(other).state()).isEqualTo("done");
        assertThat(store.past()).extracting(ExportStore.Run::id).containsExactly(other, id);
    }

    @Test
    void atStartupARunningExportIsPausedAsStoppedByClosing() {
        long running = create("photos", ExportStore.RUNNING, 1);

        assertThat(store.markClosing()).isEqualTo(1);
        assertThat(store.markClosing()).isZero();

        assertThat(store.run(running)).extracting(ExportStore.Run::state, ExportStore.Run::pausedBy, ExportStore.Run::notice)
                .containsExactly("paused", "closing", "This export stopped when Butterfly Atlas closed.");
    }

    @Test
    void problemsComeSkippedAndFailedFirstInItemOrderThenRenamedAndAreCapped() {
        long id = create("photos", ExportStore.RUNNING, 5);
        store.save(id, List.of(new ExportStore.Outcome(4, ExportStore.FAILED, "The photo could not be read.", 0),
                new ExportStore.Outcome(2, ExportStore.SKIPPED, "The photo was deleted after the export started.", 0)));
        assertThat(store.problemCount(id)).as("renamed but not copied yet: not a problem").isEqualTo(2);
        store.save(id, List.of(new ExportStore.Outcome(3, ExportStore.ITEM_DONE, null, 2900)));
        assertThat(store.run(id).renamed()).isEqualTo(1);
        assertThat(store.run(id).bytesDone()).isEqualTo(4000 + 2000 + 3000);

        assertThat(store.problems(id, 1000)).containsExactly(
                new ExportStore.Problem("skipped", 102, "Danaus_chrysippus_02.jpg", "Danaus chrysippus",
                        "Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_02.jpg", "The photo was deleted after the export started."),
                new ExportStore.Problem("failed", 104, "Danaus_chrysippus_04.jpg", "Danaus chrysippus",
                        "Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_04.jpg", "The photo could not be read."),
                new ExportStore.Problem("renamed", 103, "Danaus_chrysippus_03.jpg", "Danaus chrysippus",
                        "Nymphalidae/Danaus_chrysippus/Danaus_chrysippus_03.jpg",
                        "Saved as Danaus_chrysippus_03.jpg: the full name was too long."));
        assertThat(store.problems(id, 2)).hasSize(2);
        assertThat(store.problemCount(id)).isEqualTo(3);
    }

    @Test
    void onlyFinishedExportsLeaveTheHistory() {
        long done = create("photos", ExportStore.RUNNING, 1);
        store.finish(done);
        long cancelled = create("photos", ExportStore.RUNNING, 1);
        store.cancel(cancelled, "keep", 0);
        long running = create("photos", ExportStore.RUNNING, 1);

        assertThat(store.delete(running)).isFalse();
        assertThat(store.delete(done)).isTrue();
        assertThat(store.find(done)).isEmpty();
        assertThat(store.deleteFinished()).isEqualTo(1);
        assertThat(store.past()).isEmpty();
        assertThat(store.current()).map(ExportStore.Run::id).contains(running);
        int items = db.read(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM export_item"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertThat(items).as("their items go with them").isEqualTo(1);
    }

    /** What another store reads from the database now: the current export and the history as recorded. */
    private void keptAsRecorded(String after) {
        ExportStore.History recorded = new ExportStore(() -> db, TestBundles.CLOCK).history();
        assertThat(store.history()).as("the history in memory after " + after).isEqualTo(recorded);
        assertThat(store.current()).as("the current export in memory after " + after)
                .isEqualTo(java.util.Optional.ofNullable(recorded.current()));
        assertThat(store.past()).as("the past in memory after " + after).isEqualTo(recorded.past());
    }

    @Test
    void theCurrentExportAndTheHistoryInMemoryFollowEveryChangeAsTheDatabaseRecordsIt() {
        keptAsRecorded("nothing");
        java.util.Random random = new java.util.Random(4242);
        AtomicBoolean armed = new AtomicBoolean();
        TestDatabases.failCloseAfterCommit(db, armed);
        List<String> done = new ArrayList<>();
        for (int step = 0; step < 400; step++) {
            ExportStore.Run current = store.current().orElse(null);
            List<ExportStore.Run> past = store.past();
            String change;
            switch (random.nextInt(11)) {
                case 0 -> {
                    change = "a start";
                    try {
                        create("photos", ExportStore.RUNNING, 1 + random.nextInt(6));
                    } catch (DatabaseException refused) {
                        change = "a start refused (one at a time)";
                    }
                }
                case 1 -> {
                    change = "a species list";
                    create("csv", ExportStore.DONE, 0);
                }
                case 2 -> {
                    change = "a save";
                    if (current != null) {
                        List<ExportStore.Item> pending = store.pending(current.id(), 0, 2);
                        List<ExportStore.Outcome> outcomes = new ArrayList<>();
                        for (ExportStore.Item item : pending) {
                            String state = List.of(ExportStore.ITEM_DONE, ExportStore.SKIPPED, ExportStore.FAILED)
                                    .get(random.nextInt(3));
                            outcomes.add(new ExportStore.Outcome(item.seq(), state, state.equals("done") ? null : "why",
                                    item.bytes(), 1_791_890_603_026L));
                        }
                        store.save(current.id(), outcomes);
                    }
                }
                case 3 -> {
                    change = "a pause";
                    if (current != null) {
                        store.pause(current.id(), "drive_full", "The drive is full.");
                    }
                }
                case 4 -> {
                    change = "a resume";
                    if (current != null) {
                        store.resume(current.id());
                    }
                }
                case 5 -> {
                    change = "a cancel";
                    if (current != null) {
                        store.cancel(current.id(), random.nextBoolean() ? "keep" : "remove", random.nextInt(3));
                    }
                }
                case 6 -> {
                    change = "a finish";
                    if (current != null) {
                        store.finish(current.id());
                    }
                }
                case 7 -> {
                    change = "a delete";
                    if (!past.isEmpty()) {
                        store.delete(past.get(random.nextInt(past.size())).id());
                    } else if (current != null) {
                        store.delete(current.id()); // refused: not finished
                    }
                }
                case 8 -> {
                    change = "a clear";
                    if (random.nextInt(4) == 0) {
                        store.deleteFinished();
                    }
                }
                case 9 -> {
                    change = "a restart";
                    store = new ExportStore(() -> db, TestBundles.CLOCK);
                    store.markClosing();
                }
                default -> {
                    change = "a change whose connection failed to close after the commit";
                    armed.set(true);
                    try {
                        if (current != null) {
                            store.pause(current.id(), "you", null);
                        } else {
                            create("photos", ExportStore.RUNNING, 2);
                        }
                    } catch (DatabaseException expected) {
                        // the change stands
                    }
                    armed.set(false);
                }
            }
            done.add(change);
            keptAsRecorded("step " + step + ", " + change);
        }
        assertThat(done).as("every kind of change was made").contains("a start", "a start refused (one at a time)",
                "a species list", "a save", "a pause", "a resume", "a cancel", "a finish", "a delete", "a clear",
                "a restart", "a change whose connection failed to close after the commit");
        assertThat(store.past()).as("the sequence left a history").isNotEmpty();
    }

    @Test
    void theCurrentExportAndTheHistoryAreReadFromTheDatabaseOnceThenFromMemory() {
        long done = create("photos", ExportStore.RUNNING, 1);
        store.finish(done);
        long running = create("photos", ExportStore.RUNNING, 3);
        ExportStore fresh = new ExportStore(() -> db, TestBundles.CLOCK);
        AtomicInteger opened = TestDatabases.countConnections(db);

        assertThat(fresh.history().current().id()).isEqualTo(running);
        assertThat(opened.get()).as("read once").isEqualTo(1);
        fresh.save(running, List.of(new ExportStore.Outcome(1, ExportStore.ITEM_DONE, null, 1000)));
        fresh.pause(running, "you", null);
        opened.set(0);
        for (int i = 0; i < 5; i++) {
            assertThat(fresh.current()).map(ExportStore.Run::copied).contains(1);
            assertThat(fresh.current()).map(ExportStore.Run::state).contains("paused");
            assertThat(fresh.history().past()).extracting(ExportStore.Run::id).containsExactly(done);
        }
        assertThat(opened.get()).as("no connection for the current export and the history").isZero();
    }

    @Test
    void anUnknownExportIsNotFound() {
        assertThatThrownBy(() -> store.run(404)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("not_found");
            assertThat(e.getMessage()).isEqualTo("That export can't be found.");
        });
    }
}
