package com.butterflyatlas.importer.taxonomy;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Permanent iNaturalist id → Atlas id map. Atlas ids never change and are never reused, so the
 * app can track a species across renames, moves and iNaturalist id swaps.
 */
public final class AtlasIdRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter WRITER = JSON.writer(prettyPrinter());

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    private final TreeMap<Long, String> ids = new TreeMap<>();
    private int next = 1;

    private static final Pattern ATLAS_ID = Pattern.compile("A(\\d{1,9})");

    public static AtlasIdRegistry load(Path file) throws IOException {
        AtlasIdRegistry registry = new AtlasIdRegistry();
        if (!Files.exists(file)) {
            return registry;
        }
        JsonNode root = JSON.readTree(file.toFile());
        if (root == null || !root.isObject()) {
            throw invalid(file, "not a JSON object");
        }
        if (!root.path("next").isInt()) {
            throw invalid(file, "\"next\" is missing or not an integer");
        }
        if (!root.path("ids").isObject()) {
            throw invalid(file, "\"ids\" is missing or not an object");
        }
        int highest = 0;
        for (Map.Entry<String, JsonNode> entry : root.path("ids").properties()) {
            long inatId;
            try {
                inatId = Long.parseLong(entry.getKey());
            } catch (NumberFormatException e) {
                throw invalid(file, "key \"" + entry.getKey() + "\" is not an iNaturalist id");
            }
            if (inatId <= 0) {
                throw invalid(file, "key \"" + entry.getKey() + "\" is not a positive iNaturalist id");
            }
            Matcher m = ATLAS_ID.matcher(entry.getValue().asText());
            if (!entry.getValue().isTextual() || !m.matches()) {
                throw invalid(file, "value for " + entry.getKey() + " is not an Atlas id (A<number>)");
            }
            highest = Math.max(highest, Integer.parseInt(m.group(1)));
            registry.ids.put(inatId, entry.getValue().asText());
        }
        registry.next = Math.max(root.path("next").asInt(), highest + 1);
        return registry;
    }

    private static IOException invalid(Path file, String reason) {
        return new IOException("Atlas id registry " + file + " is invalid: " + reason);
    }

    /** The Atlas id for this taxon, assigning the next free one if it has none yet. */
    public String idFor(long inatId) {
        return ids.computeIfAbsent(inatId, id -> "A" + next++);
    }

    public Optional<String> find(long inatId) {
        return Optional.ofNullable(ids.get(inatId));
    }

    /** Records that {@code inatId} replaces a taxon that already has {@code atlasId}. */
    public void alias(long inatId, String atlasId) {
        Matcher m = ATLAS_ID.matcher(atlasId);
        if (!m.matches() || Integer.parseInt(m.group(1)) >= next) {
            throw new IllegalArgumentException("Atlas id " + atlasId + " has not been issued");
        }
        String existing = ids.get(inatId);
        if (existing != null && !existing.equals(atlasId)) {
            throw new IllegalStateException("iNaturalist taxon " + inatId + " already has Atlas id " + existing);
        }
        ids.put(inatId, atlasId);
    }

    public void save(Path file) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("next", next);
        ObjectNode map = root.putObject("ids");
        ids.forEach((inatId, atlasId) -> map.put(String.valueOf(inatId), atlasId));
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temp, WRITER.writeValueAsString(root) + "\n");
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
