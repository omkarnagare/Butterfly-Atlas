package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.backup.TestBackups;
import com.butterflyatlas.app.db.Database;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestBundles;
import com.butterflyatlas.app.testing.TestLocations;
import com.butterflyatlas.app.testing.TestServices;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Backups over HTTP (Phase 6 contract §3–§5): every route, the exact shapes, the refusals, /api/status. */
class BackupApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private AtlasServer server;
    private Database db;
    private Path dest;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home, holder);
        server = AtlasServer.start(0, services);
        db = Database.open(home.resolve("Atlas").resolve(Database.FILE_NAME));
        dest = Files.createDirectories(home.resolve("Backup"));
        photo("A5");
        photo("A12");
    }

    @AfterEach
    void stop() {
        server.close();
        services.exports().close();
        services.backups().close();
        services.thumbnails().close();
    }

    private void photo(String taxonId) throws Exception {
        long id = TestBundles.addImage(db, taxonId);
        byte[] bytes = TestLocations.jpeg();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE image SET rel_path = ?, file_name = ?, file_size = ?"
                    + " WHERE id = ?")) {
                ps.setString(1, "images/test/photo_" + id + ".jpg");
                ps.setString(2, "photo_" + id + ".jpg");
                ps.setLong(3, bytes.length);
                ps.setLong(4, id);
                ps.executeUpdate();
            }
            return null;
        });
        Path file = home.resolve("Atlas").resolve("images/test/photo_" + id + ".jpg");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private RawHttp.Response get(String path) throws Exception {
        return RawHttp.send(server.port(), "GET", path, Map.of(), null);
    }

    private RawHttp.Response send(String method, String path, String body) throws Exception {
        return RawHttp.send(server.port(), method, path, Map.of("Origin", "http://127.0.0.1:" + server.port(),
                "Content-Type", "application/json"), body);
    }

    private static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private JsonNode json(RawHttp.Response response) throws Exception {
        return JSON.readTree(response.body());
    }

    private long startEverything() throws Exception {
        RawHttp.Response made = send("POST", "/api/backup", "{\"what\":\"everything\",\"path\":" + JSON.writeValueAsString(
                dest.toString()) + "}");
        assertThat(made.status()).isEqualTo(201);
        return json(made).get("id").asLong();
    }

    private JsonNode awaitDone(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode backup = json(get("/api/backup/" + id));
            if (!"running".equals(backup.get("state").asText())) {
                return backup;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the backup did not finish");
    }

    @Test
    void listsWhatTheUiNeedsBeforeAnythingWasAskedOrBackedUp() throws Exception {
        RawHttp.Response response = get("/api/backup");

        assertThat(response.status()).isEqualTo(200);
        JsonNode backups = json(response);
        assertThat(backups.get("asked").asBoolean()).isFalse();
        assertThat(backups.get("preference").asText()).isEqualTo("remind");
        assertThat(backups.get("lastAt").isNull()).isTrue();
        assertThat(backups.get("lastWhat").isNull()).isTrue();
        assertThat(backups.get("lastUnread").asInt()).isZero();
        assertThat(backups.get("daysSince").isNull()).isTrue();
        assertThat(backups.get("due").asBoolean()).isTrue();
        assertThat(backups.get("current").isNull()).isTrue();
        assertThat(backups.get("past").isArray()).isTrue();
        assertThat(backups.get("past")).isEmpty();
    }

    @Test
    void thePreferenceIsSetAndTheListSaysItIsAskedAndNotDue() throws Exception {
        RawHttp.Response set = send("PUT", "/api/backup/preference", "{\"preference\":\"none\"}");

        assertThat(set.status()).isEqualTo(200);
        assertThat(json(set).get("asked").asBoolean()).isTrue();
        assertThat(json(set).get("preference").asText()).isEqualTo("none");
        assertThat(json(set).get("due").asBoolean()).isFalse();
        assertThat(json(get("/api/backup")).get("preference").asText()).isEqualTo("none");

        RawHttp.Response bad = send("PUT", "/api/backup/preference", "{\"preference\":\"weekly\"}");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(json(bad).get("error").get("code").asText()).isEqualTo("bad_preference");
        assertThat(json(get("/api/backup")).get("preference").asText()).isEqualTo("none");
        assertThat(send("PUT", "/api/backup/preference", "{}").status()).isEqualTo(400);
    }

    @Test
    void theCheckHasTheSheetsFields() throws Exception {
        RawHttp.Response response = get("/api/backup/check?what=everything&path=" + enc(dest.toString()));

        assertThat(response.status()).isEqualTo(200);
        JsonNode check = json(response);
        assertThat(check.get("photos").asInt()).isEqualTo(2);
        assertThat(check.get("bytes").asLong()).isPositive();
        assertThat(check.get("neededBytes").asLong()).isGreaterThan(check.get("bytes").asLong());
        assertThat(check.get("freeBytes").asLong()).isPositive();
        assertThat(check.get("name").asText()).startsWith("Butterfly_Atlas_backup_2026-10-09");
        assertThat(check.get("sameDrive").asBoolean()).isTrue();
        assertThat(check.get("problem").isNull()).isTrue();

        JsonNode missing = json(get("/api/backup/check?what=database&path=" + enc(home.resolve("nowhere").toString())));
        assertThat(missing.get("problem").get("code").asText()).isEqualTo("not_found");
        assertThat(missing.get("problem").get("message").asText()).isEqualTo("That folder can't be found.");
        assertThat(get("/api/backup/check?what=photos&path=" + enc(dest.toString())).status()).isEqualTo(400);
    }

    @Test
    void aBackupOfEverythingRunsToDoneWithItsDetailAndTheStatusSaysNothingAfterwards() throws Exception {
        long id = startEverything();

        JsonNode done = awaitDone(id);

        assertThat(done.get("state").asText()).isEqualTo("done");
        assertThat(done.get("what").asText()).isEqualTo("everything");
        assertThat(done.get("folder").asText()).startsWith("Butterfly_Atlas_backup_");
        assertThat(done.get("path").asText()).isEqualTo(dest.resolve(done.get("folder").asText()).toString());
        assertThat(done.get("total").asInt()).isEqualTo(2);
        assertThat(done.get("copied").asInt()).isEqualTo(2);
        assertThat(done.get("skipped").asInt()).isZero();
        assertThat(done.get("failed").asInt()).isZero();
        assertThat(done.has("pausedBy")).isTrue();
        assertThat(done.has("notRemoved")).isTrue();
        assertThat(done.get("problems").isArray()).isTrue();
        assertThat(done.get("moreProblems").asInt()).isZero();
        assertThat(Files.exists(Path.of(done.get("path").asText()).resolve("atlas.db"))).isTrue();
        JsonNode list = json(get("/api/backup"));
        assertThat(list.get("lastWhat").asText()).isEqualTo("everything");
        assertThat(list.get("past")).hasSize(1);
        assertThat(json(get("/api/status")).get("backup").isNull()).isTrue();
    }

    @Test
    void aDatabaseBackupIsDoneInTheAnswer() throws Exception {
        RawHttp.Response made = send("POST", "/api/backup", "{\"what\":\"database\",\"path\":" + JSON.writeValueAsString(
                dest.toString()) + "}");

        assertThat(made.status()).isEqualTo(201);
        assertThat(json(made).get("state").asText()).isEqualTo("done");
    }

    @Test
    void theRefusalsHaveTheirOwnCodes() throws Exception {
        RawHttp.Response noPath = send("POST", "/api/backup", "{\"what\":\"database\"}");
        assertThat(noPath.status()).isEqualTo(400);
        assertThat(json(noPath).get("error").get("code").asText()).isEqualTo("invalid");
        RawHttp.Response inside = send("POST", "/api/backup", "{\"what\":\"database\",\"path\":" + JSON.writeValueAsString(
                home.resolve("Atlas").toString()) + "}");
        assertThat(json(inside).get("error").get("code").asText()).isEqualTo("library_folder");
        assertThat(send("POST", "/api/backup", "{\"what\":\"nothing\",\"path\":\"/x\"}").status()).isEqualTo(400);
        assertThat(send("POST", "/api/backup", "not json").status()).isEqualTo(400);
        assertThat(get("/api/backup/999").status()).isEqualTo(404);
        assertThat(get("/api/backup/abc").status()).isEqualTo(404);
        assertThat(send("POST", "/api/backup/999/pause", "{}").status()).isEqualTo(404);
        assertThat(send("DELETE", "/api/backup/999", null).status()).isEqualTo(404);
    }

    @Test
    void aTaxonomyUpdateHoldingTheGateIs409TaxonomyUpdating() throws Exception {
        holder.library().gate().close();
        try {
            RawHttp.Response response = send("POST", "/api/backup", "{\"what\":\"database\",\"path\":"
                    + JSON.writeValueAsString(dest.toString()) + "}");

            assertThat(response.status()).isEqualTo(409);
            assertThat(json(response).get("error").get("code").asText()).isEqualTo("taxonomy_updating");
        } finally {
            holder.library().gate().open();
        }
    }

    @Test
    void theStatusFollowsARunningBackupAndItsPauseWithoutTheDatabase() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestBackups.beforePhoto(services.backups(), id -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long id = startEverything();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        JsonNode running = json(get("/api/status")).get("backup");
        assertThat(running.get("id").asLong()).isEqualTo(id);
        assertThat(running.get("state").asText()).isEqualTo("running");
        assertThat(running.get("total").asInt()).isEqualTo(2);
        assertThat(running.get("done").asInt()).isZero();
        assertThat(running.get("pausedBy").isNull()).isTrue();
        assertThat(running.get("path").asText()).startsWith(dest.toString());
        assertThat(running.get("holdsPhotos").asBoolean()).as("a backup of Everything copying its photos").isTrue();
        assertThat(json(get("/api/backup")).get("current").get("id").asLong()).isEqualTo(id);

        assertThat(send("POST", "/api/backup/" + id + "/pause", "{}").status()).isEqualTo(200);
        release.countDown();
        JsonNode paused = awaitDone(id);
        assertThat(paused.get("state").asText()).isEqualTo("paused");
        assertThat(paused.get("pausedBy").asText()).isEqualTo("user");
        assertThat(json(get("/api/status")).get("backup").get("state").asText()).isEqualTo("paused");
        assertThat(send("DELETE", "/api/backup/" + id, null).status()).isEqualTo(409);

        TestBackups.beforePhoto(services.backups(), x -> { });
        assertThat(send("POST", "/api/backup/" + id + "/resume", "{}").status()).isEqualTo(200);
        assertThat(awaitDone(id).get("state").asText()).isEqualTo("done");
        RawHttp.Response cancelled = send("POST", "/api/backup/" + id + "/cancel", "{\"remove\":false}");
        assertThat(cancelled.status()).isEqualTo(409);
    }

    @Test
    void afterDoneThereIsNothingToPauseResumeOrCancel() throws Exception {
        long id = startEverything();
        awaitDone(id);

        for (String action : new String[] {"pause", "resume", "cancel"}) {
            RawHttp.Response response = send("POST", "/api/backup/" + id + "/" + action, "{}");
            assertThat(response.status()).isEqualTo(409);
            assertThat(json(response).get("error").get("code").asText()).isEqualTo("backup_finished");
        }
    }

    @Test
    void aFinishedBackupIsForgottenAloneOrWithTheRestAndItsFilesStay() throws Exception {
        long first = startEverything();
        JsonNode done = awaitDone(first);
        long second = startEverything();
        awaitDone(second);

        assertThat(send("DELETE", "/api/backup/" + first, null).status()).isEqualTo(204);
        assertThat(get("/api/backup/" + first).status()).isEqualTo(404);
        RawHttp.Response cleared = send("DELETE", "/api/backup", null);
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(json(cleared).get("removed").asInt()).isEqualTo(1);
        assertThat(Files.isDirectory(Path.of(done.get("path").asText()))).isTrue();
    }

    @Test
    void theChangesNeedTheAppsOwnOriginLikeEveryOtherChange() throws Exception {
        RawHttp.Response response = RawHttp.send(server.port(), "POST", "/api/backup", Map.of("Content-Type",
                "application/json"), "{\"what\":\"database\",\"path\":\"/x\"}");

        assertThat(response.status()).isEqualTo(403);
    }
}
