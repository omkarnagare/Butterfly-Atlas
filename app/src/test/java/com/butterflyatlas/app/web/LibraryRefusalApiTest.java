package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.window.InstanceLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The setup route answers 409 {@code library_in_use} for a library another copy holds (contract §2). */
class LibraryRefusalApiTest {

    @TempDir
    Path home;

    @Test
    void settingUpALibraryAnotherCopyUsesIs409LibraryInUseWithThePlainMessage() throws Exception {
        Path folder = Files.createDirectories(home.resolve("Atlas"));
        InstanceLock.Result other = InstanceLock.acquire(folder);
        AtlasServices base = TestServices.unconfigured(home);
        SetupService setup = new SetupService(new ConfigStore(home.resolve("config")), new LibraryHolder(),
                TestBundles.fixture(), TestPlaces.bundles(), home, TestBundles.CLOCK, InstanceLock::acquire);
        AtlasServices services = new AtlasServices(setup, base.taxa(), base.searchWords(), base.search(), base.photos(),
                base.photoQueries(), base.tags(), base.thumbnails(), base.rescan(), base.processing(), base.folders(),
                base.imports(), base.placeSearch(), base.places(), base.photoPlaces(), base.filters(), base.exports(),
                base.custom(), base.updates(), base.splits(), base.drive(), base.placeNotes(), base.backups(), base.heic());
        try (AtlasServer server = AtlasServer.start(0, services)) {
            String origin = "http://127.0.0.1:" + server.port();
            RawHttp.Response response = RawHttp.send(server.port(), "POST", "/api/setup",
                    Map.of("Origin", origin, "Content-Type", "application/json"),
                    "{\"dataFolder\":\"" + folder.toString().replace("\\", "\\\\") + "\"}");

            assertThat(response.status()).isEqualTo(409);
            assertThat(response.body()).contains("\"code\":\"library_in_use\"")
                    .contains("Another copy of Butterfly Atlas is using this library.");
        } finally {
            other.lock().close();
            base.exports().close();
            base.thumbnails().close();
        }
    }
}
