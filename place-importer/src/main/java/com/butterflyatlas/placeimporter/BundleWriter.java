package com.butterflyatlas.placeimporter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns an {@link AtlasBuilder.Atlas} into the bundle files of contract §2 ({@code places-<version>.json} and
 * {@code .bin}), checks their structure, and writes them with {@code NOTICE.txt} and {@code places.sha256}.
 */
public final class BundleWriter {

    public static final String CHECKSUMS = "places.sha256";
    public static final String NOTICE = "NOTICE.txt";
    private static final Pattern BUNDLE_FILE = Pattern.compile("places-\\d{4}-\\d{2}-\\d{2}\\.(json|bin)");
    private static final List<String> CODE_ORDER = List.of("iso", "lgd", "gb", "ne");
    /** Segments longer than this (90° of longitude) get points in between, so none can be read as crossing ±180. */
    private static final int MAX_STEP = 9_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter WRITER = JSON.writer(prettyPrinter());

    public record Bundle(String version, String jsonName, String binName, byte[] json, byte[] bin,
                         List<BundlePlace> places, List<BinWriter.Entry> entries) {
    }

    private BundleWriter() {
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    /**
     * Gives every place its permanent id (new keys get the next ones, in tree order; an {@code lgd:} district takes the
     * id of its {@code gb:} key when it has one) and encodes the leaves. The list is based on nothing and has no changes
     * until {@link #withHistory} gives it those.
     */
    public static Bundle assemble(String version, AtlasBuilder.Atlas atlas, PlaceIdRegistry registry) {
        if (!version.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("The version must be a date like yyyy-MM-dd, got: " + version);
        }
        String jsonName = "places-" + version + ".json";
        String binName = "places-" + version + ".bin";
        Map<String, String> ids = new HashMap<>();
        List<BundlePlace> places = new ArrayList<>();
        for (AtlasBuilder.Place p : atlas.places()) {
            // A district matched to an LGD code now, known before by its geoBoundaries key, keeps its id (5c §3).
            String gb = p.codes().get("gb");
            if (p.key().startsWith("lgd:") && gb != null && registry.find(p.key()).isEmpty()) {
                registry.find("gb:" + gb).ifPresent(id -> registry.alias(p.key(), id));
            }
            ids.put(p.key(), registry.idFor(p.key()));
        }
        for (AtlasBuilder.Place p : atlas.places()) {
            places.add(new BundlePlace(ids.get(p.key()), p.parentKey() == null ? null : ids.get(p.parentKey()), p.level(),
                    p.name(), p.aliases(), p.codes()));
        }
        Map<String, Geometry> leaves = new HashMap<>();
        for (Partition.Piece leaf : atlas.leaves()) {
            if (!ids.containsKey(leaf.key())) {
                throw new IllegalStateException("Boundary for " + leaf.key() + ", which is not a place");
            }
            leaves.put(leaf.key(), leaf.geometry());
        }
        List<BinWriter.Entry> entries = new ArrayList<>();
        for (AtlasBuilder.Place p : atlas.places()) {
            if (leaves.containsKey(p.key())) {
                entries.add(new BinWriter.Entry(ids.get(p.key()), rings(leaves.get(p.key()))));
            }
        }
        return new Bundle(version, jsonName, binName, json(version, binName, places, null, List.of()), BinWriter.write(entries),
                places, entries);
    }

    /**
     * The bundle as a later list (5c contract §3): based on {@code basedOn}, with its {@code changes} and each place's
     * other names as {@code aliases} gives them (a place missing from it keeps its own).
     */
    public static Bundle withHistory(Bundle bundle, String basedOn, List<PlaceDiff.Change> changes,
                                     Map<String, List<String>> aliases) {
        List<BundlePlace> places = bundle.places().stream().map(p -> new BundlePlace(p.id(), p.parent(), p.level(), p.name(),
                aliases.getOrDefault(p.id(), p.aliases()), p.codes())).toList();
        return new Bundle(bundle.version(), bundle.jsonName(), bundle.binName(),
                json(bundle.version(), bundle.binName(), places, basedOn, changes), bundle.bin(), places, bundle.entries());
    }

    private static byte[] json(String version, String binName, List<BundlePlace> places, String basedOn,
                               List<PlaceDiff.Change> changes) {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", 1);
        root.put("version", version);
        root.put("basedOn", basedOn);
        root.putArray("levels").add("country").add("state").add("district");
        root.put("geometry", binName);
        ArrayNode list = root.putArray("places");
        for (BundlePlace p : places) {
            ObjectNode node = list.addObject();
            node.put("id", p.id());
            node.put("parent", p.parent());
            node.put("level", p.level());
            node.put("name", p.name());
            ArrayNode aliases = node.putArray("aliases");
            p.aliases().forEach(aliases::add);
            ObjectNode codes = node.putObject("codes");
            for (String key : p.codes().keySet()) {
                if (!CODE_ORDER.contains(key)) {
                    throw new IllegalArgumentException("Unknown code " + key + " of " + p.name());
                }
            }
            CODE_ORDER.stream().filter(p.codes()::containsKey).forEach(k -> codes.put(k, p.codes().get(k)));
        }
        ArrayNode changeList = root.putArray("changes");
        for (PlaceDiff.Change c : changes) {
            ObjectNode node = changeList.addObject();
            node.put("type", c.type());
            ArrayNode from = node.putArray("from");
            c.from().forEach(from::add);
            ArrayNode to = node.putArray("to");
            c.to().forEach(to::add);
            node.put("detail", c.detail());
        }
        try {
            return (WRITER.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Each polygon's outer ring then its holes, as closed integer rings in 1e-5 degree. The geometry is normalised
     * first (JTS's canonical order of parts, rings and ring starts), because the overlay hands back the same area
     * with a different ring start from run to run; normalised, the same boundaries always give the same bytes.
     */
    static List<int[]> rings(Geometry original) {
        Geometry geometry = original.norm();
        List<int[]> rings = new ArrayList<>();
        for (int g = 0; g < geometry.getNumGeometries(); g++) {
            Polygon polygon = (Polygon) geometry.getGeometryN(g);
            List<LineString> lines = new ArrayList<>();
            lines.add(polygon.getExteriorRing());
            for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                lines.add(polygon.getInteriorRingN(h));
            }
            for (LineString line : lines) {
                int[] ring = ring(line.getCoordinates());
                if (ring.length >= 8) {
                    rings.add(ring);
                }
            }
        }
        return rings;
    }

    private static int[] ring(Coordinate[] coordinates) {
        List<Integer> points = new ArrayList<>();
        for (Coordinate c : coordinates) {
            int x = (int) Math.round(c.x * 1e5);
            int y = (int) Math.round(c.y * 1e5);
            int n = points.size();
            if (n >= 2) {
                int px = points.get(n - 2);
                int py = points.get(n - 1);
                if (px == x && py == y) {
                    continue;
                }
                int steps = (Math.abs(x - px) + MAX_STEP - 1) / MAX_STEP;
                for (int s = 1; s < steps; s++) {
                    points.add(px + (int) Math.round((double) (x - px) * s / steps));
                    points.add(py + (int) Math.round((double) (y - py) * s / steps));
                }
            }
            points.add(x);
            points.add(y);
        }
        return points.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The structure the app relies on (contract §2); empty when the bundle is sound. */
    public static List<String> structure(List<BundlePlace> places, List<BinWriter.Entry> entries) {
        List<String> problems = new ArrayList<>();
        Map<String, BundlePlace> byId = new HashMap<>();
        places.forEach(p -> byId.putIfAbsent(p.id(), p));
        Set<String> seen = new HashSet<>();
        for (BundlePlace p : places) {
            if (!p.id().matches("P[1-9]\\d*")) {
                problems.add("Place id " + p.id() + " is not P<number>");
            }
            if (!seen.add(p.id())) {
                problems.add("Place id " + p.id() + " is used twice");
            }
            BundlePlace parent = p.parent() == null ? null : byId.get(p.parent());
            if (p.parent() != null && parent == null) {
                problems.add("Place " + p.id() + " has an unknown parent " + p.parent());
            } else if (parent != null && !seen.contains(parent.id())) {
                problems.add("Place " + p.id() + " is listed before its parent " + parent.id());
            }
            if (p.name() == null || p.name().isBlank()) {
                problems.add("Place " + p.id() + " has no name");
            }
            if (p.aliases().stream().anyMatch(a -> a.equals(p.name()))) {
                problems.add("Place " + p.id() + " has an alias equal to its name");
            }
            if (p.aliases().stream().anyMatch(a -> a == null || a.isBlank())) {
                problems.add("Place " + p.id() + " has a blank alias");
            }
            String parentLevel = parent == null ? null : parent.level();
            switch (p.level()) {
                case "country" -> {
                    if (p.parent() != null) {
                        problems.add("Country " + p.id() + " has a parent");
                    }
                }
                case "state" -> {
                    if (!"country".equals(parentLevel)) {
                        problems.add("State " + p.id() + " is not under a country");
                    }
                }
                case "district" -> {
                    if (!"state".equals(parentLevel)) {
                        problems.add("District " + p.id() + " is not under a state");
                    }
                }
                default -> problems.add("Place " + p.id() + " has an unknown level " + p.level());
            }
        }
        Map<String, List<String>> sameName = new LinkedHashMap<>();   // parent and name without accents or case → ids
        for (BundlePlace p : places) {
            if (p.name() != null && !p.name().isBlank()) {
                sameName.computeIfAbsent(p.parent() + "/" + Names.fold(p.name()), k -> new ArrayList<>()).add(p.id());
            }
        }
        for (List<String> ids : sameName.values()) {
            if (ids.size() > 1) {
                BundlePlace first = byId.get(ids.get(0));
                BundlePlace parent = first.parent() == null ? null : byId.get(first.parent());
                problems.add((parent == null ? "Countries " : "Places ") + String.join(", ", ids)
                        + (parent == null ? "" : " in " + parent.name()) + " share the name " + first.name()
                        + " (add rows to name-overrides.csv)");
            }
        }
        Set<String> boundaries = new HashSet<>();
        for (BinWriter.Entry e : entries) {
            if (!byId.containsKey(e.id())) {
                problems.add("Boundary " + e.id() + " is not a place");
            }
            if (!boundaries.add(e.id())) {
                problems.add("Boundary " + e.id() + " is listed twice");
            }
            if (e.rings().isEmpty()) {
                problems.add("Boundary " + e.id() + " has no rings");
            }
            boolean wide = false;
            boolean outside = false;
            for (int[] ring : e.rings()) {
                for (int i = 0; i < ring.length; i += 2) {
                    outside |= Math.abs(ring[i]) > 18_000_000 || Math.abs(ring[i + 1]) > 9_000_000;
                    wide |= i + 3 < ring.length && Math.abs((long) ring[i + 2] - ring[i]) > 18_000_000;
                }
            }
            if (wide) {
                problems.add("Boundary " + e.id() + " has a segment spanning more than 180° of longitude (it must be split at ±180)");
            }
            if (outside) {
                problems.add("Boundary " + e.id() + " has a point beyond ±180° longitude or ±90° latitude");
            }
        }
        return problems;
    }

    /** Moves a file within the bundle folder (replaced in tests to make a move fail). */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;

        default void delete(Path file) throws IOException {
            Files.deleteIfExists(file);
        }
    }

    /** The write failed and the folder could not be put back as it was; the message lists what is left. */
    public static final class PartialWriteException extends IOException {
        PartialWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Writes the bundle, NOTICE.txt and the checksum list of every place list in the directory and the new boundaries
     * (an older list's boundaries are deleted once the bundle is in place: only the newest ship), all or nothing:
     * every file is first written under a temporary name in the folder (the checksums from the bytes in memory),
     * then moved into place; if a move fails, the files already moved are removed (or put back as they were) and
     * the temporary files deleted. A {@link PartialWriteException} means even that failed. Returns warnings: old
     * copies it could not delete after the bundle was in place.
     */
    public static List<String> write(Path directory, Bundle bundle, String notice) throws IOException {
        return write(directory, bundle, notice, (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING));
    }

    static List<String> write(Path directory, Bundle bundle, String notice, Mover mover) throws IOException {
        Files.createDirectories(directory);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(bundle.jsonName(), bundle.json());
        files.put(bundle.binName(), bundle.bin());
        files.put(NOTICE, notice.getBytes(StandardCharsets.UTF_8));
        Map<String, String> sums = new TreeMap<>();
        List<Path> oldBoundaries = new ArrayList<>();   // only the newest list's boundaries ship (5c contract §2)
        try (Stream<Path> list = Files.list(directory)) {
            for (Path file : list.toList()) {
                String name = file.getFileName().toString();
                if (BUNDLE_FILE.matcher(name).matches() && !files.containsKey(name)) {
                    if (name.endsWith(".bin")) {
                        oldBoundaries.add(file);
                    } else {
                        sums.put(name, Sources.sha256(file));
                    }
                }
            }
        }
        sums.put(bundle.jsonName(), sha256(bundle.json()));
        sums.put(bundle.binName(), sha256(bundle.bin()));
        StringBuilder list = new StringBuilder();
        sums.forEach((name, sum) -> list.append(sum).append("  ").append(name).append('\n'));
        files.put(CHECKSUMS, list.toString().getBytes(StandardCharsets.UTF_8));

        Map<Path, Path> temps = new LinkedHashMap<>();   // target → its new content under a temporary name
        List<Path[]> moved = new ArrayList<>();           // {target, its old content or null}
        try {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Path temp = Files.createTempFile(directory, file.getKey(), ".tmp");
                temps.put(directory.resolve(file.getKey()), temp);
                Files.write(temp, file.getValue());
            }
            for (Map.Entry<Path, Path> file : temps.entrySet()) {
                Path target = file.getKey();
                Path old = null;
                if (Files.exists(target)) {
                    old = Files.createTempFile(directory, target.getFileName().toString(), ".old");
                    try {
                        mover.move(target, old);
                    } catch (IOException | RuntimeException e) {
                        mover.delete(old);   // the target is still in place; only the empty copy goes
                        throw e;
                    }
                }
                moved.add(new Path[] {target, old});
                mover.move(file.getValue(), target);
            }
        } catch (IOException | RuntimeException e) {
            List<String> left = undo(moved, temps.values(), mover);
            if (!left.isEmpty()) {
                throw new PartialWriteException("Writing the bundle to " + directory + " failed (" + e.getMessage()
                        + ") and could not be undone; check " + String.join(", ", left), e);
            }
            throw e;
        }
        List<String> warnings = new ArrayList<>();   // the bundle is in place: a leftover old copy is no failure
        for (Path bin : oldBoundaries) {
            try {
                mover.delete(bin);
            } catch (IOException | RuntimeException e) {
                warnings.add("Could not delete the older boundaries " + bin.getFileName() + " (" + e.getMessage()
                        + "); delete it by hand: the app refuses a boundaries file not on " + CHECKSUMS);
            }
        }
        for (Path[] file : moved) {
            if (file[1] != null) {
                try {
                    mover.delete(file[1]);
                } catch (IOException | RuntimeException e) {
                    warnings.add("Could not delete " + file[1].getFileName() + " (" + e.getMessage() + "); delete it by hand");
                }
            }
        }
        return warnings;
    }

    /** Puts the folder back as it was; returns the files it could not put back or remove. */
    private static List<String> undo(List<Path[]> moved, Iterable<Path> temps, Mover mover) {
        List<String> left = new ArrayList<>();
        for (int i = moved.size() - 1; i >= 0; i--) {
            Path target = moved.get(i)[0];
            Path old = moved.get(i)[1];
            try {
                if (old == null) {
                    mover.delete(target);
                } else {
                    mover.move(old, target);
                }
            } catch (IOException | RuntimeException e) {
                left.add(target.getFileName().toString());
            }
        }
        for (Path temp : temps) {
            try {
                mover.delete(temp);
            } catch (IOException | RuntimeException e) {
                left.add(temp.getFileName().toString());
            }
        }
        return left;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
