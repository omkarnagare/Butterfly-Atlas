package com.butterflyatlas.app.updates;

import com.butterflyatlas.app.photos.ConsumerRunner;
import com.butterflyatlas.app.photos.PhotoIngest;
import com.butterflyatlas.app.photos.Rescan;
import com.butterflyatlas.app.photos.StartupWork;
import com.butterflyatlas.app.photos.Thumbnails;
import com.butterflyatlas.app.photos.consumers.Dimensions;
import com.butterflyatlas.app.testing.HoldingProcessor;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** An update asked for while the startup's rescan runs waits for it ({@code waitingFor} "startup"; review J15). */
class UpdatesStartupTest {

    private static final String GENUTIA = "images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_genutia_[A6]/";

    @TempDir
    Path dir;

    @Test
    void anApplyBetweenTheRescansReadsAndItsWalkWaitsForTheStartupFiles() throws Exception {
        UpdateFixture f = new UpdateFixture(dir);
        f.populated();
        // A file copied in by hand: the rescan reads it (and is held there) after it has read the photos' rows.
        Path copied = f.file(GENUTIA + "copied.jpg");
        Files.write(copied, TestImages.jpeg(70, 50, 4242));
        Files.setLastModifiedTime(copied, FileTime.from(Instant.now().minusSeconds(3600)));
        HoldingProcessor processor = new HoldingProcessor();
        processor.hold((call, file) -> call.equals("read") && file.getFileName().toString().equals("copied.jpg"));
        Updates updates = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        updates.holdForStartup();
        updates.startup();
        try (Thumbnails thumbnails = new Thumbnails(() -> f.library, processor)) {
            Rescan rescan = new Rescan(() -> f.library, processor, f.clock, id -> { });
            ConsumerRunner processing = new ConsumerRunner(() -> f.library, new PhotoIngest(processor), List.of(new Dimensions()),
                    thumbnails, f.clock);
            Thread startup = new StartupWork(rescan, f.photos, processing, thumbnails, updates::awaitContinuation,
                    updates::startupFilesSettled).start();
            assertThat(processor.awaitHeld()).isTrue();

            updates.apply();
            Thread.sleep(300);

            assertThat(updates.status().orElseThrow().state()).isEqualTo("waiting");
            assertThat(updates.status().orElseThrow().waitingFor()).isEqualTo("startup");
            assertThat(f.rows("SELECT stage, total FROM taxonomy_update")).as("nothing planned yet").containsExactly("plan|0");
            processor.release();
            startup.join(30_000);
            assertThat(updates.awaitIdle(Duration.ofSeconds(30))).isTrue();
        }

        assertThat(updates.update().state()).isEqualTo("done");
        assertThat(f.rows("SELECT file_name, source FROM image WHERE taxon_id = 'A6' AND deleted_at IS NULL ORDER BY id"))
                .as("the rescan's photo, then moved with the others").containsExactly("Danaus_melanippus_01.jpg|app",
                        "Danaus_melanippus_02.jpg|app", "copied.jpg|rescan");
        assertThat(f.rows("SELECT COUNT(*), COUNT(DISTINCT rel_path) FROM image WHERE deleted_at IS NULL"))
                .containsExactly("12|12");
        for (String rel : f.rows("SELECT rel_path FROM image WHERE deleted_at IS NULL")) {
            assertThat(f.file(rel)).as(rel).isRegularFile();
        }
    }

    @Test
    void theStartupsOwnContinuationDoesNotWaitForTheFilesItRunsBefore() throws Exception {
        UpdateFixture f = new UpdateFixture(dir);
        f.populated();
        Updates first = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock,
                (from, to) -> {
                    throw new java.nio.file.AccessDeniedException(from.toString());
                }, path -> Long.MAX_VALUE);
        first.startup();
        first.apply();
        assertThat(first.awaitIdle(Duration.ofSeconds(30))).isTrue();
        f.sql("UPDATE taxonomy_update SET state = 'running', notice_code = NULL, notice = NULL");

        Updates next = new Updates(() -> f.library, UpdateBundles.bundles(UpdateFixture.v1(), UpdateFixture.v2()), f.clock);
        next.holdForStartup();
        next.startup();
        next.awaitContinuation();

        assertThat(next.update().state()).isEqualTo("done");
    }
}
