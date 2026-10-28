package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.exports.ExportStore;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestLocations;
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
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exports over HTTP (4b contract §2): every route, the exact shapes, the refusals, /api/status. */
class ExportApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;
    private Database db;
    private Path dest;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home);
        server = AtlasServer.start(0, services);
        db = Database.open(home.resolve("Atlas").resolve(Database.FILE_NAME));
        dest = Files.createDirectories(home.resolve("Backup"));
        photo("A5", "P4");
        photo("A12", null);
    }

    @AfterEach
    void stop() {
        server.close();
        services.exports().close();
        services.thumbnails().close();
        http.close();
    }

    private void photo(String taxonId, String placeId) throws Exception {
        long id = TestBundles.addImage(db, taxonId);
        byte[] bytes = TestLocations.jpeg();
        Path file = home.resolve("Atlas").resolve("images/test/photo_" + id + ".jpg");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET rel_path = ?, file_name = ?, file_size = ?,"
                    + " place_id = ? WHERE id = ?")) {
                ps.setString(1, "images/test/photo_" + id + ".jpg");
                ps.setString(2, "photo_" + id + ".jpg");
                ps.setLong(3, bytes.length);
                ps.setString(4, placeId);
                ps.setLong(5, id);
                ps.executeUpdate();
            }
            return null;
        });
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
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

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return send(method, path, json, true);
    }

    private JsonNode answer(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        return answer(send("GET", path, null), 200);
    }

    private static void error(HttpResponse<String> response, int status, String code, String message) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"error\":{\"code\":\"" + code + "\",\"message\":"
                + JSON.writeValueAsString(message) + "}}");
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String start(String what) throws Exception {
        return JSON.writeValueAsString(Map.of("filter", "", "what", what, "path", dest.toString()));
    }

    private JsonNode finished(long id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            JsonNode export = get("api/exports/" + id);
            if (!export.get("state").asText().equals("running") || System.nanoTime() > deadline) {
                return export;
            }
            Thread.sleep(20);
        }
    }

    @Test
    void theCheckAnswersTheSpaceLine() throws Exception {
        String path = URLEncoder.encode(dest.toString(), StandardCharsets.UTF_8);

        JsonNode check = get("api/exports/check?taxa=A5&what=photos&path=" + path);

        assertThat(fields(check)).containsExactly("photos", "species", "bytes", "neededBytes", "freeBytes", "name", "problem", "raw", "heic");
        assertThat(check.get("raw").toString()).isEqualTo("{\"photos\":0,\"withGps\":0,\"withUnknown\":0}");
        assertThat(check.get("heic").toString()).isEqualTo("{\"photos\":0,\"withGps\":0,\"withUnknown\":0}");
        assertThat(check.get("photos").asInt()).isEqualTo(1);
        assertThat(check.get("species").asInt()).isEqualTo(1);
        assertThat(check.get("neededBytes").asLong()).isEqualTo(check.get("bytes").asLong() + 200L * 1024 * 1024);
        assertThat(check.get("freeBytes").asLong()).isPositive();
        assertThat(check.get("name").asText()).startsWith("Butterfly_Atlas_export_");
        assertThat(check.get("problem").isNull()).isTrue();
        assertThat(get("api/exports/check?what=csv").get("problem").toString())
                .isEqualTo("{\"code\":\"invalid\",\"message\":\"Choose where to export.\"}");
        error(send("GET", "api/exports/check?what=pdf&path=" + path, null), 400, "invalid", "Unknown export “pdf”.");
        error(send("GET", "api/exports/check?status=maybe&what=csv", null), 400, "invalid", "Unknown status “maybe”.");
        assertThat(dest.toFile().list()).isEmpty();
    }

    @Test
    void aPhotoExportRunsToTheEndAndIsInThePastExports() throws Exception {
        JsonNode started = answer(send("POST", "api/exports", start("photos")), 201);

        assertThat(fields(started)).containsExactly("id", "state", "what", "layout", "stripLocation", "placeDepth", "filter",
                "path", "csv", "species", "total", "done", "copied", "skipped", "failed", "renamed", "bytesTotal", "bytesDone",
                "pausedBy", "notice", "cancel", "notRemoved", "startedAt", "finishedAt", "rawCount", "heicCount");
        assertThat(started.get("what").asText()).isEqualTo("photos");
        assertThat(started.get("layout").asText()).isEqualTo("species");
        assertThat(started.get("stripLocation").asBoolean()).isTrue();
        assertThat(started.get("placeDepth").asText()).isEqualTo("none");
        assertThat(started.get("total").asInt()).isEqualTo(2);
        long id = started.get("id").asLong();

        JsonNode done = finished(id);

        assertThat(fields(done)).endsWith("startedAt", "finishedAt", "rawCount", "heicCount", "problems", "moreProblems");
        assertThat(done.get("state").asText()).isEqualTo("done");
        assertThat(done.get("copied").asInt()).isEqualTo(2);
        assertThat(done.get("problems").size()).isZero();
        assertThat(done.get("moreProblems").asInt()).isZero();
        JsonNode list = get("api/exports");
        assertThat(fields(list)).containsExactly("current", "past");
        assertThat(list.get("current").isNull()).isTrue();
        assertThat(list.get("past").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(list.get("past").get(0).has("problems")).isFalse();
        assertThat(get("api/status").get("export").isNull()).isTrue();
        error(send("POST", "api/exports/" + id + "/pause", "{}"), 409, "export_finished", "This export has already finished.");
        error(send("POST", "api/exports/" + id + "/resume", "{}"), 409, "export_finished", "This export has already finished.");
        error(send("POST", "api/exports/" + id + "/cancel", "{\"remove\":true}"), 409, "export_finished",
                "This export has already finished.");
        Path folder = Path.of(done.get("path").asText());
        assertThat(Files.list(folder).count()).isPositive();

        assertThat(send("DELETE", "api/exports/" + id, null).statusCode()).isEqualTo(204);

        assertThat(get("api/exports").get("past").size()).isZero();
        assertThat(folder).as("removing the record leaves the files").isDirectory();
        error(send("GET", "api/exports/" + id, null), 404, "not_found", "That export can't be found.");
        error(send("DELETE", "api/exports/" + id, null), 404, "not_found", "That export can't be found.");
        error(send("GET", "api/exports/abc", null), 404, "not_found", "That export can't be found.");
    }

    @Test
    void aSpeciesListIsDoneInTheAnswerAndClearHistoryRemovesTheRecordsOnly() throws Exception {
        JsonNode csv = answer(send("POST", "api/exports", JSON.writeValueAsString(Map.of("filter", "taxa=A4", "what", "csv",
                "path", dest.toString(), "placeDepth", "state"))), 201);
        answer(send("POST", "api/exports", start("csv")), 201);

        assertThat(csv.get("state").asText()).isEqualTo("done");
        assertThat(csv.get("csv").asText()).startsWith("Butterfly_Atlas_species_list_").endsWith(".csv");
        assertThat(csv.get("path").asText()).isEqualTo(dest.resolve(csv.get("csv").asText()).toString());
        assertThat(csv.get("filter").asText()).isEqualTo("taxa=A4");

        assertThat(answer(send("DELETE", "api/exports", null), 200).toString()).isEqualTo("{\"removed\":2}");
        assertThat(Files.list(dest).count()).isEqualTo(2);
    }

    @Test
    void aPausedExportIsInTheStatusAndTheCardsActionsAnswer() throws Exception {
        ExportStore store = services.exports().store();
        long id = store.record(new ExportStore.NewRun(dest.toString(), "Butterfly_Atlas_export_2026-10-09",
                "photos", "species", true, "none", "", null, 2, ExportStore.RUNNING, null, null),
                List.of(new ExportStore.NewItem(1, "A5", "photo_1.jpg", "a/photo_1.jpg", false, 10)));
        store.pause(id, "drive_full", "The drive is full: about 2.1 GB more is needed.");

        JsonNode status = get("api/status").get("export");

        assertThat(status.toString()).isEqualTo("{\"id\":" + id + ",\"state\":\"paused\",\"done\":0,\"total\":1,"
                + "\"pausedBy\":\"drive_full\",\"notice\":\"The drive is full: about 2.1 GB more is needed.\",\"path\":"
                + JSON.writeValueAsString(dest.resolve("Butterfly_Atlas_export_2026-10-09").toString()) + ",\"rawCount\":0,\"heicCount\":0}");
        assertThat(get("api/exports").get("current").get("id").asLong()).isEqualTo(id);
        error(send("POST", "api/exports", start("csv")), 409, "export_running", "Finish or cancel the export in progress first.");
        error(send("DELETE", "api/exports/" + id, null), 409, "export_running", "Finish or cancel the export first.");
        assertThat(answer(send("POST", "api/exports/" + id + "/pause", "{}"), 200).get("pausedBy").asText())
                .as("paused already: as it is").isEqualTo("drive_full");

        JsonNode resumed = answer(send("POST", "api/exports/" + id + "/resume", "{}"), 200);
        assertThat(resumed.get("state").asText()).isEqualTo("paused");
        assertThat(resumed.get("pausedBy").asText()).as("its folder is not there").isEqualTo("drive_gone");

        JsonNode cancelled = answer(send("POST", "api/exports/" + id + "/cancel", "{\"remove\":false}"), 200);
        assertThat(cancelled.get("state").asText()).isEqualTo("cancelled");
        assertThat(cancelled.get("cancel").asText()).isEqualTo("keep");
        assertThat(get("api/status").get("export").isNull()).isTrue();
    }

    @Test
    void changesNeedTheAppsOrigin() throws Exception {
        ExportStore store = services.exports().store();
        long id = store.record(new ExportStore.NewRun(dest.toString(), "Butterfly_Atlas_export_2026-10-09",
                "photos", "species", true, "none", "", null, 2, ExportStore.RUNNING, null, null),
                List.of(new ExportStore.NewItem(1, "A5", "photo_1.jpg", "a/photo_1.jpg", false, 10)));
        store.pause(id, "you", null);

        assertThat(send("POST", "api/exports", start("csv"), false).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "api/exports", null, false).statusCode()).isEqualTo(403);
        for (String action : List.of("pause", "resume", "cancel")) {
            assertThat(send("POST", "api/exports/" + id + "/" + action, "{}", false).statusCode()).as(action).isEqualTo(403);
        }
        assertThat(send("DELETE", "api/exports/" + id, null, false).statusCode()).isEqualTo(403);
        assertThat(dest.toFile().list()).isEmpty();
        assertThat(store.run(id)).extracting(ExportStore.Run::state, ExportStore.Run::pausedBy).as("untouched")
                .containsExactly("paused", "you");
    }

    @Test
    void anActionWithoutJsonIsRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve("api/exports/1/pause"))
                .header("Origin", "http://127.0.0.1:" + server.port()).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();

        error(http.send(request, HttpResponse.BodyHandlers.ofString()), 415, "unsupported_media_type",
                "Butterfly Atlas expects changes as JSON.");
    }

    @Test
    void anExportsDetailListsAThousandProblemsAndCountsTheRest() throws Exception {
        ExportStore store = services.exports().store();
        List<ExportStore.NewItem> items = new ArrayList<>();
        List<ExportStore.Outcome> failed = new ArrayList<>();
        for (int n = 1; n <= 1001; n++) {
            items.add(new ExportStore.NewItem(n, "A5", "photo_" + n + ".jpg", "a/photo_" + n + ".jpg", false, 10));
            failed.add(new ExportStore.Outcome(n, ExportStore.FAILED, "The copy could not be written.", 0));
        }
        long id = store.record(new ExportStore.NewRun(dest.toString(), "Butterfly_Atlas_export_2026-10-09",
                "photos", "flat", true, "none", "", null, 1, ExportStore.RUNNING, null, null), items);
        store.save(id, failed);
        store.finish(id);

        JsonNode detail = get("api/exports/" + id);

        assertThat(detail.get("problems").size()).isEqualTo(1000);
        assertThat(detail.get("moreProblems").asInt()).isEqualTo(1);
        assertThat(detail.get("problems").get(0).toString()).isEqualTo("{\"kind\":\"failed\",\"imageId\":1,"
                + "\"file\":\"photo_1.jpg\",\"species\":\"Danaus chrysippus\",\"target\":\"a/photo_1.jpg\","
                + "\"reason\":\"The copy could not be written.\"}");
    }

    @Test
    void badChoicesAndAMissingLibraryAreRefused() throws Exception {
        error(send("POST", "api/exports", JSON.writeValueAsString(Map.of("what", "photos", "path", dest.toString(),
                "layout", "tree"))), 400, "invalid", "Unknown layout “tree”.");
        error(send("POST", "api/exports", JSON.writeValueAsString(Map.of("what", "both", "path", dest.toString(),
                "placeDepth", "town"))), 400, "invalid", "Unknown place depth “town”.");
        error(send("POST", "api/exports", JSON.writeValueAsString(Map.of("what", "photos", "filter", "taxa=A8",
                "path", dest.toString()))), 400, "nothing", "No photos match these filters.");
        error(send("POST", "api/exports", "not json"), 400, "bad_request", "The request was not valid JSON.");
        server.close();
        services.exports().close();
        services = TestServices.unconfigured(home.resolve("other"));
        server = AtlasServer.start(0, services);
        assertThat(send("GET", "api/exports", null).statusCode()).isEqualTo(409);
        assertThat(send("GET", "api/exports", null).body()).contains("\"code\":\"setup_required\"");
        HttpResponse<String> start = send("POST", "api/exports", start("photos"));
        assertThat(start.statusCode()).isEqualTo(409);
        assertThat(start.body()).contains("\"code\":\"setup_required\"");
        assertThat(dest.toFile().list()).isEmpty();
    }
}
