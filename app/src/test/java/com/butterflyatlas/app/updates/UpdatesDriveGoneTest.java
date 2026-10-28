package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.taxa.TaxonSnapshot;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A drive unplugged mid-update takes the library's database with it: the moves done cannot be recorded. The update pauses
 * {@code drive_gone} (in memory), and Retry once the drive is back carries on without a restart (review J17).
 */
class UpdatesDriveGoneTest {

    @TempDir
    Path dir;

    private UpdateFixture f;
    private final AtomicBoolean unplugged = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        f = new UpdateFixture(dir);
        f.populated();
        TestDatabases.beforeCommit(f.db, () -> {
            if (unplugged.get()) {
                throw new IllegalStateException("disk I/O error");
            }
        });
    }

    /** A mover that runs {@code after} once its second move is done. */
    private Updates updates(Runnable after) {
        AtomicInteger calls = new AtomicInteger();
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock,
                (from, to) -> {
                    SpeciesFiles.moveFile(from, to);
                    if (calls.incrementAndGet() == 2) {
                        after.run();
                    }
                }, path -> Long.MAX_VALUE);
        updates.startup();
        return updates;
    }

    private void moveImages(String from, String to) {
        try {
            Files.move(f.library.folder().resolve(from), f.library.folder().resolve(to));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void everyPhotoIsWhereItsRowSays() {
        java.util.Map<String, String> folders = f.db.read(c -> {
            TaxonSnapshot snapshot = TaxonSnapshot.load(c);
            java.util.Map<String, String> byId = new java.util.HashMap<>();
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
    void theDriveGoneWithTheDatabasePausesTheUpdateAndRetryCarriesOnOnceItIsBack() throws Exception {
        Updates updates = updates(() -> {
            moveImages("images", "images_away");
            unplugged.set(true);
        });

        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().notice()).isEqualTo(new UpdateViews.Notice("drive_gone",
                "The drive with your library can't be found. Reconnect it, then choose Retry."));
        assertThatThrownBy(updates::retry).as("still gone").isInstanceOfSatisfying(com.butterflyatlas.app.AtlasException.class,
                e -> assertThat(e.code()).isEqualTo("not_resumed"));
        assertThat(updates.update().state()).isEqualTo("paused");

        unplugged.set(false);
        moveImages("images_away", "images");
        updates.retry();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(f.rows("SELECT done, total FROM taxonomy_update")).containsExactly("5|5");
        everyPhotoIsWhereItsRowSays();
    }

    @Test
    void aDatabaseThatCannotBeWrittenWithTheDriveThereIsAFailure() throws Exception {
        Updates updates = updates(() -> unplugged.set(true));

        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(updates.update().notice().code()).isEqualTo("failed");
        unplugged.set(false);
        updates.retry();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(updates.update().state()).isEqualTo("done");
        everyPhotoIsWhereItsRowSays();
    }
}
