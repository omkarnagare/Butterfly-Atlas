package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.imaging.heif.HeicDecoders;
import com.butterflyatlas.app.imaging.heif.TestDecoders;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestHeif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEIC photos in Import folder (Phase 7b contract §4, audit 6, 10, 16, D3, 24), with a fake decoder program: items read
 * with or without a decoder, previews made later, the summary's {@code heic}, the large view, previews that fail or time
 * out, and a Live Photo's video.
 */
class HeicImportApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(60);

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<String> mode = new AtomicReference<>("ok");
    private final LibraryHolder holder = new LibraryHolder();
    private AtlasServices services;
    private AtlasServer server;
    private HeicDecoders heic;
    private Path photos;

    @BeforeEach
    void start() throws Exception {
        heic = TestDecoders.fake(() -> home.resolve("Atlas").resolve(".thumbs").resolve(".decode"), mode::get, 640, 480,
                Duration.ofSeconds(3));
        services = TestServices.configured(home, new ImageIoPhotoProcessor(ZoneOffset.UTC, heic), holder);
        server = AtlasServer.start(0, services);
        assertThat(heic.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();
        photos = Files.createDirectories(home.resolve("Pictures").resolve("Phone"));
        Files.write(photos.resolve("IMG_0042.HEIC"), portrait());
    }

    @AfterEach
    void stop() {
        server.close();
        services.imports().close();
        services.thumbnails().close();
        services.placeNotes().close();
        http.close();
        HeicStatus.publish(HeicStatus.checking("mac"));
    }

    private static byte[] portrait() {
        return TestHeif.still(640, 480).turn(1)
                .exif(TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").gps(18.5, 73.5, 600).tiffBlock()).build();
    }

    /** File's body: the import's revision as its summary says it now (multi-window contract §5). */
    private String fileBody(long id) throws Exception {
        return "{\"revision\":" + JSON.readTree(send("GET", "api/imports/" + id, null).body()).get("revision").asLong() + "}";
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(WAIT)
                .header("Origin", "http://127.0.0.1:" + server.port());
        if (json != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> bytes(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(WAIT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private long started() throws Exception {
        HttpResponse<String> response = send("POST", "api/imports", JSON.writeValueAsString(Map.of("path", photos.toString())));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(services.imports().awaitHeicPreviews(WAIT)).isTrue();
        return JSON.readTree(response.body()).at("/import/id").asLong();
    }

    private Map<String, JsonNode> items(long id) throws Exception {
        Map<String, JsonNode> byPath = new HashMap<>();
        get("api/imports/" + id + "/items").get("items").forEach(item -> byPath.put(item.get("path").asText(), item));
        return byPath;
    }

    private String item(long id, String name) throws Exception {
        return "api/imports/" + id + "/items/" + items(id).get(name).get("id").asLong();
    }

    @Test
    void heicItemsAreReadAndShownThroughTheDecoder() throws Exception {
        Files.write(photos.resolve("A7S_0001.HIF"), TestHeif.still(640, 480).brands("heix", "mif1", "heix").bitDepth(10)
                .build());
        Files.write(photos.resolve("burst.HEICS"), TestHeif.still(64, 32).brands("msf1", "msf1", "heic").withMoov().build());

        long id = started();

        Map<String, JsonNode> items = items(id);
        JsonNode phone = items.get("IMG_0042.HEIC");
        assertThat(phone.get("state").asText()).isEqualTo("read");
        assertThat(phone.get("fileType").asText()).isEqualTo("heic");
        assertThat(phone.get("width").asInt()).isEqualTo(480);
        assertThat(phone.get("height").asInt()).isEqualTo(640);
        assertThat(phone.get("heic").toString()).isEqualTo("{\"tenBit\":false,\"auxiliary\":false}");
        assertThat(phone.get("previewFailed").asBoolean()).isFalse();
        assertThat(phone.get("place").get("id").asText()).as("placed from its GPS").isEqualTo("P4");
        assertThat(items.get("A7S_0001.HIF").get("heic").get("tenBit").asBoolean()).isTrue();
        assertThat(items.get("burst.HEICS").get("problem").asText()).isEqualTo(PhotoFormat.HEIC_SEQUENCE);
        assertThat(get("api/imports/" + id).get("heic").toString())
                .isEqualTo("{\"count\":2,\"unshowable\":0,\"making\":0}");

        HttpResponse<byte[]> preview = bytes(item(id, "IMG_0042.HEIC") + "/preview");
        assertThat(preview.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(preview.body()));
        assertThat(small.getWidth()).as("upright").isEqualTo(360);
        HttpResponse<byte[]> large = bytes(item(id, "IMG_0042.HEIC") + "/display");
        assertThat(large.statusCode()).isEqualTo(200);
        BufferedImage big = ImageIO.read(new ByteArrayInputStream(large.body()));
        assertThat(big.getWidth()).isEqualTo(480);
        assertThat(big.getHeight()).isEqualTo(640);
    }

    @Test
    void withoutADecoderItemsAreReadAndPreviewsComeOnceItIsAvailable() throws Exception {
        HeicStatus.publish(HeicStatus.missing("linux", "absent"));

        long id = started();

        JsonNode phone = items(id).get("IMG_0042.HEIC");
        assertThat(phone.get("state").asText()).isEqualTo("read");
        assertThat(phone.get("previewFailed").asBoolean()).as("nothing failed").isFalse();
        assertThat(get("api/imports/" + id).get("heic").toString())
                .isEqualTo("{\"count\":1,\"unshowable\":1,\"making\":0}");
        HttpResponse<String> large = send("GET", item(id, "IMG_0042.HEIC") + "/display", null);
        assertThat(large.statusCode()).isEqualTo(415);
        assertThat(large.body()).contains("\"code\":\"heic_decoder_missing\"", "Install heif-convert");
        assertThat(bytes(item(id, "IMG_0042.HEIC") + "/preview").headers().firstValue("Content-Type")).hasValue("image/png");

        // a filed one is no longer the import's to show (fixes J13)
        Files.write(photos.resolve("IMG_0043.HEIC"), TestHeif.still(320, 240).build());
        long second = started();
        assertThat(second).isEqualTo(id);
        assertThat(get("api/imports/" + id).at("/heic/unshowable").asInt()).isEqualTo(2);
        send("POST", "api/imports/" + id + "/assign", "{\"itemIds\":[" + items(id).get("IMG_0043.HEIC").get("id")
                + "],\"taxonId\":\"A5\"}");
        assertThat(send("POST", "api/imports/" + id + "/file", fileBody(id)).statusCode()).isEqualTo(202);
        assertThat(services.imports().awaitIdle(WAIT)).isTrue();
        assertThat(get("api/imports/" + id).get("heic").toString())
                .isEqualTo("{\"count\":2,\"unshowable\":1,\"making\":0}");

        HeicStatus.publish(HeicStatus.available("fake", "mac"));
        services.imports().heicAvailable();
        assertThat(services.imports().awaitHeicPreviews(WAIT)).isTrue();

        assertThat(get("api/imports/" + id).get("heic").toString())
                .isEqualTo("{\"count\":2,\"unshowable\":0,\"making\":0}");
        assertThat(bytes(item(id, "IMG_0042.HEIC") + "/preview").headers().firstValue("Content-Type"))
                .hasValue("image/jpeg");
    }

    @Test
    void aSecondFillDoesNotQueueAPreviewAgain() throws Exception {
        HeicStatus.publish(HeicStatus.missing("mac", "failed"));
        long id = started();
        mode.set("slow");
        HeicStatus.publish(HeicStatus.available("fake", "mac"));

        services.imports().heicAvailable();
        services.imports().heicAvailable(); // fixes J2: the item is queued once

        assertThat(get("api/imports/" + id).at("/heic/making").asInt()).isEqualTo(1);
        assertThat(services.imports().awaitHeicPreviews(WAIT)).isTrue();
        assertThat(get("api/imports/" + id).get("heic").toString()).isEqualTo("{\"count\":1,\"unshowable\":0,\"making\":0}");
    }

    @Test
    void whileTheDecoderIsCheckedPreviewsWait() throws Exception {
        HeicStatus.publish(HeicStatus.checking("mac"));

        long id = started();

        HttpResponse<byte[]> preview = bytes(item(id, "IMG_0042.HEIC") + "/preview");
        assertThat(preview.headers().firstValue("X-Atlas-Pending")).hasValue("1");
        HttpResponse<String> large = send("GET", item(id, "IMG_0042.HEIC") + "/display", null);
        assertThat(large.statusCode()).isEqualTo(503);
        assertThat(large.body()).contains(HeicStatus.CHECKING_NOTE);
    }

    @Test
    void aPreviewThatCannotBeMadeSaysSo() throws Exception {
        mode.set("garbage");

        long id = started();

        assertThat(items(id).get("IMG_0042.HEIC").get("previewFailed").asBoolean()).isTrue();
        HttpResponse<String> large = send("GET", item(id, "IMG_0042.HEIC") + "/display", null);
        assertThat(large.statusCode()).isEqualTo(415);
        assertThat(large.body()).contains(HeicStatus.DECODE_FAILED);
    }

    @Test
    void aPreviewThatTookTooLongIsMadeAgainOnceWhenAskedFor() throws Exception {
        mode.set("hang");

        long id = started();
        assertThat(items(id).get("IMG_0042.HEIC").get("previewFailed").asBoolean())
                .as("a timeout is not a failure: the tile asks again (fixes J9)").isFalse();

        mode.set("ok");
        assertThat(bytes(item(id, "IMG_0042.HEIC") + "/preview").headers().firstValue("X-Atlas-Pending")).hasValue("1");
        assertThat(services.imports().awaitHeicPreviews(WAIT)).isTrue();

        assertThat(items(id).get("IMG_0042.HEIC").get("previewFailed").asBoolean()).isFalse();
        assertThat(bytes(item(id, "IMG_0042.HEIC") + "/preview").headers().firstValue("Content-Type"))
                .hasValue("image/jpeg");
    }

    @Test
    void aLivePhotosVideoSaysWhichPhotoItGoesWith() throws Exception {
        Files.write(photos.resolve("IMG_0042.MOV"), new byte[] {0, 0, 0, 20, 'f', 't', 'y', 'p', 'q', 't'});
        Files.write(photos.resolve("IMG_0043.jpg"), TestImages.jpeg(64, 48));
        Files.write(photos.resolve("img_0043.mov"), new byte[] {1});
        Files.write(photos.resolve("clip.mov"), new byte[] {1});
        Files.write(photos.resolve("IMG_0044.mp4"), new byte[] {1});
        Files.createDirectories(photos.resolve("other"));
        Files.write(photos.resolve("other").resolve("IMG_0042.mov"), new byte[] {1});

        long id = started();

        Map<String, JsonNode> items = items(id);
        assertThat(items.get("IMG_0042.MOV").get("problem").asText()).isEqualTo("This is the video part of a Live"
                + " Photo. Its photo (IMG_0042.HEIC) is in this import; videos can't be imported.");
        assertThat(items.get("img_0043.mov").get("problem").asText()).contains("Its photo (IMG_0043.jpg)");
        assertThat(items.get("clip.mov").get("problem").asText()).isEqualTo("Videos can't be imported.");
        assertThat(items.get("IMG_0044.mp4").get("problem").asText()).isEqualTo("Videos can't be imported.");
        assertThat(items.get("other/IMG_0042.mov").get("problem").asText()).as("another folder")
                .isEqualTo("Videos can't be imported.");
    }
}
