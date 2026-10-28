package com.butterflyatlas.placeimporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The new place list compared with the one it is based on, by id (5c contract §3): renames (same id, new name), moves
 * (same id, new parent) and adds (new id) are found; every id gone must be a merge, split or retire of
 * {@code place-changes.csv}, and every line must agree with the lists. The {@code changes} the list carries come out in
 * the order merge, split, retire, rename, move, add (ids ascending within a type). Every place keeps the other names it
 * had, a renamed place its old name too, and a merged place's names go to the place it merged into.
 */
public final class PlaceDiff {

    private static final List<String> ORDER = List.of("merge", "split", "retire", "rename", "move", "add");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A place list as written ({@code places-<version>.json}): its version and places. */
    public record Previous(String version, List<BundlePlace> places) {
    }

    /** One entry of the list's {@code changes}, in place ids. */
    public record Change(String type, List<String> from, List<String> to, String detail) {
    }

    /** The changes, the other names each place has in the new list, and the problems that stop the build. */
    public record Result(List<Change> changes, Map<String, List<String>> aliases, List<String> failures) {

        /** "1 merged, 0 split, 0 retired, 0 renamed, 0 moved, 0 added". */
        public String counts() {
            int[] n = new int[ORDER.size()];
            for (Change c : changes) {
                int kind = ORDER.indexOf(c.type());
                n[kind] += c.type().equals("merge") ? c.from().size() : c.type().equals("add") ? c.to().size() : 1;
            }
            return String.format(Locale.ROOT, "%d merged, %d split, %d retired, %d renamed, %d moved, %d added", n[0], n[1],
                    n[2], n[3], n[4], n[5]);
        }
    }

    private PlaceDiff() {
    }

