package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.testing.TestHeif;
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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decoder's status in {@code /api/status} and Check again (Phase 7b contract §3, audit 11), and what the rescan did
 * not add ({@code rescanLeft}, audit D2).
 */
class HeicStatusApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private AtlasServer server;

    @BeforeEach
    void start() {
        services = TestServices.configured(home, holder);
        server = AtlasServer.start(0, services);
    }

    @AfterEach
    void stop() {
        server.close();
        services.thumbnails().close();
        http.close();
        HeicStatus.publish(HeicStatus.checking("mac"));
    }

    private JsonNode send(String method, String path) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Origin", "http://127.0.0.1:" + server.port());
        r = method.equals("GET") ? r.GET() : r.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}"));
        HttpResponse<String> response = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 204);
        return response.body().isEmpty() ? null : JSON.readTree(response.body());
    }

    @Test
    void checkAgainAnswersTheFreshStatusAndTheStatusCarriesIt() throws Exception {
        JsonNode checked = send("POST", "api/heic/check");

        // TestServices' decoder is forced to none (as -Datlas.heicDecoder=none)
        assertThat(checked.toString()).isEqualTo("{\"state\":\"missing\",\"decoder\":null,\"system\":\"mac\","
                + "\"reason\":\"absent\"}");
        assertThat(send("GET", "api/status").get("heic")).isEqualTo(checked);

        HeicStatus.publish(HeicStatus.available("sips", "mac"));
        assertThat(send("GET", "api/status").get("heic").toString()).isEqualTo("{\"state\":\"available\",\"decoder\":"
                + "\"sips\",\"system\":\"mac\",\"reason\":null}");
    }

    @Test
    void theRescanSaysWhatItDidNotAddUntilPutAway() throws Exception {
        JsonNode added = JSON.readTree(http.send(HttpRequest.newBuilder(server.baseUri().resolve("api/taxa/A5/photos"
                + "?name=a.jpg")).header("Origin", "http://127.0.0.1:" + server.port())
                .header("Content-Type", "image/jpeg").POST(HttpRequest.BodyPublishers.ofByteArray(TestImages.jpeg(8, 8)))
                .build(), HttpResponse.BodyHandlers.ofString()).body());
        Path species = holder.library().folder().resolve(added.at("/photo/relPath").asText()).getParent();
        Instant old = Instant.now().minusSeconds(60);
        byte[] sequence = TestHeif.still(64, 32).brands("msf1", "msf1", "heic").withMoov().build();
        for (String name : new String[] {"burst.heic", "burst.HEICS", "clip.heifs"}) {
            Files.write(species.resolve(name), sequence);
            Files.setLastModifiedTime(species.resolve(name), FileTime.from(old));
        }
        Files.write(species.resolve("damaged.heic"), "not a photo".getBytes());
        Files.setLastModifiedTime(species.resolve("damaged.heic"), FileTime.from(old));
        assertThat(send("GET", "api/status").get("rescanLeft").size()).isZero();

        services.rescan().run();

        JsonNode left = send("GET", "api/status").get("rescanLeft");
        String folder = species.getFileName().toString();
        // named in the folder's order, which is this OS's order of file names (Windows ignores case)
        String sequences = java.util.stream.Stream.of("burst.heic", "burst.HEICS", "clip.heifs").map(species::resolve)
                .sorted().map(file -> "\"" + folder + "/" + file.getFileName() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        assertThat(left.toString()).as("fixes J12: a short reason, what to do, up to 5 examples").isEqualTo("["
                + "{\"reason\":\"HEIC image sequences (several frames in one file) aren't supported\","
                + "\"next\":\"Save the frame you want as a photo and add that.\",\"count\":3,\"examples\":["
                + sequences + "]},"
                + "{\"reason\":\"Files Butterfly Atlas can't read as photos\",\"next\":\"Move them out of the library"
                + " folder, or replace them with JPEG copies.\",\"count\":1,\"examples\":[\"" + folder + "/damaged.heic\"]}]");

        assertThat(send("POST", "api/rescan-left/dismiss")).isNull();
        assertThat(send("GET", "api/status").get("rescanLeft").size()).isZero();
        services.rescan().run();
        assertThat(send("GET", "api/status").get("rescanLeft").size()).as("the next rescan says it again").isEqualTo(2);
    }
}
