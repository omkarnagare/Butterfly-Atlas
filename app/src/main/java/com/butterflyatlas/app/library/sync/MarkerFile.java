package com.butterflyatlas.app.library.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Stream;

/**
 * The marker file {@code .atlas-device.json} in the library folder (one-device design §3). It is always written as
 * {@code .atlas-device.json.part} and renamed over the old one, so a sync service never sees half a marker. Any other file
 * whose name starts with {@code .atlas-device} (a conflict copy such as {@code .atlas-device (1).json}) is ignored, and
 * deleted when the marker is next written.
 */
public final class MarkerFile {

    private static final Logger LOG = LoggerFactory.getLogger(MarkerFile.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = ".atlas-device.json";
    private static final String PREFIX = ".atlas-device";
    private static final String PART = NAME + ".part";
    /** How long a marker that can't be read is given before it is read again (§6: the service may be mid-update). */
    static final long SETTLE_MILLIS = 2000;

    /** What reading the marker found: none ({@link #MISSING}), one, or one that can't be read (and why). */
    public record Read(Marker marker, boolean unreadable, String problem) {

        public static final Read MISSING = new Read(null, false, null);

        static Read unreadable(String problem) {
            return new Read(null, true, problem);
        }
    }

    /** A wait between two reads (tests don't wait). */
    @FunctionalInterface
    public interface Pause {
        void pause(long millis) throws IOException, InterruptedException;

        Pause REAL = Thread::sleep;
    }

    private MarkerFile() {
    }

    /** Reads the marker once. */
    public static Read read(Path library) {
        Path file = library.resolve(NAME);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Read.MISSING;
        } catch (IOException e) {
            if (!Files.exists(file) && Files.isDirectory(library)) {
                return Read.MISSING;
            }
            return Read.unreadable("it couldn't be read: " + e.getMessage());
        }
        try {
            JsonNode node = JSON.readTree(text);
            String device = text(node, "device");
            String state = text(node, "state");
            Instant seen = Instant.parse(text(node, "seen"));
            String sinceText = node.hasNonNull("since") ? node.get("since").asText() : null;
            Instant since = sinceText == null ? seen : Instant.parse(sinceText);
            if (device == null || device.isBlank() || !(Marker.OPEN.equals(state) || Marker.CLOSED.equals(state))
                    || !node.hasNonNull("generation") || !node.get("generation").canConvertToLong()
                    || node.get("generation").asLong() < 0) {
                return Read.unreadable("it isn't a complete marker");
            }
            String name = node.hasNonNull("name") && !node.get("name").asText().isBlank() ? node.get("name").asText() : null;
            String sha256 = node.hasNonNull("sha256") && !node.get("sha256").asText().isBlank() ? node.get("sha256").asText()
                    : null;
            return new Read(new Marker(device, name, state, since, seen, node.get("generation").asLong(), sha256), false,
                    null);
        } catch (IOException | DateTimeParseException | NullPointerException e) {
            return Read.unreadable("it isn't a complete marker");
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** Reads the marker; one that can't be read is read again 2 seconds later (§6), and that answer stands. */
    public static Read readSettled(Path library, Pause pause) {
        Read first = read(library);
        if (!first.unreadable()) {
            return first;
        }
        try {
            pause.pause(SETTLE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return first;
        } catch (IOException e) {
            LOG.debug("The wait before reading the marker again failed", e);
        }
        return read(library);
    }

    /**
     * Writes the marker as {@code .part} and renames it over the old one (tried again for a moment when it is refused,
     * {@link Replace}); then deletes conflict copies.
     */
    public static void write(Path library, Marker marker) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("device", marker.device());
        node.put("name", marker.name());
        node.put("state", marker.state());
        node.put("since", marker.since().toString());
        node.put("seen", marker.seen().toString());
        node.put("generation", marker.generation());
        if (marker.sha256() != null) {
            node.put("sha256", marker.sha256());
        }
        Path part = library.resolve(PART);
        try {
            Files.writeString(part, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n",
                    StandardCharsets.UTF_8);
            Replace.replace(part, library.resolve(NAME));
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // the folder can't be written: the failure itself is what the caller reports
            }
            throw e;
        }
        deleteConflictCopies(library);
    }

    private static void deleteConflictCopies(Path library) {
        List<Path> copies;
        try (Stream<Path> files = Files.list(library)) {
            copies = files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(PREFIX) && !name.equals(NAME) && !name.equals(PART);
            }).toList();
        } catch (IOException e) {
            LOG.warn("Could not look for copies of the device marker in {}", library, e);
            return;
        }
        for (Path copy : copies) {
            try {
                Files.deleteIfExists(copy);
                LOG.info("Deleted {}, a copy of the device marker a sync app made", copy.getFileName());
            } catch (IOException e) {
                LOG.warn("Could not delete {}, a copy of the device marker (it is ignored)", copy, e);
            }
        }
    }
}
