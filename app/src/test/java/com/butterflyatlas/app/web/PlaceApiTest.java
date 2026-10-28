package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Places and the place of photos over HTTP (3c-2 contract §1): every success and error, exact JSON. */
class PlaceApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALPHA = "{\"id\":\"P4\",\"name\":\"Alpha\",\"path\":[\"Alpha\",\"Northstate\",\"Testland\"],"
            + "\"level\":\"district\",\"mine\":false,\"former\":false}";
    private static final String BETA = "{\"id\":\"P5\",\"name\":\"Beta\",\"path\":[\"Beta\",\"Northstate\",\"Testland\"],"
            + "\"level\":\"district\",\"mine\":false,\"former\":false}";
    private static final String BETA_USER = "{\"id\":\"P5\",\"path\":[\"Beta\",\"Northstate\",\"Testland\"],\"ids\":[\"P5\",\"P2\",\"P1\"],\"source\":\"user\",\"former\":false,\"level\":\"district\"}";

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
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Origin", "http://127.0.0.1:" + server.port());
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

    private void exec(String sql) {
        Database.open(home.resolve("Atlas").resolve(Database.FILE_NAME)).write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Adds a photo through Add photos and waits for its preview; returns it as the API gave it. */
    private ObjectNode added(byte[] jpeg) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(server.baseUri().resolve("api/taxa/A5/photos"))
                .timeout(Duration.ofSeconds(20)).header("Origin", "http://127.0.0.1:" + server.port())
                .header("Content-Type", "image/jpeg").POST(HttpRequest.BodyPublishers.ofByteArray(jpeg)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        for (int i = 0; i < 200 && JSON.readTree(send("GET", "api/status", null).body()).at("/previews/pending").asInt() > 0; i++) {
            Thread.sleep(50);
        }
        assertThat(JSON.readTree(send("GET", "api/status", null).body()).at("/previews/pending").asInt()).as("previews finished").isZero();
        return (ObjectNode) JSON.readTree(response.body()).get("photo");
    }

    /** The photo as the API shows it now (one of species A5's). */
    private ObjectNode photo(long id) throws Exception {
        for (JsonNode p : JSON.readTree(send("GET", "api/taxa/A5/photos", null).body()).get("photos")) {
            if (p.get("id").asLong() == id) {
                return (ObjectNode) p;
            }
        }
        throw new AssertionError("no photo " + id);
    }

    private static String withPlace(ObjectNode photo, String place) throws Exception {
        ObjectNode copy = photo.deepCopy();
        copy.set("place", JSON.readTree(place));
        return JSON.writeValueAsString(copy);
    }

    @Test
    void searchFindsPlacesByNameAndOtherNames() throws Exception {
        answers(send("GET", "api/places/search?q=al", null), 200, "{\"places\":[" + ALPHA + "],\"formerMatches\":[]}");
        answers(send("GET", "api/places/search?q=ALFA", null), 200, "{\"places\":[" + ALPHA + "],\"formerMatches\":[]}");
        answers(send("GET", "api/places/search?q=north&level=state", null), 200, "{\"places\":[{\"id\":\"P2\","
                + "\"name\":\"Northstate\",\"path\":[\"Northstate\",\"Testland\"],\"level\":\"state\",\"mine\":false,\"former\":false}],\"formerMatches\":[]}");
        answers(send("GET", "api/places/search?q=farl", null), 200, "{\"places\":[{\"id\":\"P8\",\"name\":\"Farland\","
                + "\"path\":[\"Farland\"],\"level\":\"country\",\"mine\":false,\"former\":false}],\"formerMatches\":[]}");
        answers(send("GET", "api/places/search?q=a", null), 200, "{\"places\":[],\"formerMatches\":[]}");
        answers(send("GET", "api/places/search", null), 200, "{\"places\":[],\"formerMatches\":[]}");
        refused(send("GET", "api/places/search?q=al&level=town", null), 400, "invalid", "Search districts, states or countries.");
        answers(send("GET", "api/places/recent", null), 200, "{\"places\":[]}");
        answers(send("GET", "api/places/mine", null), 200, "{\"places\":[]}");
    }

    @Test
    void userPlacesAreAddedRenamedListedAndDeleted() throws Exception {
        answers(send("POST", "api/places", "{\"name\":\" Tamhini \",\"parentId\":\"P2\"}"), 201, "{\"id\":\"PU1\","
                + "\"name\":\"Tamhini\",\"path\":[\"Tamhini\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":true,\"former\":false}");
        answers(send("PATCH", "api/places/PU1", "{\"name\":\"Tamhini Ghat\"}"), 200, "{\"id\":\"PU1\","
                + "\"name\":\"Tamhini Ghat\",\"path\":[\"Tamhini Ghat\",\"Northstate\",\"Testland\"],\"level\":\"district\","
                + "\"mine\":true,\"former\":false}");
        answers(send("GET", "api/places/search?q=tamhini", null), 200, "{\"places\":[{\"id\":\"PU1\",\"name\":\"Tamhini Ghat\","
                + "\"path\":[\"Tamhini Ghat\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":true,\"former\":false}],\"formerMatches\":[]}");
        long photo = added(TestImages.jpeg(64, 48, 1)).get("id").asLong();
        answers(send("POST", "api/photos/place", "{\"ids\":[" + photo + "],\"placeId\":\"PU1\"}"), 200,
                "{\"photos\":[" + withPlace(photo(photo), "{\"id\":\"PU1\",\"path\":[\"Tamhini Ghat\",\"Northstate\","
                        + "\"Testland\"],\"ids\":[\"PU1\",\"P2\",\"P1\"],\"source\":\"user\",\"former\":false,\"level\":\"district\"}") + "]}");
        answers(send("GET", "api/places/mine", null), 200, "{\"places\":[{\"id\":\"PU1\",\"name\":\"Tamhini Ghat\","
                + "\"path\":[\"Tamhini Ghat\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":true,\"photos\":1}]}");

        answers(send("DELETE", "api/places/PU1", null), 200, "{\"photosCleared\":1}");

        assertThat(photo(photo).get("place").isNull()).isTrue();
        answers(send("GET", "api/places/mine", null), 200, "{\"places\":[]}");
        answers(send("GET", "api/places/recent", null), 200, "{\"places\":[]}");
        answers(send("POST", "api/places", "{\"name\":\"Tamhini\",\"parentId\":\"P2\"}"), 201, "{\"id\":\"PU2\","
                + "\"name\":\"Tamhini\",\"path\":[\"Tamhini\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":true,\"former\":false}");
    }

    @Test
    void placeEditsAreRefusedWithTheirOwnCodes() throws Exception {
        refused(send("POST", "api/places", "{\"name\":\"  \",\"parentId\":\"P2\"}"), 400, "invalid", "Give the place a name.");
        refused(send("POST", "api/places", "{\"parentId\":\"P2\"}"), 400, "invalid", "Give the place a name.");
        refused(send("POST", "api/places", "{\"name\":\"" + "x".repeat(81) + "\",\"parentId\":\"P2\"}"), 400, "invalid",
                "Keep place names to 80 characters or fewer.");
        refused(send("POST", "api/places", "{\"name\":\"Tamhini\",\"parentId\":\"P1\"}"), 400, "invalid",
                "Choose the state it is in.");
        refused(send("POST", "api/places", "{\"name\":\"Tamhini\"}"), 400, "invalid", "Choose the state it is in.");
        refused(send("POST", "api/places", "{\"name\":\"alpha\",\"parentId\":\"P2\"}"), 409, "place_exists",
                "Alpha is already in Northstate.");
        refused(send("PATCH", "api/places/P4", "{\"name\":\"Alpha Nagar\"}"), 409, "bundled_place",
                "Places that come with Butterfly Atlas can't be renamed.");
        refused(send("DELETE", "api/places/P4", null), 409, "bundled_place",
                "Places that come with Butterfly Atlas can't be deleted.");
        refused(send("PATCH", "api/places/PU9", "{\"name\":\"X\"}"), 404, "not_found", "There is no such place.");
        refused(send("DELETE", "api/places/PU9", null), 404, "not_found", "There is no such place.");
        send("POST", "api/places", "{\"name\":\"Tamhini\",\"parentId\":\"P2\"}");
        refused(send("PATCH", "api/places/PU1", "{\"name\":\"BETA\"}"), 409, "place_exists", "Beta is already in Northstate.");
        refused(send("PATCH", "api/places/PU1", "{}"), 400, "invalid", "Give the place a name.");
    }

    @Test
    void malformedBodiesAreBadRequests() throws Exception {
        for (String[] call : new String[][] {
                {"POST", "api/places", "not json"}, {"POST", "api/places", "{\"name\":[\"Tamhini\"],\"parentId\":\"P2\"}"},
                {"PATCH", "api/places/P4", "not json"}, {"PATCH", "api/places/P4", "{\"name\":{}}"},
                {"POST", "api/photos/place", "not json"}, {"POST", "api/photos/place", "{\"ids\":\"1\",\"placeId\":\"P5\"}"},
                {"POST", "api/photos/place", "{\"ids\":[1],\"placeId\":\"P5\",\"remove\":\"yes\"}"}}) {
            HttpResponse<String> response = send(call[0], call[1], call[2]);
            assertThat(response.statusCode()).as(call[0] + " " + call[1] + " " + call[2]).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).at("/error/code").asText()).as(call[2]).isEqualTo("bad_request");
        }
        answers(send("GET", "api/places/mine", null), 200, "{\"places\":[]}");
    }

    @Test
    void photosGetAPlaceAsTheUsersAndLoseIt() throws Exception {
        ObjectNode gps = added(TestExif.camera().gps(18.5, 73.5, 600).jpeg(TestImages.jpeg(64, 48, 2)));
        ObjectNode none = added(TestImages.jpeg(64, 48, 3));
        assertThat(gps.get("place").toString())
                .isEqualTo("{\"id\":\"P4\",\"path\":[\"Alpha\",\"Northstate\",\"Testland\"],\"ids\":[\"P4\",\"P2\",\"P1\"],\"source\":\"gps\",\"former\":false,\"level\":\"district\"}");
        long a = gps.get("id").asLong();
        long b = none.get("id").asLong();

        answers(send("POST", "api/photos/place", "{\"ids\":[" + b + "," + a + "],\"placeId\":\"P5\"}"), 200,
                "{\"photos\":[" + withPlace(photo(b), BETA_USER) + "," + withPlace(photo(a), BETA_USER) + "]}");
        answers(send("GET", "api/places/recent", null), 200, "{\"places\":[" + BETA + "]}");

        answers(send("POST", "api/photos/place", "{\"ids\":[" + a + "],\"remove\":true}"), 200,
                "{\"photos\":[" + withPlace(photo(a), "null") + "]}");
        assertThat(photo(a).get("place").isNull()).isTrue();
        answers(send("GET", "api/places/recent", null), 200, "{\"places\":[" + BETA + "]}");
    }

    @Test
    void photoPlaceEditsAreRefusedAndChangeNothing() throws Exception {
        long a = added(TestImages.jpeg(64, 48, 4)).get("id").asLong();
        long gone = added(TestImages.jpeg(64, 48, 5)).get("id").asLong();
        assertThat(send("POST", "api/photos/delete", "{\"ids\":[" + gone + "]}").statusCode()).isEqualTo(200);
        exec("UPDATE place SET active = 0 WHERE id = 'P7'");

        refused(send("POST", "api/photos/place", "{\"ids\":[" + a + ",999],\"placeId\":\"P5\"}"), 404, "not_found",
                "There is no photo 999 in your atlas.");
        refused(send("POST", "api/photos/place", "{\"ids\":[" + a + "," + gone + "],\"remove\":true}"), 404, "not_found",
                "There is no photo " + gone + " in your atlas.");
        refused(send("POST", "api/photos/place", "{\"ids\":[" + a + "],\"placeId\":\"P404\"}"), 404, "not_found",
                "There is no such place.");
        refused(send("POST", "api/photos/place", "{\"ids\":[" + a + "],\"placeId\":\"P7\"}"), 404, "not_found",
                "There is no such place.");
        refused(send("POST", "api/photos/place", "{\"ids\":[" + a + "],\"placeId\":\"P5\",\"remove\":true}"), 400, "invalid",
                "Choose a place, or remove the place.");
        refused(send("POST", "api/photos/place", "{\"ids\":[" + a + "]}"), 400, "invalid", "Choose a place, or remove the place.");
        refused(send("POST", "api/photos/place", "{\"ids\":[" + a + "],\"remove\":false}"), 400, "invalid",
                "Choose a place, or remove the place.");
        refused(send("POST", "api/photos/place", "{\"ids\":[],\"placeId\":\"P5\"}"), 400, "invalid", "Choose at least one photo.");
        StringBuilder many = new StringBuilder();
        for (int n = 1; n <= 10_001; n++) {
            many.append(n == 1 ? "" : ",").append(n);
        }
        refused(send("POST", "api/photos/place", "{\"ids\":[" + many + "],\"placeId\":\"P5\"}"), 400, "invalid",
                "Choose at most 10,000 photos at a time.");

        assertThat(photo(a).get("place").isNull()).isTrue();
        answers(send("GET", "api/places/recent", null), 200, "{\"places\":[]}");
    }
}
