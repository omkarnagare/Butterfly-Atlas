package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Import folder with RAW photos and RAW + JPEG pairs (7a contract §3). */
class RawImportApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;
    private Path photos;
    private byte[] nef1;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home);
        server = AtlasServer.start(0, services);
        photos = Files.createDirectories(home.resolve("Pictures").resolve("Card"));
        Path day1 = Files.createDirectories(photos.resolve("Day 1"));
        nef1 = TestRaw.tiff(new TestRaw.Ifd().shortValue(0x0112, 6).jpeg(TestImages.jpeg(1024, 768, 11)));
        Files.write(day1.resolve("DSC_0001.NEF"), nef1);
        Files.write(day1.resolve("DSC_0001.JPG"), TestImages.jpeg(64, 48, 1));
        Files.write(day1.resolve("DSC_0002.NEF"), TestRaw.tiff(new TestRaw.Ifd().jpeg(TestImages.jpeg(1024, 768, 12))));
        Files.write(day1.resolve("dsc_0002.jpeg"), TestImages.jpeg(64, 48, 2));
        Files.write(day1.resolve("DSC_0003.NEF"), TestRaw.tiff(new TestRaw.Ifd().jpeg(TestImages.jpeg(1024, 768, 13))));
        Files.write(day1.resolve("X.CR2"), TestRaw.cr2(new TestRaw.Ifd().shortValue(0x0112, 1))); // no preview
        Files.write(day1.resolve("X.JPG"), TestImages.jpeg(64, 48, 3));
        Files.write(Files.createDirectories(photos.resolve("Day 2")).resolve("DSC_0001.JPG"), TestImages.jpeg(64, 48, 4));
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
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(WAIT)
                .header("Origin", "http://127.0.0.1:" + server.port());
        if (json != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 202);
        return JSON.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        return ok(send("GET", path, null));
    }

    private static void refused(HttpResponse<String> response, int status, String code, String message) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).isEqualTo("{\"error\":{\"code\":" + JSON.writeValueAsString(code) + ",\"message\":"
                + JSON.writeValueAsString(message) + "}}");
    }

    private long started() throws Exception {
        JsonNode body = ok(send("POST", "api/imports", JSON.writeValueAsString(Map.of("path", photos.toString()))));
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        return body.at("/import/id").asLong();
    }

    private Map<String, JsonNode> items(long id) throws Exception {
        Map<String, JsonNode> byPath = new HashMap<>();
        get("api/imports/" + id + "/items").get("items").forEach(item -> byPath.put(item.get("path").asText(), item));
        return byPath;
    }

    private long id(Map<String, JsonNode> items, String path) {
        return items.get(path).get("id").asLong();
    }

    @Test
    void pairsFormBetweenARawAndAJpegOfOneNameAndCountOnce() throws Exception {
        long id = started();

        Map<String, JsonNode> items = items(id);
        JsonNode raw = items.get("Day 1/DSC_0001.NEF");
        JsonNode jpeg = items.get("Day 1/DSC_0001.JPG");
        assertThat(raw.get("fileType").asText()).isEqualTo("raw");
        assertThat(raw.get("rawPreview").toString()).isEqualTo("{\"width\":768,\"height\":1024,\"orientation\":6}");
        assertThat(raw.get("pairId").asLong()).isEqualTo(jpeg.get("id").asLong());
        assertThat(jpeg.get("pairId").asLong()).isEqualTo(raw.get("id").asLong());
        assertThat(jpeg.get("fileType").asText()).isEqualTo("jpeg");
        assertThat(jpeg.get("rawPreview").isNull()).isTrue();
        assertThat(items.get("Day 1/dsc_0002.jpeg").get("pairId").asLong()).as("names compared without case")
                .isEqualTo(id(items, "Day 1/DSC_0002.NEF"));
        assertThat(items.get("Day 1/DSC_0003.NEF").get("pairId").isNull()).isTrue();
        assertThat(items.get("Day 2/DSC_0001.JPG").get("pairId").isNull()).as("another folder").isTrue();
        assertThat(items.get("Day 1/X.CR2").get("problem").asText()).isEqualTo("This RAW photo has no preview Butterfly"
                + " Atlas can show. Save it as JPEG from your RAW software and add that.");
        assertThat(items.get("Day 1/X.JPG").get("pairId").isNull()).as("a problem is never half a pair").isTrue();

        JsonNode summary = get("api/imports/" + id);
        assertThat(summary.get("pairs").toString()).isEqualTo("{\"count\":2,\"toFile\":0,\"choice\":null,\"locked\":false,\"kinds\":[\"jpeg\"]}");
        assertThat(summary.at("/counts/total").asInt()).as("6 shots of 8 files").isEqualTo(6);
        assertThat(summary.at("/counts/problems").asInt()).isEqualTo(1);

        HttpResponse<byte[]> display = http.send(HttpRequest.newBuilder(server.baseUri().resolve("api/imports/" + id
                + "/items/" + raw.get("id").asLong() + "/display")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(display.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(display.body()).isEqualTo(TestImages.jpeg(1024, 768, 11));
    }

    @Test
    void filingWaitsForTheChoiceAndJpegOnlyLeavesTheRawOut() throws Exception {
        long id = started();
        Map<String, JsonNode> items = items(id);
        long raw = id(items, "Day 1/DSC_0001.NEF");
        long jpeg = id(items, "Day 1/DSC_0001.JPG");

        JsonNode assigned = ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + raw + "],\"taxonId\":\"A5\"}"));
        assertThat(assigned.get("items")).extracting(i -> i.get("id").asLong()).containsExactlyInAnyOrder(raw, jpeg);
        ok(send("POST", "api/imports/" + id + "/tags", "{\"itemIds\":[" + jpeg + "],\"add\":[\"male\"]}"));
        assertThat(items(id).get("Day 1/DSC_0001.NEF").get("tags").toString()).isEqualTo("[\"male\"]");
        assertThat(get("api/imports/" + id).get("pairs").toString())
                .isEqualTo("{\"count\":2,\"toFile\":1,\"choice\":null,\"locked\":false,\"kinds\":[\"jpeg\"]}");

        refused(send("POST", "api/imports/" + id + "/file", fileBody(id)), 409, "pair_choice_needed",
                "Choose how to file the 1 RAW + JPEG pair first.");
        refused(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"jpg\"}"), 400, "invalid", "Unknown choice “jpg”.");
        JsonNode chosen = ok(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"jpeg\"}"));
        assertThat(chosen.at("/pairs/choice").asText()).isEqualTo("jpeg");
        assertThat(items(id).get("Day 1/DSC_0001.NEF").get("outcome").isNull())
                .as("left out only when its JPEG is filed (fixes J3)").isTrue();
        ok(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"raw\"}"));
        ok(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"jpeg\"}"));

        ok(send("POST", "api/imports/" + id + "/file", fileBody(id)));
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();

        items = items(id);
        assertThat(items.get("Day 1/DSC_0001.JPG").get("outcome").asText()).isEqualTo("filed");
        assertThat(items.get("Day 1/DSC_0001.NEF").get("outcome").asText()).isEqualTo("pair_left");
        assertThat(items.get("Day 1/DSC_0001.NEF").get("error").asText())
                .isEqualTo("The RAW of this pair was not filed: you chose JPEG only.");
        JsonNode result = get("api/imports/" + id + "/result");
        assertThat(result.get("filed").asInt()).isEqualTo(1);
        assertThat(result.get("pairLeft").asInt()).isEqualTo(1);
        assertThat(result.get("pairChoice").asText()).isEqualTo("jpeg");
        assertThat(result.get("skipped").asInt()).isZero();
        JsonNode summary = get("api/imports/" + id);
        assertThat(summary.get("pairs").toString()).isEqualTo("{\"count\":2,\"toFile\":0,\"choice\":\"jpeg\",\"locked\":true,\"kinds\":[\"jpeg\"]}");
        assertThat(summary.at("/counts/filed").asInt()).as("one shot").isEqualTo(1);
        refused(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"raw\"}"), 409, "pair_choice_locked",
                "Some pairs were already filed as you chose. Start a new import to file them differently.");
    }

    @Test
    void bothFilesBothHalvesUnderOneSpeciesAndTheRawKeepsItsExtension() throws Exception {
        long id = started();
        Map<String, JsonNode> items = items(id);
        long raw = id(items, "Day 1/DSC_0001.NEF");
        ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + raw + "],\"taxonId\":\"A5\"}"));
        ok(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"both\"}"));

        ok(send("POST", "api/imports/" + id + "/file", fileBody(id)));
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();

        items = items(id);
        assertThat(items.get("Day 1/DSC_0001.NEF").get("outcome").asText()).isEqualTo("filed");
        assertThat(items.get("Day 1/DSC_0001.JPG").get("outcome").asText()).isEqualTo("filed");
        JsonNode photos = get("api/taxa/A5/photos").get("photos");
        assertThat(photos.size()).isEqualTo(2);
        JsonNode rawPhoto = photos.get(0).get("fileType").asText().equals("raw") ? photos.get(0) : photos.get(1);
        assertThat(rawPhoto.get("fileName").asText()).endsWith(".nef");
        try (Stream<Path> files = Files.walk(home.resolve("Atlas").resolve("images"))) {
            Path filed = files.filter(f -> f.getFileName().toString().endsWith(".nef")).findFirst().orElseThrow();
            assertThat(Files.readAllBytes(filed)).isEqualTo(nef1);
        }
        assertThat(get("api/imports/" + id + "/result").get("pairLeft").asInt()).isZero();
    }

    @Test
    void rawOnlyLeavesTheJpegOut() throws Exception {
        long id = started();
        long raw = id(items(id), "Day 1/DSC_0002.NEF");
        ok(send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + raw + "],\"taxonId\":\"A6\"}"));
        ok(send("PUT", "api/imports/" + id + "/pairs", "{\"choice\":\"raw\"}"));

        ok(send("POST", "api/imports/" + id + "/file", fileBody(id)));
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();

        Map<String, JsonNode> items = items(id);
        assertThat(items.get("Day 1/DSC_0002.NEF").get("outcome").asText()).isEqualTo("filed");
        assertThat(items.get("Day 1/dsc_0002.jpeg").get("outcome").asText()).isEqualTo("pair_left");
        assertThat(items.get("Day 1/dsc_0002.jpeg").get("error").asText())
                .isEqualTo("The JPEG of this pair was not filed: you chose RAW only.");
        assertThat(get("api/imports/" + id).at("/counts/filed").asInt()).isEqualTo(1);
        assertThat(new String(Files.readAllBytes(photos.resolve("Day 1").resolve("dsc_0002.jpeg")), StandardCharsets.ISO_8859_1))
                .as("the source is only read").isNotEmpty();
    }
}
