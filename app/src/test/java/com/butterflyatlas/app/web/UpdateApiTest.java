package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.taxonomy.bundle.Bundle;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.testing.UpdateBundles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Taxonomy updates over HTTP (5b contract §5): every route, the exact shapes, the refusals, /api/status. */
class UpdateApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MELANIPPUS = "Atlas/images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_melanippus_[A6]/";

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;
    private final List<Long> chrysippus = new ArrayList<>();

    /**
     * v1 → v2: Danaus genutia renamed Danaus melanippus; Papilio clytia merged into the new Papilio agestor; Danaus
     * chrysippus split into itself and the new Danaus petilia; Neptis sankara added (a custom species has that name).
     */
    private static Bundle v2() {
        return UpdateBundles.derive(UpdateBundles.fixture(), "2026-02-01").rename("A6", "Danaus melanippus")
                .add("A15", "A11", "species", "Papilio agestor", null).merge("A15", "A14")
                .add("A16", "A4", "species", "Danaus petilia", null).split("A5", "A5", "A16")
                .add("A20", "A3", "genus", "Neptis", null).add("A21", "A20", "species", "Neptis sankara", null).build();
    }

    @BeforeEach
    void start() throws Exception {
        AtlasServices first = TestServices.configured(home);
        int seed = 0;
        for (String taxon : List.of("A5", "A5", "A6", "A6", "A14")) {
            Photo photo = first.photos().add(taxon, new ByteArrayInputStream(TestImages.jpeg(30 + ++seed, 20, seed)),
                    "image/jpeg", null, List.of()).photo();
            if (taxon.equals("A5")) {
                chrysippus.add(photo.id());
            }
        }
        first.custom().add("A1", "Neptis sankara", null);
        first.imports().close();
        first.exports().close();
        first.thumbnails().close();
        services = TestServices.configured(home, UpdateBundles.bundles(UpdateBundles.fixture(), v2()));
        server = AtlasServer.start(0, services);
    }

    @AfterEach
    void stop() {
        server.close();
        services.updates().close();
        services.imports().close();
        services.exports().close();
        services.thumbnails().close();
        services.placeNotes().close(); // its count may still be reading the library (Windows can't delete an open file)
        http.close();
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

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode answer(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

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

    /** Polls {@code GET /api/update} until its state is {@code state}. */
    private JsonNode until(String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode update = get("api/update");
        while (!state.equals(update.get("state").asText()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
            update = get("api/update");
        }
        assertThat(update.get("state").asText()).as(update.toString()).isEqualTo(state);
        assertThat(services.updates().awaitIdle(Duration.ofSeconds(30))).isTrue();
        return update;
    }

    @Test
    void aPendingUpdateIsSummedUpAppliedOnRequestAndReportedDone() throws Exception {
        JsonNode status = get("api/status");
        assertThat(status.get("taxonomyUpdate").toString()).isEqualTo("{\"state\":\"pending\",\"kind\":null,\"later\":false,"
                + "\"done\":0,\"total\":0,\"step\":0,\"steps\":1,\"continuing\":false,\"waitingFor\":null,\"notice\":null,"
                + "\"dismissed\":false}");
        assertThat(status.get("splitsToSort").asInt()).isZero();
        assertThat(status.get("customMatches").asInt()).isZero();

        JsonNode pending = get("api/update");
        assertThat(fields(pending)).containsExactly("state", "kind", "later", "from", "to", "steps", "summary", "summaryProblem",
                "progress", "notice", "result", "resultProblem", "last", "lastProblem");
        assertThat(pending.get("last").isNull()).isTrue();
        assertThat(pending.get("summaryProblem").isNull()).isTrue();
        assertThat(pending.get("state").asText()).isEqualTo("pending");
        assertThat(pending.get("from").asText()).isEqualTo("2026-01-01");
        assertThat(pending.get("to").asText()).isEqualTo("2026-02-01");
        assertThat(pending.get("steps").toString()).isEqualTo("[\"2026-02-01\"]");
        JsonNode summary = pending.get("summary");
        assertThat(fields(summary)).containsExactly("photos", "species", "kinds");
        assertThat(summary.get("photos").asInt()).as("2 renamed, 1 merged, 2 split").isEqualTo(5);
        assertThat(summary.get("species").toString()).isEqualTo("{\"before\":{\"got\":3,\"total\":7},"
                + "\"after\":{\"got\":3,\"total\":9}}");
        assertThat(summary.get("kinds")).extracting(k -> k.get("kind").asText()).containsExactly("rename", "merge", "split", "add");
        JsonNode rename = summary.get("kinds").get(0);
        assertThat(fields(rename)).containsExactly("kind", "count", "photos", "items");
        assertThat(rename.get("items").get(0).toString()).isEqualTo("{\"rank\":\"species\",\"from\":[{\"id\":\"A6\","
                + "\"name\":\"Danaus genutia\",\"common\":\"Striped Tiger\"}],\"to\":[{\"id\":\"A6\",\"name\":\"Danaus melanippus\","
                + "\"common\":\"Striped Tiger\"}],\"photos\":2,\"detail\":\"Danaus genutia → Danaus melanippus\"}");
        assertThat(pending.get("progress").isNull()).isTrue();
        assertThat(pending.get("result").isNull()).isTrue();

        assertThat(send("POST", "api/update/later", "{}").statusCode()).isEqualTo(204);
        assertThat(get("api/update").get("later").asBoolean()).isTrue();
        assertThat(get("api/status").at("/taxonomyUpdate/later").asBoolean()).isTrue();

        JsonNode applying = answer(send("POST", "api/update/apply", "{}"), 202);
        assertThat(applying.get("kind").asText()).isEqualTo("bundle");
        assertThat(applying.get("state").asText()).isIn("running", "waiting", "done");
        JsonNode done = until("done");

        assertThat(done.get("progress").isNull()).isTrue();
        JsonNode result = done.get("result");
        assertThat(fields(result)).containsExactly("from", "to", "photos", "moved", "keptFolders", "notRemoved", "leftBehind",
                "splits", "splitsMerged", "matches", "notes", "finishedAt");
        assertThat(result.get("notes").toString()).isEqualTo("[]");
        assertThat(done.get("last")).isEqualTo(result);
        assertThat(result.get("photos").asInt()).isEqualTo(5);
        assertThat(result.get("keptFolders").toString()).isEqualTo("[]");
        assertThat(result.get("splits").toString()).isEqualTo("[{\"id\":\"A5\",\"name\":\"Danaus chrysippus\","
                + "\"common\":\"Plain Tiger\",\"family\":\"Nymphalidae\",\"photos\":2,\"version\":\"2026-02-01\",\"targets\":["
                + "{\"id\":\"A5\",\"name\":\"Danaus chrysippus\",\"common\":\"Plain Tiger\"},"
                + "{\"id\":\"A16\",\"name\":\"Danaus petilia\",\"common\":null}]}]");
        assertThat(result.get("matches").toString()).isEqualTo("[{\"custom\":{\"id\":\"U1\",\"name\":\"Neptis sankara\","
                + "\"common\":null,\"photos\":0},\"official\":{\"id\":\"A21\",\"name\":\"Neptis sankara\",\"common\":null,"
                + "\"family\":\"Nymphalidae\"},\"offeredVersion\":\"2026-02-01\"}]");
        assertThat(home.resolve(MELANIPPUS + "Danaus_melanippus_02.jpg")).isRegularFile();
        status = get("api/status");
        assertThat(status.get("taxonomyUpdate").toString()).isEqualTo("{\"state\":\"done\",\"kind\":\"bundle\",\"later\":true,"
                + "\"done\":3,\"total\":3,\"step\":1,\"steps\":1,\"continuing\":false,\"waitingFor\":null,\"notice\":null,"
                + "\"dismissed\":false}");
        assertThat(status.get("splitsToSort").asInt()).isEqualTo(1);
        assertThat(status.get("customMatches").asInt()).isEqualTo(1);

        refused(send("POST", "api/update/apply", "{}"), 409, "no_update", "There is no taxonomy update to apply.");
        refused(send("POST", "api/update/retry", "{}"), 409, "not_paused", "The taxonomy update is not paused.");
        // Home's "The taxonomy was updated" card: its Dismiss holds across page reloads (the status says it).
        assertThat(send("POST", "api/update/dismiss", "{}").statusCode()).isEqualTo(204);
        assertThat(get("api/status").at("/taxonomyUpdate/dismissed").asBoolean()).isTrue();
    }

    @Test
    void splitPhotosAreListedAndSorted() throws Exception {
        answer(send("POST", "api/update/apply", "{}"), 202);
        until("done");

        assertThat(get("api/splits").get("splits")).extracting(s -> s.get("id").asText()).containsExactly("A5");
        JsonNode split = get("api/splits/A5");
        assertThat(fields(split)).containsExactly("taxon", "targets", "missingTargets", "left", "total", "photos");
        assertThat(split.get("targets").get(1).toString()).isEqualTo("{\"id\":\"A16\",\"name\":\"Danaus petilia\","
                + "\"common\":null,\"family\":\"Nymphalidae\",\"species\":{\"got\":0,\"total\":1}}");
        assertThat(split.get("left").asInt()).isEqualTo(2);
        assertThat(fields(split.get("photos").get(0))).as("as in GET /api/taxa/{id}/photos")
                .isEqualTo(fields(get("api/taxa/A5/photos").get("photos").get(0)));
        assertThat(get("api/taxa/A5").get("toSort").asInt()).isEqualTo(2);
        assertThat(get("api/search?q=chrysippus").get("results").get(0).get("toSort").asInt()).isEqualTo(2);

        refused(send("POST", "api/splits/A5/sort", "{\"photoIds\":[" + chrysippus.getFirst() + "],\"target\":\"A6\"}"), 400,
                "invalid", "Choose one of the species Danaus chrysippus was split into.");
        refused(send("GET", "api/splits/A6", null), 404, "not_found", "There is no split species A6 in your atlas.");

        JsonNode sorted = get("api/splits/A5"); // unchanged by the refusals
        assertThat(sorted.get("left").asInt()).isEqualTo(2);
        JsonNode after = answer(send("POST", "api/splits/A5/sort", "{\"photoIds\":[" + chrysippus.getFirst()
                + "],\"target\":\"A16\"}"), 200);
        assertThat(after.get("left").asInt()).isEqualTo(1);
        assertThat(after.get("total").asInt()).isEqualTo(2);
        assertThat(get("api/taxa/A5").get("toSort").asInt()).isEqualTo(1);
        assertThat(get("api/taxa/A16/photos").get("photos")).hasSize(1);
    }

    @Test
    void aCustomMatchIsListedMergedOrDeclined() throws Exception {
        answer(send("POST", "api/update/apply", "{}"), 202);
        until("done");

        JsonNode matches = get("api/custom-matches");
        assertThat(fields(matches)).containsExactly("matches");
        assertThat(matches.get("matches")).hasSize(1);
        refused(send("POST", "api/custom-matches/U1/decline", "{\"officialId\":\"A6\"}"), 404, "not_found",
                "There is no open match of U1 with A6.");
        refused(send("POST", "api/custom-matches/U1/merge", "{\"officialId\":\"A5\"}"), 409, "no_match",
                "U1 is not offered for merging into A5.");

        JsonNode merging = answer(send("POST", "api/custom-matches/U1/merge", "{\"officialId\":\"A21\"}"), 202);
        assertThat(merging.get("kind").asText()).isEqualTo("match");
        JsonNode merged = until("done");

        assertThat(merged.get("kind").asText()).isEqualTo("match");
        assertThat(get("api/custom-matches").get("matches")).isEmpty();
        assertThat(get("api/status").get("customMatches").asInt()).isZero();
        refused(send("GET", "api/taxa/U1", null), 404, "not_found", "There is no taxon U1 in your atlas.");
    }

    @Test
    void aDeclinedMatchGoesAndAPausedUpdateRefusesChangesThatMovePhotos() throws Exception {
        // A file in the way of a rename: the update pauses before anything moves.
        Path inTheWay = home.resolve(MELANIPPUS + "Danaus_melanippus_01.jpg");
        Files.createDirectories(inTheWay.getParent());
        Files.writeString(inTheWay, "someone else's");

        answer(send("POST", "api/update/apply", "{}"), 202);
        JsonNode paused = until("paused");

        assertThat(paused.get("notice").toString()).isEqualTo("{\"code\":\"target_exists\",\"message\":\"A file named"
                + " Danaus_melanippus_01.jpg is already in Danaus_melanippus_[A6]. Move it out of the way, then choose Retry.\"}");
        assertThat(paused.get("progress").toString()).isEqualTo("{\"step\":1,\"steps\":1,\"stage\":\"plan\",\"done\":0,"
                + "\"total\":0,\"continuing\":false,\"waitingFor\":null}");
        assertThat(get("api/status").at("/taxonomyUpdate/notice/code").asText()).isEqualTo("target_exists");
        String wait = "The taxonomy update is paused, so this can't be done yet. Open Taxonomy update to see why, then choose"
                + " Retry.";
        refused(send("POST", "api/photos/delete", "{\"ids\":[" + chrysippus.getFirst() + "]}"), 409, "taxonomy_updating", wait);
        refused(send("POST", "api/photos/move", "{\"ids\":[" + chrysippus.getFirst() + "],\"taxonId\":\"A6\"}"), 409,
                "taxonomy_updating", wait);
        refused(send("POST", "api/custom-species", "{\"familyId\":\"A1\",\"name\":\"Neptis soma\"}"), 409,
                "taxonomy_updating", wait);
        refused(send("POST", "api/exports", "{\"what\":\"photos\",\"path\":" + JSON.writeValueAsString(home.toString())
                + "}"), 409, "taxonomy_updating", wait);
        assertThat(get("api/taxa/A5").get("id").asText()).as("browsing works").isEqualTo("A5");

        Files.delete(inTheWay);
        answer(send("POST", "api/update/retry", "{}"), 202);
        until("done");
        assertThat(send("POST", "api/custom-matches/U1/decline", "{\"officialId\":\"A21\"}").statusCode()).isEqualTo(204);
        assertThat(get("api/custom-matches").get("matches")).isEmpty();
        assertThat(get("api/status").get("customMatches").asInt()).isZero();
    }
}
