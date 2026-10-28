package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.TestImages;
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

/** Custom species over HTTP (5a contract §3): every success and error, exact JSON where the contract fixes it. */
class CustomSpeciesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TIGER = "{\"id\":\"A5\",\"name\":\"Danaus chrysippus\",\"common\":\"Plain Tiger\"}";

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
        services.placeNotes().close(); // its count may still be reading the library (Windows can't delete an open file)
        http.close();
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return send(method, path, json, true);
    }

    private HttpResponse<String> send(String method, String path, String json, boolean origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20));
        if (origin) {
            request.header("Origin", "http://127.0.0.1:" + server.port());
        }
        if (json != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void answers(HttpResponse<String> response, int status, String body) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).isEqualTo(body);
    }

    private static void refused(HttpResponse<String> response, int status, String code, String message) throws Exception {
        answers(response, status, "{\"error\":{\"code\":" + JSON.writeValueAsString(code) + ",\"message\":"
                + JSON.writeValueAsString(message) + "}}");
    }

    private static JsonNode json(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private static String check(String name) {
        return "api/custom-species/check?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    @Test
    void aCustomSpeciesIsAddedRenamedAndDeleted() throws Exception {
        JsonNode added = json(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"neptis  sankara\","
                + "\"common\":\"Broad-banded Sailer\"}"), 201);
        assertThat(added.get("id").asText()).isEqualTo("U1");
        assertThat(added.get("name").asText()).isEqualTo("Neptis sankara");
        assertThat(added.get("common").asText()).isEqualTo("Broad-banded Sailer");
        assertThat(added.get("family").asText()).isEqualTo("Nymphalidae");
        assertThat(added.get("familyId").asText()).isEqualTo("A1");
        assertThat(added.get("source").asText()).isEqualTo("manual");
        assertThat(added.get("rank").asText()).isEqualTo("species");
        assertThat(added.get("folder").asText()).isEqualTo("images/Custom_additions/Neptis_sankara_[U1]/");
        assertThat(added.get("path").toString()).isEqualTo("[{\"id\":\"G:butterflies\",\"rank\":\"group\",\"name\":\"Butterflies\","
                + "\"common\":null},{\"id\":\"U:butterflies\",\"rank\":\"additions\",\"name\":\"Custom additions\",\"common\":null}]");
        assertThat(json(send("GET", "api/taxa/U1", null), 200)).isEqualTo(added);
        assertThat(json(send("GET", "api/search?q=sankara", null), 200).at("/results/0/source").asText()).isEqualTo("manual");
        assertThat(json(send("GET", "api/summary", null), 200).at("/families/2/id").asText()).isEqualTo("U:butterflies");

        HttpResponse<String> photo = http.send(HttpRequest.newBuilder(server.baseUri().resolve("api/taxa/U1/photos"))
                .timeout(Duration.ofSeconds(20)).header("Origin", "http://127.0.0.1:" + server.port())
                .header("Content-Type", "image/jpeg").POST(HttpRequest.BodyPublishers.ofByteArray(TestImages.jpeg(40, 30, 1))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(photo.statusCode()).as(photo.body()).isEqualTo(200);
        long photoId = JSON.readTree(photo.body()).at("/photo/id").asLong();

        JsonNode renamed = json(send("PATCH", "api/custom-species/U1", "{\"name\":\"Neptis soma\",\"common\":null}"), 200);
        assertThat(renamed.get("name").asText()).isEqualTo("Neptis soma");
        assertThat(renamed.get("common").isNull()).isTrue();
        assertThat(renamed.get("folder").asText()).isEqualTo("images/Custom_additions/Neptis_soma_[U1]/");
        assertThat(home.resolve("Atlas/images/Custom_additions/Neptis_soma_[U1]/Neptis_soma_01.jpg")).exists();

        refused(send("DELETE", "api/custom-species/U1", null), 409, "has_photos", "Move its photo first.");
        assertThat(send("POST", "api/photos/delete", "{\"ids\":[" + photoId + "]}").statusCode()).isEqualTo(200);
        refused(send("DELETE", "api/custom-species/U1", null), 409, "has_photos",
                "Empty or restore its photo in Recently deleted first.");
        assertThat(send("POST", "api/deleted/empty", "{}").statusCode()).isEqualTo(200);
        answers(send("DELETE", "api/custom-species/U1", null), 204, "");
        refused(send("GET", "api/taxa/U1", null), 404, "not_found", "There is no taxon U1 in your atlas.");
        assertThat(Files.exists(home.resolve("Atlas/images/Custom_additions/Neptis_soma_[U1]"))).isFalse();
    }

    @Test
    void theCheckAnswersAsTheContractSays() throws Exception {
        answers(send("GET", check(" neptis  SANKARA "), null), 200, "{\"ok\":true,\"name\":\"Neptis sankara\"}");
        answers(send("GET", check("Neptis"), null), 200, "{\"ok\":false,\"code\":\"invalid\",\"message\":\"Enter the scientific"
                + " name as genus and species, e.g. Neptis sankara.\",\"taxon\":null}");
        answers(send("GET", "api/custom-species/check", null), 200, "{\"ok\":false,\"code\":\"invalid\",\"message\":\"Enter"
                + " the scientific name as genus and species, e.g. Neptis sankara.\",\"taxon\":null}");
        answers(send("GET", check("Danaus chrysippus"), null), 200, "{\"ok\":false,\"code\":\"in_list\",\"message\":"
                + "\"Danaus chrysippus is already in the list as Plain Tiger.\",\"taxon\":" + TIGER + "}");
        answers(send("GET", check("limnas klugii"), null), 200, "{\"ok\":false,\"code\":\"in_list\",\"message\":"
                + "\"Limnas klugii is already in the list as Danaus chrysippus (Plain Tiger).\",\"taxon\":" + TIGER + "}");
        json(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis sankara\"}"), 201);
        answers(send("GET", check("neptis sankara"), null), 200, "{\"ok\":false,\"code\":\"name_exists\",\"message\":"
                + "\"You already added Neptis sankara.\",\"taxon\":{\"id\":\"U1\",\"name\":\"Neptis sankara\",\"common\":null}}");
        answers(send("GET", check("neptis sankara") + "&id=U1", null), 200, "{\"ok\":true,\"name\":\"Neptis sankara\"}");
    }

    @Test
    void refusalsCarryTheContractsCodesAndTheTaxonToOpen() throws Exception {
        answers(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"danaus chrysippus\",\"common\":null}"), 409,
                "{\"error\":{\"code\":\"in_list\",\"message\":\"Danaus chrysippus is already in the list as Plain Tiger.\","
                        + "\"taxon\":" + TIGER + "}}");
        refused(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis\"}"), 400, "invalid",
                "Enter the scientific name as genus and species, e.g. Neptis sankara.");
        refused(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis sankara\",\"common\":\""
                + "x".repeat(101) + "\"}"), 400, "invalid", "The common name can be at most 100 characters.");
        refused(send("POST", "api/custom-species", "{\"name\":\"Neptis sankara\"}"), 400, "invalid", "Choose the species' family.");
        refused(send("POST", "api/custom-species", "{\"familyId\":\"A5\",\"name\":\"Neptis sankara\"}"), 400, "invalid",
                "Choose the species' family.");
        json(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis sankara\"}"), 201);
        json(send("POST", "api/custom-species", "{\"familyId\":\"A9\",\"name\":\"Graphium eurypylus\",\"extra\":1}"), 201);
        answers(send("PATCH", "api/custom-species/U2", "{\"name\":\"NEPTIS SANKARA\"}"), 409,
                "{\"error\":{\"code\":\"name_exists\",\"message\":\"You already added Neptis sankara.\","
                        + "\"taxon\":{\"id\":\"U1\",\"name\":\"Neptis sankara\",\"common\":null}}}");
        Path taken = home.resolve("Atlas/images/Custom_additions/Neptis_soma_[U1]/Neptis_soma_01.jpg");
        Files.createDirectories(taken.getParent());
        Files.writeString(taken, "mine");
        HttpResponse<String> photo = http.send(HttpRequest.newBuilder(server.baseUri().resolve("api/taxa/U1/photos"))
                .timeout(Duration.ofSeconds(20)).header("Origin", "http://127.0.0.1:" + server.port())
                .header("Content-Type", "image/jpeg").POST(HttpRequest.BodyPublishers.ofByteArray(TestImages.jpeg(40, 30, 2))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(photo.statusCode()).as(photo.body()).isEqualTo(200);
        refused(send("PATCH", "api/custom-species/U1", "{\"name\":\"Neptis soma\"}"), 409, "file_exists",
                "A file named Neptis_soma_01.jpg is already in the species folder.");
        for (String id : new String[] {"A5", "U9", "U%3Abutterflies"}) {
            refused(send("PATCH", "api/custom-species/" + id, "{\"name\":\"Neptis soma\"}"), 404, "not_found",
                    "There is no custom species " + id.replace("%3A", ":") + " in your atlas.");
            refused(send("DELETE", "api/custom-species/" + id, null), 404, "not_found",
                    "There is no custom species " + id.replace("%3A", ":") + " in your atlas.");
        }
    }

    @Test
    void changesNeedTheAppsOrigin() throws Exception {
        assertThat(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis sankara\"}", false).statusCode())
                .isEqualTo(403);
        json(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis sankara\"}"), 201);
        assertThat(send("PATCH", "api/custom-species/U1", "{\"name\":\"Neptis soma\"}", false).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "api/custom-species/U1", null, false).statusCode()).isEqualTo(403);
        assertThat(json(send("GET", "api/taxa/U1", null), 200).get("name").asText()).isEqualTo("Neptis sankara");
        assertThat(send("GET", check("Neptis soma"), null, false).statusCode()).as("the check is a GET").isEqualTo(200);
    }
}
