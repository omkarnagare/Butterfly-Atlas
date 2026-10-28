package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.imaging.ImageIoPhotoProcessor;
import com.butterflyatlas.app.imaging.heif.HeicDecoders;
import com.butterflyatlas.app.imaging.heif.TestDecoders;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.photos.PhotoQueries;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEIC photos in the gallery and the viewer (Phase 7b contract §2, audit 5, 7, 12, 18), with a fake decoder program:
 * Photo's fields, {@code /thumb}, {@code /display} and {@code /original} while the decoder is available, checked and
 * missing, a decode that fails, and the cover rule.
 */
class HeicPhotoApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<String> mode = new AtomicReference<>("ok");
    private AtlasServices services;
    private AtlasServer server;
    private HeicDecoders heic;
    private final LibraryHolder holder = new LibraryHolder();

    @BeforeEach
    void start() throws Exception {
        heic = TestDecoders.fake(() -> home.resolve("Atlas").resolve(".thumbs").resolve(".decode"), mode::get, 640, 480,
                Duration.ofSeconds(20));
        services = TestServices.configured(home, new ImageIoPhotoProcessor(ZoneOffset.UTC, heic), holder);
        heic.onChange(status -> {
            if (status.isAvailable()) {
                services.thumbnails().heicAvailable();
            }
        });
        server = AtlasServer.start(0, services);
        assertThat(heic.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();
    }

    @AfterEach
    void stop() {
        server.close();
        services.thumbnails().close();
        services.placeNotes().close();
        http.close();
        HeicStatus.publish(HeicStatus.checking("mac"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Origin", "http://127.0.0.1:" + server.port());
    }

    private JsonNode upload(byte[] bytes, String name, String taxon) throws Exception {
        HttpResponse<String> r = http.send(request("api/taxa/" + taxon + "/photos?name=" + name)
                .header("Content-Type", "image/heic").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("photo");
    }

    private HttpResponse<byte[]> fetch(long id, String kind) throws Exception {
        return http.send(request("api/photos/" + id + "/" + kind).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode photo(long id) throws Exception {
        return JSON.valueToTree(services.photoQueries().photo(id));
    }

    private long cover() {
        return holder.library().database().read(c -> PhotoQueries.cover(c, "A5")).orElseThrow().id();
    }

    private static JsonNode error(HttpResponse<byte[]> r) throws Exception {
        return JSON.readTree(r.body()).get("error");
    }

    private static byte[] portrait() {
        // stored 640 x 480 with irot 1 (as a phone held upright writes it), GPS and a capture time in its Exif
        return TestHeif.still(640, 480).turn(1)
                .exif(TestExif.camera().taken("2024:05:01 10:22:33", "+05:30").gps(18.52, 73.85, 560).tiffBlock()).build();
    }

    @Test
    void aHeicPhotoIsShownUprightThroughTheDecoderAndKeepsItsFile() throws Exception {
        byte[] heif = portrait();
        JsonNode photo = upload(heif, "IMG_0042.HEIC", "A5");
        long id = photo.get("id").asLong();

        assertThat(photo.get("fileType").asText()).isEqualTo("heic");
        assertThat(photo.get("fileName").asText()).endsWith(".heic");
        assertThat(photo.get("width").asInt()).as("as displayed").isEqualTo(480);
        assertThat(photo.get("height").asInt()).isEqualTo(640);
        assertThat(photo.get("heic").toString()).isEqualTo("{\"tenBit\":false,\"auxiliary\":false}");
        assertThat(photo.get("rawPreview").isNull()).isTrue();
        assertThat(photo.get("unreadable").asBoolean()).isFalse();

        HttpResponse<byte[]> display = fetch(id, "display");
        assertThat(display.statusCode()).isEqualTo(200);
        assertThat(display.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        BufferedImage shown = ImageIO.read(new ByteArrayInputStream(display.body()));
        assertThat(shown.getWidth()).as("turned by the app: the decoder left it as stored").isEqualTo(480);
        assertThat(shown.getHeight()).isEqualTo(640);

        HttpResponse<byte[]> original = fetch(id, "original");
        assertThat(original.headers().firstValue("Content-Type")).hasValue("image/heic");
        assertThat(original.body()).isEqualTo(heif);

        assertThat(services.thumbnails().await(id, Duration.ofSeconds(60))).isPresent();
        HttpResponse<byte[]> thumb = fetch(id, "thumb");
        assertThat(thumb.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(thumb.body()));
        assertThat(small.getWidth()).isEqualTo(360);
        assertThat(small.getHeight()).isEqualTo(480);
    }

    @Test
    void whileTheDecoderIsMissingTheViewerGetsTheInstallNoteAndNothingIsFailed() throws Exception {
        HeicStatus.publish(HeicStatus.missing("windows", "absent"));
        long id = upload(portrait(), "IMG_0042.HEIC", "A5").get("id").asLong();

        HttpResponse<byte[]> display = fetch(id, "display");
        assertThat(display.statusCode()).isEqualTo(415);
        assertThat(error(display).get("code").asText()).isEqualTo("heic_decoder_missing");
        assertThat(error(display).get("message").asText()).isEqualTo("This computer can't show HEIC photos yet. Install"
                + " Windows' HEVC decoder, then choose Check again in Settings → About. Your photo is safely in the"
                + " library.");
        HttpResponse<byte[]> thumb = fetch(id, "thumb");
        assertThat(thumb.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(thumb.headers().firstValue("X-Atlas-Pending")).as("not on its way while missing").isEmpty();
        assertThat(photo(id).get("unreadable").asBoolean()).isTrue();
        assertThat(services.thumbnails().await(id, Duration.ofMillis(200))).isEmpty();

        HeicStatus.publish(HeicStatus.missing("linux", "absent"));
        assertThat(error(fetch(id, "display")).get("message").asText()).startsWith("This computer can't show HEIC photos"
                + " yet. Install heif-convert");
    }

    @Test
    void whileTheDecoderIsCheckedThePhotoWaitsAndIsNotUnreadable() throws Exception {
        HeicStatus.publish(HeicStatus.checking("mac"));
        long id = upload(portrait(), "IMG_0042.HEIC", "A5").get("id").asLong();

        HttpResponse<byte[]> display = fetch(id, "display");
        assertThat(display.statusCode()).isEqualTo(503);
        assertThat(error(display).get("code").asText()).isEqualTo("busy");
        assertThat(error(display).get("message").asText())
                .isEqualTo("Butterfly Atlas is still checking for a HEIC decoder. Try again in a moment.");
        assertThat(fetch(id, "thumb").headers().firstValue("X-Atlas-Pending")).hasValue("1");
        assertThat(photo(id).get("unreadable").asBoolean()).as("audit 18").isFalse();
    }

    @Test
    void aFileTheDecoderCannotReadSaysSoAndCheckingAgainForgetsTheFailure() throws Exception {
        mode.set("garbage");
        long id = upload(portrait(), "IMG_0042.HEIC", "A5").get("id").asLong();

        HttpResponse<byte[]> display = fetch(id, "display");
        assertThat(display.statusCode()).isEqualTo(415);
        assertThat(error(display).get("message").asText()).isEqualTo(HeicStatus.DECODE_FAILED);
        assertThat(services.thumbnails().await(id, Duration.ofSeconds(60))).isEmpty();
        assertThat(photo(id).get("unreadable").asBoolean()).isTrue();

        mode.set("ok");
        // a Check again finding the same decoder changes nothing (fixes J2); its becoming available again does
        assertThat(heic.check().get(30, TimeUnit.SECONDS).isAvailable()).isTrue();
        assertThat(photo(id).get("unreadable").asBoolean()).isTrue();
        services.thumbnails().heicAvailable();
        assertThat(services.thumbnails().await(id, Duration.ofSeconds(60))).isPresent();
        assertThat(photo(id).get("unreadable").asBoolean()).isFalse();
        assertThat(fetch(id, "display").statusCode()).isEqualTo(200);
    }

    @Test
    void aWorkingFolderThatCannotBeUsedIsSaidSoNotThePhoto() throws Exception {
        long id = upload(portrait(), "IMG_0042.HEIC", "A5").get("id").asLong();
        Path decode = home.resolve("Atlas").resolve(".thumbs").resolve(".decode");
        assertThat(services.thumbnails().await(id, Duration.ofSeconds(60))).isPresent();
        try (var left = Files.list(decode)) {
            for (Path file : left.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(decode);
        Files.write(decode, new byte[] {1}); // a file where the working folder should be

        HttpResponse<byte[]> display = fetch(id, "display");

        assertThat(display.statusCode()).isEqualTo(503);
        assertThat(error(display).get("code").asText()).isEqualTo("heic_workspace");
        assertThat(error(display).get("message").asText()).isEqualTo(HeicStatus.WORKSPACE_NOTE);
        assertThat(photo(id).get("unreadable").asBoolean()).as("not the photo's fault").isFalse();
    }

    @Test
    void theCoverSkipsHeicPhotosUntilTheyCanBeShown() throws Exception {
        long jpeg = upload(TestImages.jpeg(64, 48), "a.jpg", "A5").get("id").asLong();
        long heicPhoto = upload(portrait(), "IMG_0042.HEIC", "A5").get("id").asLong();
        assertThat(cover()).as("the newest").isEqualTo(heicPhoto);

        HeicStatus.publish(HeicStatus.checking("mac"));
        assertThat(cover()).isEqualTo(jpeg);
        HeicStatus.publish(HeicStatus.missing("mac", "failed"));
        assertThat(cover()).isEqualTo(jpeg);
        holder.library().database().write(c -> {
            try (var ps = c.prepareStatement("UPDATE taxon SET cover_image_id = ? WHERE id = 'A5'")) {
                ps.setLong(1, heicPhoto);
                return ps.executeUpdate();
            }
        });
        assertThat(cover()).as("a chosen HEIC cover waits too").isEqualTo(jpeg);
        HeicStatus.publish(HeicStatus.available("fake", "mac"));
        assertThat(cover()).isEqualTo(heicPhoto);
    }
}
