package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Light or Dark choice over HTTP (Meadow UI contract §2, §3). */
class AppearanceApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private AtlasServices services;
    private AtlasServer server;

    @BeforeEach
    void start() {
        services = TestServices.unconfigured(home);
        server = AtlasServer.start(0, services);
    }

    @AfterEach
    void stop() {
        server.close();
        services.exports().close();
        services.thumbnails().close();
    }

    private Map<String, String> own() {
        return Map.of("Origin", "http://127.0.0.1:" + server.port(), "Content-Type", "application/json");
    }

    private RawHttp.Response get() throws Exception {
        return RawHttp.send(server.port(), "GET", "/api/appearance", Map.of(), null);
    }

    private RawHttp.Response put(String body) throws Exception {
        return RawHttp.send(server.port(), "PUT", "/api/appearance", own(), body);
    }

    private void chooseLibrary() throws Exception {
        String folder = home.resolve("Atlas").toString().replace("\\", "\\\\");
        assertThat(RawHttp.send(server.port(), "POST", "/api/setup", own(), "{\"dataFolder\":\"" + folder + "\"}").status())
                .isEqualTo(200);
    }

    private void assertError(RawHttp.Response response, int status, String code, String message) throws Exception {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(error.get("message").asText()).isEqualTo(message);
    }

    @Test
    void answersNullBeforeSetupAndBeforeAnyChoice() throws Exception {
        assertThat(get().body()).isEqualTo("{\"theme\":null}");

        chooseLibrary();

        assertThat(get().body()).isEqualTo("{\"theme\":null}");
    }

    @Test
    void savesTheChoiceAnswersItAndKeepsTheLibraryFolder() throws Exception {
        chooseLibrary();

        RawHttp.Response saved = put("{\"theme\":\"dark\"}");

        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body()).isEqualTo("{\"theme\":\"dark\"}");
        assertThat(get().body()).isEqualTo("{\"theme\":\"dark\"}");
        assertThat(new ConfigStore(home.resolve("config")).load())
                .contains(new AppConfig(home.resolve("Atlas").toString(), "dark"));
        assertThat(put("{\"theme\":\"light\"}").body()).isEqualTo("{\"theme\":\"light\"}");
        assertThat(get().body()).isEqualTo("{\"theme\":\"light\"}");
    }

    @Test
    void refusesAnythingButLightOrDark() throws Exception {
        chooseLibrary();
        for (String body : new String[] {"{\"theme\":\"blue\"}", "{\"theme\":\"Dark\"}", "{\"theme\":\"\"}",
                "{\"theme\":null}", "{}", "", "not json", "{\"theme\":5}"}) {
            assertError(put(body), 400, "invalid", "Choose Light or Dark.");
        }
        assertThat(get().body()).isEqualTo("{\"theme\":null}");
    }

    @Test
    void refusesAChoiceBeforeALibraryIsChosen() throws Exception {
        assertError(put("{\"theme\":\"dark\"}"), 409, "not_set_up", "Choose where to keep your photos first.");
        assertThat(get().body()).isEqualTo("{\"theme\":null}");
        assertThat(home.resolve("config").resolve(ConfigStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void aFailedWriteIsSaidWithItsReasonAndTheChoiceStaysAsItWas() throws Exception {
        chooseLibrary();
        assertThat(put("{\"theme\":\"light\"}").status()).isEqualTo(200);
        // a folder where the temporary file goes: the write fails (as on a drive that will not take it)
        Path blocker = Files.createDirectories(home.resolve("config").resolve(ConfigStore.FILE_NAME + ".tmp"));
        Files.writeString(blocker.resolve("x"), "x");

        RawHttp.Response failed = put("{\"theme\":\"dark\"}");

        assertThat(failed.status()).isEqualTo(500);
        JsonNode error = JSON.readTree(failed.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("save_failed");
        assertThat(error.get("message").asText()).startsWith("Couldn't save the appearance: ").doesNotContain("/");
        assertThat(get().body()).isEqualTo("{\"theme\":\"light\"}");
    }

    @Test
    void aChangeFromAnotherSiteIsRefusedAndNothingIsSaved() throws Exception {
        chooseLibrary();

        RawHttp.Response response = RawHttp.send(server.port(), "PUT", "/api/appearance",
                Map.of("Origin", "http://evil.example", "Content-Type", "application/json"), "{\"theme\":\"dark\"}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(get().body()).isEqualTo("{\"theme\":null}");
    }

    @Test
    void aChangeFromAnotherHostIsRefused() throws Exception {
        RawHttp.Response response = RawHttp.send(server.port(), "GET", "/api/appearance", Map.of("Host", "evil.example"),
                null);

        assertThat(response.status()).isEqualTo(403);
    }

    private JsonNode status() throws Exception {
        RawHttp.Response response = RawHttp.send(server.port(), "GET", "/api/status", Map.of(), null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Test
    void theStatusCarriesTheChoiceFromMemory() throws Exception {
        chooseLibrary();
        assertThat(status().get("theme").isNull()).isTrue();

        put("{\"theme\":\"dark\"}");

        assertThat(status().get("theme").asText()).isEqualTo("dark");
    }
}
