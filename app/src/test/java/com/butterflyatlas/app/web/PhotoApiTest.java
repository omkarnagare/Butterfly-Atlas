package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.testing.RawHttp;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoApiTest {

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
        services.thumbnails().close();
        services.placeNotes().close(); // its count may still be reading the library (Windows can't delete an open file)
        http.close();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(server.baseUri().resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Origin", "http://127.0.0.1:" + server.port());
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = request(path);
        if (json != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        return ok(send("GET", path, null));
    }

    private HttpResponse<String> upload(String taxonId, byte[] bytes, String contentType, String query) throws Exception {
        return http.send(request("api/taxa/" + taxonId + "/photos" + query)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode added(String taxonId, byte[] bytes) throws Exception {
        JsonNode photo = ok(upload(taxonId, bytes, "image/jpeg", "")).get("photo");
        // The thumbnail worker holds the file open while it works; wait so moves and deletes never race it.
        for (int i = 0; i < 200 && get("api/status").at("/previews/pending").asInt() > 0; i++) {
            Thread.sleep(50);
        }
        assertThat(get("api/status").at("/previews/pending").asInt()).as("previews settled").isZero();
        return photo;
    }

    @Test
    void uploadingAPhotoAndSeeingItEverywhere() throws Exception {
        JsonNode result = ok(upload("A5", TestImages.jpeg(400, 300), "image/jpeg", "?name=IMG_0001.JPG&tags=male,larva"));

        assertThat(result.get("status").asText()).isEqualTo("added");
        assertThat(result.get("otherTaxon").isNull()).isTrue();
        assertThat(result.get("firstForSpecies").asBoolean()).isTrue();
        assertThat(result.get("milestone").isNull()).isTrue();
        assertThat(result.get("speciesCount").asInt()).isEqualTo(1);
        JsonNode photo = result.get("photo");
        assertThat(photo.get("fileName").asText()).isEqualTo("Danaus_chrysippus_01.jpg");
        assertThat(photo.get("relPath").asText())
                .isEqualTo("images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/Danaus_chrysippus_01.jpg");
        assertThat(photo.get("taxonId").asText()).isEqualTo("A5");
        assertThat(photo.get("tags").toString()).isEqualTo("[\"larva\",\"male\"]");
        assertThat(photo.get("width").asInt()).isEqualTo(400);
        assertThat(photo.get("addedAt").asText()).isEqualTo("2026-10-09T08:00:00Z");
        assertThat(photo.get("isCover").asBoolean()).isTrue();
        long id = photo.get("id").asLong();

        assertThat(get("api/taxa/A5/photos").get("photos").get(0).get("id").asLong()).isEqualTo(id);
        JsonNode detail = get("api/taxa/A5");
        assertThat(detail.get("photoCount").asInt()).isEqualTo(1);
        assertThat(detail.get("stages").toString()).isEqualTo("{\"egg\":false,\"larva\":true,\"pupa\":false,\"adult\":true}");
        assertThat(detail.at("/cover/id").asLong()).isEqualTo(id);
        JsonNode card = get("api/taxa/A4/children").get("children").get(0);
        assertThat(card.at("/cover/id").asLong()).isEqualTo(id);
        assertThat(card.at("/cover/v").asText()).matches("\\d+-\\d+").isEqualTo(photo.get("v").asText());
        JsonNode summary = get("api/summary");
        assertThat(summary.get("photoCount").asInt()).isEqualTo(1);
        assertThat(summary.at("/latest/photo/id").asLong()).isEqualTo(id);
        assertThat(summary.at("/latest/taxon/id").asText()).isEqualTo("A5");
        assertThat(summary.get("recent").size()).isEqualTo(1);
        assertThat(summary.get("nextOnList").get(0).get("id").asText()).isEqualTo("A6");
        assertThat(summary.get("fullLifeCycles").size()).isZero();
    }

    @Test
    void uploadsThatAreNotPhotosAreRefusedInPlainWords() throws Exception {
        HttpResponse<String> heicBytes = upload("A5", "\0\0\0\u0018ftypmsf1\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1),
                "image/jpeg", "");
        assertThat(heicBytes.statusCode()).isEqualTo(415);
        assertThat(JSON.readTree(heicBytes.body()).get("error").toString())
                .isEqualTo("{\"code\":\"unsupported_photo\",\"message\":\"" + PhotoFormat.HEIC_SEQUENCE + "\"}");

        HttpResponse<String> heicType = upload("A5", new byte[] {1, 2, 3}, "image/heic", "");
        assertThat(heicType.statusCode()).as("the bytes decide").isEqualTo(415);
        assertThat(heicType.body()).contains("This file isn't a photo Butterfly Atlas can read.");

        HttpResponse<String> text = upload("A5", "hello".getBytes(StandardCharsets.US_ASCII), "image/png", "");
        assertThat(text.statusCode()).isEqualTo(415);
        assertThat(text.body()).contains("This file isn't a photo Butterfly Atlas can read.");

        assertThat(upload("A4", TestImages.jpeg(20, 20), "image/jpeg", "").statusCode()).isEqualTo(400);
        assertThat(upload("A999", TestImages.jpeg(20, 20), "image/jpeg", "").statusCode()).isEqualTo(404);
        assertThat(upload("A5", TestImages.jpeg(20, 20), "image/jpeg", "?tags=wingspan").statusCode()).isEqualTo(400);
    }

    @Test
    void aDeclaredSizeOverTheLimitIsRefusedWithoutReadingTheRest() throws Exception {
        // Announce 400 MB but send only the first 64 KB (Jetty dispatches once some body has arrived).
        String head = "POST /api/taxa/A5/photos HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                + "\r\nOrigin: http://127.0.0.1:" + server.port() + "\r\nContent-Type: image/jpeg"
                + "\r\nContent-Length: " + (400L * 1024 * 1024) + "\r\nConnection: close\r\n\r\n";
        String response;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(new byte[64 * 1024]);
            out.flush();
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(response).startsWith("HTTP/1.1 413");
        assertThat(response).contains("\"code\":\"too_large\"", "larger than 300 MB");
        assertThat(services.photoQueries().photos("A5")).isEmpty();
    }

    @Test
    void duplicatesAreSkippedOrFlagged() throws Exception {
        byte[] jpeg = TestImages.jpeg(40, 30);
        added("A5", jpeg);

        JsonNode again = ok(upload("A5", jpeg, "image/jpeg", ""));
        assertThat(again.get("status").asText()).isEqualTo("skipped");
        assertThat(again.get("photo").isNull()).isTrue();
        assertThat(again.at("/otherTaxon/id").asText()).isEqualTo("A5");
        assertThat(again.get("speciesCount").asInt()).isEqualTo(1);

        JsonNode elsewhere = ok(upload("A6", jpeg, "image/jpeg", ""));
        assertThat(elsewhere.get("status").asText()).isEqualTo("addedElsewhereToo");
        assertThat(elsewhere.get("speciesCount").asInt()).isEqualTo(2);
        assertThat(elsewhere.get("otherTaxon").toString())
                .isEqualTo("{\"id\":\"A5\",\"name\":\"Danaus chrysippus\",\"common\":\"Plain Tiger\"}");
    }

    @Test
    void tagsCanBeListedCreatedRenamedAndDeleted() throws Exception {
        JsonNode list = get("api/tags").get("tags");
        assertThat(list.size()).isEqualTo(10);
        assertThat(list.get(9).toString())
                .isEqualTo("{\"id\":10,\"key\":\"unconfirmed\",\"label\":\"unconfirmed\",\"source\":\"builtin\",\"photoCount\":0}");
        assertThat(list.get(3).toString())
                .isEqualTo("{\"id\":4,\"key\":\"upper_wing\",\"label\":\"upper_wing\",\"source\":\"builtin\",\"photoCount\":0}");

        JsonNode created = ok(send("POST", "api/tags", "{\"label\":\"Mud-puddling\"}"));
        assertThat(created.get("key").asText()).isEqualTo("mud_puddling");
        long tagId = created.get("id").asLong();
        assertThat(ok(send("PATCH", "api/tags/" + tagId, "{\"label\":\"Puddling\"}")).get("label").asText()).isEqualTo("puddling");

        long photo = added("A5", TestImages.jpeg(40, 30)).get("id").asLong();
        ok(send("PATCH", "api/photos/" + photo, "{\"tags\":[\"mud_puddling\"]}"));
        assertThat(ok(send("DELETE", "api/tags/" + tagId, null)).get("removedFromPhotos").asInt()).isEqualTo(1);

        HttpResponse<String> builtIn = send("DELETE", "api/tags/1", null);
        assertThat(builtIn.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(builtIn.body()).at("/error/message").asText())
                .isEqualTo("Built-in tags can be renamed but not deleted.");
        assertThat(send("PATCH", "api/tags/abc", "{\"label\":\"x\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void photoTagsCanBeSetOneByOneAndInBulk() throws Exception {
        long a = added("A5", TestImages.jpeg(40, 30, 1)).get("id").asLong();
        long b = added("A5", TestImages.jpeg(40, 30, 2)).get("id").asLong();

        assertThat(ok(send("PATCH", "api/photos/" + a, "{\"tags\":[\"pupa\",\"egg\"]}")).get("tags").toString())
                .isEqualTo("[\"egg\",\"pupa\"]");
        JsonNode bulk = ok(send("POST", "api/photos/tags", "{\"ids\":[" + a + "," + b + "],\"add\":[\"male\"],\"remove\":[\"egg\"]}"));
        assertThat(bulk.get("photos").get(0).get("tags").toString()).isEqualTo("[\"pupa\",\"male\"]");
        assertThat(bulk.get("photos").get(1).get("tags").toString()).isEqualTo("[\"male\"]");
        assertThat(send("PATCH", "api/photos/" + a, "{}").statusCode()).isEqualTo(400);
        assertThat(send("PATCH", "api/photos/999", "{\"tags\":[]}").statusCode()).isEqualTo(404);
    }

    @Test
    void photosCanBeMovedAndChosenAsCover() throws Exception {
        long a = added("A5", TestImages.jpeg(40, 30, 1)).get("id").asLong();
        long b = added("A6", TestImages.jpeg(40, 30, 2)).get("id").asLong();

        JsonNode moved = ok(send("POST", "api/photos/move", "{\"ids\":[" + a + "],\"taxonId\":\"A6\"}"));
        assertThat(moved.get("photos").get(0).get("fileName").asText()).isEqualTo("Danaus_genutia_02.jpg");

        assertThat(ok(send("PUT", "api/taxa/A6/cover", "{\"photoId\":" + a + "}")).toString()).isEqualTo("{\"coverId\":" + a + "}");
        assertThat(get("api/taxa/A6").at("/cover/id").asLong()).isEqualTo(a);
        assertThat(ok(send("PUT", "api/taxa/A6/cover", "{\"photoId\":null}")).toString()).isEqualTo("{\"coverId\":null}");
        assertThat(get("api/taxa/A6").at("/cover/id").asLong()).as("automatic again: the newest photo").isEqualTo(b);
    }

    @Test
    void deletingRestoringAndEmptying() throws Exception {
        long a = added("A5", TestImages.jpeg(40, 30, 1)).get("id").asLong();
        long b = added("A5", TestImages.jpeg(40, 30, 2)).get("id").asLong();

        assertThat(ok(send("POST", "api/photos/delete", "{\"ids\":[" + a + "," + b + "]}")).toString()).isEqualTo("{\"deleted\":2}");
        assertThat(get("api/status").get("deletedCount").asInt()).isEqualTo(2);
        JsonNode deleted = get("api/deleted").get("photos");
        assertThat(deleted.size()).isEqualTo(2);
        assertThat(deleted.get(0).get("daysLeft").asInt()).isEqualTo(30);
        assertThat(deleted.get(0).get("deletedAt").asText()).isEqualTo("2026-10-09T08:00:00Z");
        assertThat(deleted.get(0).at("/taxon/common").asText()).isEqualTo("Plain Tiger");
        assertThat(get("api/taxa/A5").get("photoCount").asInt()).isZero();

        JsonNode restored = ok(send("POST", "api/deleted/restore", "{\"ids\":[" + a + "]}"));
        assertThat(restored.get("photos").get(0).get("fileName").asText()).isEqualTo("Danaus_chrysippus_01.jpg");
        assertThat(ok(send("POST", "api/deleted/empty", "{\"ids\":[]}")).toString()).isEqualTo("{\"removed\":0}");
        assertThat(ok(send("POST", "api/deleted/empty", "{}")).toString()).isEqualTo("{\"removed\":1}");
        assertThat(get("api/status").get("deletedCount").asInt()).isZero();
    }

    /** Sets a photo's place as a later step would (3c-2 writes 'user'; GPS lookups write 'gps'). */
    private void place(long id, String placeId, String source) throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + home.resolve("Atlas/atlas.db"));
             var ps = c.prepareStatement("UPDATE image SET place_id = ?, place_source = ? WHERE id = ?")) {
            ps.setString(1, placeId);
            ps.setString(2, source);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    @Test
    void everyAnswerWithPhotosGivesTheirPlaceAsAPathUpToTheCountry() throws Exception {
        JsonNode first = added("A5", TestImages.jpeg(40, 30, 1));
        assertThat(first.get("place").isNull()).as("no GPS: unknown place").isTrue();
        long a = first.get("id").asLong();
        long b = added("A5", TestImages.jpeg(40, 30, 2)).get("id").asLong();
        long c = added("A5", TestImages.jpeg(40, 30, 3)).get("id").asLong();
        place(a, "P4", "gps");
        place(b, "P8", "user");
        place(c, null, "user");

        JsonNode gallery = get("api/taxa/A5/photos").get("photos");
        assertThat(gallery.get(2).get("place").toString())
                .isEqualTo("{\"id\":\"P4\",\"path\":[\"Alpha\",\"Northstate\",\"Testland\"],\"ids\":[\"P4\",\"P2\",\"P1\"],\"source\":\"gps\",\"former\":false,\"level\":\"district\"}");
        assertThat(gallery.get(1).get("place").toString()).isEqualTo("{\"id\":\"P8\",\"path\":[\"Farland\"],\"ids\":[\"P8\"],\"source\":\"user\",\"former\":false,\"level\":\"country\"}");
        assertThat(gallery.get(0).get("place").isNull()).as("removed by the user: unknown place").isTrue();
        assertThat(get("api/summary").at("/recent/2/photo/place/id").asText()).isEqualTo("P4");
        JsonNode tagged = ok(send("POST", "api/photos/tags", "{\"ids\":[" + a + "],\"add\":[\"male\"],\"remove\":[]}"));
        assertThat(tagged.toString()).contains("\"place\":{\"id\":\"P4\"");

        ok(send("POST", "api/photos/delete", "{\"ids\":[" + a + "]}"));
        assertThat(get("api/deleted").get("photos").get(0).at("/place/path").toString())
                .as("a photo in Recently deleted keeps its place").isEqualTo("[\"Alpha\",\"Northstate\",\"Testland\"]");
        JsonNode restored = ok(send("POST", "api/deleted/restore", "{\"ids\":[" + a + "]}"));
        assertThat(restored.get("photos").get(0).at("/place/id").asText()).isEqualTo("P4");

        JsonNode moved = ok(send("POST", "api/photos/move", "{\"ids\":[" + a + "],\"taxonId\":\"A6\"}"));
        assertThat(moved.get("photos").get(0).at("/place/id").asText()).as("a moved photo keeps its place").isEqualTo("P4");

        place(c, "P4", "gps");
        assertThat(get("api/summary").at("/latest/photo/id").asLong()).isEqualTo(c);
        assertThat(get("api/summary").at("/latest/photo/place/path").toString()).isEqualTo("[\"Alpha\",\"Northstate\",\"Testland\"]");
        place(c, "P999", "gps");
        assertThat(get("api/summary").at("/latest/photo/place").isNull()).as("a place the library does not have reads as unknown").isTrue();
        assertThat(get("api/taxa/A5/photos").get("photos").get(0).get("place").isNull()).isTrue();
    }

    @Test
    void thumbnailsAndOriginals() throws Exception {
        byte[] jpeg = TestImages.jpeg(1200, 800);
        long id = added("A5", jpeg).get("id").asLong();

        HttpResponse<byte[]> thumb = http.send(request("api/photos/" + id + "/thumb?v=1").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(thumb.statusCode()).isEqualTo(200);
        assertThat(thumb.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(thumb.headers().firstValue("Cache-Control")).hasValue("public, max-age=31536000, immutable");
        assertThat(ImageIO.read(new ByteArrayInputStream(thumb.body())).getWidth()).isEqualTo(480);

        HttpResponse<byte[]> original = http.send(request("api/photos/" + id + "/original").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(original.statusCode()).isEqualTo(200);
        assertThat(original.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(original.headers().firstValue("Cache-Control")).hasValue("no-cache");
        assertThat(original.body()).isEqualTo(jpeg);

        // Asked again (the viewer opens the photo again): an unchanged file is not sent again, a changed one is.
        for (String kind : new String[] {"original", "display"}) {
            HttpResponse<byte[]> first = http.send(request("api/photos/" + id + "/" + kind).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String tag = first.headers().firstValue("ETag").orElseThrow();
            HttpResponse<byte[]> again = http.send(request("api/photos/" + id + "/" + kind).header("If-None-Match", tag)
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(again.statusCode()).as(kind).isEqualTo(304);
            assertThat(again.body()).as(kind).isEmpty();
            assertThat(again.headers().firstValue("ETag")).as(kind).hasValue(tag);
        }
        Path file = services.photoQueries().original(id).file();
        String before = original.headers().firstValue("ETag").orElseThrow();
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        HttpResponse<byte[]> changed = http.send(request("api/photos/" + id + "/display").header("If-None-Match", before)
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(changed.statusCode()).as("the file changed: sent again").isEqualTo(200);
        assertThat(changed.body()).isEqualTo(jpeg);
        assertThat(changed.headers().firstValue("ETag")).isPresent().get().isNotEqualTo(before);

        assertThat(send("GET", "api/photos/999/thumb", null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "api/photos/abc/original", null).body()).contains("\"code\":\"not_found\"");
    }

    @Test
    void theViewerGetsTiffAsAJpegCopyAndOtherFormatsAsTheyAre() throws Exception {
        long tiff = ok(upload("A5", TestImages.tiff(3000, 1500), "image/tiff", "")).at("/photo/id").asLong();
        byte[] png = TestImages.png(300, 200);
        long other = ok(upload("A5", png, "image/png", "")).at("/photo/id").asLong();

        HttpResponse<byte[]> copy = http.send(request("api/photos/" + tiff + "/display").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(copy.statusCode()).isEqualTo(200);
        assertThat(copy.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(ImageIO.read(new ByteArrayInputStream(copy.body())).getWidth()).isEqualTo(2560);
        assertThat(home.resolve("Atlas/.thumbs")).isDirectoryContaining(f -> f.getFileName().toString().startsWith(tiff + "-2560-"));

        HttpResponse<byte[]> same = http.send(request("api/photos/" + other + "/display").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(same.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(same.body()).isEqualTo(png);
        assertThat(send("GET", "api/photos/999/display", null).statusCode()).isEqualTo(404);
    }

    @Test
    void statusReportsTheRescanAndPreviews() throws Exception {
        assertThat(get("api/status").toString()).isEqualTo("{\"rescanning\":false,\"rescan\":null,"
                + "\"previews\":{\"pending\":0,\"done\":0},\"deletedCount\":0,\"processing\":null,\"import\":null,\"stoppedImports\":[],"
                + "\"export\":null,\"taxonomyUpdate\":null,\"splitsToSort\":0,\"customMatches\":0,\"library\":null,\"placeUpdate\":null,\"backup\":null,\"heic\":{\"state\":\"checking\",\"decoder\":null,"
                + "\"system\":\"" + com.butterflyatlas.app.photos.HeicStatus.current().system() + "\",\"reason\":null},\"rescanLeft\":[],\"libraryVersion\":0,\"theme\":null}");
        Path copied = home.resolve("Atlas/images/Copied [A5]/mine.jpg");
        Files.createDirectories(copied.getParent());
        Files.write(copied, TestImages.jpeg(60, 40));
        // The rescan leaves files that changed in the last few seconds alone (they may still be arriving).
        Files.setLastModifiedTime(copied, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

        services.rescan().run();
        long id = get("api/taxa/A5/photos").get("photos").get(0).get("id").asLong();
        assertThat(services.thumbnails().await(id, Duration.ofSeconds(10))).isPresent();

        assertThat(get("api/status").toString()).isEqualTo("{\"rescanning\":false,"
                + "\"rescan\":{\"checked\":1,\"total\":1,\"added\":1,\"removed\":0,\"updated\":0,\"finishedAt\":\"2026-10-09T08:00:00Z\"},"
                + "\"previews\":{\"pending\":0,\"done\":0},\"deletedCount\":0,\"processing\":null,\"import\":null,\"stoppedImports\":[],"
                + "\"export\":null,\"taxonomyUpdate\":null,\"splitsToSort\":0,\"customMatches\":0,\"library\":null,\"placeUpdate\":null,\"backup\":null,\"heic\":{\"state\":\"checking\",\"decoder\":null,"
                + "\"system\":\"" + com.butterflyatlas.app.photos.HeicStatus.current().system() + "\",\"reason\":null},\"rescanLeft\":[],\"libraryVersion\":0,\"theme\":null}");
    }

    /** Services whose photo reads and thumbnails wait for {@code gate} while it is closed. */
    private void restartWithGate(CountDownLatch gate) {
        server.close();
        services.thumbnails().close();
        ImageIoPhotoProcessor real = new ImageIoPhotoProcessor();
        PhotoProcessor gated = new PhotoProcessor() {
            @Override
            public PhotoFacts read(Path file) throws IOException {
                pass(gate);
                return real.read(file);
            }

            @Override
            public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
                pass(gate);
                real.thumbnail(source, orientation, longSide, target);
            }

            @Override
            public String dominantColor(Path image) throws IOException {
                return real.dominantColor(image);
            }
        };
        services = TestServices.configured(home, gated);
        server = AtlasServer.start(0, services);
    }

    private static void pass(CountDownLatch gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aThumbnailThatIsNotReadyAnswersAtOnceWithAPlaceholder() throws Exception {
        long id = added("A5", TestImages.jpeg(600, 400)).get("id").asLong();
        try (var thumbs = Files.list(home.resolve("Atlas/.thumbs"))) {
            for (Path thumb : thumbs.toList()) {
                Files.delete(thumb);
            }
        }
        CountDownLatch gate = new CountDownLatch(1);
        restartWithGate(gate);

        long started = System.nanoTime();
        HttpResponse<byte[]> waiting = http.send(request("api/photos/" + id + "/thumb?v=1").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(waiting.statusCode()).isEqualTo(200);
        assertThat(waiting.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(waiting.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(waiting.headers().firstValue("X-Atlas-Pending")).hasValue("1");

        gate.countDown();
        assertThat(services.thumbnails().await(id, Duration.ofSeconds(10))).isPresent();
        HttpResponse<byte[]> ready = http.send(request("api/photos/" + id + "/thumb?v=1").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(ready.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(ready.headers().firstValue("X-Atlas-Pending")).isEmpty();
    }

    @Test
    void statusReportsPhotoDetailsBeingUpdated() throws Exception {
        added("A5", TestImages.jpeg(60, 40));
        CountDownLatch gate = new CountDownLatch(1);
        restartWithGate(gate);
        // As after the upgrade to 3b-1: a photo without its snapshot.
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + home.resolve("Atlas/atlas.db"));
             var st = c.createStatement()) {
            st.executeUpdate("DELETE FROM image_meta");
        }
        Thread run = new Thread(services.processing()::run);
        run.start();
        try {
            JsonNode processing = get("api/status").get("processing");
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (processing.isNull() && System.nanoTime() < until) {
                Thread.sleep(10);
                processing = get("api/status").get("processing");
            }
            assertThat(processing).hasToString("{\"step\":\"details\",\"done\":0,\"total\":1}");
        } finally {
            gate.countDown(); // a failed check must not leave the run stuck at the gate
            run.join(10_000);
        }
        assertThat(get("api/status").get("processing").isNull()).isTrue();
    }

    @Test
    void otherWebsitesCannotReadPhotosThroughTheBrowser() throws Exception {
        long id = added("A5", TestImages.jpeg(60, 40)).get("id").asLong();
        String thumb = "/api/photos/" + id + "/thumb?v=1";

        for (String site : new String[] {"cross-site", "same-site", "Cross-Site"}) {
            RawHttp.Response refused = RawHttp.send(server.port(), "GET", thumb, Map.of("Sec-Fetch-Site", site), null);
            assertThat(refused.status()).as(site).isEqualTo(403);
            assertThat(refused.body()).contains("\"code\":\"forbidden_site\"", "Butterfly Atlas only answers its own window.");
        }
        assertThat(RawHttp.send(server.port(), "GET", "/api/photos/" + id + "/display", Map.of("Sec-Fetch-Site", "cross-site"), null)
                .status()).isEqualTo(403);
        assertThat(RawHttp.send(server.port(), "GET", "/api/status", Map.of("Sec-Fetch-Site", "cross-site"), null).status())
                .isEqualTo(403);
        for (Map<String, String> allowed : List.of(Map.of("Sec-Fetch-Site", "same-origin"), Map.of("Sec-Fetch-Site", "none"),
                Map.<String, String>of())) {
            assertThat(RawHttp.send(server.port(), "GET", thumb, allowed, null).status()).as(allowed.toString()).isEqualTo(200);
        }
    }

    @Test
    void apiAnswersCarryHeadersThatKeepThemOutOfOtherPages() throws Exception {
        long id = added("A5", TestImages.jpeg(60, 40)).get("id").asLong();
        assertThat(services.thumbnails().await(id, Duration.ofSeconds(10))).isPresent();

        RawHttp.Response thumb = RawHttp.send(server.port(), "GET", "/api/photos/" + id + "/thumb?v=1", Map.of(), null);
        assertThat(thumb.headers()).containsEntry("cross-origin-resource-policy", "same-origin")
                .containsEntry("x-content-type-options", "nosniff")
                .containsEntry("cache-control", "public, max-age=31536000, immutable");
        assertThat(RawHttp.send(server.port(), "GET", "/api/photos/" + id + "/thumb", Map.of(), null).headers())
                .as("no ?v= means no long-lived caching").containsEntry("cache-control", "no-cache");
        assertThat(RawHttp.send(server.port(), "GET", "/api/photos/" + id + "/thumb?v=", Map.of(), null).headers())
                .as("an empty ?v= is no version either").containsEntry("cache-control", "no-cache");

        for (String kind : new String[] {"original", "display"}) {
            RawHttp.Response photo = RawHttp.send(server.port(), "GET", "/api/photos/" + id + "/" + kind, Map.of(), null);
            assertThat(photo.headers()).as(kind).containsEntry("content-security-policy", "sandbox")
                    .containsEntry("cross-origin-resource-policy", "same-origin").containsKey("last-modified")
                    .containsEntry("content-length", String.valueOf(Files.size(services.photoQueries().original(id).file())));
        }
        RawHttp.Response error = RawHttp.send(server.port(), "GET", "/api/photos/999/thumb", Map.of(), null);
        assertThat(error.headers()).containsEntry("x-content-type-options", "nosniff");
    }
}
