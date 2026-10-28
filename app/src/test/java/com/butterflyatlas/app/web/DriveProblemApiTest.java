package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.db.TestDatabases;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that fails because the library's drive is full is 507 {@code drive_full}, never a generic 500, and
 * {@code /api/status} names the drive's problem for an app-wide banner (5b contract §7).
 */
class DriveProblemApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicLong free = new AtomicLong(Long.MAX_VALUE);
    private final AtomicBoolean failing = new AtomicBoolean();
    private AtlasServices services;
    private AtlasServer server;

    @BeforeEach
    void start() {
        LibraryHolder holder = new LibraryHolder();
        services = TestServices.configured(home, holder, path -> free.get());
        TestDatabases.failStatements(holder.library().database(), failing::get);
        server = AtlasServer.start(0, services);
    }

    @AfterEach
    void stop() {
        server.close();
        services.updates().close();
        services.imports().close();
        services.exports().close();
        services.thumbnails().close();
        services.placeNotes().close();
        http.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRequestThatFailsOnAFullDriveIs507DriveFullAndTheStatusSaysSo() throws Exception {
        assertThat(JSON.readTree(get("api/status").body()).get("library").isNull()).isTrue();
        free.set(0);
        failing.set(true);

        HttpResponse<String> tags = get("api/tags");

        assertThat(tags.statusCode()).isEqualTo(507);
        assertThat(tags.body()).isEqualTo("{\"error\":{\"code\":\"drive_full\",\"message\":\"The drive with your library is"
                + " full, so Butterfly Atlas can't read or save anything. Free some space, then try again.\"}}");
        JsonNode library = JSON.readTree(get("api/status").body()).get("library");
        assertThat(library.get("code").asText()).isEqualTo("drive_full");
        assertThat(library.get("message").asText()).startsWith("The drive with your library is full");
    }

    @Test
    void aRequestThatFailsWithSpaceToSpareIsStillTheGenericError() throws Exception {
        failing.set(true);

        HttpResponse<String> tags = get("api/tags");

        assertThat(tags.statusCode()).isEqualTo(500);
        assertThat(JSON.readTree(tags.body()).get("error").get("code").asText()).isEqualTo("internal");
    }
}
