package com.butterflyatlas.app.places;

import com.butterflyatlas.app.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Finding places by name (3c-2 contract §1 "Places"; design C8). Search runs in memory over every active place's name
 * and other names, folded (accents and case ignored); the list is read once per library and again after the user adds,
 * renames or deletes a place.
 */
public final class PlaceSearch {

    public static final int SEARCH_LIMIT = 20;
    static final List<String> LEVELS = List.of("district", "state", "country");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A place as the picker shows it: {@code path} from the place up to its country; {@code mine}: added by the user;
     * {@code former}: no longer active (only places shown elsewhere are: search and the picker list active ones), its
     * {@code name} and its {@code path} labelled "(former district)".
     */
    public record Place(String id, String name, List<String> path, String level, boolean mine, boolean former) {

        public Place {
            path = List.copyOf(path);
        }

        /** An active place. */
        public Place(String id, String name, List<String> path, String level, boolean mine) {
            this(id, name, path, level, mine, false);
        }
    }

    /**
     * An inactive place matching a search (5c contract §10), never pickable: {@code place} labelled ("Chittoor (former
     * district)") and what it became: a merged place's survivor, a split place's places still active, nothing for a
     * retired one.
     */
    public record FormerMatch(Place place, List<Place> now) {

        public FormerMatch {
            now = List.copyOf(now);
        }
    }

    /** One searchable place: its name and other names folded, and what orders the results. */
    private record Entry(Place place, String name, List<String> aliases, boolean india, int level) {
    }

    /**
     * The searchable places of one library's database, as they were at {@code generation}: the active ones, the inactive
     * ones (for {@link FormerMatch}), every place by id and each inactive place's survivor ({@code replaced_by}).
     */
    private record Index(Database database, long generation, List<Entry> entries, List<Entry> former,
                         Map<String, Place> places, Map<String, String> replacedBy) {
    }

    /** The most former matches a search gives. */
    public static final int FORMER_LIMIT = 5;

    /** The open library's database (another library, another database: its own index). */
    private final Supplier<Database> database;
    /** What each split place was split into, from every shipped list's changes. */
    private final Map<String, List<String>> splitInto;
    /** Moves on after every change to the user's places, so the next search reads the places again. */
    private final AtomicLong generation = new AtomicLong();
    private volatile Index index;

    public PlaceSearch(Supplier<Database> database) {
        this(database, null);
    }

    /** With the shipped place lists, whose splits say what a split place became ({@link #formerMatches}). */
    public PlaceSearch(Supplier<Database> database, PlaceBundles bundles) {
        this.database = database;
        Map<String, List<String>> splits = new HashMap<>();
        if (bundles != null) {
            for (PlaceBundles.Shipped list : bundles.all()) {
                for (PlaceChange change : list.changes()) {
                    if (change.type().equals(PlaceChange.SPLIT)) {
                        splits.put(change.from().get(0), change.to());
                    }
                }
            }
        }
        this.splitInto = Map.copyOf(splits);
    }

    /**
     * Places matching {@code query} (trimmed; under 2 characters finds nothing), at most {@value #SEARCH_LIMIT}: exact
     * name, then name prefix, then a word of the name, then another name (exact or prefix), then contains; within each,
     * places in India first, then districts, states, countries, then by name. Retired places and countries that have
     * states are never found. {@code level} (optional) keeps one level only.
     */
    public List<Place> search(String query, String level) {
        if (level != null && !LEVELS.contains(level)) {
            throw PlaceRules.invalid("Search districts, states or countries.");
        }
        String q = fold(query == null ? "" : query);
        if (q.length() < 2) {
            return List.of();
        }
        return hits(index().entries(), q, level).stream().limit(SEARCH_LIMIT).map(Entry::place).toList();
    }

    /**
     * Inactive places matching {@code query} as {@link #search} matches, at most {@value #FORMER_LIMIT}, each with what
     * it became (5c contract §10): never offered to pick, they tell the user where the place went.
     */
    public List<FormerMatch> formerMatches(String query, String level) {
        if (level != null && !LEVELS.contains(level)) {
            throw PlaceRules.invalid("Search districts, states or countries.");
        }
        String q = fold(query == null ? "" : query);
        if (q.length() < 2) {
            return List.of();
        }
        Index index = index();
        List<FormerMatch> matches = new ArrayList<>();
        for (Entry entry : hits(index.former(), q, level)) {
            if (matches.size() == FORMER_LIMIT) {
                break;
            }
            matches.add(new FormerMatch(entry.place(), now(index, entry.place().id())));
        }
        return matches;
    }

