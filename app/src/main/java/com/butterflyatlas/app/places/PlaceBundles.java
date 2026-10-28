package com.butterflyatlas.app.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The place data shipped inside the app ({@code places/}, contract §2), verified as the taxonomy bundles are:
 * {@code places.sha256} lists every bundle file; each listed file must exist and match its SHA-256, and a
 * {@code places-*.json} or {@code places-*.bin} that is not listed is refused, so a damaged or stray bundle stops the
 * app at startup instead of placing photos wrongly. Every place list ever shipped is there ({@code places-<version>.json},
 * oldest first through their {@code basedOn} links, one unbroken chain), each list's {@code changes} agreeing with the
 * list before it (5c contract §2); only the newest list's boundaries ({@code .bin}) ship. The newest list is the one in
 * use; its tree is read now, its boundaries ({@link #index()}) when a library opens ({@link PlaceIndex#warm}) or on the
 * first lookup, whichever comes first.
 */
public final class PlaceBundles {

    public static final String DIRECTORY = "/places/";
    public static final String CHECKSUMS = "places.sha256";

    private static final Pattern LINE = Pattern.compile("^([0-9a-f]{64}) [ *]([^/\\\\]+)$");
    private static final Pattern BUNDLE_FILE = Pattern.compile("places-.*\\.(json|bin)");
    private static final Pattern JSON_FILE = Pattern.compile("places-(\\d{4}-\\d{2}-\\d{2})\\.json");
    private static final Pattern BIN_FILE = Pattern.compile("places-.*\\.bin");
    private static final Pattern ID = Pattern.compile("P[1-9]\\d*");
    private static final List<String> LEVELS = List.of("country", "state", "district");
    private static final String REINSTALL = " Reinstall Butterfly Atlas.";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One shipped place list: its file and SHA-256, its version, the version it is based on (null for the first), its
     * places (parents before children) and its changes from the list it is based on.
     */
    public record Shipped(String fileName, String sha256, String version, String basedOn, List<PlaceDef> places,
                          List<PlaceChange> changes) {

        public Shipped {
            places = List.copyOf(places);
            changes = List.copyOf(changes);
        }
    }

    private final List<Shipped> lists;
    private final PlaceIndex index;

    private PlaceBundles(List<Shipped> lists, PlaceIndex index) {
        this.lists = List.copyOf(lists);
        this.index = index;
    }

    /** The bundle inside the app (its jar, or the build's classes folder). */
    public static PlaceBundles fromClasspath() {
        return fromResource(PlaceBundles.class.getResource(DIRECTORY + CHECKSUMS));
    }

    /**
     * The place lists in {@code folder} instead of the app's (developer only, {@code -Datlas.placesDir}; 5c contract §2):
     * its {@code places.sha256} and the files it lists, with the same checks.
     */
    public static PlaceBundles fromDirectory(Path folder) {
        return load(folder);
    }

    /** The bundle in the folder holding this {@code places.sha256} (a {@code file:} or {@code jar:} URL); null: none. */
    static PlaceBundles fromResource(URL checksums) {
        if (checksums == null) {
            throw new PlaceBundleException("The place list " + CHECKSUMS + " is missing." + REINSTALL);
        }
        URI uri;
        try {
            uri = checksums.toURI();
        } catch (URISyntaxException e) {
            throw new PlaceBundleException("The place list " + CHECKSUMS + " could not be read." + REINSTALL, e);
        }
        if (!"jar".equals(uri.getScheme())) {
            return load(Path.of(uri).getParent());
        }
        FileSystem opened = null;
        try {
            FileSystem jar;
            try {
                jar = FileSystems.newFileSystem(uri, Map.of());
                opened = jar;
            } catch (FileSystemAlreadyExistsException e) {
                jar = FileSystems.getFileSystem(uri);
            }
            return load(jar.provider().getPath(uri).getParent());
        } catch (IOException e) {
            throw new PlaceBundleException("The place data could not be read." + REINSTALL, e);
        } finally {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException e) {
                    // everything was read; nothing to keep open
                }
            }
        }
    }

    /** The bundle in a folder: {@code places.sha256} and the {@code places-*} files next to it. */
    public static PlaceBundles load(Path directory) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "places*")) {
            for (Path file : entries) {
                if (Files.isRegularFile(file)) {
                    files.put(file.getFileName().toString(), Files.readAllBytes(file));
                }
            }
        } catch (IOException | UncheckedIOException e) {
            throw new PlaceBundleException("The place data could not be read." + REINSTALL, e);
        }
        return load(files);
    }

    /** The bundle made of these files (name to bytes). */
    public static PlaceBundles load(Map<String, byte[]> files) {
        byte[] list = files.get(CHECKSUMS);
        if (list == null) {
            throw new PlaceBundleException("The place list " + CHECKSUMS + " is missing." + REINSTALL);
        }
        Map<String, String> listed = readChecksums(list);
        for (String name : files.keySet()) {
            if (BUNDLE_FILE.matcher(name).matches() && !listed.containsKey(name)) {
                throw new PlaceBundleException("The place file " + name + " is not on the list " + CHECKSUMS + "." + REINSTALL);
            }
        }
        for (Map.Entry<String, String> entry : listed.entrySet()) {
            byte[] bytes = files.get(entry.getKey());
            if (bytes == null) {
                throw new PlaceBundleException("The place file " + entry.getKey() + " is missing." + REINSTALL);
            }
            if (!sha256(bytes).equals(entry.getValue())) {
                throw new PlaceBundleException("The place file " + entry.getKey() + " is damaged." + REINSTALL);
            }
        }
        List<Shipped> lists = new ArrayList<>();
        Map<String, String> geometries = new HashMap<>();
        String bin = null;
        for (String name : listed.keySet()) {
            if (JSON_FILE.matcher(name).matches()) {
                lists.add(read(name, files.get(name), geometries));
            } else if (BIN_FILE.matcher(name).matches()) {
                if (bin != null) {
                    throw new PlaceBundleException("The place files " + bin + " and " + name + " are both on the list "
                            + CHECKSUMS + "; only the newest list's boundaries ship." + REINSTALL);
                }
                bin = name;
            }
        }
        if (lists.isEmpty()) {
            throw new PlaceBundleException("No place data is bundled with this copy of Butterfly Atlas." + REINSTALL);
        }
        // Versions are zero-padded ISO dates (yyyy-MM-dd), so text order is date order.
        lists.sort(Comparator.comparing(Shipped::version));
        checkChain(lists);
        Shipped newest = lists.getLast();
        String geometry = geometries.get(newest.fileName());
        if (!geometry.equals(bin)) {
            throw bad(newest.fileName(), "its boundaries " + geometry + " are not on the list " + CHECKSUMS);
        }
        byte[] bytes = files.get(bin);
        Set<String> known = new HashSet<>();
        newest.places().forEach(p -> known.add(p.id()));
        PlaceIndex index = PlaceIndex.lazy(() -> new ByteArrayInputStream(bytes), known);
        return new PlaceBundles(lists, index);
    }

    /** Every shipped place list, oldest first (the chain of their {@code basedOn} links). */
    public List<Shipped> all() {
        return lists;
    }

    /** The newest list, the one in use. */
    public Shipped newest() {
        return lists.getLast();
    }

    /** Where this version is in the chain: 1 for the first list, the number of lists for the newest; -1 if not shipped. */
    public int position(String version) {
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).version().equals(version)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The newest list's version ({@code yyyy-MM-dd}). */
    public String version() {
        return newest().version();
    }

    /** The SHA-256 of the newest place list. */
    public String sha256() {
        return newest().sha256();
    }

    /** The newest list's places, parents before children. */
    public List<PlaceDef> places() {
        return newest().places();
    }

    /** The newest list's boundaries, read when a library opens or on the first lookup, and then kept. */
    public PlaceIndex index() {
        return index;
    }

    private static Map<String, String> readChecksums(byte[] bytes) {
        Map<String, String> entries = new LinkedHashMap<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            Matcher m = LINE.matcher(lines[i].strip());
            if (!m.matches() || entries.put(m.group(2), m.group(1)) != null) {
                throw new PlaceBundleException("The place list " + CHECKSUMS + " is damaged at line " + (i + 1) + "." + REINSTALL);
            }
        }
        return entries;
    }

    /** One place list, its own structure checked; its boundaries' file name goes into {@code geometries}. */
    private static Shipped read(String name, byte[] bytes, Map<String, String> geometries) {
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException e) {
            throw new PlaceBundleException("The place file " + name + " could not be read." + REINSTALL, e);
        }
        if (root == null || !root.isObject()) {
            throw bad(name, "it is not a place list");
        }
        int format = root.path("format").asInt(0);
        if (format != 1) {
            throw bad(name, "it has format " + root.path("format").asText() + "; this version reads format 1");
        }
        String version = root.path("version").asText("");
        if (!name.equals("places-" + version + ".json")) {
            throw bad(name, "its version " + version + " does not match its name");
        }
        JsonNode basedOnNode = root.path("basedOn");
        String basedOn = basedOnNode.isNull() || basedOnNode.isMissingNode() ? null : basedOnNode.asText();
        geometries.put(name, root.path("geometry").asText(""));
        List<PlaceDef> places = new ArrayList<>();
        Map<String, String> levels = new LinkedHashMap<>();
        JsonNode list = root.path("places");
        if (!list.isArray() || list.isEmpty()) {
            throw bad(name, "it lists no places");
        }
        for (JsonNode node : list) {
            places.add(place(name, node, levels));
        }
        JsonNode changeList = root.path("changes");
        if (!changeList.isArray()) {
            throw bad(name, "it has no list of changes");
        }
        List<PlaceChange> changes = new ArrayList<>();
        for (JsonNode node : changeList) {
            changes.add(change(name, node));
        }
        return new Shipped(name, sha256(bytes), version, basedOn, places, changes);
    }

    /** One entry of a list's {@code changes}: a known type and lists of place ids. */
    private static PlaceChange change(String file, JsonNode node) {
        String type = node.path("type").asText("");
        if (!PlaceChange.TYPES.contains(type)) {
            throw bad(file, "it has a change of unknown type " + type);
        }
        for (String field : List.of("from", "to")) {
            JsonNode ids = node.path(field);
            if (!ids.isArray()) {
                throw bad(file, "a " + type + " change has no list " + field);
            }
            for (JsonNode id : ids) {
                if (!id.isTextual() || !ID.matcher(id.asText()).matches()) {
                    throw bad(file, "a " + type + " change names a place id " + id.asText() + " that is not P<number>");
                }
            }
        }
        return new PlaceChange(type, ids(node.path("from")), ids(node.path("to")), node.path("detail").asText(""));
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(id -> ids.add(id.asText()));
        return ids;
    }

    /**
     * The lists, oldest first, as one unbroken chain: the first based on nothing, each later one on the one before it;
     * each list's changes agreeing with the list before it ({@link #checkChanges}).
     */
    private static void checkChain(List<Shipped> lists) {
        Set<String> everShipped = new HashSet<>();
        for (int i = 0; i < lists.size(); i++) {
            Shipped list = lists.get(i);
            if (i == 0) {
                if (list.basedOn() != null) {
                    throw bad(list.fileName(), "it is based on " + list.basedOn() + ", which is not shipped");
                }
                if (!list.changes().isEmpty()) {
                    throw bad(list.fileName(), "it is the first list but lists changes");
                }
            } else {
                Shipped before = lists.get(i - 1);
                if (!before.version().equals(list.basedOn())) {
                    throw bad(list.fileName(), list.basedOn() == null ? "it is based on no list, but "
                            + before.fileName() + " comes before it" : "it is based on " + list.basedOn()
                            + ", but the list before it is " + before.version());
                }
                checkChanges(before, list, everShipped);
            }
            list.places().forEach(p -> everShipped.add(p.id()));
        }
    }

    /**
     * A list's changes against the list it is based on (5c contract §2, §10): a merge's {@code from} gone and its one
     * {@code to} in the list; a split's one {@code from} and two or more {@code to} in the list (the {@code from} may be
     * one of them); merges and splits within one level; a retire's one {@code from} gone and no {@code to}; the renames,
     * moves and adds exactly those that comparing the lists by id gives. Every id gone is in exactly one merge, split or retire; no id is in two of those; an id of an
     * earlier list never comes back; a place keeps its level.
     */
    private static void checkChanges(Shipped before, Shipped list, Set<String> everShipped) {
        String file = list.fileName();
        Map<String, PlaceDef> old = new HashMap<>();
        before.places().forEach(p -> old.put(p.id(), p));
        Map<String, PlaceDef> now = new HashMap<>();
        list.places().forEach(p -> now.put(p.id(), p));
        Set<String> renamed = new HashSet<>();
        Set<String> moved = new HashSet<>();
        Set<String> added = new HashSet<>();
        for (PlaceDef p : list.places()) {
            PlaceDef was = old.get(p.id());
            if (was == null) {
                if (everShipped.contains(p.id())) {
                    throw bad(file, "it lists " + p.id() + " again, which an earlier list gave up");
                }
                added.add(p.id());
            } else {
                if (!was.level().equals(p.level())) {
                    throw bad(file, "place " + p.id() + " changes its level from " + was.level() + " to " + p.level());
                }
                if (!was.name().equals(p.name())) {
                    renamed.add(p.id());
                }
                if (!Objects.equals(was.parentId(), p.parentId())) {
                    moved.add(p.id());
                }
            }
        }
        Set<String> gone = new HashSet<>(old.keySet());
        gone.removeAll(now.keySet());
        Set<String> accounted = new HashSet<>();
        Map<String, Set<String>> declared = new HashMap<>();
        for (PlaceChange change : list.changes()) {
            String what = change.type() + " of " + String.join(", ", change.from());
            switch (change.type()) {
                case PlaceChange.MERGE -> {
                    if (change.from().isEmpty() || change.to().size() != 1 || !now.containsKey(change.to().get(0))) {
                        throw bad(file, "its merge of " + String.join(", ", change.from()) + " does not name one place of the"
                                + " list to merge into");
                    }
                    requireGone(file, what, change.from(), gone);
                    requireSameLevel(file, change, old, now);
                }
                case PlaceChange.SPLIT -> {
                    if (change.from().size() != 1 || change.to().size() < 2
                            || !change.to().stream().allMatch(now::containsKey)) {
                        throw bad(file, "its split of " + String.join(", ", change.from()) + " does not name one place split"
                                + " into two or more places of the list");
                    }
                    if (!change.to().contains(change.from().get(0))) {
                        requireGone(file, what, change.from(), gone);
                    } else if (!old.containsKey(change.from().get(0))) {
                        throw bad(file, "its " + what + " names a place the list before it does not have");
                    }
                    requireSameLevel(file, change, old, now);
                }
                case PlaceChange.RETIRE -> {
                    if (change.from().size() != 1 || !change.to().isEmpty()) {
                        throw bad(file, "its retire of " + String.join(", ", change.from()) + " does not name one place"
                                + " and nothing it became");
                    }
                    requireGone(file, what, change.from(), gone);
                }
                default -> {
                    declared.computeIfAbsent(change.type(), k -> new HashSet<>())
                            .addAll(change.type().equals(PlaceChange.ADD) ? change.to() : change.from());
                    continue;
                }
            }
            for (String id : change.from()) {
                if (!accounted.add(id)) {
                    throw bad(file, "place " + id + " is in two merges, splits or retires");
                }
            }
        }
        for (String id : gone) {
            if (!accounted.contains(id)) {
                throw bad(file, "place " + id + " (" + old.get(id).name() + ") is gone without a merge, split or retire");
            }
        }
        Map<String, Set<String>> found = Map.of(PlaceChange.RENAME, renamed, PlaceChange.MOVE, moved, PlaceChange.ADD, added);
        for (Map.Entry<String, Set<String>> kind : found.entrySet()) {
            Set<String> listed = declared.getOrDefault(kind.getKey(), Set.of());
            if (!listed.equals(kind.getValue())) {
                Set<String> differ = new TreeSet<>(listed);
                differ.addAll(kind.getValue());
                Set<String> both = new HashSet<>(listed);
                both.retainAll(kind.getValue());
                differ.removeAll(both);
                throw bad(file, "its " + kind.getKey() + " changes do not match the lists (" + String.join(", ", differ) + ")");
            }
        }
    }

    /** A merge or split stays on one level: every {@code from} (in the list before) and {@code to} (in this one). */
    private static void requireSameLevel(String file, PlaceChange change, Map<String, PlaceDef> old,
                                         Map<String, PlaceDef> now) {
        for (String from : change.from()) {
            for (String to : change.to()) {
                String was = old.get(from).level();
                String is = now.get(to).level();
                if (!was.equals(is)) {
                    throw bad(file, "its " + change.type() + " of " + from + " goes from a " + was + " to a " + is);
                }
            }
        }
    }

    private static void requireGone(String file, String what, List<String> ids, Set<String> gone) {
        for (String id : ids) {
            if (!gone.contains(id)) {
                throw bad(file, "its " + what + " names " + id + ", which is still in the list or was never in the one"
                        + " before it");
            }
        }
    }

    /** One place, checked against those before it (parents come first; a country has no parent, others one level up). */
    private static PlaceDef place(String file, JsonNode node, Map<String, String> levels) {
        String id = node.path("id").asText("");
        if (!ID.matcher(id).matches()) {
            throw bad(file, "it has a place id " + id + " that is not P<number>");
        }
        if (levels.containsKey(id)) {
            throw bad(file, "it lists " + id + " twice");
        }
        String level = node.path("level").asText("");
        if (!LEVELS.contains(level)) {
            throw bad(file, "place " + id + " has an unknown level " + level);
        }
        String parent = node.path("parent").isNull() || node.path("parent").isMissingNode() ? null : node.path("parent").asText();
        String expectedParentLevel = level.equals("country") ? null : LEVELS.get(LEVELS.indexOf(level) - 1);
        if (parent == null ? expectedParentLevel != null : !levels.containsKey(parent)
                || !levels.get(parent).equals(expectedParentLevel)) {
            throw bad(file, "place " + id + " has no " + (expectedParentLevel == null ? "country level" : expectedParentLevel)
                    + " before it as its parent");
        }
        String placeName = node.path("name").asText("");
        if (placeName.isBlank()) {
            throw bad(file, "place " + id + " has no name");
        }
        JsonNode aliasNode = node.path("aliases");
        if (!aliasNode.isArray()) {
            throw bad(file, "place " + id + " has no list of other names");
        }
        List<String> aliases = new ArrayList<>();
        for (JsonNode alias : aliasNode) {
            if (!alias.isTextual() || alias.asText().isBlank() || alias.asText().equals(placeName)) {
                throw bad(file, "place " + id + " has an unusable other name");
            }
            aliases.add(alias.asText());
        }
        Map<String, String> codes = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.path("codes").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> code = it.next();
            if (code.getValue().isTextual()) {
                codes.put(code.getKey(), code.getValue().asText());
            }
        }
        levels.put(id, level);
        return new PlaceDef(id, parent, level, placeName, aliases, codes);
    }

    private static PlaceBundleException bad(String file, String why) {
        return new PlaceBundleException("The place file " + file + " is not usable: " + why + "." + REINSTALL);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
