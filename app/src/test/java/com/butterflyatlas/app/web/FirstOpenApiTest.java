package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first run after a change of library folder over HTTP (change-library-folder contract §2, §3): Home's note after a
 * move, and the setup screen's old library instead of a new one that won't open.
 */
class FirstOpenApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private static void library(Path folder) {
        Library.open(folder, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
    }

    private static RawHttp.Response post(AtlasServer server, String path) throws Exception {
        return RawHttp.send(server.port(), "POST", path, Map.of("Origin", "http://127.0.0.1:" + server.port(),
                "Content-Type", "application/json"), "{}");
    }

    @Test
    void afterAMoveTheStatusSaysWhereTheLibraryIsUntilTheNoteIsDismissed() throws Exception {
        Path old = home.resolve("Old");
        Path moved = home.resolve("Moved");
        library(old);
        library(moved);
        new ConfigStore(home.resolve("config")).save(new AppConfig(moved.toString(), null, old.toString(), true, true));
        AtlasServices services = TestServices.restarted(home);
        try (AtlasServer server = AtlasServer.start(0, services)) {
            JsonNode status = JSON.readTree(RawHttp.send(server.port(), "GET", "/api/status", Map.of(), null).body());
            assertThat(status.get("libraryMoved").get("to").asText()).isEqualTo(moved.toString());

            assertThat(post(server, "/api/library/moved-note/dismiss").status()).isEqualTo(204);

            status = JSON.readTree(RawHttp.send(server.port(), "GET", "/api/status", Map.of(), null).body());
            assertThat(status.get("libraryMoved").isNull()).isTrue();
            RawHttp.Response none = post(server, "/api/setup/previous");
            assertThat(none.status()).isEqualTo(409);
            assertThat(none.body()).contains("\"code\":\"no_previous\"");
        } finally {
            services.exports().close();
            services.thumbnails().close();
        }
    }

    @Test
    void aNewLibraryThatWontOpenOffersTheOldOneOnTheSetupScreen() throws Exception {
        Path old = home.resolve("Old");
        library(old);
        Path moved = home.resolve("Unplugged");
        new ConfigStore(home.resolve("config")).save(new AppConfig(moved.toString(), null, old.toString(), true, true));
        AtlasServices services = TestServices.unconfigured(home);
        services.setup().startup(Optional.empty());
        try (AtlasServer server = AtlasServer.start(0, services)) {
            JsonNode setup = JSON.readTree(RawHttp.send(server.port(), "GET", "/api/setup", Map.of(), null).body());
            assertThat(setup.get("configured").asBoolean()).isFalse();
            assertThat(setup.get("previousFolder").asText()).isEqualTo(old.toString());
            assertThat(setup.get("movedCopy").asText()).isEqualTo(moved.toString());

            RawHttp.Response back = post(server, "/api/setup/previous");

            assertThat(back.status()).isEqualTo(200);
            JsonNode opened = JSON.readTree(back.body());
            assertThat(opened.get("configured").asBoolean()).isTrue();
            assertThat(opened.get("dataFolder").asText()).isEqualTo(old.toString());
            assertThat(opened.get("previousFolder").isNull()).isTrue();
        } finally {
            services.exports().close();
            services.thumbnails().close();
        }
    }
}
