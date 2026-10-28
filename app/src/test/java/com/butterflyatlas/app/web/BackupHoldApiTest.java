package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.backup.TestBackups;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.photos.Photo;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The photos a backup of Everything holds (multi-window design §5 G1/G1b, contract §4, audit A5/A6): every photo change is
 * refused with {@code backup_copying} in the backup's words while it runs or is paused before its photos are all copied,
 * an upload's refusal is a clean answer, and a backup neither starts nor resumes while an import files.
 */
class BackupHoldApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUNNING = "A backup is copying your photos, so photos can't be changed right now. Wait for"
            + " it to finish, or cancel it, change your photos, then start a new backup.";
    /** Paused by the user: the card's buttons are Resume and Cancel… (§11 A22). */
    private static final String PAUSED = "A paused backup still holds your photos, so they can't be changed yet. Resume"
            + " the backup and let it finish, or cancel it, change your photos, then start a new backup.";

    @TempDir
    Path home;

    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private AtlasServer server;
    private Path dest;
    private final CountDownLatch inside = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean hold = new AtomicBoolean(true);
    private Photo live;
    private Photo deleted;
    private String custom;

    @BeforeEach
    void start() throws Exception {
        services = TestServices.configured(home, holder);
        server = AtlasServer.start(0, services);
        dest = Files.createDirectories(home.resolve("Backup"));
        live = photo("A5", 1);
        photo("A5", 3); // two live photos: a pause after the first leaves one to copy
        deleted = photo("A5", 2);
        services.photos().delete(List.of(deleted.id()));
        custom = services.custom().add("A1", "Neptis sankara", null).id();
        TestBackups.beforePhoto(services.backups(), id -> {
            if (hold.get()) {
                inside.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.close();
        services.exports().close();
        services.backups().close();
        services.imports().close();
        services.thumbnails().close();
        services.placeNotes().close(); // its count may still be reading the library (Windows can't delete an open file)
    }

    private Photo photo(String taxonId, int seed) {
        return services.photos().add(taxonId, new ByteArrayInputStream(TestImages.jpeg(40, 30, seed)), "image/jpeg", null,
                List.of()).photo();
    }

    private RawHttp.Response send(String method, String path, String body) throws Exception {
        return RawHttp.send(server.port(), method, path, Map.of("Origin", "http://127.0.0.1:" + server.port(),
                "Content-Type", "application/json"), body);
    }

    private long startEverything() throws Exception {
        RawHttp.Response made = send("POST", "/api/backup", "{\"what\":\"everything\",\"path\":"
                + JSON.writeValueAsString(dest.toString()) + "}");
        assertThat(made.status()).isEqualTo(201);
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        return JSON.readTree(made.body()).get("id").asLong();
    }

    private static void refused(RawHttp.Response response, String code, String message) throws Exception {
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(error.get("message").asText()).isEqualTo(message);
    }

    /** Every write that changes photos, each refused in these words. */
    private void everyPhotoChangeIsRefused(String message) throws Exception {
        refused(send("POST", "/api/photos/move", "{\"ids\":[" + live.id() + "],\"taxonId\":\"A6\"}"), "backup_copying",
                message);
        refused(send("POST", "/api/photos/delete", "{\"ids\":[" + live.id() + "]}"), "backup_copying", message);
        refused(send("POST", "/api/deleted/restore", "{\"ids\":[" + deleted.id() + "]}"), "backup_copying", message);
        refused(send("POST", "/api/deleted/empty", "{}"), "backup_copying", message);
        refused(send("PATCH", "/api/custom-species/" + custom, "{\"name\":\"Neptis sankarae\"}"), "backup_copying", message);
        refused(send("DELETE", "/api/custom-species/" + custom, null), "backup_copying", message);
        refused(send("POST", "/api/splits/A5/sort", "{\"photoIds\":[" + live.id() + "],\"target\":\"A6\"}"),
                "backup_copying", message);
        refused(send("POST", "/api/imports/999/file", "{\"revision\":0}"), "backup_copying", message);
        refused(upload(4 * 1024 * 1024), "backup_copying", message);
    }

    @Test
    void whileABackupOfEverythingCopiesEveryPhotoChangeIsRefusedInItsWordsAndRowEditsGoOn() throws Exception {
        startEverything();

        everyPhotoChangeIsRefused(RUNNING);
        assertThat(send("PATCH", "/api/custom-species/" + custom, "{\"name\":\"Neptis sankara\",\"common\":\"Sankara\"}")
                .status()).as("a common name alone moves nothing").isEqualTo(200);
        assertThat(send("PATCH", "/api/photos/" + live.id(), "{\"tags\":[]}").status())
                .as("a photo's tags are rows").isEqualTo(200);
        assertThat(services.photoQueries().photos("A5")).extracting(Photo::id).contains(live.id());
    }

    @Test
    void whilePausedByTheUserBeforeItsPhotosAreAllCopiedTheRefusalSaysResumeOrCancel() throws Exception {
        long id = startEverything();
        assertThat(send("POST", "/api/backup/" + id + "/pause", "{}").status()).isEqualTo(200);
        release.countDown();
        long deadline = System.currentTimeMillis() + 20_000;
        while (services.backups().copying() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        everyPhotoChangeIsRefused(PAUSED);

        assertThat(send("POST", "/api/backup/" + id + "/cancel", "{\"remove\":false}").status()).isEqualTo(200);
        assertThat(send("POST", "/api/photos/move", "{\"ids\":[" + live.id() + "],\"taxonId\":\"A6\"}").status())
                .as("discarded: photos can change again").isEqualTo(200);
    }

    @Test
    void aBackupPausedAtItsDatabaseWithItsPhotosAllCopiedStillHoldsThem() throws Exception {
        hold.set(false);
        TestBackups.afterDatabase(services.backups(), copy -> {
            try {
                Files.writeString(copy, "this is no longer a database ".repeat(500));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        RawHttp.Response made = send("POST", "/api/backup", "{\"what\":\"everything\",\"path\":"
                + JSON.writeValueAsString(dest.toString()) + "}");
        assertThat(made.status()).isEqualTo(201);
        long id = JSON.readTree(made.body()).get("id").asLong();
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode backup = JSON.readTree(send("GET", "/api/backup/" + id, null).body());
        while (!"paused".equals(backup.get("state").asText()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            backup = JSON.readTree(send("GET", "/api/backup/" + id, null).body());
        }
        assertThat(backup.get("state").asText()).isEqualTo("paused");
        assertThat(backup.get("done").asInt()).as("its photos all copied").isEqualTo(backup.get("total").asInt());

        refused(send("POST", "/api/photos/move", "{\"ids\":[" + live.id() + "],\"taxonId\":\"A6\"}"), "backup_copying",
                PAUSED);
    }

    @Test
    void aBackupNeitherStartsNorResumesWhileAnImportFiles() throws Exception {
        hold.set(false);
        services.backups().importFiling(() -> true);
        String words = "An import is filing photos. Wait for filing to finish, or pause it, then start the backup.";

        refused(send("POST", "/api/backup", "{\"what\":\"everything\",\"path\":" + JSON.writeValueAsString(dest.toString())
                + "}"), "import_filing", words);
        refused(send("POST", "/api/backup", "{\"what\":\"database\",\"path\":" + JSON.writeValueAsString(dest.toString())
                + "}"), "import_filing", words);

        services.backups().importFiling(() -> false);
        hold.set(true);
        long id = startEverything();
        assertThat(send("POST", "/api/backup/" + id + "/pause", "{}").status()).isEqualTo(200);
        release.countDown();
        long deadline = System.currentTimeMillis() + 20_000;
        while (services.backups().copying() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        services.backups().importFiling(() -> true);
        refused(send("POST", "/api/backup/" + id + "/resume", "{}"), "import_filing", words);
    }

    /**
     * Uploads {@code size} bytes over a plain socket, the whole body sent whatever the server does meanwhile, and reads the
     * answer: a server that answered without reading the body would reset the connection (audit A5).
     */
    private RawHttp.Response upload(int size) throws Exception {
        byte[] body = new byte[size];
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), server.port())) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/taxa/A5/photos?name=big.jpg HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                    + "\r\nOrigin: http://127.0.0.1:" + server.port() + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                    + size + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            for (int at = 0; at < size; at += 64 * 1024) {
                out.write(body, at, Math.min(64 * 1024, size - at));
            }
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(text.substring(9, 12));
            String content = text.substring(text.indexOf("\r\n\r\n") + 4);
            if (text.toLowerCase().contains("transfer-encoding: chunked")) {
                content = content.substring(content.indexOf("\r\n") + 2, content.lastIndexOf("}") + 1);
            }
            return new RawHttp.Response(status, content, Map.of());
        }
    }
}
