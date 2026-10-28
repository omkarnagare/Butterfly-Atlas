package com.butterflyatlas.app;

import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.web.AtlasServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlasServerTest {

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void closeClient() {
        http.close();
    }

    private AtlasServices services() {
        return TestServices.unconfigured(home);
    }

    private HttpResponse<String> get(AtlasServer server, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(5)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthEndpointReportsOkAndVersion() throws Exception {
        try (AtlasServer server = AtlasServer.start(0, services())) {
            HttpResponse<String> response = get(server, "api/health");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/json"));
            assertThat(response.body())
                    .contains("\"status\":\"ok\"")
                    .contains("\"version\":\"" + AtlasVersion.current() + "\"");
        }
    }

    @Test
    void listensOnLoopbackOnly() {
        try (AtlasServer server = AtlasServer.start(0, services())) {
            assertThat(server.isLoopbackOnly()).isTrue();
            assertThat(server.baseUri().getHost()).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void picksAFreePortWhenAskedForPortZero() {
        try (AtlasServer first = AtlasServer.start(0, services()); AtlasServer second = AtlasServer.start(0, services())) {
            assertThat(first.port()).isPositive();
            assertThat(second.port()).isPositive().isNotEqualTo(first.port());
        }
    }

    @Test
    void unknownApiPathsReturnAJson404() throws Exception {
        try (AtlasServer server = AtlasServer.start(0, services())) {
            HttpResponse<String> response = get(server, "api/nope");

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("\"code\":\"not_found\"");
        }
    }

    @Test
    void failsCleanlyWhenThePortIsTaken() throws Exception {
        try (AtlasServer first = AtlasServer.start(0, services())) {
            assertThatThrownBy(() -> AtlasServer.start(first.port(), services()))
                    .isInstanceOf(RuntimeException.class);

            assertThat(get(first, "api/health").statusCode()).isEqualTo(200);
        }
    }

    // The fixture in src/test/resources/ui shadows the real UI jar's /ui on the test classpath, so
    // tests that need the real bundle must not rely on /ui/index.html.
    @Test
    void servesTheUiIndexPageAtTheRoot() throws Exception {
        try (AtlasServer server = AtlasServer.start(0, services())) {
            HttpResponse<String> response = get(server, "");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/html"));
            assertThat(response.body()).contains("Butterfly Atlas test page");
        }
    }
}
