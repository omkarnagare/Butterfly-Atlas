package com.butterflyatlas.app;

import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.exports.ExportStore;
import com.butterflyatlas.app.exports.Exports;
import com.butterflyatlas.app.exports.TestExports;
import com.butterflyatlas.app.imports.Imports;
import com.butterflyatlas.app.imports.TestImports;
import com.butterflyatlas.app.testing.HoldingProcessor;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.web.AtlasServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A normal quit closes the imports and the export's copy while the library is still there, so the import working then is
 * reported stopped and the export's current file is saved and the export paused (closing) at the next start.
 */
class MainShutdownTest {

    @TempDir
    Path home;

    @Test
    void quittingMidReadingLeavesTheImportStoppedForTheNextStart() throws Exception {
        HoldingProcessor processor = new HoldingProcessor();
        AtlasServices services = TestServices.configured(home, processor);
        AtlasServer server = AtlasServer.start(0, services);
        Path photos = Files.createDirectories(home.resolve("Pictures").resolve("Butterflies 2024"));
        Files.write(photos.resolve("IMG_0001.JPG"), TestImages.jpeg(640, 480, 1));
        Files.write(photos.resolve("IMG_0002.JPG"), TestImages.jpeg(640, 480, 2));
        processor.hold((call, file) -> call.equals("thumbnail") && file.startsWith(photos));
        long id = services.imports().start(photos.toString()).summary().id();
        assertThat(processor.awaitHeld()).isTrue();

        Thread quit = new Thread(() -> Main.shutdown(server, services), "test-quit");
        quit.start();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!TestImports.closing(services.imports()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(TestImports.closing(services.imports())).as("the quit closes the imports").isTrue();
        processor.release();
        quit.join(30_000);
        assertThat(quit.isAlive()).isFalse();

        AtlasServices next = TestServices.configured(home);
        try (Imports imports = next.imports()) {
            imports.startup();
            List<Imports.Working> stopped = imports.stopped();
            assertThat(stopped).hasSize(1);
            assertThat(stopped.get(0).id()).isEqualTo(id);
            assertThat(stopped.get(0).phase()).isEqualTo("reading");
        } finally {
            next.thumbnails().close();
        }
    }

    @Test
    void quittingMidCopyWaitsForTheCurrentFileSavesItAndTheNextStartPausesTheExport() throws Exception {
        AtlasServices services = TestServices.configured(home);
        AtlasServer server = AtlasServer.start(0, services);
        CountDownLatch release = new CountDownLatch(1);
        try {
            quitMidCopy(services, server, release);
        } finally {
            release.countDown();
            server.close(); // closed already by the quit, unless the test failed before it
            services.exports().close();
            services.thumbnails().close();
        }
    }

    private void quitMidCopy(AtlasServices services, AtlasServer server, CountDownLatch release) throws Exception {
        Database db = Database.open(home.resolve("Atlas").resolve(Database.FILE_NAME));
        for (int n = 1; n <= 3; n++) {
            long photo = TestBundles.addImage(db, "A5");
            Path file = Files.createDirectories(home.resolve("Atlas/images/test")).resolve("photo_" + photo + ".jpg");
            Files.write(file, TestImages.jpeg(16, 12, n));
            long size = Files.size(file);
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE image SET rel_path = ?, file_name = ?, file_size = ?"
                        + " WHERE id = ?")) {
                    ps.setString(1, "images/test/" + file.getFileName());
                    ps.setString(2, file.getFileName().toString());
                    ps.setLong(3, size);
                    ps.setLong(4, photo);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        CountDownLatch inFile = new CountDownLatch(1);
        TestExports.beforePhoto(services.exports(), item -> {
            if (item.seq() == 2) {
                inFile.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Path dest = Files.createDirectories(home.resolve("Backup"));
        long id = services.exports().start(new Exports.Request("", "photos", dest.toString(), null, false, null)).id();
        assertThat(inFile.await(20, TimeUnit.SECONDS)).isTrue();

        Thread quit = new Thread(() -> Main.shutdown(server, services), "test-quit");
        quit.start();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!TestExports.stopAsked(services.exports(), id) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(TestExports.stopAsked(services.exports(), id)).as("the quit asks the copy to stop").isTrue();
        quit.join(300);
        assertThat(quit.isAlive()).as("the quit waits for the current file").isTrue();
        release.countDown();
        quit.join(30_000);

        assertThat(quit.isAlive()).isFalse();
        ExportStore.Run cut = services.exports().store().run(id);
        assertThat(List.of(cut.state(), String.valueOf(cut.done()))).as("the current file finished and was saved")
                .containsExactly("running", "2");
        AtlasServices next = TestServices.configured(home);
        try (Exports exports = next.exports()) {
            assertThat(exports.startup()).isEqualTo(1);
            assertThat(exports.store().run(id)).extracting(ExportStore.Run::state, ExportStore.Run::pausedBy)
                    .containsExactly("paused", "closing");
        } finally {
            next.thumbnails().close();
        }
    }
}
