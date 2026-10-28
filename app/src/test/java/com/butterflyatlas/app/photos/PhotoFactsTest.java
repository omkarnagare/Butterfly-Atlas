package com.butterflyatlas.app.photos;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoFactsTest {

    @Test
    void theSnapshotHasAStableShape() {
        Map<String, String> exif = new LinkedHashMap<>();
        exif.put("Make", "Canon");
        exif.put("Model", "EOS \"R7\"");

        PhotoFacts facts = PhotoFacts.of(PhotoFormat.JPEG, 40, 20, 6, Instant.parse("2024-05-01T08:22:33Z"),
                new PhotoFacts.Gps(1.5, -2.25, null), exif, Map.of("xmp:Rating", "4"));

        assertThat(facts.metaJson()).isEqualTo("{\"v\":1,\"format\":\"JPEG\",\"width\":40,\"height\":20,\"orientation\":6,"
                + "\"capturedAt\":\"2024-05-01T08:22:33Z\",\"gps\":{\"lat\":1.5,\"lon\":-2.25},"
                + "\"exif\":{\"Make\":\"Canon\",\"Model\":\"EOS \\\"R7\\\"\"},\"xmp\":{\"xmp:Rating\":\"4\"}}");
        assertThat(facts.width()).isEqualTo(40);
        assertThat(facts.gps().altitude()).isNull();
    }

    @Test
    void factsComeBackFromTheirSnapshot() {
        PhotoFacts full = PhotoFacts.of(PhotoFormat.TIFF, 12, 16, 1, Instant.parse("2024-05-01T08:22:33Z"),
                new PhotoFacts.Gps(-33.5, 70.25, -12.5), Map.of("Make", "Nikon"), Map.of());
        PhotoFacts bare = PhotoFacts.of(PhotoFormat.WEBP, 2, 2, 1, null, null, Map.of(), Map.of());

        assertThat(PhotoFacts.fromJson(full.metaJson())).isEqualTo(full);
        assertThat(PhotoFacts.fromJson(bare.metaJson())).isEqualTo(bare);
        assertThat(bare.metaJson()).isEqualTo("{\"v\":1,\"format\":\"WEBP\",\"width\":2,\"height\":2,\"orientation\":1}");
    }

    @Test
    void anUnreadableSnapshotIsRefusedNotCrashed() {
        for (String json : new String[] {"{\"v\":1,\"width\":2}", "{\"v\":1,\"format\":\"JPEG\",\"gps\":{\"lon\":1}}",
                "{\"v\":1,\"format\":\"JPEG\",\"gps\":{\"lat\":1}}", "{\"v\":2,\"format\":\"JPEG\"}", "[]", "null"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> PhotoFacts.fromJson(json))
                    .as(json).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
