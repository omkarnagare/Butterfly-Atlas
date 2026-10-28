package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestGuardsTest {

    @TempDir
    Path home;

    private AtlasServer server;

    @BeforeEach
    void start() {
        server = AtlasServer.start(0, TestServices.unconfigured(home));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private RawHttp.Response send(String method, String path, Map<String, String> headers, String body) throws Exception {
        return RawHttp.send(server.port(), method, path, headers, body);
    }

    private String own() {
        return "http://127.0.0.1:" + server.port();
    }

    @Test
    void refusesRequestsForAnotherHost() throws Exception {
        RawHttp.Response response = send("GET", "/api/health", Map.of("Host", "evil.example"), null);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"forbidden_host\"");
    }

    @Test
    void refusesUiPagesForAnotherHost() throws Exception {
        assertThat(send("GET", "/", Map.of("Host", "evil.example:" + server.port()), null).status()).isEqualTo(403);
        assertThat(send("GET", "/", Map.of(), null).status()).isEqualTo(200);
    }

    @Test
    void acceptsTheHostInAnyCase() throws Exception {
        assertThat(send("GET", "/api/health", Map.of("Host", "LOCALHOST:" + server.port()), null).status()).isEqualTo(200);
    }

    @Test
    void acceptsLocalhost() throws Exception {
        assertThat(send("GET", "/api/health", Map.of("Host", "localhost:" + server.port()), null).status()).isEqualTo(200);
    }

    @Test
    void refusesTheRightHostOnTheWrongPort() throws Exception {
        assertThat(send("GET", "/api/health", Map.of("Host", "127.0.0.1:1"), null).status()).isEqualTo(403);
    }

    @Test
    void refusesChangesWithoutAnOrigin() throws Exception {
        RawHttp.Response response = send("POST", "/api/nothing-here", Map.of("Content-Type", "application/json"), "{}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"forbidden_origin\"");
    }

    @Test
    void refusesChangesFromAnotherOrigin() throws Exception {
        assertThat(send("POST", "/api/nothing-here",
                Map.of("Origin", "http://evil.example", "Content-Type", "application/json"), "{}").status()).isEqualTo(403);
    }

    @Test
    void refusesChangesThatAreNotJson() throws Exception {
        RawHttp.Response response = send("POST", "/api/nothing-here",
                Map.of("Origin", own(), "Content-Type", "text/plain"), "word=x");

        assertThat(response.status()).isEqualTo(415);
        assertThat(response.body()).contains("\"code\":\"unsupported_media_type\"");
    }

    @Test
    void letsChangesFromItsOwnWindowThrough() throws Exception {
        // No such route, so a 404 proves the guards let the request through.
        assertThat(send("POST", "/api/nothing-here", Map.of("Origin", own(), "Content-Type", "application/json"), "{}")
                .status()).isEqualTo(404);
        assertThat(send("POST", "/api/nothing-here",
                Map.of("Origin", "http://localhost:" + server.port(), "Content-Type", "application/json; charset=utf-8"), "{}")
                .status()).isEqualTo(404);
    }

    @Test
    void deleteNeedsAnOriginButNoContentType() throws Exception {
        assertThat(send("DELETE", "/api/nothing-here", Map.of("Origin", own()), null).status()).isEqualTo(404);
        assertThat(send("DELETE", "/api/nothing-here", Map.of(), null).status()).isEqualTo(403);
    }

    @Test
    void photoUploadsAcceptTheFourPhotoTypes() throws Exception {
        for (String type : new String[] {"image/jpeg", "image/png", "image/tiff", "image/webp", "IMAGE/JPEG; x=1",
                "image/x-raw", "image/x-nikon-nef", "image/x-canon-cr3", "image/x-adobe-dng", "image/x-fuji-raf"}) {
            // No library yet, so 409 proves the guards let the upload through to its route.
            assertThat(send("POST", "/api/taxa/A5/photos", Map.of("Origin", own(), "Content-Type", type), "xyz").status())
                    .as(type).isEqualTo(409);
        }
    }

    @Test
    void photoUploadsRefuseOtherTypes() throws Exception {
        RawHttp.Response json = send("POST", "/api/taxa/A5/photos", Map.of("Origin", own(), "Content-Type", "application/json"), "{}");
        assertThat(json.status()).isEqualTo(415);
        assertThat(json.body()).contains("\"code\":\"unsupported_media_type\"", "Butterfly Atlas expects a photo here.");

        // HEIC types pass the guard (the bytes decide, Phase 7b contract §2); here the route answers that there is no library
        for (String type : List.of("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")) {
            RawHttp.Response heic = send("POST", "/api/taxa/A5/photos", Map.of("Origin", own(), "Content-Type", type), "x");
            assertThat(heic.status()).as(type).isNotEqualTo(415);
            assertThat(heic.body()).as(type).doesNotContain("unsupported");
        }

        RawHttp.Response gif = send("POST", "/api/taxa/A5/photos", Map.of("Origin", own(), "Content-Type", "image/gif"), "x");
        assertThat(gif.body()).contains("\"code\":\"unsupported_photo\"", "This file isn't a photo Butterfly Atlas can read.");

        RawHttp.Response avif = send("POST", "/api/taxa/A5/photos", Map.of("Origin", own(), "Content-Type", "image/avif"), "x");
        assertThat(avif.body()).contains("\"code\":\"unsupported_photo\"", "AVIF photos aren't supported yet.");
        RawHttp.Response octets = send("POST", "/api/taxa/A5/photos", Map.of("Origin", own(), "Content-Type",
                "application/octet-stream"), "x");
        assertThat(octets.body()).contains("\"code\":\"unsupported_media_type\"");

        assertThat(send("POST", "/api/taxa/A5/photos", Map.of("Origin", own()), "x").status()).isEqualTo(415);
    }

    @Test
    void photoTypesAreOnlyForTheUploadRoute() throws Exception {
        assertThat(send("POST", "/api/nothing-here", Map.of("Origin", own(), "Content-Type", "image/jpeg"), "x").status())
                .isEqualTo(415);
        assertThat(send("POST", "/api/taxa/A5/photos/more", Map.of("Origin", own(), "Content-Type", "image/jpeg"), "x").status())
                .isEqualTo(415);
        assertThat(send("PUT", "/api/taxa/A5/photos", Map.of("Origin", own(), "Content-Type", "image/jpeg"), "x").status())
                .isEqualTo(415);
    }

    @Test
    void photoUploadsStillNeedTheAppsOwnOrigin() throws Exception {
        assertThat(send("POST", "/api/taxa/A5/photos", Map.of("Content-Type", "image/jpeg"), "x").status()).isEqualTo(403);
        assertThat(send("POST", "/api/taxa/A5/photos", Map.of("Origin", "http://evil.example", "Content-Type", "image/jpeg"), "x")
                .status()).isEqualTo(403);
    }
}
