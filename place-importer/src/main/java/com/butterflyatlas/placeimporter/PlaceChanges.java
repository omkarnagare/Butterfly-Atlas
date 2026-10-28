package com.butterflyatlas.placeimporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reviewed table of merges, splits and retirements ({@code place-changes.csv}, 5c contract §3): one line per change,
 * {@code version,type,from,to,reason}, with registry keys ({@code ne:SVN-939}) separated by {@code ;}. Only the lines of
 * the version being built count. A merge is applied to the build (the merged place's area becomes its survivor's); a
 * split or retire declares what the sources did, and is checked against them.
 */
public final class PlaceChanges {

    public static final String MERGE = "merge";
    public static final String SPLIT = "split";
    public static final String RETIRE = "retire";
    private static final Set<String> TYPES = Set.of(MERGE, SPLIT, RETIRE);

    /** One line: its type, the keys it changes and what they became, and why. */
    public record Line(String type, List<String> from, List<String> to, String reason) {

        public Line {
            from = List.copyOf(from);
            to = List.copyOf(to);
        }
    }

    private PlaceChanges() {
    }

    /** The lines of {@code version}, in the table's order; a line that breaks the format is refused with its number. */
    public static List<Line> read(InputStream in, String version) throws IOException {
        return read(in, version, false);
    }

    /**
     * The lines of {@code version} and of every earlier version: an earlier merge still applies to the build (its place
     * stays merged in every later list; the sources still have it), though only this version's lines are its changes.
     */
    public static List<Line> readUpTo(InputStream in, String version) throws IOException {
        return read(in, version, true);
    }

    private static List<Line> read(InputStream in, String version, boolean earlier) throws IOException {
        List<List<String>> rows = Lgd.csv(new InputStreamReader(in, StandardCharsets.UTF_8));
        if (rows.isEmpty() || !rows.get(0).equals(List.of("version", "type", "from", "to", "reason"))) {
            throw new IllegalArgumentException("place-changes.csv must start with the header version,type,from,to,reason");
        }
        List<Line> lines = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            String where = "place-changes.csv line " + (i + 1);
            if (row.size() != 5) {
                throw new IllegalArgumentException(where + " has " + row.size() + " fields; it needs 5");
            }
            String lineVersion = row.get(0).trim();
            if (earlier ? lineVersion.compareTo(version) > 0 : !lineVersion.equals(version)) {
                continue;
            }
            String type = row.get(1).trim();
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException(where + " has the type " + type + "; use merge, split or retire");
            }
            List<String> from = keys(row.get(2));
            List<String> to = keys(row.get(3));
            String reason = row.get(4).trim();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException(where + " gives no reason");
            }
            boolean shaped = switch (type) {
                case MERGE -> !from.isEmpty() && to.size() == 1;
                case SPLIT -> from.size() == 1 && to.size() >= 2;
                default -> from.size() == 1 && to.isEmpty();
            };
            if (!shaped) {
                throw new IllegalArgumentException(where + ": a merge names places and the one they merge into, a split"
                        + " one place and two or more, a retire one place and nothing");
            }
            for (String key : java.util.stream.Stream.concat(from.stream(), to.stream()).toList()) {
                if (!key.matches("(iso|lgd|gb|ne|ne0):\\S+")) {
                    throw new IllegalArgumentException(where + " names " + key + ", which is not a registry key");
                }
            }
            lines.add(new Line(type, from, to, reason));
        }
        return lines;
    }

    /**
     * Where each merged key's area goes: the key it merges into, followed to the end of a chain of merges in the table
     * (a merge into a place that is merged too). A cycle is refused with the place named.
     */
    public static Map<String, String> mergedInto(List<Line> lines) {
        Map<String, String> direct = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.type().equals(MERGE)) {
                for (String key : line.from()) {
                    direct.put(key, line.to().get(0));
                }
            }
        }
        Map<String, String> into = new HashMap<>();
        for (String key : direct.keySet()) {
            String at = key;
            for (int hops = 0; direct.containsKey(at); hops++) {
                if (hops > direct.size()) {
                    throw new IllegalArgumentException("place-changes.csv merges " + key + " in a circle");
                }
                at = direct.get(at);
            }
            into.put(key, at);
        }
        return into;
    }

    private static List<String> keys(String field) {
        List<String> keys = new ArrayList<>();
        for (String key : field.split(";")) {
            if (!key.isBlank()) {
                keys.add(key.trim());
            }
        }
        return keys;
    }
}
