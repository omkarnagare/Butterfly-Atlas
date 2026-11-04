package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.library.SetupService;
import com.butterflyatlas.app.sync.LibrarySync;
import com.butterflyatlas.app.sync.Marker;
import com.butterflyatlas.app.sync.MarkerFile;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestClock;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.TestServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The setup routes of one device at a time (one-device contract §1): {@code GET /api/setup} answers {@code otherDevice}
 * and keeps answering it; {@code POST /api/setup/retry} runs the checks again and opens the library once it can;
 * {@code POST /api/setup/open-anyway} is 409 {@code too_soon} before {@code openAnywayFrom} and opens after it; 409
 * {@code not_on_other_device} and {@code nothing_discarded} when nothing waits.
 */
class OneDeviceSetupApiTest {

    @TempDir
    Path home;

    @Test
    void theRefusalIsAnsweredUntilTryAgainOrOpenHereAnywayChangesIt() throws Exception {
        TestClock clock = new TestClock();
        Path folder = Files.createDirectories(home.resolve("Atlas"));
        Instant seen = clock.instant();
        // a library another device has open (its marker came through; its database is a new library's)
        new SetupService(new ConfigStore(home.resolve("other")), new LibraryHolder(), TestBundles.fixture(),
                TestPlaces.bundles(), home, clock, null, new LibrarySync(home.resolve("other"), () -> "DESKTOP-PUNE", clock,
                millis -> { })).setup(folder.toString());
        AtlasServices base = TestServices.unconfigured(home);
        SetupService setup = new SetupService(new ConfigStore(home.resolve("config")), new LibraryHolder(),
                TestBundles.fixture(), TestPlaces.bundles(), home, clock, null,
                new LibrarySync(home.resolve("config"), () -> "Omkar's MacBook Air", clock, millis -> { }));
        AtlasServices services = new AtlasServices(setup, base.taxa(), base.searchWords(), base.search(), base.photos(),
                base.photoQueries(), base.tags(), base.thumbnails(), base.rescan(), base.processing(), base.folders(),
                base.imports(), base.placeSearch(), base.places(), base.photoPlaces(), base.filters(), base.exports(),
                base.custom(), base.updates(), base.splits(), base.drive(), base.placeNotes(), base.backups(), base.heic(),
                base.change());
        try (AtlasServer server = AtlasServer.start(0, services)) {
            Map<String, String> headers = Map.of("Origin", "http://127.0.0.1:" + server.port(), "Content-Type",
                    "application/json");
            clock.advance(Duration.ofMinutes(2));
            RawHttp.Response chosen = RawHttp.send(server.port(), "POST", "/api/setup", headers,
                    "{\"dataFolder\":\"" + folder.toString().replace("\\", "\\\\") + "\"}");
            assertThat(chosen.status()).isEqualTo(200);
            String other = "\"otherDevice\":{\"name\":\"DESKTOP-PUNE\",\"seen\":\"" + seen + "\",\"openAnywayFrom\":\""
                    + seen.plus(Duration.ofMinutes(15)) + "\"}";
            assertThat(chosen.body()).contains("\"configured\":false", "\"problem\":null", other,
                    "\"notSynced\":null", "\"discarded\":null");

            RawHttp.Response status = RawHttp.send(server.port(), "GET", "/api/setup", headers, null);
            assertThat(status.body()).contains(other);

            RawHttp.Response retried = RawHttp.send(server.port(), "POST", "/api/setup/retry", headers, "{}");
            assertThat(retried.status()).isEqualTo(200);
            assertThat(retried.body()).contains(other);

            RawHttp.Response early = RawHttp.send(server.port(), "POST", "/api/setup/open-anyway", headers, "{}");
            assertThat(early.status()).isEqualTo(409);
            assertThat(early.body()).contains("\"code\":\"too_soon\"", "You can open the library here from ");
            assertThat(RawHttp.send(server.port(), "GET", "/api/setup", headers, null).body()).contains(other);

            RawHttp.Response nothing = RawHttp.send(server.port(), "POST", "/api/setup/discarded/continue", headers, "{}");
            assertThat(nothing.status()).isEqualTo(409);
            assertThat(nothing.body()).contains("\"code\":\"nothing_discarded\"");

            clock.advance(Duration.ofMinutes(13));
            RawHttp.Response opened = RawHttp.send(server.port(), "POST", "/api/setup/open-anyway", headers, "{}");
            assertThat(opened.status()).isEqualTo(200);
            assertThat(opened.body()).contains("\"configured\":true", "\"otherDevice\":null");
            Marker marker = MarkerFile.read(folder).marker();
            assertThat(marker.name()).isEqualTo("Omkar's MacBook Air");

            RawHttp.Response again = RawHttp.send(server.port(), "POST", "/api/setup/open-anyway", headers, "{}");
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.body()).contains("\"code\":\"not_on_other_device\"");
        } finally {
            base.exports().close();
            base.thumbnails().close();
        }
    }

    @Test
    void tryAgainOpensTheLibraryOnceTheOtherDeviceHasClosedIt() throws Exception {
        TestClock clock = new TestClock();
        Path folder = Files.createDirectories(home.resolve("Atlas"));
        new SetupService(new ConfigStore(home.resolve("other")), new LibraryHolder(), TestBundles.fixture(),
                TestPlaces.bundles(), home, clock, null, new LibrarySync(home.resolve("other"), () -> null, clock,
                millis -> { })).setup(folder.toString());
        SetupService setup = new SetupService(new ConfigStore(home.resolve("config")), new LibraryHolder(),
                TestBundles.fixture(), TestPlaces.bundles(), home, clock, null,
                new LibrarySync(home.resolve("config"), () -> null, clock, millis -> { }));
        assertThat(setup.setup(folder.toString()).otherDevice().name()).isNull(); // "another device"

        MarkerFile.write(folder, MarkerFile.read(folder).marker().closedAt(clock.instant()));

        assertThat(setup.status().otherDevice()).isNotNull(); // nothing re-read until Try again
        SetupService.Status opened = setup.retry();
        assertThat(opened.configured()).isTrue();
        assertThat(opened.otherDevice()).isNull();
    }
}
