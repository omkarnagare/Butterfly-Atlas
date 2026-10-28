package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;

    @BeforeEach
    void start() {
        services = TestServices.configured(home);
        server = AtlasServer.start(0, services);
    }

    @AfterEach
    void stop() {
        server.close();
        services.thumbnails().close();
        http.close();
    }

    private HttpResponse<String> get(String path, String site) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(10));
        if (site != null) {
            request.header("Sec-Fetch-Site", site);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String q(Path path) {
        return URLEncoder.encode(path.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void rootsAndAFolderAreListed() throws Exception {
        Path trip = Files.createDirectories(home.resolve("Pictures").resolve("Trip"));
        Files.createDirectories(trip.resolve("Day 1"));
        Files.write(trip.resolve("IMG_1.JPG"), new byte[] {1});

        HttpResponse<String> roots = get("api/fs/roots", null);
        assertThat(roots.statusCode()).isEqualTo(200);
        JsonNode first = JSON.readTree(roots.body()).get("roots").get(0);
        assertThat(first.get("kind").asText()).isEqualTo("home");
        assertThat(first.get("path").asText()).isEqualTo(home.toString());

        HttpResponse<String> list = get("api/fs/list?path=" + q(trip), "same-origin");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(list.body());
        assertThat(body.get("name").asText()).isEqualTo("Trip");
        assertThat(body.get("photos").asInt()).isEqualTo(1);
        assertThat(body.get("folders").get(0).toString())
                .isEqualTo("{\"name\":\"Day 1\",\"path\":" + JSON.writeValueAsString(trip.resolve("Day 1").toString())
                        + ",\"folders\":0,\"photos\":0}");
        assertThat(body.get("isLibrary").asBoolean()).isFalse();
        assertThat(body.get("openImport").isNull()).isTrue();
        assertThat(body.get("crumbs").get(0).get("name").asText()).isEqualTo("Pictures");
        assertThat(JSON.readTree(get("api/fs/list?path=" + q(home.resolve("Atlas")), null).body()).get("isLibrary").asBoolean())
                .isTrue();
    }

    @Test
    void errorsHaveTheirOwnCodes() throws Exception {
        HttpResponse<String> relative = get("api/fs/list?path=Pictures", null);
        assertThat(relative.statusCode()).isEqualTo(400);
        assertThat(relative.body()).isEqualTo(
                "{\"error\":{\"code\":\"bad_path\",\"message\":\"Type or paste the full path of a folder.\"}}");
        HttpResponse<String> missing = get("api/fs/list?path=" + q(home.resolve("nope")), null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"folder_not_found\"");
        assertThat(get("api/fs/list", null).statusCode()).isEqualTo(400);
    }

    @Test
    void anotherSiteCannotBrowseTheComputer() throws Exception {
        HttpResponse<String> response = get("api/fs/roots", "cross-site");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }
}
