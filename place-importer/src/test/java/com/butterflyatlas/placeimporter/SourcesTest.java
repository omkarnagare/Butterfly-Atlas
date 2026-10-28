package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcesTest {

    @TempDir
    Path cache;

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final Sources.Source SOURCE = new Sources.Source("x.geojson", URI.create("https://example.org/x.geojson"),
            BundleWriter.sha256(BODY));

    private final List<URI> fetched = new ArrayList<>();

    private Sources.Fetcher serving(byte[] body) {
        return (uri, target) -> {
            fetched.add(uri);
            Files.write(target, body);
        };
    }

    @Test
    void downloadsOnceAndThenUsesTheCache() throws IOException {
        Path first = Sources.ensure(SOURCE, cache, serving(BODY));
        Path second = Sources.ensure(SOURCE, cache, serving(BODY));

        assertThat(first).isEqualTo(cache.resolve("x.geojson")).isEqualTo(second);
        assertThat(Files.readAllBytes(first)).isEqualTo(BODY);
        assertThat(fetched).containsExactly(URI.create("https://example.org/x.geojson"));
    }

    @Test
    void aDownloadWithTheWrongChecksumIsRefusedAndNotKept() {
        assertThatThrownBy(() -> Sources.ensure(SOURCE, cache, serving("{ }".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("https://example.org/x.geojson")
                .hasMessageContaining("expected " + SOURCE.sha256());
        assertThat(cache.resolve("x.geojson")).doesNotExist();
        assertThat(cache.resolve("x.geojson.part")).doesNotExist();
    }

    @Test
    void aDamagedCachedFileIsDownloadedAgain() throws IOException {
        Files.writeString(cache.resolve("x.geojson"), "damaged");

        Path file = Sources.ensure(SOURCE, cache, serving(BODY));

        assertThat(Files.readAllBytes(file)).isEqualTo(BODY);
        assertThat(fetched).hasSize(1);
    }

    @Test
    void hashesAFileWithoutReadingItWhole() throws IOException {
        Path file = cache.resolve("big.bin");
        Files.write(file, new byte[3 * 1024 * 1024 + 7]);

        assertThat(Sources.sha256(file)).isEqualTo(BundleWriter.sha256(Files.readAllBytes(file)));
    }

    @Test
    void everySourceIsPinnedByACommitAndAChecksum() {
        assertThat(Sources.ALL).extracting(Sources.Source::file).containsExactly(
                "ne_10m_admin_0_countries_ind.geojson", "ne_10m_admin_1_states_provinces.geojson",
                "geoBoundaries-IND-ADM1.geojson", "geoBoundaries-IND-ADM2.geojson");
        assertThat(Sources.ALL).allSatisfy(s -> {
            assertThat(s.uri().toString()).matches("https://.*/[0-9a-f]{40}/.*");
            assertThat(s.sha256()).matches("[0-9a-f]{64}");
        });
    }
}
