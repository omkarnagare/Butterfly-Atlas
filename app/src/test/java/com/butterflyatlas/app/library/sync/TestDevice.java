package com.butterflyatlas.app.library.sync;

import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;

/**
 * One device in a two-device test (one-device design §11): its own config folder (so its own device ID and working
 * copies), its own setup, and a library folder of its own that {@link #sync} keeps in step with another device's, as a
 * sync service would: late, and file by file in any order.
 */
final class TestDevice {

    final Path config;
    final LibraryHolder holder = new LibraryHolder();
    final LibrarySync sync;
    final SetupService setup;

    final ManualTicker ticker;

    TestDevice(Path home, Path config, String name, Clock clock) {
        this.config = config;
        this.ticker = new ManualTicker(clock);
        this.sync = new LibrarySync(config, () -> name, clock, millis -> { }, ticker);
        holder.whenOpened(sync::start);
        this.setup = new SetupService(new ConfigStore(config), holder, TestBundles.fixture(), TestPlaces.bundles(), home,
                clock, null, sync);
    }

    /** The same device started again (after a quit or a crash): the same config folder, new services. */
    TestDevice restarted(Path home, String name, Clock clock) {
        return new TestDevice(home, config, name, clock);
    }

    Database db() {
        return holder.get();
    }

    /** One write, as any change in the app makes. */
    void write(String key) {
        db().write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT OR REPLACE INTO library_setting (key, value) VALUES ('" + key + "', 'x')");
            }
            return null;
        });
    }

    boolean has(String key) {
        return db().read(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM library_setting WHERE key = '" + key + "'")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        });
    }

    /** The sync service: copies these files of one library folder into another (missing ones are deleted there). */
    static void sync(Path from, Path to, String... names) throws IOException {
        Files.createDirectories(to);
        for (String name : names) {
            Path source = from.resolve(name);
            Path target = to.resolve(name);
            if (Files.exists(source)) {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(target);
            }
        }
    }

    /** Syncs the database and the marker, the database first. */
    static void syncAll(Path from, Path to) throws IOException {
        sync(from, to, Database.FILE_NAME, MarkerFile.NAME);
    }
}
