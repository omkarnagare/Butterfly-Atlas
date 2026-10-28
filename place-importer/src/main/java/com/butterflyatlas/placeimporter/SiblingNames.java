package com.butterflyatlas.placeimporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tells apart places that share a name with a sibling (3c-2 contract §0). First the reviewed override table
 * ({@code name-overrides.csv}: {@code key,name,note}) names places outright. Then, for a state outside India whose
 * name equals a sibling's (ignoring case and accents, as search does): Natural Earth's own {@code name} if no other sibling has or takes it; else
 * the name qualified with Natural Earth's {@code type_en} in lower case ("Cork (city)", "Cork (county)") if that is
 * unique; else the name stays (and {@link BundleWriter#structure} fails the build). A renamed place keeps its old name
 * as an alias. Places keep their order, so the tree (and the boundary file, written in the same order) is unchanged.
 * Last, reviewed other spellings ({@code extra-aliases.csv}: {@code key,alias,note}, e.g. former names such as
 * "Bombay") join the places' aliases.
 */
public final class SiblingNames {

    /** Natural Earth's own name and {@code type_en} of a foreign state. */
    public record Source(String name, String type) {
    }

    /** A reviewed name for the place with this source key. */
    public record Override(String key, String name) {
    }

    /** A reviewed other spelling of the place with this source key. */
    public record Alias(String key, String alias) {
    }

    public record Rename(String key, String parentKey, String from, String to) {
    }

    public record Result(List<AtlasBuilder.Place> places, List<Rename> renames, List<Override> unusedOverrides,
                         List<Alias> unusedAliases) {
    }

    private SiblingNames() {
    }

    public static List<Override> readOverrides(InputStream in) throws IOException {
        List<List<String>> rows = Lgd.csv(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<Override> overrides = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (List<String> row : rows.subList(Math.min(1, rows.size()), rows.size())) {
            if (row.size() >= 2 && !row.get(0).isBlank() && !row.get(1).isBlank()) {
                if (!keys.add(row.get(0).trim())) {
                    throw new IllegalArgumentException("name-overrides.csv names " + row.get(0).trim() + " twice");
                }
                overrides.add(new Override(row.get(0).trim(), row.get(1).trim()));
            }
        }
        return overrides;
    }

    public static List<Alias> readAliases(InputStream in) throws IOException {
        List<List<String>> rows = Lgd.csv(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<Alias> aliases = new ArrayList<>();
        for (List<String> row : rows.subList(Math.min(1, rows.size()), rows.size())) {
            if (row.size() >= 2 && !row.get(0).isBlank() && !row.get(1).isBlank()) {
                aliases.add(new Alias(row.get(0).trim(), row.get(1).trim()));
            }
        }
        return aliases;
    }

    /** The build failures for overrides whose key is not in the data (a typo, or a key the sources no longer have). */
    static List<String> unknownOverrides(List<Override> unused) {
        return unused.stream().map(o -> "name-overrides.csv names " + o.key() + " (" + o.name()
                + "), which is not in the data").toList();
    }

    public static Result resolve(List<AtlasBuilder.Place> places, Map<String, Source> sources, List<Override> overrides) {
        return resolve(places, sources, overrides, List.of());
    }

    /** {@code sources} holds Natural Earth's details for the foreign states the rules may rename. */
    public static Result resolve(List<AtlasBuilder.Place> places, Map<String, Source> sources, List<Override> overrides,
                                 List<Alias> extraAliases) {
        Map<String, String> names = new HashMap<>();
        places.forEach(p -> names.put(p.key(), p.name()));
        Set<String> fixed = new HashSet<>();
        List<Override> unused = new ArrayList<>();
        for (Override o : overrides) {
            if (names.containsKey(o.key())) {
                names.put(o.key(), o.name());
                fixed.add(o.key());
            } else {
                unused.add(o);
            }
        }

        Map<String, List<AtlasBuilder.Place>> siblings = new LinkedHashMap<>();
        places.forEach(p -> siblings.computeIfAbsent(String.valueOf(p.parentKey()), k -> new ArrayList<>()).add(p));
        for (List<AtlasBuilder.Place> family : siblings.values()) {
            Map<String, List<AtlasBuilder.Place>> byName = new LinkedHashMap<>();
            family.forEach(p -> byName.computeIfAbsent(lower(names.get(p.key())), k -> new ArrayList<>()).add(p));
            for (List<AtlasBuilder.Place> group : byName.values()) {
                if (group.size() > 1) {
                    tellApart(family, group, names, sources, fixed);
                }
            }
        }

        Map<String, List<String>> extra = new HashMap<>();
        List<Alias> unusedAliases = new ArrayList<>();
        for (Alias a : extraAliases) {
            if (names.containsKey(a.key())) {
                extra.computeIfAbsent(a.key(), k -> new ArrayList<>()).add(a.alias());
            } else {
                unusedAliases.add(a);
            }
        }

        List<AtlasBuilder.Place> result = new ArrayList<>();
        List<Rename> renames = new ArrayList<>();
        for (AtlasBuilder.Place p : places) {
            String name = names.get(p.key());
            if (name.equals(p.name()) && !extra.containsKey(p.key())) {
                result.add(p);
                continue;
            }
            List<String> spellings = new ArrayList<>();
            if (!name.equals(p.name())) {
                spellings.add(p.name());
                renames.add(new Rename(p.key(), p.parentKey(), p.name(), name));
            }
            spellings.addAll(p.aliases());
            spellings.addAll(extra.getOrDefault(p.key(), List.of()));
            result.add(new AtlasBuilder.Place(p.key(), p.parentKey(), p.level(), name, AtlasBuilder.aliases(name, spellings),
                    p.codes()));
        }
        return new Result(result, renames, unused, unusedAliases);
    }

    private static void tellApart(List<AtlasBuilder.Place> family, List<AtlasBuilder.Place> group, Map<String, String> names,
                                  Map<String, Source> sources, Set<String> fixed) {
        List<AtlasBuilder.Place> movable = group.stream().filter(p -> !fixed.contains(p.key())
                && p.level().equals("state") && !AtlasBuilder.INDIA.equals(p.parentKey()) && sources.containsKey(p.key())).toList();
        Set<String> taken = new HashSet<>();   // names of the siblings that keep theirs
        family.stream().filter(p -> !movable.contains(p)).forEach(p -> taken.add(lower(names.get(p.key()))));

        Map<String, String> own = new LinkedHashMap<>();
        movable.forEach(p -> own.put(p.key(), blankToNull(sources.get(p.key()).name())));
        List<AtlasBuilder.Place> left = new ArrayList<>();
        for (AtlasBuilder.Place p : movable) {
            String candidate = own.get(p.key());
            if (candidate != null && !taken.contains(lower(candidate)) && count(own.values(), candidate) == 1) {
                names.put(p.key(), candidate);
            } else {
                left.add(p);
            }
        }
        movable.stream().filter(p -> !left.contains(p)).forEach(p -> taken.add(lower(names.get(p.key()))));

        Map<String, String> typed = new LinkedHashMap<>();
        for (AtlasBuilder.Place p : left) {
            String type = blankToNull(sources.get(p.key()).type());
            typed.put(p.key(), type == null ? null : names.get(p.key()) + " (" + type.toLowerCase(Locale.ROOT) + ")");
        }
        for (AtlasBuilder.Place p : left) {
            String candidate = typed.get(p.key());
            if (candidate != null && !taken.contains(lower(candidate)) && count(typed.values(), candidate) == 1) {
                names.put(p.key(), candidate);
            }
        }
    }

    private static long count(Iterable<String> values, String value) {
        long n = 0;
        for (String v : values) {
            if (v != null && lower(v).equals(lower(value))) {
                n++;
            }
        }
        return n;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** Accents and case left out, as search compares names: "Krsko" and "Krško" are the same name. */
    static String lower(String name) {
        return Names.fold(name);
    }
}
