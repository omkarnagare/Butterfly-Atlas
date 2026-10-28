package com.butterflyatlas.app.web;

import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.testing.UpdateBundles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /api/status} never touches the database, even on its very first poll after a start with an update pending: on
 * a drive filled to the last byte (every SQL call fails) it still answers, with {@code library} drive_full for the
 * app-wide banner (5b contract §7).
 */
class StatusDriveFullTest {

    @TempDir
    Path home;

    private AtlasServices services;
    private StatusRoutes status;

    @AfterEach
    void close() {
        // The status's read and the place note's count under way end before the test's folder is deleted.
        status.close();
        services.placeNotes().close();
        services.updates().close();
        services.imports().close();
        services.exports().close();
        services.thumbnails().close();
    }

    @Test
    void theFirstPollAfterAStartOnAFullDriveRunsNoSqlAndSaysTheDriveIsFull() throws Exception {
        AtlasServices first = TestServices.configured(home);
        Photo photo = first.photos().add("A5", new ByteArrayInputStream(TestImages.jpeg(40, 30, 1)), "image/jpeg", null,
                List.of()).photo();
        first.photos().delete(List.of(photo.id()));
        first.imports().close();
        first.exports().close();
        first.thumbnails().close();
        LibraryHolder holder = new LibraryHolder();
        services = TestServices.configured(home, UpdateBundles.bundles(UpdateBundles.fixture(),
                UpdateBundles.derive(UpdateBundles.fixture(), "2026-02-01").rename("A6", "Danaus melanippus").build()), holder,
                path -> 0);
        AtomicInteger opened = TestDatabases.failEverythingCounting(holder.library().database(), Thread.currentThread());
        status = new StatusRoutes(services.rescan(), services.thumbnails(), services.photos(),
                services.processing(), services.imports(), services.exports(), services.updates(), services.drive(),
                services.placeNotes());

        StatusRoutes.Status now = status.status();
        StatusRoutes.Status again = status.status();

        assertThat(opened.get()).as("no poll touches the database").isZero();
        assertThat(now.library().code()).isEqualTo("drive_full");
        assertThat(now.taxonomyUpdate().state()).isEqualTo("pending");
        assertThat(now.deletedCount()).isEqualTo(1);
        assertThat(now.stoppedImports()).isEmpty();
        assertThat(now.export()).isNull();
        assertThat(again).isEqualTo(now);

        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread restore = new Thread(() -> {
            try {
                services.photos().restore(List.of(photo.id()));
            } catch (RuntimeException e) {
                failed.set(e);
            }
        });
        restore.start();
        restore.join();
        assertThat(failed.get()).as("the write fails: the count is forgotten").isNotNull();
        assertThat(services.photos().deletedCountKept()).isNull();
        StatusRoutes.Status after = status.status();
        assertThat(opened.get()).as("still no SQL on the polling thread").isZero();
        assertThat(after.deletedCount()).as("the last known count").isEqualTo(1);
        assertThat(after.library().code()).isEqualTo("drive_full");
    }
}
