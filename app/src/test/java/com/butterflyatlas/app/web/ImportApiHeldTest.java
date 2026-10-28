package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.HoldingProcessor;
import com.butterflyatlas.app.testing.TestImages;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The import routes while a step is held mid-way: filing refuses changes, a step that outlasts a cancel answers 503. */
class ImportApiHeldTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private final HoldingProcessor processor = new HoldingProcessor();
    private AtlasServices services;
    private AtlasServer server;
    private Path photos;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home, processor, Duration.ofMillis(50));
        server = AtlasServer.start(0, services);
        photos = Files.createDirectories(home.resolve("Pictures").resolve("Butterflies 2024"));
        Files.createDirectories(photos.resolve("Plain tiger"));
        Files.write(photos.resolve("Plain tiger").resolve("IMG_0001.JPG"), TestImages.jpeg(640, 480, 1));
        Files.write(photos.resolve("IMG_0002.JPG"), TestImages.jpeg(640, 480, 2));
    }

    @AfterEach
    void stop() {
        processor.release();
        server.close();
        services.imports().close();
        services.thumbnails().close();
        services.placeNotes().close();
        http.close();
    }

    /** File's body: the import's revision as its summary says it now (multi-window contract §5). */
    private String fileBody(long id) throws Exception {
        return "{\"revision\":" + JSON.readTree(send("GET", "api/imports/" + id, null).body()).get("revision").asLong() + "}";
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Origin", "http://127.0.0.1:" + server.port());
        if (json != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void refused(HttpResponse<String> response, int status, String code, String message) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"error\":{\"code\":" + JSON.writeValueAsString(code) + ",\"message\":"
                + JSON.writeValueAsString(message) + "}}");
    }

    private String path(Path folder) throws Exception {
        return JSON.writeValueAsString(Map.of("path", folder.toString()));
    }

    @Test
    void finishingOrRelocatingWhileTheReadingOutlastsTheWaitAnswersBusyAndChangesNothing() throws Exception {
        processor.hold((call, file) -> call.equals("thumbnail") && file.startsWith(photos));
        long id = ok(send("POST", "api/imports", path(photos))).at("/import/id").asLong();
        assertThat(processor.awaitHeld()).isTrue();

        JsonNode working = ok(send("GET", "api/status", null)).get("import");
        List<String> fields = new ArrayList<>();
        working.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("id", "rootName", "phase", "done", "total");
        assertThat(working.get("id").asLong()).isEqualTo(id);
        assertThat(working.get("rootName").asText()).isEqualTo("Butterflies 2024");
        assertThat(working.get("phase").asText()).isEqualTo("reading");
        JsonNode summary = ok(send("GET", "api/imports/" + id, null));
        assertThat(summary.get("phase").asText()).isEqualTo("reading");
        assertThat(summary.get("progress").isObject()).isTrue();

        String busy = "Butterfly Atlas is still finishing the last step. Try again in a moment.";
        refused(send("POST", "api/imports/" + id + "/finish", "{\"skipRest\":true}"), 503, "busy", busy);
        refused(send("POST", "api/imports/" + id + "/relocate", path(photos)), 503, "busy", busy);
        assertThat(ok(send("GET", "api/imports/" + id, null)).get("state").asText()).isEqualTo("open");

        processor.release();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        JsonNode after = ok(send("GET", "api/imports/" + id, null));
        assertThat(after.at("/counts/skipped").asInt()).as("nothing was skipped").isZero();
        assertThat(ok(send("POST", "api/imports/" + id + "/finish", "{\"skipRest\":false}")).at("/import/state").asText())
                .isEqualTo("finished");
    }

    @Test
    void whileFilingRunsEveryChangeButPauseIsRefused() throws Exception {
        long id = ok(send("POST", "api/imports", path(photos))).at("/import/id").asLong();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        List<Long> ids = new ArrayList<>();
        ok(send("GET", "api/imports/" + id + "/items", null)).get("items").forEach(item -> ids.add(item.get("id").asLong()));
        ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":" + ids + ",\"taxonId\":\"A5\"}"));
        processor.hold((call, file) -> call.equals("read") && file.toString().contains(".incoming"));

        HttpResponse<String> filing = send("POST", "api/imports/" + id + "/file", fileBody(id));
        assertThat(filing.statusCode()).as(filing.body()).isEqualTo(202);
        assertThat(processor.awaitHeld()).isTrue();

        String wait = "Wait for filing to finish or pause it.";
        refused(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":" + ids + ",\"taxonId\":\"A6\"}"), 409,
                "import_filing", wait);
        refused(send("POST", "api/imports/" + id + "/skip", "{\"itemIds\":" + ids + ",\"skipped\":true}"), 409,
                "import_filing", wait);
        refused(send("POST", "api/imports/" + id + "/file", fileBody(id)), 409, "import_filing", wait);
        refused(send("POST", "api/imports/" + id + "/relocate", path(photos)), 409, "import_filing", wait);
        refused(send("POST", "api/imports/" + id + "/finish", "{\"skipRest\":false}"), 409, "import_filing", wait);
        refused(send("DELETE", "api/imports/" + id, null), 409, "import_filing", wait);
        assertThat(ok(send("POST", "api/imports/" + id + "/pause", "{}")).get("paused").asBoolean()).isTrue();

        processor.release();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(ok(send("GET", "api/imports/" + id, null)).get("phase").asText()).isEqualTo("idle");
    }

    @Test
    void aTagPlaceOrCustomSpeciesAFilingImportChoseIsNotDeletedUntilFilingEnds() throws Exception {
        long id = ok(send("POST", "api/imports", path(photos))).at("/import/id").asLong();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        List<Long> ids = new ArrayList<>();
        ok(send("GET", "api/imports/" + id + "/items", null)).get("items").forEach(item -> ids.add(item.get("id").asLong()));
        long tag = JSON.readTree(send("POST", "api/tags", "{\"label\":\"Garden\"}").body()).get("id").asLong();
        String place = JSON.readTree(send("POST", "api/places", "{\"name\":\"Tamhini\",\"parentId\":\"P2\"}").body())
                .get("id").asText();
        String species = JSON.readTree(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis sankara\"}")
                .body()).get("id").asText();
        ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + ids.get(0) + "],\"taxonId\":\"" + species + "\"}"));
        ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + ids.get(1) + "],\"taxonId\":\"A5\"}"));
        ok(send("POST", "api/imports/" + id + "/tags", "{\"itemIds\":" + ids + ",\"add\":[\"garden\"]}"));
        ok(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":" + ids + ",\"placeId\":\"" + place + "\"}"));
        processor.hold((call, file) -> call.equals("read") && file.toString().contains(".incoming"));
        assertThat(send("POST", "api/imports/" + id + "/file", fileBody(id)).statusCode()).isEqualTo(202);
        assertThat(processor.awaitHeld()).isTrue();

        refused(send("DELETE", "api/tags/" + tag, null), 409, "import_filing",
                "An import is filing photos with this tag. Wait for filing to finish, then delete it.");
        refused(send("DELETE", "api/places/" + place, null), 409, "import_filing",
                "An import is filing photos with this place. Wait for filing to finish, then delete it.");
        refused(send("DELETE", "api/custom-species/" + species, null), 409, "import_filing",
                "An import is filing photos into this species. Wait for filing to finish, then delete it.");
        assertThat(send("GET", "api/places/mine", null).body()).as("the place is still there").contains(place);

        processor.release();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(send("DELETE", "api/tags/" + tag, null).statusCode()).as("filing over").isEqualTo(200);
    }
}
