package com.butterflyatlas.app.web;

import com.butterflyatlas.app.AtlasServer;
import com.butterflyatlas.app.library.LibraryHolder;
import com.butterflyatlas.app.testing.TestExif;
import com.butterflyatlas.app.testing.TestImages;
import com.butterflyatlas.app.testing.TestRaw;
import com.butterflyatlas.app.testing.TestServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** RAW photos in the gallery and the viewer (contract §2): Photo's new fields, {@code /display} and {@code /original}. */
class RawPhotoApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newHttpClient();
    private AtlasServices services;
    private AtlasServer server;
    private final LibraryHolder holder = new LibraryHolder();

    @BeforeEach
    void start() {
        services = TestServices.configured(home, holder);
        server = AtlasServer.start(0, services);
    }

    @AfterEach
    void stop() {
        server.close();
        services.thumbnails().close();
        services.placeNotes().close();
        http.close();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Origin", "http://127.0.0.1:" + server.port());
    }

    private JsonNode upload(byte[] bytes, String name) throws Exception {
        HttpResponse<String> r = http.send(request("api/taxa/A5/photos?name=" + name).header("Content-Type", "image/x-raw")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode photo = JSON.readTree(r.body()).get("photo");
        for (int i = 0; i < 200 && status().at("/previews/pending").asInt() > 0; i++) {
            Thread.sleep(50);
        }
        return photo;
    }

    private JsonNode status() throws Exception {
        return JSON.readTree(http.send(request("api/status").GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private HttpResponse<byte[]> fetch(long id, String kind, String etag) throws Exception {
        HttpRequest.Builder r = request("api/photos/" + id + "/" + kind).GET();
        if (etag != null) {
            r.header("If-None-Match", etag);
        }
        return http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void aRawPhotoShowsItsPreviewAndKeepsItsFile() throws Exception {
        byte[] preview = TestImages.jpeg(1600, 1200);
        byte[] cr3 = TestRaw.cr3(preview, null, TestRaw.cmt(new TestRaw.Ifd().shortValue(0x0112, 6)), null, null);

        JsonNode photo = upload(cr3, "IMG_0001.CR3");
        long id = photo.get("id").asLong();

        assertThat(photo.get("fileType").asText()).isEqualTo("raw");
        assertThat(photo.get("rawPreview").toString()).isEqualTo("{\"width\":1200,\"height\":1600,\"orientation\":6}");
        assertThat(photo.get("unreadable").asBoolean()).isFalse();
        assertThat(photo.get("width").asInt()).isEqualTo(1200);

        HttpResponse<byte[]> display = fetch(id, "display", null);
        assertThat(display.statusCode()).isEqualTo(200);
        assertThat(display.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(display.body()).as("the preview's bytes, read from inside the file").isEqualTo(preview);
        String etag = display.headers().firstValue("ETag").orElseThrow();
        assertThat(fetch(id, "display", etag).statusCode()).isEqualTo(304);

        HttpResponse<byte[]> original = fetch(id, "original", null);
        assertThat(original.headers().firstValue("Content-Type")).hasValue("application/octet-stream");
        assertThat(original.body()).isEqualTo(cr3);

        HttpResponse<byte[]> thumb = fetch(id, "thumb", null);
        assertThat(thumb.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(thumb.body()));
        assertThat(small.getHeight()).as("upright").isEqualTo(480);
        assertThat(small.getWidth()).isEqualTo(360);
    }

    @Test
    void aPreviewThatNoLongerReadsIsRefusedWithTheReason() throws Exception {
        byte[] cr3 = TestRaw.cr3(TestImages.jpeg(1600, 1200), null, null, null, null);
        long id = upload(cr3, "IMG_0002.CR3").get("id").asLong();
        Path file = services.photoQueries().original(id).file();

        // the file damaged in place: same size, its preview gone
        byte[] damaged = cr3.clone();
        int at = indexOf(cr3, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        damaged[at] = 0;
        Files.write(file, damaged);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(60)));

        HttpResponse<byte[]> display = fetch(id, "display", null);

        assertThat(display.statusCode()).isEqualTo(415);
        assertThat(new String(display.body())).contains("This RAW photo's preview can't be read any more. The file may have"
                + " changed or be damaged. Add it again from your camera's card, or save it as JPEG from your RAW software"
                + " and add that.");
    }

    @Test
    void aMovedPreviewIsFoundAgainWhenTheFileChanged() throws Exception {
        byte[] first = TestImages.jpeg(1600, 1200, 1);
        long id = upload(TestRaw.cr3(first, null, null, null, null), "IMG_0003.CR3").get("id").asLong();
        Path file = services.photoQueries().original(id).file();
        byte[] edited = TestRaw.cr3(TestImages.jpeg(1600, 1200, 2), TestRaw.preview(),
                TestRaw.cmt(new TestRaw.Ifd().shortValue(0x0112, 1)), null, null); // a RAW editor rewrote it
        Files.write(file, edited);

        HttpResponse<byte[]> display = fetch(id, "display", null);

        assertThat(display.statusCode()).isEqualTo(200);
        assertThat(display.body()).isEqualTo(TestImages.jpeg(1600, 1200, 2));
        String meta = holder.library().database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT json FROM image_meta WHERE image_id = ?")) {
                ps.setLong(1, id);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        JsonNode remembered = JSON.readTree(meta).get("rawPreview");
        assertThat(remembered.get("offset").asLong()).as("written back (fixes J9)")
                .isEqualTo(indexOf(edited, TestImages.jpeg(1600, 1200, 2)));
        assertThat(remembered.get("fileSize").asLong()).isEqualTo(edited.length);
        assertThat(fetch(id, "display", null).body()).as("read from where it was remembered")
                .isEqualTo(TestImages.jpeg(1600, 1200, 2));
    }

    @Test
    void aPreviewsOwnExifOrientationIsSetToOneSoOnlyThePageTurnsIt() throws Exception {
        byte[] jpeg = TestExif.camera().orientation(6).jpeg(TestRaw.preview());
        long id = upload(TestRaw.raf(jpeg), "DSCF0001.RAF").get("id").asLong();

        byte[] shown = fetch(id, "display", null).body();

        assertThat(shown).hasSameSizeAs(jpeg).isNotEqualTo(jpeg);
        assertThat(com.drew.imaging.ImageMetadataReader.readMetadata(new ByteArrayInputStream(shown))
                .getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class)
                .getInt(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(1);
        assertThat(JSON.readTree(http.send(request("api/taxa/A5/photos").GET().build(), HttpResponse.BodyHandlers.ofString())
                .body()).get("photos").get(0).at("/rawPreview/orientation").asInt()).isEqualTo(6);
    }

    @Test
    void aPhotoIsUnreadableFromItsFirstThumbnailFailureAndOthersAreNot() throws Exception {
        HttpResponse<String> r = http.send(request("api/taxa/A5/photos").header("Content-Type", "image/jpeg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(TestImages.jpeg(40, 30))).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode jpeg = JSON.readTree(r.body()).get("photo");
        assertThat(jpeg.get("fileType").asText()).isEqualTo("jpeg");
        assertThat(jpeg.get("rawPreview").isNull()).isTrue();
        assertThat(jpeg.get("unreadable").asBoolean()).isFalse();
        long id = jpeg.get("id").asLong();
        for (int i = 0; i < 200 && status().at("/previews/pending").asInt() > 0; i++) {
            Thread.sleep(50);
        }

        holder.library().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO image_processing (image_id, consumer,"
                    + " version, status, error, done_at, attempts) VALUES (?, 'thumbnail', 1, 'failed', 'x', 't', 1)")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });

        JsonNode listed = JSON.readTree(http.send(request("api/taxa/A5/photos").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("photos").get(0);
        assertThat(listed.get("unreadable").asBoolean()).as("after the first failure (fixes J2)").isTrue();
        int pending = holder.library().database().read(c -> new com.butterflyatlas.app.photos.ProcessingLedger(
                java.time.Clock.systemUTC()).countPending(c, "thumbnail", 1));
        assertThat(pending)
                .as("still tried again at the next start").isEqualTo(1);
    }

    @Test
    void aRawWithoutAPreviewFoundByTheRescanIsShownAsUnreadable() throws Exception {
        Path file = holder.library().folder().resolve("images/Danaus chrysippus [A5]/DSC_0043.NEF");
        Files.createDirectories(file.getParent());
        Files.write(file, TestRaw.tiff(new TestRaw.Ifd().shortValue(0x0112, 1))); // a RAW with no preview
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));

        assertThat(services.rescan().run().added()).isEqualTo(1);

        JsonNode photo = JSON.readTree(http.send(request("api/taxa/A5/photos").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("photos").get(0);
        assertThat(photo.get("fileType").asText()).isEqualTo("raw");
        assertThat(photo.get("rawPreview").isNull()).isTrue();
        assertThat(photo.get("unreadable").asBoolean()).isTrue();
        long id = photo.get("id").asLong();

        HttpResponse<byte[]> display = fetch(id, "display", null);
        assertThat(display.statusCode()).isEqualTo(415);
        assertThat(new String(display.body())).contains(com.butterflyatlas.app.photos.PhotoFormat.RAW_PREVIEW_UNREADABLE);

        assertThat(services.thumbnails().await(id, Duration.ofSeconds(10))).as("no thumbnail can be made").isEmpty();
        HttpResponse<byte[]> thumb = fetch(id, "thumb", null);
        assertThat(thumb.statusCode()).isEqualTo(200);
        assertThat(thumb.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(thumb.headers().firstValue("X-Atlas-Pending")).as("not on its way: the placeholder for good").isEmpty();
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = data.length - pattern.length; i >= 0; i--) {
            for (int k = 0; k < pattern.length; k++) {
                if (data[i + k] != pattern[k]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalArgumentException("not found");
    }
}
