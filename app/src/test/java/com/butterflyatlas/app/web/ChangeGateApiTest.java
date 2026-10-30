package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.ChangeSteps;
import com.butterflyatlas.app.library.LibraryChange;
import com.butterflyatlas.app.library.LibraryDrive;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.HandlerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate (change-library-folder contract §4): while a change runs or waits to be dismissed, every {@code /api} request
 * but the status, the session's routes and the change's own answers 409 {@code library_changing}; the status carries
 * {@code changing} (contract §2).
 */
class ChangeGateApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    /** The real steps, stopping in the database's copy until the test lets them go. */
    private static final class Held implements ChangeSteps {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);

        @Override
        public void createFolder(Path folder) throws IOException {
            REAL.createFolder(folder);
        }

        @Override
        public void copyFile(Path source, Path target) throws IOException {
            REAL.copyFile(source, target);
        }

        @Override
        public void copyDatabase(Database database, Path target) throws IOException {
            reached.countDown();
            try {
                go.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            REAL.copyDatabase(database, target);
        }

        @Override
        public String integrity(Path database) {
            return REAL.integrity(database);
        }

        @Override
        public void delete(Path path) throws IOException {
            REAL.delete(path);
        }
    }

    @Test
    void whileAChangeRunsEveryApiRequestButTheStatusIsRefusedAndTheStatusSaysWhatRuns() throws Exception {
        LibraryHolder holder = new LibraryHolder();
        AtlasServices base = TestServices.configured(home, holder);
        Held steps = new Held();
        LibraryChange change = new LibraryChange(holder::library, base.setup(), steps, LibraryDrive::usableSpace,
                LibraryChange.Background.NONE, (kind, from, to) -> { }, Optional::empty);
        AtlasServices services = TestServices.withChange(base, change);
        Path drive = Files.createDirectories(home.resolve("Drive"));
        try (AtlasServer server = AtlasServer.start(0, services)) {
            String origin = "http://127.0.0.1:" + server.port();
            assertThat(RawHttp.send(server.port(), "GET", "/api/health", Map.of(), null).status()).isEqualTo(200);
            assertThat(JSON.readTree(RawHttp.send(server.port(), "GET", "/api/status", Map.of(), null).body())
                    .get("changing").isNull()).isTrue();

            change.move(drive.toString());
            assertThat(steps.reached.await(20, TimeUnit.SECONDS)).isTrue();

            for (String[] request : new String[][] {{"GET", "/api/health"}, {"GET", "/api/summary"},
                    {"GET", "/api/setup"}, {"POST", "/api/photos/delete"}}) {
                RawHttp.Response refused = RawHttp.send(server.port(), request[0], request[1],
                        Map.of("Origin", origin, "Content-Type", "application/json"), "POST".equals(request[0]) ? "{}" : null);
                assertThat(refused.status()).as(request[1]).isEqualTo(409);
                assertThat(refused.body()).as(request[1]).contains("\"code\":\"library_changing\"")
                        .contains("Butterfly Atlas is changing your library folder.");
            }
            RawHttp.Response status = RawHttp.send(server.port(), "GET", "/api/status", Map.of(), null);
            assertThat(status.status()).isEqualTo(200);
            JsonNode changing = JSON.readTree(status.body()).get("changing");
            assertThat(changing.get("kind").asText()).isEqualTo("move");
            assertThat(changing.get("to").asText()).isEqualTo(drive.resolve("Butterfly_Atlas").toString());
            assertThat(changing.get("step").asText()).isEqualTo("copying_database");
            assertThat(changing.get("canCancel").asBoolean()).isTrue();
            assertThat(changing.get("files").get("total").asLong()).isEqualTo(changing.get("files").get("done").asLong());
            assertThat(changing.has("bytes") && changing.has("problem") && changing.has("left") && changing.has("reopen"))
                    .isTrue();

            change.cancel();
            steps.go.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (change.blocks() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertThat(RawHttp.send(server.port(), "GET", "/api/health", Map.of(), null).status()).isEqualTo(200);
        } finally {
            steps.go.countDown();
            base.exports().close();
            base.thumbnails().close();
        }
    }

    @Test
    void theStatusTheSessionAndTheChangesOwnRoutesPassTheGate() {
        assertThat(ChangeGate.passes(HandlerType.GET, "/api/status")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.GET, "/api/session")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.GET, "/api/session/stream")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.POST, "/api/window")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.POST, "/launch/new")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.GET, "/launch")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.GET, "/")).as("the page itself").isTrue();
        assertThat(ChangeGate.passes(HandlerType.GET, "/api/library/change")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.POST, "/api/library/change/cancel")).isTrue();
        assertThat(ChangeGate.passes(HandlerType.POST, "/api/library/change/dismiss")).isTrue();

        assertThat(ChangeGate.passes(HandlerType.POST, "/api/status")).isFalse();
        assertThat(ChangeGate.passes(HandlerType.POST, "/api/library/change/move")).isFalse();
        assertThat(ChangeGate.passes(HandlerType.GET, "/api/library/change/inspect")).isFalse();
        assertThat(ChangeGate.passes(HandlerType.DELETE, "/api/library/old-copy")).isFalse();
        assertThat(ChangeGate.passes(HandlerType.POST, "/api/rescan-left/dismiss")).isFalse();
    }
}
