package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ImportApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;
    private Path photos;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home);
        server = AtlasServer.start(0, services);
        photos = Files.createDirectories(home.resolve("Pictures").resolve("Butterflies 2024"));
        Files.createDirectories(photos.resolve("Plain tiger"));
        Files.write(photos.resolve("Plain tiger").resolve("IMG_0001.JPG"), TestImages.jpeg(640, 480, 1));
        Files.write(photos.resolve("IMG_0002.JPG"), TestImages.jpeg(640, 480, 2));
        byte[] heic = new byte[64];
        System.arraycopy("\0\0\0\u0018ftypmsf1\0\0\0\0msf1heic".getBytes(StandardCharsets.ISO_8859_1), 0, heic, 0, 24);
        Files.write(photos.resolve("IMG_0003.HEIC"), heic);
    }

    @AfterEach
    void stop() {
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

    private JsonNode get(String path) throws Exception {
        return ok(send("GET", path, null));
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }

    private static String path(Path folder) throws Exception {
        return JSON.writeValueAsString(Map.of("path", folder.toString()));
    }

    private long started() throws Exception {
        JsonNode body = ok(send("POST", "api/imports", path(photos)));
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        return body.at("/import/id").asLong();
    }

    private Map<String, JsonNode> items(long id) throws Exception {
        Map<String, JsonNode> byPath = new HashMap<>();
        get("api/imports/" + id + "/items").get("items").forEach(item -> byPath.put(item.get("path").asText(), item));
        return byPath;
    }

    @Test
    void itemsCarryThePlaceTheirGpsLiesIn() throws Exception {
        Files.write(photos.resolve("IMG_0004.JPG"), TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(640, 480, 4)));

        long id = started();

        Map<String, JsonNode> items = items(id);
        assertThat(items.get("IMG_0004.JPG").get("place").toString())
                .isEqualTo("{\"id\":\"P4\",\"path\":[\"Alpha\",\"Northstate\",\"Testland\"],\"ids\":[\"P4\",\"P2\",\"P1\"],\"source\":\"gps\",\"former\":false,\"level\":\"district\"}");
        assertThat(items.get("IMG_0002.JPG").get("place").isNull()).as("no GPS").isTrue();
        assertThat(items.get("IMG_0003.HEIC").get("place").isNull()).as("not read").isTrue();
    }

    @Test
    void itemsTakeThePlaceTheUserChoosesAndAreFiledWithIt() throws Exception {
        Files.write(photos.resolve("IMG_0004.JPG"), TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(640, 480, 4)));
        long id = started();
        Map<String, JsonNode> items = items(id);
        long gps = items.get("IMG_0004.JPG").get("id").asLong();
        long none = items.get("IMG_0002.JPG").get("id").asLong();
        long version = get("api/imports/" + id + "/items").get("version").asLong();

        JsonNode page = ok(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":[" + gps + "," + none + "],\"placeId\":\"P5\"}"));

        assertThat(page.get("version").asLong()).isEqualTo(version + 1);
        assertThat(page.get("items").size()).isEqualTo(2);
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("place").toString())
                    .isEqualTo("{\"id\":\"P5\",\"path\":[\"Beta\",\"Northstate\",\"Testland\"],\"ids\":[\"P5\",\"P2\",\"P1\"],\"source\":\"user\",\"former\":false,\"level\":\"district\"}");
        }
        assertThat(get("api/places/recent").toString()).startsWith("{\"places\":[{\"id\":\"P5\",");
        JsonNode removed = ok(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":[" + gps + "],\"remove\":true}"));
        assertThat(removed.get("items").get(0).get("place").isNull()).isTrue();
        assertThat(ok(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":[" + gps + "],\"remove\":true}"))
                .get("items").size()).as("nothing changed").isZero();

        refused(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":[" + gps + "],\"placeId\":\"P404\"}"), 404,
                "not_found", "There is no such place.");
        refused(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":[" + gps + "],\"placeId\":\"P5\",\"remove\":true}"),
                400, "invalid", "Choose a place, or remove the place.");
        refused(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":[],\"placeId\":\"P5\"}"), 400, "invalid",
                "Choose at least one photo.");
        refused(send("POST", "api/imports/404/place", "{\"itemIds\":[" + gps + "],\"placeId\":\"P5\"}"), 404,
                "import_not_found", "That import can't be found.");
        StringBuilder many = new StringBuilder();
        for (int n = 1; n <= 10_001; n++) {
            many.append(n == 1 ? "" : ",").append(n);
        }
        refused(send("POST", "api/imports/" + id + "/place", "{\"itemIds\":[" + many + "],\"placeId\":\"P5\"}"), 400,
                "invalid", "Choose at most 10,000 photos at a time.");
        for (String body : new String[] {"not json", "{\"itemIds\":\"" + gps + "\",\"placeId\":\"P5\"}",
                "{\"itemIds\":[" + gps + "],\"placeId\":[\"P5\"]}", "{\"itemIds\":[" + gps + "],\"remove\":\"yes\"}"}) {
            error(send("POST", "api/imports/" + id + "/place", body), 400, "bad_request");
        }

        ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + gps + "," + none + "],\"taxonId\":\"A5\"}"));
        assertThat(send("POST", "api/imports/" + id + "/file", fileBody(id)).statusCode()).isEqualTo(202);
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        Map<String, String> places = new HashMap<>();
        get("api/taxa/A5/photos").get("photos").forEach(p -> places.put(p.get("fileName").asText(), p.get("place").toString()));
        assertThat(places.values()).containsExactlyInAnyOrder("null",
                "{\"id\":\"P5\",\"path\":[\"Beta\",\"Northstate\",\"Testland\"],\"ids\":[\"P5\",\"P2\",\"P1\"],\"source\":\"user\",\"former\":false,\"level\":\"district\"}");
    }

    @Test
    void aWholeImportOverTheApi() throws Exception {
        JsonNode started = ok(send("POST", "api/imports", path(photos)));
        assertThat(started.get("continued").asBoolean()).isFalse();
        JsonNode summary = started.get("import");
        assertThat(summary.get("rootPath").asText()).isEqualTo(photos.toString());
        assertThat(summary.get("rootName").asText()).isEqualTo("Butterflies 2024");
        assertThat(summary.get("state").asText()).isEqualTo("open");
        assertThat(summary.get("paused").asBoolean()).isFalse();
        assertThat(summary.get("stopped").isNull()).isTrue();
        assertThat(summary.get("phase").asText()).isIn("walking", "reading", "idle");
        long id = summary.get("id").asLong();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();

        JsonNode page = get("api/imports/" + id + "/items");
        assertThat(page.get("version").asLong()).isPositive();
        Map<String, JsonNode> items = items(id);
        JsonNode tiger = items.get("Plain tiger/IMG_0001.JPG");
        assertThat(tiger.get("state").asText()).isEqualTo("read");
        assertThat(tiger.get("folder").asText()).isEqualTo("Plain tiger");
        assertThat(tiger.get("suggestedTaxonId").asText()).isEqualTo("A5");
        assertThat(tiger.get("width").asInt()).isEqualTo(640);
        assertThat(tiger.get("v").asText()).hasSize(8);
        assertThat(page.at("/taxa/A5/common").asText()).isEqualTo("Plain Tiger");
        assertThat(items.get("IMG_0003.HEIC").get("problem").asText()).isEqualTo(PhotoFormat.HEIC_SEQUENCE);
        assertThat(get("api/imports/" + id + "/items?since=" + page.get("version").asLong()).get("items").size()).isZero();

        JsonNode accepted = ok(send("POST", "api/imports/" + id + "/accept-suggestions",
                "{\"itemIds\":[" + tiger.get("id").asLong() + "]}"));
        assertThat(accepted.get("items").get(0).get("taxonId").asText()).isEqualTo("A5");
        assertThat(accepted.get("version").asLong()).isGreaterThan(page.get("version").asLong());
        ok(send("POST", "api/imports/" + id + "/tags", "{\"itemIds\":[" + tiger.get("id").asLong() + "],\"add\":[\"male\"]}"));
        ok(send("POST", "api/imports/" + id + "/assign",
                "{\"itemIds\":[" + items.get("IMG_0002.JPG").get("id").asLong() + "],\"taxonId\":\"A6\"}"));
        ok(send("POST", "api/imports/" + id + "/skip",
                "{\"itemIds\":[" + items.get("IMG_0002.JPG").get("id").asLong() + "],\"skipped\":false}"));
        assertThat(get("api/imports/" + id).at("/counts/assigned").asInt()).isEqualTo(2);

        HttpResponse<String> filing = send("POST", "api/imports/" + id + "/file", fileBody(id));
        assertThat(filing.statusCode()).as(filing.body()).isEqualTo(202);
        JsonNode began = JSON.readTree(filing.body());
        if (!began.get("phase").asText().equals("filing")) {
            // two photos can be filed before File answers (seen on Windows): then its filing is over
            assertThat(began.get("phase").asText()).isEqualTo("idle");
            assertThat(began.get("filings").asInt()).isEqualTo(1);
        }
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();

        JsonNode result = get("api/imports/" + id + "/result");
        assertThat(result.get("filed").asInt()).isEqualTo(2);
        assertThat(result.get("newSpecies").asInt()).isEqualTo(2);
        assertThat(result.get("speciesCount").asInt()).isEqualTo(2);
        assertThat(result.get("problems").toString()).isEqualTo("[{\"reason\":\"" + PhotoFormat.HEIC_SEQUENCE + "\",\"count\":1}]");
        assertThat(result.get("lastFiling").toString())
                .isEqualTo("{\"filed\":2,\"newSpecies\":2,\"milestone\":null,\"speciesCount\":2}");
        assertThat(result.get("left").asInt()).isZero();
        assertThat(get("api/taxa/A5/photos").get("photos").get(0).get("tags").toString()).isEqualTo("[\"male\"]");
        JsonNode status = get("api/status");
        assertThat(status.get("import").isNull()).isTrue();
        assertThat(status.get("stoppedImports").size()).isZero();
        assertThat(get("api/imports").get("imports").get(0).get("id").asLong()).isEqualTo(id);

        JsonNode finished = ok(send("POST", "api/imports/" + id + "/finish", "{\"skipRest\":false}"));
        assertThat(finished.at("/import/state").asText()).isEqualTo("finished");
        assertThat(finished.at("/result/filed").asInt()).isEqualTo(2);
        HttpResponse<String> deleted = send("DELETE", "api/imports/" + id, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        error(send("GET", "api/imports/" + id, null), 404, "import_not_found");
        assertThat(Files.exists(photos.resolve("IMG_0002.JPG"))).isTrue();
        assertThat(get("api/taxa/A6/photos").get("photos").size()).as("filed photos stay").isEqualTo(1);
    }

    @Test
    void refusalsHaveTheirOwnCodes() throws Exception {
        error(send("GET", "api/imports/404", null), 404, "import_not_found");
        error(send("GET", "api/imports/abc/items", null), 404, "import_not_found");
        error(send("POST", "api/imports", "{\"path\":\"relative\"}"), 400, "bad_path");
        error(send("POST", "api/imports", path(home.resolve("nope"))), 404, "folder_not_found");
        error(send("POST", "api/imports", path(home.resolve("Atlas"))), 400, "library_folder");
        long id = started();
        long item = items(id).get("IMG_0002.JPG").get("id").asLong();
        error(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + item + "],\"taxonId\":\"A4\"}"), 400,
                "not_a_species");
        error(send("POST", "api/imports/" + id + "/tags", "{\"itemIds\":[" + item + "],\"add\":[\"wings\"]}"), 400,
                "unknown_tag");
        error(send("POST", "api/imports/" + id + "/skip", "{\"itemIds\":[" + item + "]}"), 400, "bad_request");
        error(send("POST", "api/imports/" + id + "/file", fileBody(id)), 409, "nothing_to_file");
        Path elsewhere = Files.createDirectories(home.resolve("elsewhere"));
        Files.write(elsewhere.resolve("other.jpg"), TestImages.jpeg(10, 10));
        error(send("POST", "api/imports/" + id + "/relocate", path(elsewhere)), 400, "folder_mismatch");
        HttpResponse<String> noOrigin = http.send(HttpRequest.newBuilder(server.baseUri().resolve("api/imports/" + id + "/file"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        error(noOrigin, 403, "forbidden_origin");
    }

    @Test
    void previewsAndTheLargeViewComeFromThisImport() throws Exception {
        long id = started();
        Map<String, JsonNode> items = items(id);
        JsonNode tiger = items.get("Plain tiger/IMG_0001.JPG");
        String base = "api/imports/" + id + "/items/";

        HttpResponse<byte[]> preview = http.send(HttpRequest.newBuilder(server.baseUri().resolve(base + tiger.get("id").asLong()
                + "/preview?v=" + tiger.get("v").asText())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(preview.headers().firstValue("Cache-Control")).contains("public, max-age=31536000, immutable");
        HttpResponse<byte[]> problem = http.send(HttpRequest.newBuilder(server.baseUri().resolve(base
                + items.get("IMG_0003.HEIC").get("id").asLong() + "/preview")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(problem.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(problem.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(problem.headers().firstValue("X-Atlas-Pending")).isEmpty();

        HttpResponse<byte[]> display = http.send(HttpRequest.newBuilder(server.baseUri().resolve(base + tiger.get("id").asLong()
                + "/display")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(display.statusCode()).isEqualTo(200);
        assertThat(display.headers().firstValue("Content-Security-Policy")).contains("sandbox");
        assertThat(display.body()).isEqualTo(Files.readAllBytes(photos.resolve("Plain tiger").resolve("IMG_0001.JPG")));
        error(send("GET", base + items.get("IMG_0003.HEIC").get("id").asLong() + "/display", null), 404, "not_found");
        error(send("GET", base + "999999/preview", null), 404, "not_found");
    }

    @Test
    void anImportTheAppsClosingStoppedIsReportedInTheStatus() throws Exception {
        long id = started();
        services.imports().store().setActive(id, "reading", 5, 12);

        services.imports().startup();

        JsonNode status = get("api/status");
        assertThat(status.get("stoppedImports").toString())
                .isEqualTo("[{\"id\":" + id + ",\"rootName\":\"Butterflies 2024\",\"phase\":\"reading\",\"done\":5,\"total\":12}]");
        assertThat(get("api/imports/" + id).get("stopped").toString())
                .isEqualTo("{\"phase\":\"reading\",\"done\":5,\"total\":12,\"at\":\"2026-10-09T08:00:00Z\"}");
        JsonNode continued = ok(send("POST", "api/imports", path(photos)));
        assertThat(continued.get("continued").asBoolean()).isTrue();
        assertThat(continued.at("/import/stopped").isNull()).isTrue();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(get("api/status").get("stoppedImports").size()).isZero();
    }

    /** The whole answer: the status and the exact error body. */
    private static void refused(HttpResponse<String> response, int status, String code, String message) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"error\":{\"code\":" + JSON.writeValueAsString(code) + ",\"message\":"
                + JSON.writeValueAsString(message) + "}}");
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void fileNeedsTheRevisionTheWindowLastHadAndIsRefusedWhenTheImportChangedSince() throws Exception {
        long id = started();
        long item = items(id).get("IMG_0002.JPG").get("id").asLong();
        long start = get("api/imports/" + id).get("revision").asLong();
        assertThat(get("api/imports/" + id + "/items").get("revision").asLong()).isEqualTo(start);

        JsonNode assigned = ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + item + "],\"taxonId\":\"A5\"}"));
        long mine = assigned.get("revision").asLong();
        assertThat(mine).as("an edit's answer carries the revision after it").isGreaterThan(start);
        assertThat(get("api/imports/" + id).get("revision").asLong()).isEqualTo(mine);
        long tag = JSON.readTree(send("POST", "api/tags", "{\"label\":\"Garden\"}").body()).get("id").asLong();
        long tagged = ok(send("POST", "api/imports/" + id + "/tags", "{\"itemIds\":[" + item + "],\"add\":[\"garden\"]}"))
                .get("revision").asLong();
        assertThat(tagged).isGreaterThan(mine);
        ok(send("POST", "api/imports/" + id + "/skip", "{\"itemIds\":[" + item + "],\"skipped\":false}")); // another window

        refused(send("POST", "api/imports/" + id + "/file", "{\"revision\":" + tagged + "}"), 409, "import_changed",
                "This import was changed in another window. Check the photos, then press File again.");
        refused(send("POST", "api/imports/" + id + "/file", "{}"), 400, "invalid", "Say which version of the import to file.");
        assertThat(get("api/imports/" + id).get("filings").asInt()).as("nothing was filed").isZero();

        long before = get("api/imports/" + id).get("revision").asLong();
        assertThat(send("DELETE", "api/tags/" + tag, null).statusCode()).isEqualTo(200);
        long after = get("api/imports/" + id).get("revision").asLong();
        assertThat(after).as("deleting a tag an item chose changes the import").isGreaterThan(before);
        assertThat(send("POST", "api/imports/" + id + "/file", "{\"revision\":" + after + "}").statusCode()).isEqualTo(202);
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(get("api/imports/" + id).get("filings").asInt()).isEqualTo(1);
    }

    @Test
    void everyAnswerThatChangesTheImportSaysTheRevisionBeforeAndAfterSoAWindowSeesAnotherWindowsChange() throws Exception {
        long id = started();
        long item = items(id).get("IMG_0002.JPG").get("id").asLong();
        long held = get("api/imports/" + id).get("revision").asLong();

        String[][] edits = {
                {"assign", "{\"itemIds\":[" + item + "],\"taxonId\":\"A5\"}"},
                {"tags", "{\"itemIds\":[" + item + "],\"add\":[\"male\"]}"},
                {"place", "{\"itemIds\":[" + item + "],\"placeId\":\"P5\"}"},
                {"skip", "{\"itemIds\":[" + item + "],\"skipped\":false}"},
                {"accept-suggestions", "{\"itemIds\":[" + item + "]}"},
                {"relocate", path(photos)}};
        for (String[] edit : edits) {
            JsonNode answer = ok(send("POST", "api/imports/" + id + "/" + edit[0], edit[1]));
            JsonNode body = answer.has("import") ? answer.get("import") : answer;
            assertThat(body.get("previousRevision").asLong()).as(edit[0]).isEqualTo(held);
            assertThat(body.get("revision").asLong()).as(edit[0]).isEqualTo(held + 1);
            held = body.get("revision").asLong();
            assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        }
        ok(send("POST", "api/imports/" + id + "/skip", "{\"itemIds\":[" + item + "],\"skipped\":true}")); // another window
        JsonNode mine = ok(send("POST", "api/imports/" + id + "/skip", "{\"itemIds\":[" + item + "],\"skipped\":false}"));
        assertThat(mine.get("previousRevision").asLong()).as("not the one this window held: it keeps its own")
                .isNotEqualTo(held);

        assertThat(fields(get("api/imports/" + id))).doesNotContain("previousRevision");
        assertThat(fields(get("api/imports/" + id + "/items"))).contains("revision").doesNotContain("previousRevision");
    }

    @Test
    void everyRefusalHasItsCodeAndPlainWords() throws Exception {
        refused(send("GET", "api/imports/404", null), 404, "import_not_found", "That import can't be found.");
        refused(send("POST", "api/imports", path(home.resolve("Atlas"))), 400, "library_folder",
                "That's your library folder. Choose a folder of photos outside it.");
        long id = started();
        long item = items(id).get("IMG_0002.JPG").get("id").asLong();
        refused(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + item + "],\"taxonId\":\"A4\"}"), 400,
                "not_a_species", "Photos can only be filed under a species.");
        refused(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + item + "],\"taxonId\":\"Z9\"}"), 404,
                "not_found", "There is no taxon Z9 in your atlas.");
        refused(send("POST", "api/imports/" + id + "/file", fileBody(id)), 409, "nothing_to_file",
                "Assign a species to some photos first.");
        refused(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"jpeg\"}"), 409, "no_pairs",
                "This import has no RAW + JPEG or RAW + HEIC pairs.");
        assertThat(get("api/imports/" + id).get("pairs").isNull()).as("nothing saved (fixes J4)").isTrue();
        assertThat(get("api/imports/" + id + "/result").get("pairChoice").isNull()).isTrue();
        refused(send("POST", "api/imports/" + id + "/relocate", path(home.resolve("Atlas"))), 400,
                "library_folder", "That's your library folder. Choose a folder of photos outside it.");
        Path photosLibrary = Files.createDirectories(home.resolve("Pictures").resolve("Photos Library.photoslibrary"));
        refused(send("POST", "api/imports", path(photosLibrary)), 400, "package_folder",
                "That's a photo library or app package, not a folder of photos. Choose a normal folder.");
        refused(send("POST", "api/imports/" + id + "/relocate", path(photosLibrary)), 400, "package_folder",
                "That's a photo library or app package, not a folder of photos. Choose a normal folder.");

        Path other = Files.createDirectories(home.resolve("Pictures").resolve("Garden"));
        Files.write(other.resolve("IMG_0100.JPG"), TestImages.jpeg(64, 48, 9));
        long second = ok(send("POST", "api/imports", path(other))).at("/import/id").asLong();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        refused(send("POST", "api/imports/" + id + "/relocate", path(other)), 409, "folder_in_use",
                "Another import already uses that folder.");

        ok(send("POST", "api/imports/" + id + "/finish", "{\"skipRest\":false}"));
        for (String[] call : new String[][] {
                {"assign", "{\"itemIds\":[" + item + "],\"taxonId\":\"A5\"}"},
                {"tags", "{\"itemIds\":[" + item + "],\"add\":[\"male\"]}"},
                {"skip", "{\"itemIds\":[" + item + "],\"skipped\":true}"},
                {"accept-suggestions", "{\"itemIds\":[" + item + "]}"},
                {"place", "{\"itemIds\":[" + item + "],\"placeId\":\"P5\"}"},
                {"file", fileBody(id)},
                {"relocate", path(photos)}}) {
            refused(send("POST", "api/imports/" + id + "/" + call[0], call[1]), 409, "import_finished",
                    "This import is finished.");
        }
        assertThat(ok(send("POST", "api/imports/" + id + "/finish", "{\"skipRest\":true}")).at("/import/state").asText())
                .as("finishing again changes nothing").isEqualTo("finished");
        assertThat(send("DELETE", "api/imports/" + second, null).statusCode()).isEqualTo(204);
    }

    @Test
    void anImportSummaryHasTheContractsFieldsInOrder() throws Exception {
        long id = started();
        JsonNode summary = get("api/imports/" + id);
        assertThat(fields(summary)).containsExactly("id", "rootPath", "rootName", "state", "createdAt", "updatedAt",
                "folderMissing", "paused", "phase", "progress", "stopped", "notice", "updateNote", "counts", "filings", "pairs", "heic",
                "revision");
        assertThat(summary.get("pairs").isNull()).as("no RAW + JPEG pairs").isTrue();
        assertThat(summary.get("heic").isNull()).as("no HEIC items read (the fixture's is a sequence)").isTrue();
        assertThat(summary.get("notice").isNull()).isTrue();
        assertThat(summary.get("filings").asInt()).as("no filing yet").isZero();
        assertThat(summary.get("progress").isNull()).isTrue();
        assertThat(summary.get("phase").asText()).isEqualTo("idle");
        assertThat(summary.get("counts").toString()).isEqualTo("{\"total\":3,\"photos\":2,\"reading\":0,\"unassigned\":1,"
                + "\"suggested\":1,\"assigned\":0,\"skipped\":0,\"problems\":1,\"filed\":0,\"missing\":0}");
        assertThat(fields(get("api/status"))).containsExactly("rescanning", "rescan", "previews", "deletedCount",
                "processing", "import", "stoppedImports", "export", "taxonomyUpdate", "splitsToSort", "customMatches",
                "library", "placeUpdate", "backup", "heic", "rescanLeft", "libraryVersion", "theme");
    }

    @Test
    void theListIsOpenImportsFirstThenNewestEachWithItsOwnCounts() throws Exception {
        long first = started();
        Path other = Files.createDirectories(home.resolve("Pictures").resolve("Garden"));
        Files.write(other.resolve("IMG_0100.JPG"), TestImages.jpeg(64, 48, 9));
        long second = ok(send("POST", "api/imports", path(other))).at("/import/id").asLong();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        Path third = Files.createDirectories(home.resolve("Pictures").resolve("Hills"));
        Files.write(third.resolve("IMG_0200.JPG"), TestImages.jpeg(64, 48, 10));
        long newest = ok(send("POST", "api/imports", path(third))).at("/import/id").asLong();
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        ok(send("POST", "api/imports/" + newest + "/finish", "{\"skipRest\":true}"));

        JsonNode list = get("api/imports").get("imports");
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(second);
        assertThat(list.get(1).get("id").asLong()).isEqualTo(first);
        assertThat(list.get(2).get("id").asLong()).isEqualTo(newest);
        assertThat(list.get(0).at("/counts/total").asInt()).isEqualTo(1);
        assertThat(list.get(1).at("/counts/total").asInt()).isEqualTo(3);
        assertThat(list.get(1).at("/counts/problems").asInt()).isEqualTo(1);
        assertThat(list.get(2).at("/counts/skipped").asInt()).isEqualTo(1);
        assertThat(list.get(2).get("state").asText()).isEqualTo("finished");
        assertThat(fields(list.get(0))).contains("notice");
    }

    @Test
    void aFiledPhotosPreviewIsItsLibraryThumbnail() throws Exception {
        long id = started();
        JsonNode tiger = items(id).get("Plain tiger/IMG_0001.JPG");
        String preview = "api/imports/" + id + "/items/" + tiger.get("id").asLong() + "/preview?v=" + tiger.get("v").asText();
        byte[] before = http.send(HttpRequest.newBuilder(server.baseUri().resolve(preview)).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
        ok(send("POST", "api/imports/" + id + "/accept-suggestions", "{\"itemIds\":[" + tiger.get("id").asLong() + "]}"));
        assertThat(send("POST", "api/imports/" + id + "/file", fileBody(id)).statusCode()).isEqualTo(202);
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        long imageId = items(id).get("Plain tiger/IMG_0001.JPG").get("imageId").asLong();
        Path thumb = services.thumbnails().await(imageId, Duration.ofSeconds(10)).orElseThrow();
        assertThat(Files.readAllBytes(thumb)).as("the import's preview became the thumbnail").isEqualTo(before);

        for (int round = 0; round < 2; round++) {
            HttpResponse<byte[]> answer = http.send(HttpRequest.newBuilder(server.baseUri().resolve(preview)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(answer.headers().firstValue("Content-Type")).contains("image/jpeg");
            assertThat(answer.headers().firstValue("X-Atlas-Pending")).isEmpty();
            assertThat(answer.headers().firstValue("Cross-Origin-Resource-Policy")).contains("same-origin");
            assertThat(answer.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(answer.body()).isEqualTo(before);
            // and still after the import is finished (its own previews gone)
            ok(send("POST", "api/imports/" + id + "/finish", "{\"skipRest\":true}"));
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions make the library unwritable")
    void filingThatTheLibraryCannotTakeLeavesANotice() throws Exception {
        long id = started();
        long tiger = items(id).get("Plain tiger/IMG_0001.JPG").get("id").asLong();
        ok(send("POST", "api/imports/" + id + "/accept-suggestions", "{\"itemIds\":[" + tiger + "]}"));
        Path incoming = Files.createDirectories(home.resolve("Atlas").resolve(".incoming"));
        Files.setPosixFilePermissions(incoming, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(incoming), "the folder really is read-only (root ignores r-x)");
            assertThat(send("POST", "api/imports/" + id + "/file", fileBody(id)).statusCode()).isEqualTo(202);
            assertThat(services.imports().awaitIdle(WAIT)).isTrue();
            JsonNode summary = get("api/imports/" + id);
            assertThat(summary.get("notice").asText()).isEqualTo("Butterfly Atlas can't save photos in your library folder."
                    + " Check that its drive is connected, then continue filing.");
            assertThat(summary.at("/counts/assigned").asInt()).as("left as they were").isEqualTo(1);
            assertThat(summary.at("/counts/problems").asInt()).as("no photo blamed").isEqualTo(1);
        } finally {
            Files.setPosixFilePermissions(incoming, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(send("POST", "api/imports/" + id + "/file", fileBody(id)).statusCode()).isEqualTo(202);
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(get("api/imports/" + id).get("notice").isNull()).as("cleared when filing starts again").isTrue();
        assertThat(get("api/imports/" + id).at("/counts/filed").asInt()).isEqualTo(1);
    }
}
