package com.butterflyatlas.placeimporter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Permanent source key → place id map ({@code place-ids.json}). Keys are {@code iso:<ISO 3166 code>},
 * {@code lgd:<LGD district code>}, {@code gb:<geoBoundaries shape id>}, {@code ne:<Natural Earth adm1_code>} or
 * {@code ne0:<Natural Earth ADM0_A3>}. Place ids ({@code P<n>}) never change and are never reused. One id may have keys
 * of different kinds (a district first known by its {@code gb:} key, then matched to an LGD code, keeps its id under its
 * {@code lgd:} key too: 5c contract §3), never two of the same kind.
 */
public final class PlaceIdRegistry {

    /** Duplicate keys would otherwise be read silently, the last one winning. */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectWriter WRITER = JSON.writer(prettyPrinter());
    private static final Pattern PLACE_ID = Pattern.compile("P([1-9]\\d{0,8})");
    private static final Pattern KEY = Pattern.compile("(iso|lgd|gb|ne|ne0):\\S+");

    private final TreeMap<String, String> ids = new TreeMap<>();
    private int next = 1;

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    public static PlaceIdRegistry load(Path file) throws IOException {
        PlaceIdRegistry registry = new PlaceIdRegistry();
        if (!Files.exists(file)) {
            return registry;
        }
        JsonNode root;
        try {
            root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw invalid(file, e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw invalid(file, "not a JSON object");
        }
        if (!root.path("next").isInt()) {
            throw invalid(file, "\"next\" is missing or not an integer");
        }
        int highest = 0;
        Map<String, String> keyById = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            String key = entry.getKey();
            if (key.equals("next")) {
                continue;
            }
            if (!KEY.matcher(key).matches()) {
                throw invalid(file, "\"" + key + "\" is not a source key");
            }
            Matcher m = PLACE_ID.matcher(entry.getValue().asText());
            if (!entry.getValue().isTextual() || !m.matches()) {
                throw invalid(file, "value for " + key + " is not a place id (P<number>)");
            }
            String other = keyById.put(kind(key) + " " + entry.getValue().asText(), key);
            if (other != null) {
                throw invalid(file, entry.getValue().asText() + " is given to both " + other + " and " + key);
            }
            highest = Math.max(highest, Integer.parseInt(m.group(1)));
            registry.ids.put(key, entry.getValue().asText());
        }
        registry.next = Math.max(root.path("next").asInt(), highest + 1);
        return registry;
    }

    private static IOException invalid(Path file, String reason) {
        return new IOException("Place id registry " + file + " is invalid: " + reason);
    }

    /** The place id for this source key, assigning the next free one if it has none yet. */
    public String idFor(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a source key: " + key);
        }
        return ids.computeIfAbsent(key, k -> "P" + next++);
    }

    /**
     * Gives {@code key} the id another key of a different kind already has ({@code lgd:} for a {@code gb:} district,
     * 5c contract §3); refused when the key has another id, or the id has a key of this kind already.
     */
    public void alias(String key, String id) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a source key: " + key);
        }
        String had = ids.get(key);
        if (had != null) {
            if (!had.equals(id)) {
                throw new IllegalArgumentException(key + " is " + had + " already, not " + id);
            }
            return;
        }
        for (Map.Entry<String, String> entry : ids.entrySet()) {
            if (entry.getValue().equals(id) && kind(entry.getKey()).equals(kind(key))) {
                throw new IllegalArgumentException(id + " has the key " + entry.getKey() + " already");
            }
        }
        ids.put(key, id);
    }

    private static String kind(String key) {
        return key.substring(0, key.indexOf(':'));
    }

    public Optional<String> find(String key) {
        return Optional.ofNullable(ids.get(key));
    }

    /** The number the next new place id will get. */
    public int next() {
        return next;
    }

    public void save(Path file) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ids.forEach(root::put);
        root.put("next", next);
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temp, WRITER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
