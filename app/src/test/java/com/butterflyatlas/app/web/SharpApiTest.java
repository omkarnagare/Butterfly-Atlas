package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /api/photos/{id}/sharp}: the 960 px copy kept on this device. */
class SharpApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        services.sharp().close();
        services.thumbnails().close();
        services.placeNotes().close();
        http.close();
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Origin", "http://127.0.0.1:" + server.port()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private long add(byte[] jpeg) throws Exception {
        HttpResponse<String> added = http.send(HttpRequest.newBuilder(server.baseUri().resolve("api/taxa/A5/photos"))
                .header("Origin", "http://127.0.0.1:" + server.port()).header("Content-Type", "image/jpeg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(added.statusCode()).as(added.body()).isEqualTo(200);
        return JSON.readTree(added.body()).at("/photo/id").asLong();
    }

    @Test
    void aSharpCopyIsPendingThenServedAndKeptForGoodWithAVersion() throws Exception {
        long id = add(TestImages.jpeg(2400, 1600));

        HttpResponse<byte[]> first = get("api/photos/" + id + "/sharp?v=1");
        assertThat(first.statusCode()).isEqualTo(204);
        assertThat(first.headers().firstValue("X-Atlas-Pending")).hasValue("1");
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("no-store");

        HttpResponse<byte[]> ready = first;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (ready.statusCode() != 200 && System.nanoTime() < deadline) {
            Thread.sleep(50);
            ready = get("api/photos/" + id + "/sharp?v=1");
        }
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(ready.headers().firstValue("Cache-Control")).hasValue("public, max-age=31536000, immutable");
        assertThat(ready.headers().firstValue("X-Atlas-Pending")).isEmpty();
        assertThat(ImageIO.read(new ByteArrayInputStream(ready.body())).getWidth()).isEqualTo(960);

        for (String query : new String[] {"", "?v=", "?v=%20"}) {
            HttpResponse<byte[]> unversioned = get("api/photos/" + id + "/sharp" + query);
            assertThat(unversioned.statusCode()).as(query).isEqualTo(200);
            assertThat(unversioned.headers().firstValue("Cache-Control")).as(query).hasValue("no-cache");
        }
    }

    @Test
    void aSmallPhotoHasNoSharpCopy() throws Exception {
        long id = add(TestImages.jpeg(400, 300));

        HttpResponse<byte[]> none = get("api/photos/" + id + "/sharp?v=1");

        assertThat(none.statusCode()).isEqualTo(204);
        assertThat(none.headers().firstValue("X-Atlas-Pending")).isEmpty();
        assertThat(none.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    void anUnknownPhotoIsNotFoundAsForItsThumbnail() throws Exception {
        assertThat(get("api/photos/999/sharp").statusCode()).isEqualTo(get("api/photos/999/thumb").statusCode())
                .isEqualTo(404);
        assertThat(new String(get("api/photos/abc/sharp").body())).contains("\"code\":\"not_found\"");
    }
}
