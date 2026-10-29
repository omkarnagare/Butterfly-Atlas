package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.testing.TestBundles;
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

/** The filter over HTTP (4a contract §3): the four GETs, exact JSON, every refusal. */
class FilterApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;
    private Database db;

    @BeforeEach
    void start() {
        services = TestServices.configured(home);
        server = AtlasServer.start(0, services);
        db = Database.open(home.resolve("Atlas").resolve(Database.FILE_NAME));
    }

    @AfterEach
    void stop() {
        server.close();
        services.thumbnails().close();
        http.close();
    }

    /** A GET as a browser's own page sends it (no Origin: reads need none). */
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void answers(HttpResponse<String> response, int status, String body) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).isEqualTo(body);
    }

    private static void refused(HttpResponse<String> response, String message) throws Exception {
        answers(response, 400, "{\"error\":{\"code\":\"invalid\",\"message\":" + JSON.writeValueAsString(message) + "}}");
    }

    private void exec(String sql) {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    private long photo(String taxonId, String placeId, long bytes, String... tags) {
        long id = TestBundles.addImage(db, taxonId);
        exec("UPDATE image SET file_size = " + bytes + (placeId == null ? "" : ", place_id = '" + placeId
                + "', place_source = 'gps'") + " WHERE id = " + id);
        TestBundles.tagImage(db, id, tags);
        return id;
    }

    /** The photo as the species gallery shows it. */
    private ObjectNode gallery(String taxonId, long id) throws Exception {
        for (JsonNode p : JSON.readTree(get("api/taxa/" + taxonId + "/photos").body()).get("photos")) {
            if (p.get("id").asLong() == id) {
                return (ObjectNode) p;
            }
        }
        throw new AssertionError("no photo " + id);
    }

    @Test
    void countAnswersTheCountLineAndTheFilterAsUsed() throws Exception {
        photo("A5", "P4", 1_000, "larva");
        photo("A5", "P5", 2_000);
        photo("A12", null, 4_000, "larva");

        answers(get("api/filter/count"), 200, "{\"photos\":3,\"species\":6,\"photographedSpecies\":2,\"bytes\":7000,"
                + "\"manualSpecies\":false,"
                + "\"filter\":{\"query\":\"\",\"taxa\":[],\"tags\":[],\"tagMode\":\"any\",\"places\":[],\"status\":\"all\","
                + "\"source\":\"all\",\"stages\":null,\"placeNotes\":[],\"types\":[]}}");
        answers(get("api/filter/count?taxa=A4,A404&tags=larva&places=P2,unknown&status=photographed&view=photos"), 200,
                "{\"photos\":1,\"species\":1,\"photographedSpecies\":1,\"bytes\":1000,\"manualSpecies\":false,"
                        + "\"filter\":{\"query\":"
                        + "\"taxa=A4&tags=larva&places=P2,unknown&status=photographed\",\"taxa\":[{\"id\":\"A4\",\"rank\":\"genus\","
                        + "\"name\":\"Danaus\",\"common\":\"Tigers\"}],\"tags\":[{\"key\":\"larva\",\"label\":\"larva\"}],"
                        + "\"tagMode\":\"any\",\"places\":[{\"id\":\"P2\",\"name\":\"Northstate\",\"path\":[\"Northstate\","
                        + "\"Testland\"],\"level\":\"state\",\"mine\":false,\"former\":false},{\"id\":\"unknown\"}],\"status\":\"photographed\","
                        + "\"source\":\"all\",\"stages\":null,\"placeNotes\":[],\"types\":[]}}");
    }

    @Test
    void speciesAnswersEveryRowInTaxonomicOrder() throws Exception {
        photo("A5", "P4", 1, "egg");
        photo("A13", "P4", 1);

        answers(get("api/filter/species?taxa=A4,A13&stages=missing"), 200, "{\"species\":[{\"id\":\"A5\",\"name\":"
                + "\"Danaus chrysippus\",\"common\":\"Plain Tiger\",\"family\":\"Nymphalidae\",\"subfamily\":\"Danainae\","
                + "\"tribe\":\"Danaini\",\"genus\":\"Danaus\",\"photos\":1,\"missing\":[\"larva\",\"pupa\"],\"source\":\"official\","
                + "\"active\":true},{\"id\":\"A13\",\"name\":\"Papilio demoleus\",\"common\":null,\"family\":\"Papilionidae\","
                + "\"subfamily\":\"Papilioninae\",\"tribe\":null,\"genus\":\"Papilio\",\"photos\":1,\"missing\":[\"egg\",\"larva\","
                + "\"pupa\"],\"source\":\"official\",\"active\":true}]}");
        answers(get("api/filter/species?status=photographed&places=P9"), 200, "{\"species\":[]}");
    }

    @Test
    void photosArePagedWithTheGallerysPhotoAndItsSpecies() throws Exception {
        long a = photo("A5", "P4", 1, "larva");
        long b = photo("A5", null, 1);
        long c = photo("A12", "P8", 1);

        HttpResponse<String> first = get("api/filter/photos?limit=2");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode page = JSON.readTree(first.body());
        ObjectNode expected = gallery("A5", a);
        expected.set("species", JSON.readTree("{\"id\":\"A5\",\"name\":\"Danaus chrysippus\",\"common\":\"Plain Tiger\"}"));
        assertThat(page.get("photos").get(0)).isEqualTo(expected);
        assertThat(page.get("photos").get(1).get("id").asLong()).isEqualTo(b);
        assertThat(page.get("photos").get(0).get("place").toString()).isEqualTo("{\"id\":\"P4\",\"path\":[\"Alpha\","
                + "\"Northstate\",\"Testland\"],\"ids\":[\"P4\",\"P2\",\"P1\"],\"source\":\"gps\",\"former\":false,\"level\":\"district\"}");
        String next = page.get("next").asText();

        JsonNode second = JSON.readTree(get("api/filter/photos?limit=2&after=" + next).body());
        assertThat(second.get("photos")).hasSize(1);
        assertThat(second.get("photos").get(0).get("id").asLong()).isEqualTo(c);
        assertThat(second.get("photos").get(0).get("species").toString()).isEqualTo("{\"id\":\"A12\",\"name\":"
                + "\"Papilio polytes\",\"common\":\"Common Mormon\"}");
        assertThat(second.get("next").isNull()).isTrue();
        assertThat(JSON.readTree(get("api/filter/photos").body()).get("photos")).hasSize(3);
        answers(get("api/filter/photos?status=not"), 200, "{\"photos\":[],\"next\":null}");
    }

    @Test
    void placesAnswersTheRowsOfTheFilter() throws Exception {
        photo("A5", "P4", 1);
        photo("A6", "P5", 1);
        photo("A6", "P4", 1);
        photo("A12", null, 1);

        answers(get("api/filter/places"), 200, "{\"places\":[{\"id\":\"P1\",\"name\":\"Testland\",\"path\":[\"Testland\"],"
                + "\"level\":\"country\",\"mine\":false,\"species\":2,\"photos\":3},{\"id\":\"unknown\",\"species\":1,"
                + "\"photos\":1}]}");
        answers(get("api/filter/places?places=P2"), 200, "{\"places\":[{\"id\":\"P4\",\"name\":\"Alpha\",\"path\":[\"Alpha\","
                + "\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":false,\"species\":2,\"photos\":2},{\"id\":\"P5\","
                + "\"name\":\"Beta\",\"path\":[\"Beta\",\"Northstate\",\"Testland\"],\"level\":\"district\",\"mine\":false,"
                + "\"species\":1,\"photos\":1}]}");
        answers(get("api/filter/places?status=not"), 200, "{\"places\":[]}");
    }

    @Test
    void badValuesAreRefusedWithTheContractsWords() throws Exception {
        StringBuilder many = new StringBuilder("A1");
        for (int n = 2; n <= 201; n++) {
            many.append(",A").append(n);
        }
        for (String route : new String[] {"count", "species", "photos", "places"}) {
            refused(get("api/filter/" + route + "?status=maybe"), "Unknown status “maybe”.");
            refused(get("api/filter/" + route + "?tagMode=most"), "Unknown tag mode “most”.");
            refused(get("api/filter/" + route + "?source=inat"), "Unknown source “inat”.");
            refused(get("api/filter/" + route + "?stages=some"), "Unknown stages “some”.");
            refused(get("api/filter/" + route + "?tags=egg&stages=missing"),
                    "Life stages can't be combined with Tags or Photo status.");
            refused(get("api/filter/" + route + "?taxa=" + many), "Choose at most 200 taxa.");
            refused(get("api/filter/" + route + "?places=" + many.toString().replace('A', 'P')), "Choose at most 200 places.");
        }
        refused(get("api/filter/photos?limit=0"), "The limit must be between 1 and 500.");
        refused(get("api/filter/photos?limit=501"), "The limit must be between 1 and 500.");
        refused(get("api/filter/photos?limit=lots"), "The limit must be between 1 and 500.");
        refused(get("api/filter/photos?after=nonsense"), "That page is out of date. Reload the results.");
    }

    @Test
    void theFilterNeedsALibrary() throws Exception {
        server.close();
        services.thumbnails().close();
        services = TestServices.unconfigured(home.resolve("other"));
        server = AtlasServer.start(0, services);

        for (String route : new String[] {"count", "species", "photos", "places"}) {
            HttpResponse<String> response = get("api/filter/" + route);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).contains("\"code\":\"setup_required\"");
        }
    }
}
