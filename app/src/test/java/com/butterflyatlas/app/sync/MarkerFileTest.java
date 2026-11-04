package com.butterflyatlas.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marker file {@code .atlas-device.json} (one-device design §3): written as {@code .part} then renamed, read back as
 * written; conflict copies are ignored when reading and deleted when the marker is next written; one that can't be read is
 * read again 2 seconds later (§6).
 */
class MarkerFileTest {

    @TempDir
    Path library;

    private static final Marker MARKER = new Marker("dev-1", "Omkar's MacBook Air", Marker.OPEN,
            Instant.parse("2026-11-04T10:42:00Z"), Instant.parse("2026-11-04T11:05:00Z"), 37);

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(library)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void noMarkerIsMissing() {
        assertThat(MarkerFile.read(library)).isEqualTo(MarkerFile.Read.MISSING);
    }

    @Test
    void aMarkerIsReadBackAsWrittenAndLeavesNoPart() throws IOException {
        MarkerFile.write(library, MARKER);

        assertThat(MarkerFile.read(library).marker()).isEqualTo(MARKER);
        assertThat(names()).containsExactly(MarkerFile.NAME);
        assertThat(Files.readString(library.resolve(MarkerFile.NAME)))
                .contains("\"device\"", "\"name\"", "\"state\"", "\"since\"", "\"seen\"", "\"generation\"")
                .contains("2026-11-04T11:05:00Z");
    }

    @Test
    void aWriteReplacesTheMarkerWhole() throws IOException {
        MarkerFile.write(library, MARKER);
        Marker closed = new Marker("dev-1", "Omkar's MacBook Air", Marker.CLOSED, MARKER.since(),
                Instant.parse("2026-11-04T12:00:00Z"), 38);

        MarkerFile.write(library, closed);

        assertThat(MarkerFile.read(library).marker()).isEqualTo(closed);
        assertThat(names()).containsExactly(MarkerFile.NAME);
    }

    @Test
    void conflictCopiesAreIgnoredThenDeletedAtTheNextWrite() throws IOException {
        MarkerFile.write(library, MARKER);
        Files.writeString(library.resolve(".atlas-device (1).json"), "{\"device\":\"other\"}");
        Files.writeString(library.resolve(".atlas-device.json (conflicted copy)"), "x");
        Files.writeString(library.resolve("atlas.db"), "kept");

        assertThat(MarkerFile.read(library).marker()).isEqualTo(MARKER);

        MarkerFile.write(library, MARKER);

        assertThat(names()).containsExactly(MarkerFile.NAME, "atlas.db");
    }

    @Test
    void aDamagedMarkerIsUnreadable() throws IOException {
        Files.writeString(library.resolve(MarkerFile.NAME), "{\"device\": \"dev-1\", \"sta");
        assertThat(MarkerFile.read(library).unreadable()).isTrue();

        Files.writeString(library.resolve(MarkerFile.NAME), "{\"device\": \"dev-1\", \"state\": \"half\"}");
        assertThat(MarkerFile.read(library).unreadable()).isTrue();
    }

    @Test
    void anUnreadableMarkerIsReadAgainTwoSecondsLater() throws IOException {
        Files.writeString(library.resolve(MarkerFile.NAME), "half a marker");
        List<Long> waited = new ArrayList<>();

        MarkerFile.Read read = MarkerFile.readSettled(library, millis -> {
            waited.add(millis);
            MarkerFile.write(library, MARKER); // the sync app finished updating it meanwhile
        });

        assertThat(waited).containsExactly(2000L);
        assertThat(read.marker()).isEqualTo(MARKER);
    }

    @Test
    void aMarkerUnreadableTwiceInARowStaysUnreadable() throws IOException {
        Files.writeString(library.resolve(MarkerFile.NAME), "half a marker");

        assertThat(MarkerFile.readSettled(library, millis -> { }).unreadable()).isTrue();
    }

    @Test
    void aMarkerWithoutANameOrSinceIsRead() throws IOException {
        Files.writeString(library.resolve(MarkerFile.NAME), "{\"device\":\"dev-2\",\"state\":\"open\","
                + "\"seen\":\"2026-11-04T11:05:00Z\",\"generation\":4}");

        Marker read = MarkerFile.read(library).marker();

        assertThat(read.name()).isNull();
        assertThat(read.since()).isEqualTo(read.seen());
        assertThat(read.generation()).isEqualTo(4);
    }
}
