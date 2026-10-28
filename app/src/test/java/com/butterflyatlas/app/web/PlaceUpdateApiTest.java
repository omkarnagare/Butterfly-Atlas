package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.testing.UpdatePlaces;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A place update's note over HTTP (5c contract §6, §9): the note, Dismiss, /api/status, Try again. */
class PlaceUpdateApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;

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

    private HttpResponse<String> send(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Origin", "http://127.0.0.1:" + server.port()).header("Content-Type", "application/json")
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}")
                        : HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode status() throws Exception {
        return JSON.readTree(send("GET", "api/status").body());
    }

    @Test
    void theNoteIsServedUntilDismissedAndThenOnlyItsFormerPartWhilePhotosAreLeft() throws Exception {
        AtlasServices first = TestServices.configured(home);
        long photo = first.photos().add("A5", new ByteArrayInputStream(TestImages.jpeg(40, 30, 1)), "image/jpeg", null,
                List.of()).photo().id();
        first.photoPlaces().place(List.of(photo), "P4", false);
        first.imports().close();
        first.exports().close();
        first.thumbnails().close();
        first.placeNotes().close();
        services = TestServices.configured(home, UpdatePlaces.bundles(UpdatePlaces.fixture(), UpdatePlaces.fixtureNext()));
        server = AtlasServer.start(0, services);

        assertThat(status().get("placeUpdate").toString()).isEqualTo("{\"note\":true,\"formerPhotos\":1,\"notChecked\":0,"
                + "\"pending\":1,\"moved\":0,\"lost\":0,\"rechecking\":true,\"failed\":false}");
        services.processing().run();
        long checked = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (status().get("placeUpdate").get("rechecking").asBoolean() && System.nanoTime() < checked) {
            Thread.sleep(10);
        }
        assertThat(status().get("placeUpdate").toString()).as("the re-check over; the photo placed by hand stays")
                .isEqualTo("{\"note\":true,\"formerPhotos\":1,\"notChecked\":0,\"pending\":0,\"moved\":0,\"lost\":0,"
                        + "\"rechecking\":false,\"failed\":false}");
        HttpResponse<String> note = send("GET", "api/place-update");
        assertThat(note.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(note.body());
        List<String> fields = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("dismissed", "versions", "appliedAt", "counts", "items", "mine", "kept", "moved",
                "former", "notChecked", "lost", "pending", "rechecking");
        assertThat(body.get("items").get(0).toString()).isEqualTo("{\"type\":\"split\",\"from\":[{\"id\":\"P4\",\"name\":"
                + "\"Alpha (former district)\",\"path\":[\"Alpha (former district)\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"former\":true}],\"to\":"
                + "[{\"id\":\"P11\",\"name\":\"Alpha West\",\"path\":[\"Alpha West\",\"Northstate\",\"Testland\"],\"level\":"
                + "\"district\",\"former\":false},{\"id\":\"P12\",\"name\":\"Alpha East\",\"path\":[\"Alpha East\",\"Northstate\","
                + "\"Testland\"],\"level\":\"district\",\"former\":false}],\"photos\":1}");
        assertThat(body.get("former").toString()).isEqualTo("{\"photos\":1,\"species\":[{\"id\":\"A5\",\"name\":"
                + "\"Danaus chrysippus\",\"common\":\"Plain Tiger\",\"place\":{\"id\":\"P4\",\"name\":\"Alpha (former district)\","
                + "\"path\":[\"Alpha (former district)\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"former\":true},"
                + "\"photos\":1}]}");

        com.butterflyatlas.app.db.Database.open(Path.of(services.setup().status().dataFolder()).resolve("atlas.db"))
                .write(c -> c.createStatement().executeUpdate("UPDATE image_processing SET status = 'failed',"
                        + " error = 'SQLiteException: [SQLITE_FULL] database or disk is full', attempts = 3"
                        + " WHERE consumer = 'place' AND version = 2"));
        JsonNode unchecked = JSON.readTree(send("GET", "api/place-update").body()).get("notChecked").get(0);
        List<String> keys = new java.util.ArrayList<>();
        unchecked.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("photoId", "taxonId", "name", "fileName", "reason", "final");
        assertThat(unchecked.get("final").asBoolean()).isTrue();
        assertThat(unchecked.get("reason").asText()).isEqualTo("The drive with your library was full.");
        com.butterflyatlas.app.db.Database.open(Path.of(services.setup().status().dataFolder()).resolve("atlas.db"))
                .write(c -> c.createStatement().executeUpdate("UPDATE image_processing SET status = 'done', error = NULL,"
                        + " attempts = 0 WHERE consumer = 'place' AND version = 2"));

        assertThat(send("POST", "api/place-update/dismiss").statusCode()).isEqualTo(204);
        assertThat(status().get("placeUpdate").get("note").asBoolean()).isFalse();
        assertThat(JSON.readTree(send("GET", "api/place-update").body()).get("dismissed").asBoolean()).isTrue();

        services.photoPlaces().place(List.of(photo), "P11", false);
        assertThat(send("GET", "api/place-update").statusCode()).isEqualTo(204);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!status().get("placeUpdate").isNull() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(status().get("placeUpdate").isNull()).isTrue();
        HttpResponse<String> retry = send("POST", "api/setup/retry");
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(retry.body()).contains("\"code\":\"not_failed\"");
    }
}