    /** The ids of every place list in {@code out} older than {@code before}'s version, that {@code before} lacks. */
    public static Set<String> givenUp(Path out, Previous before) throws IOException {
        Set<String> ids = new java.util.TreeSet<>();
        if (out == null || !Files.isDirectory(out)) {
            return ids;
        }
        try (java.util.stream.Stream<Path> files = Files.list(out)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.matches("places-\\d{4}-\\d{2}-\\d{2}\\.json")
                        && name.substring(7, 17).compareTo(before.version()) < 0) {
                    read(file).places().forEach(p -> ids.add(p.id()));
                }
            }
        }
        before.places().forEach(p -> ids.remove(p.id()));
        return ids;
    }

    /** Reads a place list the importer wrote. */
    public static Previous read(Path json) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(json));
        List<BundlePlace> places = new ArrayList<>();
        for (JsonNode p : root.path("places")) {
            List<String> aliases = new ArrayList<>();
            p.path("aliases").forEach(a -> aliases.add(a.asText()));
            Map<String, String> codes = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = p.path("codes").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> code = it.next();
                codes.put(code.getKey(), code.getValue().asText());
            }
            places.add(new BundlePlace(p.path("id").asText(), p.path("parent").isNull() ? null : p.path("parent").asText(),
                    p.path("level").asText(), p.path("name").asText(), aliases, codes));
        }
        return new Previous(root.path("version").asText(), places);
    }

    /** Compares {@code now} with {@code before}, given the version's lines of the change table and the registry. */
    public static Result compare(Previous before, List<BundlePlace> now, List<PlaceChanges.Line> lines,
                                 PlaceIdRegistry registry) {
        return compare(before, now, lines, registry, Set.of());
    }

    /**
     * As {@link #compare(Previous, List, List, PlaceIdRegistry)}; {@code givenUp}: the ids of the lists before
     * {@code before} that it no longer has (a place merged, split or retired earlier). One of them in the new list stops
     * the build: ids are never used twice, and the app refuses such a list.
     */
    public static Result compare(Previous before, List<BundlePlace> now, List<PlaceChanges.Line> lines,
                                 PlaceIdRegistry registry, Set<String> givenUp) {
        Map<String, BundlePlace> old = new LinkedHashMap<>();
        before.places().forEach(p -> old.put(p.id(), p));
        Map<String, BundlePlace> next = new LinkedHashMap<>();
        now.forEach(p -> next.put(p.id(), p));
        List<String> failures = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        Set<String> accounted = new LinkedHashSet<>();
        Map<String, Set<String>> carried = new HashMap<>();
        for (PlaceChanges.Line line : lines) {
            List<String> from = ids(line.from(), registry, failures);
            List<String> to = ids(line.to(), registry, failures);
            if (from == null || to == null) {
                continue;
            }
            for (String id : from) {
                if (!old.containsKey(id)) {
                    failures.add("place-changes.csv " + verb(line) + " " + id + ", which is not in the list " + before.version());
                } else if (next.containsKey(id) && !(line.type().equals(PlaceChanges.SPLIT) && to.contains(id))) {
                    failures.add(named(old.get(id)) + " is still in the new list, but place-changes.csv " + verb(line) + " it");
                }
                if (!accounted.add(id)) {
                    failures.add(named(old.get(id)) + " is in two lines of place-changes.csv");
                }
            }
            for (String id : to) {
                if (!next.containsKey(id)) {
                    failures.add("place-changes.csv " + verb(line) + " " + String.join(", ", line.from()) + " into " + id
                            + ", which is not in the new list");
                    continue;
                }
                for (String gone : from) {
                    if (old.containsKey(gone) && !old.get(gone).level().equals(next.get(id).level())) {
                        failures.add("place-changes.csv " + verb(line) + " " + named(old.get(gone)) + " (a "
                                + old.get(gone).level() + ") into " + named(next.get(id)) + " (a " + next.get(id).level()
                                + "): a merge or split stays within one level");
                    }
                }
            }
            String names = String.join(", ", from.stream().map(id -> name(old, id)).toList());
            switch (line.type()) {
                case PlaceChanges.MERGE -> {
                    String into = to.get(0);
                    for (String id : from) {
                        if (old.containsKey(id)) {
                            Set<String> gained = carried.computeIfAbsent(into, k -> new LinkedHashSet<>());
                            gained.add(old.get(id).name());
                            gained.addAll(old.get(id).aliases());
                        }
                    }
                    changes.add(new Change("merge", sorted(from), to, names + " merged into " + name(next, into)));
                }
                case PlaceChanges.SPLIT -> changes.add(new Change("split", from, to, names + " split into "
                        + String.join(", ", to.stream().map(id -> name(next, id)).toList())));
                default -> changes.add(new Change("retire", from, List.of(), names + " retired"));
            }
        }
        for (BundlePlace p : before.places()) {
            if (!next.containsKey(p.id()) && !accounted.contains(p.id())) {
                failures.add(named(p) + " is not in the new list: add a merge, split or retire line to place-changes.csv");
            }
        }
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (BundlePlace p : now) {
            BundlePlace was = old.get(p.id());
            List<String> candidates = new ArrayList<>(p.aliases());
            if (was == null) {
                if (givenUp.contains(p.id())) {
                    failures.add(named(p) + " was given up by an earlier place list and is in the sources again: a place"
                            + " id is never used twice; check the sources (an earlier merge stays applied by itself)");
                }
                changes.add(new Change("add", List.of(), List.of(p.id()), p.name() + " added"));
            } else {
                if (!was.level().equals(p.level())) {
                    failures.add(named(was) + " changes level from " + was.level() + " to " + p.level());
                }
                if (!was.name().equals(p.name())) {
                    changes.add(new Change("rename", List.of(p.id()), List.of(p.id()), was.name() + " → " + p.name()));
                    candidates.add(was.name());
                }
                if (!Objects.equals(was.parent(), p.parent())) {
                    changes.add(new Change("move", List.of(p.id()), List.of(p.id()), p.name() + ": " + name(old, was.parent())
                            + " → " + name(next, p.parent())));
                }
                candidates.addAll(was.aliases());
            }
            candidates.addAll(carried.getOrDefault(p.id(), Set.of()));
            aliases.put(p.id(), AtlasBuilder.aliases(p.name(), candidates));
        }
        changes.sort(Comparator.comparingInt((Change c) -> ORDER.indexOf(c.type()))
                .thenComparingInt(c -> number(c.from().isEmpty() ? c.to() : c.from())));
        return new Result(List.copyOf(changes), aliases, List.copyOf(failures));
    }

    /** The ids of these registry keys; a key the registry does not have is a failure (null). */
    private static List<String> ids(List<String> keys, PlaceIdRegistry registry, List<String> failures) {
        List<String> ids = new ArrayList<>();
        for (String key : keys) {
            String id = registry.find(key).orElse(null);
            if (id == null) {
                failures.add("place-changes.csv names " + key + ", which has no place id in the registry");
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    private static List<String> sorted(List<String> ids) {
        return ids.stream().sorted(Comparator.comparingInt(id -> Integer.parseInt(id.substring(1)))).toList();
    }

    private static int number(List<String> ids) {
        return ids.isEmpty() ? 0 : Integer.parseInt(ids.get(0).substring(1));
    }

    private static String verb(PlaceChanges.Line line) {
        return switch (line.type()) {
            case PlaceChanges.MERGE -> "merges";
            case PlaceChanges.SPLIT -> "splits";
            default -> "retires";
        };
    }

    private static String name(Map<String, BundlePlace> places, String id) {
        if (id == null) {
            return "(top level)";
        }
        BundlePlace p = places.get(id);
        return p == null ? id : p.name();
    }

    private static String named(BundlePlace p) {
        return p.id() + " " + p.name();
    }
}
