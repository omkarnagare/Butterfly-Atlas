package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.butterflyatlas.app.updates.UpdateViews.LeftBehind;
import com.butterflyatlas.app.updates.UpdateViews.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The result is kept (5b contract §7): photos left behind and folders that could not be removed are in it, it is
 * written into {@code change_log}, and {@code last} gives it after a restart.
 */
class UpdatesResultTest {

    private static final String DANAUS = "images/Nymphalidae/Danainae/Danaini/Danaus/";

    @TempDir
    Path dir;

    private UpdateFixture f;

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
    }

    @Test
    void theLastResultIsThereAfterARestart() throws Exception {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        updates.startup();
        assertThat(updates.update().last()).isNull();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        Result result = updates.update().result();

        Updates restarted = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        restarted.startup();

        assertThat(restarted.update().state()).isEqualTo("none");
        assertThat(restarted.update().last()).isEqualTo(result);
        assertThat(result.photos()).isEqualTo(9);
        assertThat(result.finishedAt()).isNotNull();
        assertThat(result.splits()).extracting(UpdateViews.SplitBrief::id).containsExactly("A5");
    }

    @Test
    void aPhotoGivenUpAfterMissingIsLeftBehindInTheResult() throws Exception {
        String genutia = f.rows("SELECT rel_path FROM image WHERE taxon_id = 'A6' ORDER BY id").getFirst();
        long id = Long.parseLong(f.rows("SELECT min(id) FROM image WHERE taxon_id = 'A6'").getFirst());
        Files.delete(f.file(genutia));
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(updates.update().notice().code()).isEqualTo("missing");

        updates.retry();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().result().leftBehind()).containsExactly(new LeftBehind(id, "Danaus_genutia_01.jpg", genutia));
        assertThat(updates.update().last().leftBehind()).hasSize(1);
        int moves = Integer.parseInt(f.rows("SELECT COUNT(*) FROM taxonomy_move WHERE done = 1 AND old_rel <> new_rel")
                .getFirst());
        assertThat(updates.update().result().moved()).as("the photo left behind was not moved").isEqualTo(moves - 1);
    }

    @Test
    void movedCountsTheFilesMovedNotThePhotosAffected() throws Exception {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(),
                UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").remove("A13").build(),
                UpdateBundles.derive(UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").remove("A13").build(), "2026-03-01")
                        .rename("A6", "Danaus melanippus").build()), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        Result result = updates.update().result();
        assertThat(result.photos()).as("Papilio demoleus's photo and Danaus genutia's two").isEqualTo(3);
        assertThat(result.moved()).as("only Danaus genutia's two files moved").isEqualTo(2);
        assertThat(f.rows("SELECT json_extract(payload_json, '$.moved') FROM change_log WHERE kind = 'taxonomy.done'"))
                .containsExactly("2");
    }

    @Test
    void anUpdateThatOnlyRemovesASpeciesMovesNoFile() throws Exception {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(),
                UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").remove("A13").build()), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().result().photos()).isEqualTo(1);
        assertThat(updates.update().result().moved()).isZero();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anEmptiedFolderThatCannotBeRemovedIsListedNotRemoved() throws Exception {
        Path danaus = f.file(DANAUS);
        AtomicInteger moves = new AtomicInteger();
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(),
                UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").rename("A6", "Danaus melanippus").build()), f.clock,
                (from, to) -> {
                    SpeciesFiles.moveFile(from, to);
                    if (moves.incrementAndGet() == 2) {
                        try {
                            Files.setPosixFilePermissions(danaus, PosixFilePermissions.fromString("r-xr-xr-x"));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }, path -> Long.MAX_VALUE);
        updates.startup();
        try {
            updates.apply();
            assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        } finally {
            Files.setPosixFilePermissions(danaus, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        Result result = updates.update().result();
        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(result.notRemoved()).containsExactly(DANAUS + "Danaus_genutia_[A6]/");
        assertThat(result.keptFolders()).isEmpty();
        assertThat(f.rows("SELECT json_extract(payload_json, '$.notRemoved') FROM change_log WHERE kind = 'taxonomy.done'"))
                .containsExactly("[\"" + DANAUS + "Danaus_genutia_[A6]/\"]");
    }

    @Test
    void aFolderThatCannotBeReadIsListedNotRemovedAndTheUpdateFinishes() throws Exception {
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(),
                UpdateBundles.derive(UpdateFixture.v1(), "2026-02-01").rename("A6", "Danaus melanippus").build()), f.clock);
        updates.startup();
        Folders.reading = folder -> {
            if (folder.getFileName().toString().equals("Danaus_genutia_[A6]")) {
                throw new DirectoryIteratorException(new IOException("Input/output error"));
            }
        };
        try {
            updates.apply();
            assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        } finally {
            Folders.reading = folder -> { };
        }

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(updates.update().result().notRemoved()).containsExactly(DANAUS + "Danaus_genutia_[A6]/");
        assertThat(f.library.gate().isClosed()).isFalse();
    }

    @Test
    void aResultThatCannotBeReadIsAResultProblemTheGateOpen() throws Exception {
        f.sql("CREATE TRIGGER spoil AFTER INSERT ON change_log WHEN NEW.kind = 'taxonomy.done' BEGIN"
                + " UPDATE change_log SET payload_json = json_set(NEW.payload_json, '$.keptFolders', 5) WHERE id = NEW.id; END");
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        UpdateViews.Update update = updates.update();
        assertThat(update.state()).isEqualTo("done");
        assertThat(update.result()).isNull();
        assertThat(update.resultProblem()).startsWith("The update finished, but its summary of what changed couldn't be read (")
                .endsWith(").");
        assertThat(f.library.gate().isClosed()).isFalse();

        Updates restarted = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        restarted.startup();
        assertThat(restarted.update().state()).isEqualTo("none");
        assertThat(restarted.update().last()).isNull();
        assertThat(restarted.update().lastProblem())
                .startsWith("The last update's summary of what changed couldn't be read (").endsWith(").");
    }
}
