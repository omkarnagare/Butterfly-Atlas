package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        http.close();
    }

    private void startConfigured() {
        server = AtlasServer.start(0, TestServices.configured(home));
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", "http://127.0.0.1:" + server.port());
        if (json != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).as(path + " → " + response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void firstRunSetupThenBrowse() throws Exception {
        server = AtlasServer.start(0, TestServices.unconfigured(home));
        Path folder = home.resolve("My Atlas");

        HttpResponse<String> early = send("GET", "api/summary", null);
        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(early.body()).contains("\"code\":\"setup_required\"");

        JsonNode status = get("api/setup");
        assertThat(status.get("configured").asBoolean()).isFalse();
        assertThat(status.get("suggestedFolder").asText()).endsWith("Butterfly_Atlas");

        JsonNode check = get("api/setup/inspect?path=" + encode(folder.toString()));
        assertThat(check.get("exists").asBoolean()).isFalse();
        assertThat(check.get("writable").asBoolean()).isTrue();

        HttpResponse<String> setup = send("POST", "api/setup",
                JSON.writeValueAsString(JSON.createObjectNode().put("dataFolder", folder.toString())));
        assertThat(setup.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(setup.body()).get("configured").asBoolean()).isTrue();

        assertThat(get("api/summary").get("families").size()).isEqualTo(2);
    }

    @Test
    void setupExplainsABadFolder() throws Exception {
        server = AtlasServer.start(0, TestServices.unconfigured(home));

        HttpResponse<String> response = send("POST", "api/setup", "{\"dataFolder\":\"relative/folder\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).at("/error/message").asText()).startsWith("Enter the full path of a folder");
    }

    @Test
    void invalidJsonIsABadRequest() throws Exception {
        server = AtlasServer.start(0, TestServices.unconfigured(home));

        HttpResponse<String> response = send("POST", "api/setup", "not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).at("/error/message").asText()).isEqualTo("The request was not valid JSON.");
    }

    @Test
    void summaryAndTaxonDetails() throws Exception {
        startConfigured();

        JsonNode summary = get("api/summary");
        assertThat(summary.at("/group/species/total").asInt()).isEqualTo(6);
        assertThat(ids(summary.get("families"))).containsExactly("A1", "A9");

        JsonNode plainTiger = get("api/taxa/A5");
        assertThat(plainTiger.get("common").asText()).isEqualTo("Plain Tiger");
        assertThat(plainTiger.get("folder").asText()).endsWith("Danaus_chrysippus_[A5]/");

        assertThat(plainTiger.get("source").asText()).isEqualTo("official");
        assertThat(plainTiger.get("familyId").isNull()).isTrue();

        assertThat(get("api/taxa/G%3Abutterflies").get("rank").asText()).isEqualTo("group");
        JsonNode additions = get("api/taxa/U%3Abutterflies");
        assertThat(additions.get("name").asText()).isEqualTo("Custom additions");
        assertThat(additions.get("source").asText()).isEqualTo("system");
        assertThat(additions.at("/species/total").asInt()).isZero();
    }

    @Test
    void childrenOfATaxon() throws Exception {
        startConfigured();

        assertThat(ids(get("api/taxa/A3/children").get("children"))).containsExactly("A4", "A7");
        JsonNode group = get("api/taxa/G%3Abutterflies/children").get("children");
        assertThat(ids(group)).containsExactly("A1", "A9", "U:butterflies");
        assertThat(group.get(2).get("source").asText()).isEqualTo("system");
        assertThat(group.get(0).get("source").asText()).isEqualTo("official");
    }

    @Test
    void unknownTaxaAreNotFound() throws Exception {
        startConfigured();

        HttpResponse<String> response = send("GET", "api/taxa/A999", null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"not_found\"");
    }

    @Test
    void searchWordsCanBeAddedFoundAndRemoved() throws Exception {
        startConfigured();

        HttpResponse<String> added = send("POST", "api/taxa/A5/search-words", "{\"word\":\"Kaner / tiger\"}");
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(added.body()).get("words").get(0).asText()).isEqualTo("Kaner / tiger");

        assertThat(ids(get("api/search?q=kaner").get("results"))).first().isEqualTo("A5");

        HttpResponse<String> removed = send("DELETE", "api/taxa/A5/search-words?word=" + encode("Kaner / tiger"), null);
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(removed.body()).get("words").size()).isZero();
    }

    @Test
    void searchValidatesTheLimit() throws Exception {
        startConfigured();

        assertThat(send("GET", "api/search?q=tiger&limit=lots", null).statusCode()).isEqualTo(400);
        JsonNode found = get("api/search?q=tiger&limit=2");
        assertThat(found.get("query").asText()).isEqualTo("tiger");
        assertThat(found.get("results").size()).isEqualTo(2);
    }

    @Test
    void treeForTheMapView() throws Exception {
        startConfigured();

        JsonNode tree = get("api/tree");

        assertThat(tree.get("id").asText()).isEqualTo("G:butterflies");
        assertThat(ids(tree.get("children"))).containsExactly("A1", "A9");
    }

    @Test
    void badInputGetsPlainLanguageErrors() throws Exception {
        startConfigured();

        HttpResponse<String> empty = send("POST", "api/taxa/A5/search-words", "{}");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(empty.body()).at("/error/message").asText()).isEqualTo("Type a search word.");

        assertThat(send("POST", "api/taxa/A5/search-words", "{\"word\":\"x\",\"extra\":1}").statusCode()).isEqualTo(200);

        JsonNode found = get("api/search?q=tiger&limit=0");
        assertThat(found.get("results").size()).isEqualTo(1);
    }
}
