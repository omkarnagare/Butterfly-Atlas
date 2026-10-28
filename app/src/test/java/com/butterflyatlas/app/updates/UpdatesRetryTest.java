package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.photos.SpeciesFiles;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Retry that does not take is 409 {@code not_resumed} with why and what to do, never a 202 that changes nothing; while
 * paused, refused changes say the update is paused (5b contract §7).
 */
class UpdatesRetryTest {

    @TempDir
    Path dir;

    private UpdateFixture f;
    private Updates updates;
    private final AtomicBoolean unwritable = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        f = new UpdateFixture(dir);
        f.populated();
        TestDatabases.failCommits(f.db, () -> unwritable.get() ? new SQLException("disk I/O error") : null);
        AtomicInteger calls = new AtomicInteger();
        updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock,
                (from, to) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new AccessDeniedException(from.toString());
                    }
                    SpeciesFiles.moveFile(from, to);
                }, path -> Long.MAX_VALUE);
        updates.startup();
        updates.apply();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(updates.update().state()).isEqualTo("paused");
    }

    private void notResumed(String message) {
        assertThatThrownBy(updates::retry).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("not_resumed");
            assertThat(e.getMessage()).isEqualTo(message);
        });
        assertThat(updates.update().state()).isEqualTo("paused");
    }

    @Test
    void aRetryThatCannotBeRecordedSaysWhyAndWhatToDo() throws Exception {
        unwritable.set(true);
        notResumed("Tried again, but the library couldn't be written (disk I/O error). Choose Retry again.");

        Files.move(f.library.folder().resolve("images"), f.library.folder().resolve("images_away"));
        notResumed("Tried again, but the drive with your library still can't be found. Reconnect it, then choose Retry.");

        Files.move(f.library.folder().resolve("images_away"), f.library.folder().resolve("images"));
        unwritable.set(false);
        assertThat(updates.retry().state()).isEqualTo("running");
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(updates.update().state()).isEqualTo("done");
    }

    @Test
    void whilePausedARefusalSaysSoAndWhileRunningItSaysWait() throws Exception {
        assertThatThrownBy(() -> f.add("A5")).isInstanceOfSatisfying(AtlasException.class, e -> assertThat(e.getMessage())
                .isEqualTo("The taxonomy update is paused, so this can't be done yet. Open Taxonomy update to see why, then"
                        + " choose Retry."));
        updates.beforeWork = () -> {
            try {
                f.add("A5");
            } catch (AtlasException e) {
                f.sql("INSERT INTO change_log (at, kind, payload_json) VALUES ('t', 'test.refusal', '" + e.getMessage()
                        .replace("'", "''") + "')");
            }
        };

        updates.retry();
        assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(f.rows("SELECT payload_json FROM change_log WHERE kind = 'test.refusal'"))
                .containsExactly("Wait for the taxonomy update to finish.");
    }
}