    /** What an inactive place became: its last survivor, else its split's places (each followed so), else nothing. */
    private List<Place> now(Index index, String id) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        follow(index, id, ids, 0);
        return ids.stream().map(index.places()::get).filter(p -> p != null && !p.former()).toList();
    }

    private void follow(Index index, String id, Set<String> into, int depth) {
        Place place = index.places().get(id);
        if (place == null || depth > 10) {
            return;
        }
        if (!place.former()) {
            into.add(id);
        } else if (index.replacedBy().containsKey(id)) {
            follow(index, index.replacedBy().get(id), into, depth + 1);
        } else {
            for (String target : splitInto.getOrDefault(id, List.of())) {
                if (!target.equals(id)) {
                    follow(index, target, into, depth + 1);
                }
            }
        }
    }

    private static List<Entry> hits(List<Entry> entries, String q, String level) {
        record Hit(Entry entry, int group) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : entries) {
            if (level != null && !entry.place().level().equals(level)) {
                continue;
            }
            int group = group(entry, q);
            if (group >= 0) {
                hits.add(new Hit(entry, group));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::group).thenComparing(h -> !h.entry().india())
                .thenComparingInt(h -> h.entry().level()).thenComparing(h -> h.entry().name())
                .thenComparing(h -> h.entry().place().id()));
        return hits.stream().map(Hit::entry).toList();
    }

    /** The user's places changed (after the change committed): the next search reads the places again. */
    void changed() {
        generation.incrementAndGet();
    }

    /** 0 exact name, 1 name prefix, 2 word prefix in the name, 3 another name (exact or prefix), 4 contains; -1 none. */
    private static int group(Entry entry, String q) {
        String name = entry.name();
        if (name.equals(q)) {
            return 0;
        }
        if (name.startsWith(q)) {
            return 1;
        }
        for (int i = name.indexOf(q, 1); i > 0; i = name.indexOf(q, i + 1)) {
            if (!Character.isLetterOrDigit(name.charAt(i - 1))) {
                return 2;
            }
        }
        if (entry.aliases().stream().anyMatch(a -> a.startsWith(q))) {
            return 3;
        }
        if (name.contains(q) || entry.aliases().stream().anyMatch(a -> a.contains(q))) {
            return 4;
        }
        return -1;
    }

    /** Accents and case left out ("Púnchal" → "punchal"), spaces trimmed and single. */
    public static String fold(String text) {
        String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return WHITESPACE.matcher(plain.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private Index index() {
        Database db = database.get();
        long now = generation.get();   // taken before reading: a change committed meanwhile makes the next call read again
        Index current = index;
        if (current != null && current.database() == db && current.generation() == now) {
            return current;
        }
        synchronized (this) {
            current = index;
            if (current == null || current.database() != db || current.generation() != now) {
                current = db.read(c -> read(c, db, now));
                index = current;
            }
            return current;
        }
    }

    private static Index read(Connection c, Database db, long generation) throws SQLException {
        record Raw(String id, String parent, String level, String name, String source, boolean active, String replacedBy) {
        }
        Map<String, Raw> raws = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, parent_id, level, name, source, active, replaced_by FROM place");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                raws.put(rs.getString(1), new Raw(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6) == 1, rs.getString(7)));
            }
        }
        Map<String, List<String>> aliases = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT place_id, name FROM place_alias");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                aliases.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(fold(rs.getString(2)));
            }
        }
        Set<String> withStates = new HashSet<>();
        String india = null;
        for (Raw raw : raws.values()) {
            if (raw.level().equals("state") && raw.active() && raw.parent() != null) {
                withStates.add(raw.parent());
            }
            if (raw.level().equals("country") && raw.parent() == null && PlaceStore.isIndia(raw.name())) {
                india = raw.id();
            }
        }
        List<Entry> entries = new ArrayList<>();
        List<Entry> former = new ArrayList<>();
        Map<String, Place> places = new HashMap<>();
        Map<String, String> replacedBy = new HashMap<>();
        for (Raw raw : raws.values()) {
            List<Raw> up = new ArrayList<>(3);
            for (Raw at = raw; at != null; at = at.parent() == null ? null : raws.get(at.parent())) {
                up.add(at);
            }
            String root = up.getLast().id();
            String country = up.getLast().name();
            List<String> path = new ArrayList<>(3);
            for (Raw at : up) {
                path.add(PlaceStore.label(at.name(), at.level(), at.active(), country));
            }
            Place place = new Place(raw.id(), path.getFirst(), path, raw.level(),
                    PlaceRules.USER.equals(raw.source()), !raw.active());
            places.put(raw.id(), place);
            if (raw.replacedBy() != null) {
                replacedBy.put(raw.id(), raw.replacedBy());
            }
            Entry entry = new Entry(place, fold(raw.name()), aliases.getOrDefault(raw.id(), List.of()), root.equals(india),
                    LEVELS.indexOf(raw.level()));
            if (!raw.active()) {
                former.add(entry);
            } else if (!(raw.level().equals("country") && withStates.contains(raw.id()))) {
                entries.add(entry);
            }
        }
        return new Index(db, generation, List.copyOf(entries), List.copyOf(former), Map.copyOf(places),
                Map.copyOf(replacedBy));
    }
}
