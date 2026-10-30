package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.config.AppConfig;
import com.butterflyatlas.app.config.ConfigStore;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.Library;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestPlaces;
import com.butterflyatlas.app.testing.TestServices;
import com.butterflyatlas.app.window.InstanceLock;
import com.butterflyatlas.app.window.Relaunch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library change's routes (change-library-folder contract §3): a move and a switch from the request to the reopening
 * (§2's {@code reopen}), the settings they leave (spec §8), and the refusals in their codes.
 */
class LibraryChangeApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private AtlasServer server;
    private String origin;
    private final List<String> reopened = new CopyOnWriteArrayList<>();
    private Relaunch.Plan plan = Relaunch.plan(null);
    private boolean starts = true;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home, holder);
        Files.createDirectories(home.resolve("Atlas").resolve("images").resolve("Papilionidae"));
        Files.write(home.resolve("Atlas").resolve("images").resolve("Papilionidae").resolve("Papilio_1.jpg"), new byte[5000]);
        services.change().reopenWith(new Relaunch.Reopener() {
            @Override
            public Relaunch.Plan plan() {
                return plan;
            }

            @Override
            public boolean begin(Relaunch.Plan p) {
                reopened.add("begin " + p.restarts());
                return p.restarts() && starts;
            }

            @Override
            public void exit() {
                reopened.add("exit");
            }
        }, Duration.ofMillis(10));
        server = AtlasServer.start(0, services);
        origin = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.close();
        services.exports().close();
        services.thumbnails().close();
    }

    private RawHttp.Response get(String path) throws Exception {
        return RawHttp.send(server.port(), "GET", path, Map.of(), null);
    }

    private RawHttp.Response post(String path, String body) throws Exception {
        return RawHttp.send(server.port(), "POST", path, Map.of("Origin", origin, "Content-Type", "application/json"), body);
    }

    private static String path(Path folder) {
        return "{\"path\":" + quoted(folder.toString()) + "}";
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String enc(Path folder) {
        return URLEncoder.encode(folder.toString(), StandardCharsets.UTF_8);
    }

    /** The status's {@code changing} once it reaches {@code step}. */
    private JsonNode until(String step) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode changing = null;
        while (System.nanoTime() < deadline) {
            changing = JSON.readTree(get("/api/status").body()).get("changing");
            if (changing != null && !changing.isNull() && step.equals(changing.get("step").asText())) {
                return changing;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the change never reached " + step + ": " + changing);
    }

    private void untilExited() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!reopened.contains("exit") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(reopened).contains("exit");
    }

    private AppConfig config() {
        return new ConfigStore(home.resolve("config")).load().orElseThrow();
    }

    @Test
    void aMoveCopiesSwitchesTheSettingsAndReopensAndEveryWindowIsToldHow() throws Exception {
        Path drive = Files.createDirectories(home.resolve("Drive"));
        Path moved = drive.resolve("Butterfly_Atlas");

        JsonNode overview = JSON.readTree(get("/api/library/change").body());
        assertThat(overview.get("folder").asText()).isEqualTo(home.resolve("Atlas").toString());
        assertThat(overview.get("size").asLong()).isGreaterThan(5000);
        assertThat(overview.get("fixed").asBoolean()).isFalse();
        assertThat(overview.get("changing").isNull()).isTrue();
        JsonNode inspect = JSON.readTree(get("/api/library/change/inspect?for=move&path=" + enc(drive)).body());
        assertThat(inspect.get("libraryFolder").asText()).isEqualTo(moved.toString());
        assertThat(inspect.get("refusal").isNull()).isTrue();
        assertThat(inspect.get("needed").asLong()).isGreaterThan(200_000_000L);

        RawHttp.Response started = post("/api/library/change/move", path(drive));
        assertThat(started.status()).isEqualTo(202);
        assertThat(JSON.readTree(started.body()).get("kind").asText()).isEqualTo("move");

        JsonNode reopening = until("reopening");
        untilExited();

        assertThat(reopening.get("canCancel").asBoolean()).isFalse();
        assertThat(reopening.get("reopen").get("how").asText()).isEqualTo("quit");
        assertThat(reopening.get("reopen").get("folder").asText()).isEqualTo(moved.toString());
        assertThat(reopening.get("reopen").get("why").asText()).isEqualTo(Relaunch.NOT_INSTALLED);
        assertThat(reopened).containsExactly("begin false", "exit");
        assertThat(config()).isEqualTo(new AppConfig(moved.toString(), null, home.resolve("Atlas").toString(), true, true));
        assertThat(moved.resolve("atlas.db")).isRegularFile();
        assertThat(moved.resolve("images/Papilionidae/Papilio_1.jpg")).hasSize(5000);
        assertThat(get("/api/library/change").status()).as("passes the gate").isEqualTo(200);
        assertThat(get("/api/summary").status()).isEqualTo(409);
        assertThat(holder.library().workHold().held()).as("nothing writes the old library any more").isTrue();
    }

    @Test
    void aRestartThatCannotStartTheAppBecomesAQuitThatSaysWhy() throws Exception {
        plan = Relaunch.plan(Files.createFile(home.resolve("Butterfly Atlas")).toString());
        starts = false;
        Path drive = Files.createDirectories(home.resolve("Drive"));

        assertThat(post("/api/library/change/move", path(drive)).status()).isEqualTo(202);
        untilExited();

        JsonNode changing = JSON.readTree(get("/api/status").body()).get("changing");
        assertThat(changing.get("step").asText()).isEqualTo("reopening");
        assertThat(changing.get("reopen").get("how").asText()).isEqualTo("quit");
        assertThat(changing.get("reopen").get("why").asText()).isEqualTo(Relaunch.NOT_STARTED);
        assertThat(reopened).containsExactly("begin true", "exit");
    }

    @Test
    void aRestartTellsEveryWindowItRestarts() throws Exception {
        plan = Relaunch.plan(Files.createFile(home.resolve("Butterfly Atlas")).toString());
        Path drive = Files.createDirectories(home.resolve("Drive"));

        assertThat(post("/api/library/change/move", path(drive)).status()).isEqualTo(202);
        untilExited();

        JsonNode reopen = JSON.readTree(get("/api/status").body()).get("changing").get("reopen");
        assertThat(reopen.get("how").asText()).isEqualTo("restart");
        assertThat(reopen.get("why").isNull()).isTrue();
    }

    @Test
    void aSwitchToAnExistingLibraryChecksItSwitchesAndReopensWithoutAnOldCopy() throws Exception {
        Path other = home.resolve("Other");
        Library.open(other, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);

        JsonNode inspect = JSON.readTree(get("/api/library/change/inspect?for=switch&path=" + enc(other)).body());
        assertThat(inspect.get("kind").asText()).isEqualTo("existing");
        assertThat(inspect.get("refusal").isNull()).isTrue();
        assertThat(inspect.get("unfinished").get("imports").asInt()).isZero();

        RawHttp.Response started = post("/api/library/change/switch", path(other));
        assertThat(started.status()).isEqualTo(202);
        JsonNode first = JSON.readTree(started.body());
        assertThat(first.get("kind").asText()).isEqualTo("switch");
        assertThat(first.get("step").asText()).isEqualTo("checking");
        assertThat(first.get("files").get("total").asLong()).isZero();
        untilExited();

        assertThat(config()).isEqualTo(new AppConfig(other.toString(), null, home.resolve("Atlas").toString(), true, null));
        assertThat(JSON.readTree(get("/api/status").body()).get("changing").get("reopen").get("folder").asText())
                .isEqualTo(other.toString());
    }

    @Test
    void aSwitchToANewFolderMakesItsLibraryFolderForTheNextStart() throws Exception {
        Path empty = Files.createDirectories(home.resolve("Empty"));

        assertThat(post("/api/library/change/switch", path(empty)).status()).isEqualTo(202);
        untilExited();

        assertThat(empty.resolve("Butterfly_Atlas")).isDirectory();
        assertThat(config().dataFolder()).isEqualTo(empty.resolve("Butterfly_Atlas").toString());
        assertThat(config().hasOldCopy()).isFalse();
    }

    @Test
    void aLibraryThisAppCannotOpenFailsTheSwitchAndChangesNoSetting() throws Exception {
        Path other = home.resolve("Newer");
        Database db = Library.open(other, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK).database();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO flyway_schema_history (installed_rank, version,"
                    + " description, type, script, checksum, installed_by, execution_time, success) VALUES"
                    + " ((SELECT MAX(installed_rank) + 1 FROM flyway_schema_history), '99', 'newer', 'SQL', 'V99__x.sql', 0,"
                    + " 'x', 0, 1)")) {
                ps.executeUpdate();
            }
            return null;
        });
        AppConfig before = config();

        assertThat(post("/api/library/change/switch", path(other)).status()).isEqualTo(202);
        JsonNode failed = until("failed");

        assertThat(failed.get("problem").asText()).startsWith("Butterfly Atlas can't open the library at " + other + ". ")
                .contains("newer Butterfly Atlas");
        assertThat(config()).isEqualTo(before);
        assertThat(reopened).isEmpty();
        assertThat(post("/api/library/change/dismiss", "{}").status()).isEqualTo(204);
        assertThat(JSON.readTree(get("/api/status").body()).get("changing").isNull()).isTrue();
        assertThat(get("/api/summary").status()).isEqualTo(200);
    }

    @Test
    void settingsShowTheOldCopyAMoveLeftAndDeleteRemovesIt() throws Exception {
        Path old = home.resolve("Old");
        Library.open(old, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);
        RawHttp.Response none = RawHttp.send(server.port(), "DELETE", "/api/library/old-copy", Map.of("Origin", origin), null);
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.body()).contains("\"code\":\"no_old_copy\"");
        new ConfigStore(home.resolve("config")).save(new AppConfig(home.resolve("Atlas").toString(), null, old.toString(),
                null, true));

        JsonNode oldCopy = JSON.readTree(get("/api/library/change").body()).get("oldCopy");
        assertThat(oldCopy.get("folder").asText()).isEqualTo(old.toString());
        assertThat(oldCopy.get("size").asLong()).isPositive();
        assertThat(oldCopy.get("lastBackup").isNull()).isTrue();

        RawHttp.Response deleted = RawHttp.send(server.port(), "DELETE", "/api/library/old-copy", Map.of("Origin", origin),
                null);

        assertThat(deleted.status()).isEqualTo(200);
        JsonNode answer = JSON.readTree(deleted.body());
        assertThat(answer.get("deleted").asBoolean()).isTrue();
        assertThat(answer.get("left").size()).isZero();
        assertThat(answer.get("leftCount").asInt()).isZero();
        assertThat(old).doesNotExist();
        assertThat(JSON.readTree(get("/api/library/change").body()).get("oldCopy").isNull()).isTrue();
    }

    @Test
    void theRefusalsAnswerInTheirCodes() throws Exception {
        Path atlas = home.resolve("Atlas");
        Path other = home.resolve("Other");
        Library.open(other, TestBundles.fixture(), TestPlaces.bundles(), TestBundles.CLOCK);

        RawHttp.Response inside = post("/api/library/change/move", path(atlas.resolve("images")));
        assertThat(inside.status()).isEqualTo(400);
        assertThat(inside.body()).contains("\"code\":\"invalid\"").contains("Choose a folder outside your library.");
        RawHttp.Response existing = post("/api/library/change/move", path(other));
        assertThat(existing.status()).isEqualTo(400);
        assertThat(existing.body()).contains("To open it, use Use a different library.");
        RawHttp.Response current = post("/api/library/change/switch", path(atlas));
        assertThat(current.status()).isEqualTo(400);
        assertThat(current.body()).contains("That is the library you are using.");
        InstanceLock.Result held = InstanceLock.acquire(other);
        try {
            RawHttp.Response inUse = post("/api/library/change/switch", path(other));
            assertThat(inUse.status()).isEqualTo(409);
            assertThat(inUse.body()).contains("\"code\":\"library_in_use\"");
        } finally {
            held.lock().close();
        }
        assertThat(get("/api/library/change/inspect?for=sideways&path=" + enc(other)).status()).isEqualTo(400);
        RawHttp.Response nothing = post("/api/library/change/cancel", "{}");
        assertThat(nothing.status()).isEqualTo(409);
        assertThat(nothing.body()).contains("\"code\":\"cannot_cancel\"");
        assertThat(post("/api/library/change/dismiss", "{}").status()).isEqualTo(204);
        assertThat(post("/api/library/change/move", "{}").status()).isEqualTo(400);
    }
}
