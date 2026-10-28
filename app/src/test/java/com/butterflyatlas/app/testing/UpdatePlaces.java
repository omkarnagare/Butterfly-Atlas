package com.butterflyatlas.app.testing;

import com.butterflyatlas.app.places.PlaceBundles;
import com.butterflyatlas.app.places.PlaceChange;
import com.butterflyatlas.app.places.PlaceDef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Place update chains for tests and real-app runs (5c design P9): a place list derived from another with chosen changes
 * (rename, move, add, merge, split, retire), its change list made the way the place importer makes it (renames, moves
 * and adds from comparing the lists by id; merges, splits and retires as chosen; a merged place's names become other
 * names of the place it merged into), and its boundaries changed with it (a merged place's area joins its survivor's, a
 * retired place's goes, a split place's area can be cut in two along a line of longitude or latitude, so the GPS re-check
 * visibly moves photos). The chain is given as {@link PlaceBundles} in memory or written to a folder with its
 * {@code places.sha256} for {@code -Datlas.placesDir}; only the newest list's boundaries are written, as the app ships them.
 *
 * <p>{@link #main} derives a list from the real one for a real-app run:
 * {@code ./mvnw -B -ntp -pl app test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test
 * "-Dexec.args=-cp %classpath com.butterflyatlas.app.testing.UpdatePlaces <folder>"}.
 */
public final class UpdatePlaces {

    /** The version of the list {@link #main} derives. */
    public static final String REAL_NEXT = "2027-01-15";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter WRITER = JSON.writer(prettyPrinter());
    private static final List<String> LEVELS = List.of("country", "state", "district");

    /**
     * One place list of a chain: its places (parents first), its changes, its areas (null for a list whose boundaries
     * are not at hand: only the newest list's are shipped) and its JSON as the app reads it.
     */
    public record Built(String version, String basedOn, List<PlaceDef> places, List<PlaceChange> changes,
                        List<PlaceBin.Area> areas, byte[] json) {

        public String jsonName() {
            return "places-" + version + ".json";
        }

        public String binName() {
            return "places-" + version + ".bin";
        }

        /** The same list with other changes (to test the checks), its JSON written again. */
        public Built withChanges(List<PlaceChange> other) {
            return new Built(version, basedOn, places, other, areas, UpdatePlaces.json(version, basedOn, places, other));
        }

        /** The same list with other places (to test the checks), its JSON written again. */
        public Built withPlaces(List<PlaceDef> other) {
            return new Built(version, basedOn, other, changes, areas, UpdatePlaces.json(version, basedOn, other, changes));
        }

        /** The same list as if based on another version (to test the chain), its JSON written again. */
        public Built basedOn(String other) {
            return new Built(version, other, places, changes, areas, UpdatePlaces.json(version, other, places, changes));
        }

        public PlaceDef place(String id) {
            return places.stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No place " + id + " in " + jsonName()));
        }
    }

    private UpdatePlaces() {
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    /** The fixture list ({@link TestPlaces}, version 2026-10-13) with its areas, its JSON exactly the fixture's. */
    public static Built fixture() {
        PlaceBundles.Shipped list = TestPlaces.bundles().newest();
        return new Built(list.version(), null, list.places(), List.of(), TestPlaces.areas(),
                TestPlaces.json().getBytes(StandardCharsets.UTF_8));
    }

    /** Every place list shipped with the app, oldest first, the newest with its areas (read from its {@code .bin}). */
    public static List<Built> real() {
        PlaceBundles shipped = PlaceBundles.fromClasspath();
        List<Built> chain = new ArrayList<>();
        for (PlaceBundles.Shipped list : shipped.all()) {
            boolean newest = list == shipped.newest();
            chain.add(new Built(list.version(), list.basedOn(), list.places(), list.changes(),
                    newest ? PlaceBin.decode(resource("places-" + list.version() + ".bin")) : null,
                    resource(list.fileName())));
        }
        return chain;
    }

    private static byte[] resource(String name) {
        try (InputStream in = UpdatePlaces.class.getResourceAsStream(PlaceBundles.DIRECTORY + name)) {
            return Objects.requireNonNull(in, "missing resource " + PlaceBundles.DIRECTORY + name).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A list of {@code version} based on {@code base} (which must have its areas), to be given its changes. */
    public static Derived derive(Built base, String version) {
        return new Derived(base, version);
    }

    /** The files of the chain as the app ships them: every list, the newest list's boundaries and {@code places.sha256}. */
    public static Map<String, byte[]> files(Built... chain) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Built list : chain) {
            files.put(list.jsonName(), list.json());
        }
        Built newest = chain[chain.length - 1];
        files.put(newest.binName(), PlaceBin.encode(newest.areas()));
        files.put(PlaceBundles.CHECKSUMS, TestPlaces.checksums(files));
        return files;
    }

    /** The chain as the app would ship it, in memory, with its checks. */
    public static PlaceBundles bundles(Built... chain) {
        return PlaceBundles.load(files(chain));
    }

    /** Writes the chain into {@code folder} (made if needed), for {@code -Datlas.placesDir}. */
    public static Path write(Path folder, Built... chain) {
        return TestPlaces.write(folder, files(chain));
    }

    /** A list being derived from its base; each change returns it, {@link #build} gives the list. */
    public static final class Derived {

        private final Built base;
        private final String version;
        /** The new list by id, in the base's order with new places last. */
        private final Map<String, PlaceDef> places = new LinkedHashMap<>();
        private final Map<String, PlaceBin.Area> areas = new LinkedHashMap<>();
        private final List<PlaceChange> declared = new ArrayList<>();

        private Derived(Built base, String version) {
            if (base.areas() == null) {
                throw new IllegalArgumentException(base.jsonName() + " has no areas to derive from");
            }
            this.base = base;
            this.version = version;
            base.places().forEach(p -> places.put(p.id(), p));
            base.areas().forEach(a -> areas.put(a.id(), a));
        }

        private PlaceDef get(String id) {
            PlaceDef p = places.get(id);
            if (p == null) {
                throw new IllegalArgumentException("No place " + id + " in the list being derived");
            }
            return p;
        }

        private Derived put(PlaceDef p) {
            places.put(p.id(), p);
            return this;
        }

        /** The id of the place named {@code name} (folded as the app compares names) under the place named {@code parent}. */
        public String id(String name, String parent) {
            return places.values().stream().filter(p -> p.name().equalsIgnoreCase(name) && p.parentId() != null
                            && get(p.parentId()).name().equalsIgnoreCase(parent)).map(PlaceDef::id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No place " + name + " in " + parent));
        }

        /** The next unused place id: above every id of the base (ids are never given twice). */
        public String nextId() {
            int highest = 0;
            for (String id : places.keySet()) {
                highest = Math.max(highest, Integer.parseInt(id.substring(1)));
            }
            for (PlaceDef p : base.places()) {
                highest = Math.max(highest, Integer.parseInt(p.id().substring(1)));
            }
            return "P" + (highest + 1);
        }

        /** A new name; the old one is kept as another name, as the importer keeps it. */
        public Derived rename(String id, String name) {
            PlaceDef p = get(id);
            Set<String> aliases = new LinkedHashSet<>(p.aliases());
            aliases.add(p.name());
            aliases.remove(name);
            return put(new PlaceDef(p.id(), p.parentId(), p.level(), name, List.copyOf(aliases), p.codes()));
        }

        /** New other names. */
        public Derived aliases(String id, String... aliases) {
            PlaceDef p = get(id);
            return put(new PlaceDef(p.id(), p.parentId(), p.level(), p.name(), List.of(aliases), p.codes()));
        }

        /** A new parent of the level above; its subtree goes with it. */
        public Derived move(String id, String parentId) {
            PlaceDef p = get(id);
            get(parentId);
            return put(new PlaceDef(p.id(), parentId, p.level(), p.name(), p.aliases(), p.codes()));
        }

        /** A new place (its parent must be in the list), without an area until it is given one. */
        public Derived add(String id, String parentId, String level, String name, String... aliases) {
            if (places.containsKey(id) || base.places().stream().anyMatch(p -> p.id().equals(id))) {
                throw new IllegalArgumentException(id + " is already in the list or its base");
            }
            if (parentId != null) {
                get(parentId);
            }
            return put(new PlaceDef(id, parentId, level, name, List.of(aliases), Map.of()));
        }

        /** The area of a leaf place (replacing any it had). */
        public Derived area(String id, double[][]... rings) {
            get(id);
            areas.put(id, new PlaceBin.Area(id, List.of(rings)));
            return this;
        }

        /**
         * The sources merge into the target: they go, their children (in the list) are under the target now, their names
         * become the target's other names, and their areas become part of the target's.
         */
        public Derived merge(String target, String... sources) {
            PlaceDef into = get(target);
            Set<String> aliases = new LinkedHashSet<>(into.aliases());
            List<String> names = new ArrayList<>();
            for (String source : sources) {
                PlaceDef from = get(source);
                names.add(from.name());
                aliases.add(from.name());
                aliases.addAll(from.aliases());
                for (PlaceDef child : List.copyOf(places.values())) {
                    if (source.equals(child.parentId())) {
                        move(child.id(), target);
                    }
                }
                PlaceBin.Area area = areas.remove(source);
                if (area != null) {
                    List<double[][]> rings = new ArrayList<>(areas.containsKey(target) ? areas.get(target).rings() : List.of());
                    rings.addAll(area.rings());
                    areas.put(target, new PlaceBin.Area(target, rings));
                }
                places.remove(source);
            }
            aliases.remove(into.name());
            put(new PlaceDef(into.id(), into.parentId(), into.level(), into.name(), List.copyOf(aliases), into.codes()));
            declared.add(new PlaceChange(PlaceChange.MERGE, List.of(sources), List.of(target),
                    String.join(", ", names) + " merged into " + into.name()));
            return this;
        }

        /**
         * The source splits into the targets, which must be in the list (add the new ones first); it goes unless it is
         * one of them. Give the targets their areas ({@link #cut}, {@link #area}); the source's goes with it.
         */
        public Derived split(String source, String... targets) {
            String name = get(source).name();
            List<String> to = List.of(targets);
            to.forEach(this::get);
            if (!to.contains(source)) {
                requireNoChildren(source);
                places.remove(source);
                areas.remove(source);
            }
            declared.add(new PlaceChange(PlaceChange.SPLIT, List.of(source), to, name + " split into "
                    + to.stream().map(id -> get(id).name()).collect(Collectors.joining(", "))));
            return this;
        }

        /** The place goes, with no successor; its area goes with it (it must have no children left). */
        public Derived retire(String id) {
            String name = get(id).name();
            requireNoChildren(id);
            places.remove(id);
            areas.remove(id);
            declared.add(new PlaceChange(PlaceChange.RETIRE, List.of(id), List.of(), name + " retired"));
            return this;
        }

        /**
         * Cuts the area of {@code id} along the line {@code lon} (or {@code lat}) {@code = at}: the part below it (west or
         * south) becomes the area of {@code lowId}, the rest {@code highId}'s; {@code id} keeps none unless it is one of
         * them. Call before {@link #split} removes {@code id}.
         */
        public Derived cut(String id, boolean alongLongitude, double at, String lowId, String highId) {
            PlaceBin.Area area = areas.get(id);
            if (area == null) {
                throw new IllegalArgumentException(id + " has no area to cut");
            }
            int axis = alongLongitude ? 0 : 1;
            List<double[][]> low = new ArrayList<>();
            List<double[][]> high = new ArrayList<>();
            for (double[][] ring : area.rings()) {
                addIfRing(low, clip(ring, axis, at, true));
                addIfRing(high, clip(ring, axis, at, false));
            }
            areas.remove(id);
            areas.put(lowId, new PlaceBin.Area(lowId, low));
            areas.put(highId, new PlaceBin.Area(highId, high));
            return this;
        }

        private void requireNoChildren(String id) {
            for (PlaceDef p : places.values()) {
                if (id.equals(p.parentId())) {
                    throw new IllegalArgumentException(id + " still has " + p.id() + " under it");
                }
            }
        }

        /** The list: its places parents first, its change list against the base (as the importer orders it). */
        public Built build() {
            Map<String, PlaceDef> before = new HashMap<>();
            base.places().forEach(p -> before.put(p.id(), p));
            List<PlaceChange> changes = new ArrayList<>(declared);
            List<String> added = new ArrayList<>();
            for (PlaceDef p : places.values()) {
                PlaceDef old = before.get(p.id());
                if (old == null) {
                    added.add(p.id());
                    continue;
                }
                if (!old.name().equals(p.name())) {
                    changes.add(new PlaceChange(PlaceChange.RENAME, List.of(p.id()), List.of(p.id()),
                            old.name() + " → " + p.name()));
                }
                if (!Objects.equals(old.parentId(), p.parentId())) {
                    changes.add(new PlaceChange(PlaceChange.MOVE, List.of(p.id()), List.of(p.id()), p.name() + ": "
                            + nameOf(old.parentId()) + " → " + nameOf(p.parentId())));
                }
            }
            for (String id : added) {
                changes.add(new PlaceChange(PlaceChange.ADD, List.of(), List.of(id), get(id).name() + " added"));
            }
            changes.sort(Comparator.comparingInt((PlaceChange c) -> PlaceChange.TYPES.indexOf(c.type()))
                    .thenComparing(c -> number(c.from().isEmpty() ? c.to() : c.from())));
            for (PlaceDef p : places.values()) {
                if (p.parentId() != null && !places.containsKey(p.parentId())) {
                    throw new IllegalStateException(p.id() + "'s parent " + p.parentId() + " is not in the list");
                }
            }
            List<PlaceDef> ordered = new ArrayList<>(places.values());
            ordered.sort(Comparator.comparingInt(p -> LEVELS.indexOf(p.level())));
            List<PlaceBin.Area> list = areas.values().stream().filter(a -> places.containsKey(a.id())).toList();
            return new Built(version, base.version(), ordered, changes, list, json(version, base.version(), ordered, changes));
        }

        private String nameOf(String id) {
            if (id == null) {
                return "(top level)";
            }
            PlaceDef p = places.containsKey(id) ? places.get(id) : base.places().stream().filter(b -> b.id().equals(id))
                    .findFirst().orElse(null);
            return p == null ? id : p.name();
        }
    }

    private static int number(List<String> ids) {
        return ids.isEmpty() ? 0 : Integer.parseInt(ids.get(0).substring(1));
    }

    private static void addIfRing(List<double[][]> rings, double[][] ring) {
        if (ring.length >= 3) {
            rings.add(ring);
        }
    }

    /**
     * The part of a ring on one side of the line {@code coordinate[axis] = at} (Sutherland–Hodgman against a half-plane);
     * a concave ring may come back with edges along the line, which the even-odd rule reads correctly.
     */
    static double[][] clip(double[][] ring, int axis, double at, boolean below) {
        List<double[]> points = new ArrayList<>(Arrays.asList(ring));
        if (points.size() > 1 && Arrays.equals(points.get(0), points.get(points.size() - 1))) {
            points.remove(points.size() - 1);
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double[] current = points.get(i);
            double[] previous = points.get((i + points.size() - 1) % points.size());
            boolean in = below ? current[axis] <= at : current[axis] >= at;
            boolean wasIn = below ? previous[axis] <= at : previous[axis] >= at;
            if (in != wasIn) {
                double t = (at - previous[axis]) / (current[axis] - previous[axis]);
                double[] crossing = new double[2];
                crossing[axis] = at;
                crossing[1 - axis] = previous[1 - axis] + t * (current[1 - axis] - previous[1 - axis]);
                out.add(crossing);
            }
            if (in) {
                out.add(current);
            }
        }
        return out.toArray(new double[0][]);
    }

    /** A list's JSON as the place importer writes it (contract §2). */
    public static byte[] json(String version, String basedOn, List<PlaceDef> places, List<PlaceChange> changes) {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", 1);
        root.put("version", version);
        root.put("basedOn", basedOn);
        root.putArray("levels").add("country").add("state").add("district");
        root.put("geometry", "places-" + version + ".bin");
        ArrayNode list = root.putArray("places");
        for (PlaceDef p : places) {
            ObjectNode node = list.addObject();
            node.put("id", p.id());
            node.put("parent", p.parentId());
            node.put("level", p.level());
            node.put("name", p.name());
            ArrayNode aliases = node.putArray("aliases");
            p.aliases().forEach(aliases::add);
            ObjectNode codes = node.putObject("codes");
            p.codes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> codes.put(e.getKey(), e.getValue()));
        }
        ArrayNode changeList = root.putArray("changes");
        for (PlaceChange c : changes) {
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
     * The usual next list of the fixture (2026-11-01), every kind of change once: Beta (P5) renamed Betania and moved
     * under the new state Southstate (P10); Islet (P7) merged into Lakeland (P6, its area too); Alpha (P4) split into the
     * new Alpha West (P11, west of 73.5° E) and Alpha East (P12); Westfar (P9) retired with its area.
     */
    public static Built fixtureNext() {
        return derive(fixture(), "2026-11-01")
                .rename("P5", "Betania")
                .add("P10", "P1", "state", "Southstate")
                .move("P5", "P10")
                .merge("P6", "P7")
                .add("P11", "P2", "district", "Alpha West")
                .add("P12", "P2", "district", "Alpha East")
                .cut("P4", true, 73.5, "P11", "P12")
                .split("P4", "P11", "P12")
                .retire("P9")
                .build();
    }

    /**
     * The next list of the real one for a real-app run ({@value #REAL_NEXT}), each kind of change once, in India where
     * photos are easy to place: Pune (Maharashtra) renamed Pune City; Mumbai Suburban merged into Mumbai (its area too);
     * Chittoor (Andhra Pradesh) split into the new Annamayya (west of 79.3° E) and Tirupati (east of it), so GPS photos
     * there move and hand-placed ones stay in "Chittoor (former district)"; Diu moved from Dadra and Nagar Haveli and
     * Daman and Diu to Gujarat; Dadra and Nagar Haveli and Daman merged into a new district, Dadra Nagar Haveli and
     * Daman (a merge into a place new in the same list); Lakshadweep (the state and its district) retired with its area,
     * so photos there fall to no place; and a new state Ladakh North, without districts or area, added under India.
     */
    public static Built realNext() {
        Built newest = real().getLast();
        Derived next = derive(newest, REAL_NEXT);
        String chittoor = next.id("Chittoor", "Andhra Pradesh");
        String andhra = newest.place(chittoor).parentId();
        String gujarat = next.id("Gujarat", "India");
        String india = newest.place(gujarat).parentId();
        String annamayya = next.nextId();
        next.add(annamayya, andhra, "district", "Annamayya");
        String tirupati = next.nextId();
        next.add(tirupati, andhra, "district", "Tirupati");
        String dadra = next.id("Dadra and Nagar Haveli", "Dadra and Nagar Haveli and Daman and Diu");
        String daman = next.id("Daman", "Dadra and Nagar Haveli and Daman and Diu");
        String joined = next.nextId();
        next.add(joined, newest.place(daman).parentId(), "district", "Dadra Nagar Haveli and Daman");
        next.merge(joined, dadra, daman);
        String lakshadweep = next.id("Lakshadweep", "India");
        for (PlaceDef p : newest.places()) {
            if (lakshadweep.equals(p.parentId())) {
                next.retire(p.id());
            }
        }
        return next.rename(next.id("Pune", "Maharashtra"), "Pune City")
                .merge(next.id("Mumbai", "Maharashtra"), next.id("Mumbai Suburban", "Maharashtra"))
                .cut(chittoor, true, 79.3, annamayya, tirupati)
                .split(chittoor, annamayya, tirupati)
                .move(next.id("Diu", "Dadra and Nagar Haveli and Daman and Diu"), gujarat)
                .retire(lakshadweep)
                .add(next.nextId(), india, "state", "Ladakh North")
                .build();
    }

    /**
     * Writes every shipped place list, {@link #realNext()} and its boundaries with their {@code places.sha256} into a
     * folder, for {@code -Datlas.placesDir=<folder>}. Argument: the folder.
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: UpdatePlaces <folder>");
            System.exit(2);
            return;
        }
        Path folder = Path.of(args[0]).toAbsolutePath();
        List<Built> chain = new ArrayList<>(real());
        Built next = realNext();
        chain.add(next);
        write(folder, chain.toArray(new Built[0]));
        System.out.println("Wrote " + chain.stream().map(Built::jsonName).collect(Collectors.joining(", ")) + ", "
                + next.binName() + " and " + PlaceBundles.CHECKSUMS + " to " + folder);
        next.changes().forEach(c -> System.out.println("  " + c.type() + " " + c.from() + " " + c.to() + ": " + c.detail()));
        System.out.println("Run the app with -Datlas.placesDir=" + folder);
    }
}
