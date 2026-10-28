package com.butterflyatlas.importer.inat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachingTransportTest {

    @TempDir
    Path cacheDir;

    private final List<URI> fetched = new ArrayList<>();
    private final Transport origin = uri -> {
        fetched.add(uri);
        return "body of " + uri.getPath();
    };

    @Test
    void servesRepeatRequestsFromDisk() throws Exception {
        CachingTransport transport = new CachingTransport(origin, cacheDir);
        URI uri = URI.create("https://api.inaturalist.org/v2/taxa/1");

        assertThat(transport.get(uri)).isEqualTo("body of /v2/taxa/1");
        assertThat(transport.get(uri)).isEqualTo("body of /v2/taxa/1");

        assertThat(fetched).hasSize(1);
    }

    @Test
    void survivesARestart() throws Exception {
        URI uri = URI.create("https://api.inaturalist.org/v2/taxa/2");
        new CachingTransport(origin, cacheDir).get(uri);

        new CachingTransport(origin, cacheDir).get(uri);

        assertThat(fetched).hasSize(1);
    }

    @Test
    void keepsDifferentUrlsApart() throws Exception {
        CachingTransport transport = new CachingTransport(origin, cacheDir);

        transport.get(URI.create("https://api.inaturalist.org/v2/taxa/1"));
        transport.get(URI.create("https://api.inaturalist.org/v2/taxa/2"));

        assertThat(fetched).hasSize(2);
        try (var files = Files.list(cacheDir)) {
            assertThat(files.filter(f -> f.toString().endsWith(".json"))).hasSize(2);
        }
    }

    @Test
    void doesNotCacheFailures() throws Exception {
        boolean[] failing = {true};
        CachingTransport transport = new CachingTransport(uri -> {
            fetched.add(uri);
            if (failing[0]) {
                throw new IOException("boom");
            }
            return "ok";
        }, cacheDir);
        URI uri = URI.create("https://api.inaturalist.org/v2/taxa/3");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transport.get(uri)).hasMessage("boom");
        try (var files = Files.list(cacheDir)) {
            assertThat(files.filter(f -> f.toString().endsWith(".json"))).isEmpty();
        }

        failing[0] = false;
        assertThat(transport.get(uri)).isEqualTo("ok");
        assertThat(transport.get(uri)).isEqualTo("ok");
        assertThat(fetched).hasSize(2);
    }
}
