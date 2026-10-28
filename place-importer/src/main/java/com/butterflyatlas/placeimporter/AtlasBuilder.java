package com.butterflyatlas.placeimporter;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Builds the place tree and its leaf boundaries from the sources (design C1–C3):
 * <ul>
 * <li>countries: Natural Earth admin-0, India's point of view (so disputed areas follow India's view);</li>
 * <li>India: geoBoundaries states/UTs and districts inside India's outline; LGD names and codes when given;</li>
 * <li>elsewhere: Natural Earth admin-1 states inside their country's outline; countries without states are leaves.</li>
 * </ul>
 * Every boundary is noded together into one clean coverage ({@link Partition}). Inside India, an area no district
 * covers joins the neighbouring district of its state when it is small (slivers between the sources), and becomes
 * an area of its state (geoBoundaries ADM1) when it is {@link #LARGE_GAP_KM2} or more — e.g. the parts of Jammu and
 * Kashmir and of Ladakh for which geoBoundaries has no district ("DATA NOT AVAILABLE").
 */
public final class AtlasBuilder {

    public static final String INDIA = "iso:IN";
    /** Visvalingam–Whyatt tolerances in degrees: about 80 m median deviation in India, about 1 km elsewhere. */
    public static final double INDIA_TOLERANCE = 0.001;
    public static final double WORLD_TOLERANCE = 0.015;
    public static final double LARGE_GAP_KM2 = 2000;
    /**
     * Source checks. The sources' borders and coastlines differ (geoBoundaries' Lakshadweep district includes the
     * lagoons, its state only the islands: 25% of the district lies within 2 km of its state), so these catch gross
     * errors only: a district goes into a state holding at least {@code MIN_IN_STATE} of it (inside or within 2 km,
     * {@link Checks#NEAR_DEGREES}), and an Indian unit has at most {@code MAX_IN_OTHER_COUNTRIES} of its area in
     * other countries and some part in or near India.
     */
    public static final double MIN_IN_STATE = 0.2;
    public static final double MAX_IN_OTHER_COUNTRIES = 0.1;
    /**
     * An Indian island simplification may drop: at most this large and within {@link Lookup#COAST_METRES} of the rest
     * of its place, so a photo taken on it still finds the place through the app's coast rule.
     */
    public static final double MAX_LOST_ISLAND_KM2 = 0.05;
    /**
     * LGD districts that several boundary districts may be, reviewed: Puducherry (600), which geoBoundaries splits
     * into Puducherry, Yanam and Mahe (LGD lists Yanam and Mahe as its sub-districts). Any other such merge fails.
     */
    static final Set<String> REVIEWED_MERGES = Set.of("600");
    static final String NO_DATA = "DATA NOT AVAILABLE";

    /** A place keyed by its source key (the id registry gives it its id). */
    public record Place(String key, String parentKey, String level, String name, List<String> aliases,
                        Map<String, String> codes) {
    }

    /** Natural Earth admin-0 (India's view) and admin-1; geoBoundaries India ADM1 and ADM2. */
    public record Sources(List<GeoJson.Feature> countries, List<GeoJson.Feature> states,
                          List<GeoJson.Feature> indiaStates, List<GeoJson.Feature> indiaDistricts) {
    }

    /**
     * {@code places} parents first; {@code leaves} simplified; {@code dropped} the islands simplification removed;
     * {@code lgd} null without an LGD list; {@code renames} the places renamed to tell siblings apart
     * ({@link SiblingNames}); {@code unusedNameOverrides} and {@code unusedAliases} the name overrides and other
     * spellings for keys not in the data.
     */
    public record Atlas(List<Place> places, List<Partition.Piece> leaves, List<Partition.Gap> gaps,
                        List<Partition.Dropped> dropped, Lgd.Match lgd, List<String> failures,
                        List<SiblingNames.Rename> renames, List<SiblingNames.Override> unusedNameOverrides,
                        List<SiblingNames.Alias> unusedAliases) {
    }

    private record Unit(String key, List<String> states, String name, Geometry geometry) {
    }

    private AtlasBuilder() {
    }

    public static Atlas build(Sources sources, List<Lgd.District> lgd, List<Lgd.Override> overrides) {
        return build(sources, lgd, overrides, List.of());
    }

    public static Atlas build(Sources sources, List<Lgd.District> lgd, List<Lgd.Override> overrides,
                              List<SiblingNames.Override> nameOverrides) {
        return build(sources, lgd, overrides, nameOverrides, List.of());
    }

    public static Atlas build(Sources sources, List<Lgd.District> lgd, List<Lgd.Override> overrides,
                              List<SiblingNames.Override> nameOverrides, List<SiblingNames.Alias> extraAliases) {
        return build(sources, lgd, overrides, nameOverrides, extraAliases, Map.of());
    }

    /**
     * As {@link #build(Sources, List, List, List, List)}, with the merges of {@code place-changes.csv} applied
     * ({@code mergedInto}: a merged key → the key it merges into, 5c contract §3): a merged place is not a place of the
     * list, its area is its survivor's and its children are under the survivor.
     */
    public static Atlas build(Sources sources, List<Lgd.District> lgd, List<Lgd.Override> overrides,
                              List<SiblingNames.Override> nameOverrides, List<SiblingNames.Alias> extraAliases,
                              Map<String, String> mergedInto) {
        Map<String, Place> places = new LinkedHashMap<>();
        Map<String, SiblingNames.Source> naturalEarth = new HashMap<>();
        List<String> failures = new ArrayList<>();

        // Countries. India first, so it wins wherever outlines touch.
        Map<String, String> countryKeyByA3 = countryKeys(sources.countries());
        PolygonIndex<String> countries = new PolygonIndex<>();
        Geometry india = null;
        for (int i = 0; i < sources.countries().size(); i++) {
            required(sources.countries().get(i), "Natural Earth country", i, "NAME", "ADM0_A3");
        }
        for (int i = 0; i < sources.states().size(); i++) {
            required(sources.states().get(i), "Natural Earth state", i, "name", "adm0_a3");
        }
        for (int i = 0; i < sources.indiaStates().size(); i++) {
            required(sources.indiaStates().get(i), "geoBoundaries state", i, "shapeID", "shapeName");
        }
        for (int i = 0; i < sources.indiaDistricts().size(); i++) {
            required(sources.indiaDistricts().get(i), "geoBoundaries district", i, "shapeID", "shapeName");
        }
        for (GeoJson.Feature f : sources.countries()) {
            if (f.properties().get("ADM0_A3").equals("IND")) {
                india = f.geometry();
                countries.add(f.geometry(), INDIA);
            }
        }
        if (india == null) {
            throw new IllegalArgumentException("The Natural Earth countries have no India (ADM0_A3 IND)");
        }
        Predicate<Coordinate> nearIndia = Checks.near(india);
        for (GeoJson.Feature f : sources.countries()) {
            Map<String, String> p = f.properties();
            String key = countryKeyByA3.get(p.get("ADM0_A3"));
            if (!key.equals(INDIA)) {
                countries.add(f.geometry(), key);
            }
            String name = p.getOrDefault("NAME_EN", p.get("NAME"));
            List<String> aliases = new ArrayList<>();
            if (key.equals(INDIA)) {
                aliases.add("Bharat");
            }
            aliases.addAll(List.of(p.getOrDefault("NAME", ""), p.getOrDefault("NAME_LONG", ""),
                    p.getOrDefault("ADMIN", ""), p.getOrDefault("FORMAL_EN", "")));
            Map<String, String> codes = new TreeMap<>();
            if (key.startsWith("iso:")) {
                codes.put("iso", key.substring(4));
            }
            places.put(key, new Place(key, null, "country", name, aliases(name, aliases), codes));
        }

        // States elsewhere. An admin-1 whose admin-0 is not a country in India's view joins the country containing it.
        Set<String> isoCount = new HashSet<>();
        Set<String> isoTwice = new HashSet<>();
        for (GeoJson.Feature f : sources.states()) {
            String iso = f.properties().getOrDefault("iso_3166_2", "");
            if (!isoCount.add(iso)) {
                isoTwice.add(iso);
            }
        }
        PolygonIndex<String> states = new PolygonIndex<>();
        Map<String, String> stateCountry = new HashMap<>();
        for (GeoJson.Feature f : sources.states()) {
            Map<String, String> p = f.properties();
            String a3 = p.get("adm0_a3");
            // An interior point decides (not the largest overlap): these few features (Kosovo's, Siachen, Somaliland,
            // Western Sahara, …) lie wholly inside one country of India's view; inside India they are dropped below.
            String country = countryKeyByA3.containsKey(a3) ? countryKeyByA3.get(a3)
                    : countries.first(f.geometry().getInteriorPoint());
            if (a3.equals("IND") || country == null || country.equals(INDIA)) {
                continue;
            }
            String name = p.getOrDefault("name_en", p.get("name"));
            if (name == null || name.isBlank()) {
                continue;   // a handful of unnamed admin-1 pieces (e.g. "RUS+99?"): their area joins a neighbour
            }
            String key = "ne:" + p.get("adm1_code");
            List<String> aliases = new ArrayList<>();
            aliases.add(p.getOrDefault("name", ""));
            aliases.addAll(List.of(p.getOrDefault("name_alt", "").split("\\|")));
            Map<String, String> codes = new TreeMap<>();
            String iso = p.getOrDefault("iso_3166_2", "");
            if (iso.matches("[A-Z]{2}-[A-Z0-9]{1,3}") && !isoTwice.contains(iso)) {
                codes.put("iso", iso);
            }
            codes.put("ne", p.get("adm1_code"));
            places.put(key, new Place(key, country, "state", name, aliases(name, aliases), codes));
            naturalEarth.put(key, new SiblingNames.Source(p.get("name"), p.get("type_en")));
            states.add(f.geometry(), key);
            stateCountry.put(key, country);
        }
        Set<String> countriesWithStates = new HashSet<>(stateCountry.values());

        // India's states and districts.
        List<Unit> indiaStates = new ArrayList<>();
        PolygonIndex<String> indiaStateIndex = new PolygonIndex<>();
        for (GeoJson.Feature f : sources.indiaStates()) {
            String iso = f.properties().getOrDefault("shapeISO", "");
            if (!iso.matches("IN-[A-Z]{2}")) {
                throw new IllegalArgumentException("geoBoundaries state " + f.properties().get("shapeName")
                        + " has no ISO code (shapeISO \"" + iso + "\")");
            }
            Unit state = new Unit("iso:" + iso, List.of(INDIA), f.properties().get("shapeName"), f.geometry());
            indiaStates.add(state);
            indiaStateIndex.add(state.geometry(), state.key());
            outsideIndia("State " + Names.plain(state.name()), state.geometry(), countries, nearIndia).ifPresent(failures::add);
        }
        Map<String, Predicate<Coordinate>> nearState = new HashMap<>();
        indiaStates.forEach(s -> nearState.put(s.key(), Checks.near(s.geometry())));
        List<Unit> districts = new ArrayList<>();
        for (GeoJson.Feature f : sources.indiaDistricts()) {
            String name = f.properties().get("shapeName");
            if (name.equals(NO_DATA)) {
                continue;
            }
            Map<String, Double> shares = new HashMap<>();
            Envelope box = f.geometry().getEnvelopeInternal().copy();
            box.expandBy(Checks.NEAR_DEGREES);
            for (Unit state : indiaStates) {
                if (state.geometry().getEnvelopeInternal().intersects(box)) {
                    double share = Checks.share(f.geometry(), nearState.get(state.key()));
                    if (share >= MIN_IN_STATE) {
                        shares.put(state.key(), share);
                    }
                }
            }
            List<String> candidates = shares.keySet().stream()
                    .sorted(Comparator.comparing((String k) -> -shares.get(k)).thenComparing(k -> k)).toList();
            String label = "District " + Names.plain(name);
            if (candidates.isEmpty()) {
                failures.add(String.format(Locale.ROOT, "%s lies outside every state (none holds %.0f%% of it)", label,
                        MIN_IN_STATE * 100));
            }
            outsideIndia(label, f.geometry(), countries, nearIndia).ifPresent(failures::add);
            if (!candidates.isEmpty()) {
                districts.add(new Unit(f.properties().get("shapeID"), candidates, name, f.geometry()));
            }
        }

        Lgd.Match match = null;
        if (lgd != null) {
            match = Lgd.match(
                    indiaStates.stream().map(s -> new Lgd.Boundary(s.key().substring(4), List.of(), s.name())).toList(),
                    districts.stream().map(d -> new Lgd.Boundary(d.key(),
                            d.states().stream().map(k -> k.substring(4)).toList(), d.name())).toList(),
                    lgd, overrides);
        }
        // The state is the LGD district's when it matched (one of the candidates), else the one holding most of it.
        Map<String, String> stateOf = new HashMap<>();
        for (Unit district : districts) {
            stateOf.put(district.key(), match != null && match.districtStates().containsKey(district.key())
                    ? "iso:" + match.districtStates().get(district.key()) : district.states().get(0));
        }
        for (Unit state : indiaStates) {
            String name = Names.plain(state.name());
            Map<String, String> codes = new TreeMap<>();
            codes.put("iso", state.key().substring(4));
            if (match != null && match.stateCodes().containsKey(state.key().substring(4))) {
                codes.put("lgd", match.stateCodes().get(state.key().substring(4)));
            }
            places.put(state.key(), new Place(state.key(), INDIA, "state", name, aliases(name, List.of(state.name())), codes));
        }
        PolygonIndex<String> districtIndex = new PolygonIndex<>();
        Map<String, String> districtState = new HashMap<>();
        Map<String, List<Unit>> unitsOfPlace = new LinkedHashMap<>();
        for (Unit district : districts) {
            Lgd.District official = match == null ? null : match.districts().get(district.key());
            String key = official == null ? "gb:" + district.key() : "lgd:" + official.code();
            String name = official == null ? Names.tidy(Names.plain(district.name())) : Names.tidy(official.name());
            Map<String, String> codes = new TreeMap<>();
            if (official != null) {
                codes.put("lgd", official.code());
            }
            codes.put("gb", district.key());
            List<String> spellings = new ArrayList<>();
            String parent = stateOf.get(district.key());
            // Several boundary districts that are one LGD district become one place (reviewed ones only, see below).
            // It keeps the first one's state and gb code: `codes` holds one string per kind (contract §2).
            Place merged = places.get(key);
            if (merged != null) {
                spellings.addAll(merged.aliases());
                codes = merged.codes();
                parent = merged.parentKey();
            }
            spellings.addAll(List.of(district.name(), Names.plain(district.name())));
            places.put(key, new Place(key, parent, "district", name, aliases(name, spellings), codes));
            districtIndex.add(district.geometry(), key);
            districtState.put(key, parent);
            unitsOfPlace.computeIfAbsent(key, k -> new ArrayList<>()).add(district);
        }
        for (List<Unit> units : unitsOfPlace.values()) {
            if (units.size() < 2) {
                continue;
            }
            Lgd.District official = match.districts().get(units.get(0).key());
            Set<String> statesOfUnits = new TreeSet<>();
            units.forEach(u -> statesOfUnits.add(stateOf.get(u.key())));
            String which = "Boundary districts " + String.join(", ", units.stream().map(Unit::name).toList())
                    + " are all LGD district " + official.code() + " (" + official.name() + ")";
            if (!REVIEWED_MERGES.contains(official.code())) {
                failures.add(which + "; only reviewed merges (" + String.join(", ", new TreeSet<>(REVIEWED_MERGES))
                        + ") may be one place");
            } else if (statesOfUnits.size() > 1) {
                failures.add(which + " but lie in different states (" + String.join(", ", statesOfUnits) + ")");
            }
        }

        // One coverage of the whole world, India's view.
        Partition.Labeller labeller = point -> {
            String country = countries.first(point);
            if (country == null) {
                return null;
            }
            if (country.equals(INDIA)) {
                String district = districtIndex.first(point);
                if (district != null) {
                    return new Partition.Label(district, districtState.get(district));
                }
                String state = indiaStateIndex.first(point);
                return new Partition.Label(null, state != null ? state : indiaStateIndex.nearest(point));
            }
            if (!countriesWithStates.contains(country)) {
                return new Partition.Label(country, "world");
            }
            for (String state : states.containing(point)) {
                if (stateCountry.get(state).equals(country)) {
                    return new Partition.Label(state, country);
                }
            }
            return new Partition.Label(null, country);
        };
        List<Geometry> lines = new ArrayList<>();
        for (List<GeoJson.Feature> list : List.of(sources.countries(), sources.states(), sources.indiaStates(),
                sources.indiaDistricts())) {
            for (GeoJson.Feature f : list) {
                if (!NO_DATA.equals(f.properties().get("shapeName"))) {
                    lines.add(f.geometry().getBoundary());
                }
            }
        }
        for (Map.Entry<String, String> merge : mergedInto.entrySet()) {
            // A merge whose place is not in the sources (any more) has nothing to do; one into a place they lack is wrong.
            if (places.containsKey(merge.getKey()) && !places.containsKey(merge.getValue())) {
                failures.add("place-changes.csv merges " + merge.getKey() + " into " + merge.getValue()
                        + ", which is not in the data");
            }
        }
        Partition.Labeller merging = mergedInto.isEmpty() ? labeller : point -> {
            Partition.Label label = labeller.label(point);
            if (label == null || label.leaf() == null || !mergedInto.containsKey(label.leaf())
                    || !places.containsKey(mergedInto.get(label.leaf()))) {
                return label;
            }
            String into = mergedInto.get(label.leaf());
            String parent = places.get(into).parentKey();
            return new Partition.Label(into, parent == null ? "world" : parent);
        };
        Partition.Result result = Partition.build(lines, merging, LARGE_GAP_KM2);
        Set<String> indian = new HashSet<>(districtState.keySet());
        indiaStates.forEach(s -> indian.add(s.key()));
        Partition.Simplified simplified = Partition.simplify(result.pieces(),
                key -> indian.contains(key) ? INDIA_TOLERANCE : WORLD_TOLERANCE);
        List<Partition.Piece> leaves = simplified.pieces();
        failures.addAll(Checks.overlaps(leaves));
        Map<String, Geometry> keptGeometry = new HashMap<>();
        leaves.forEach(l -> keptGeometry.put(l.key(), l.geometry()));
        for (Partition.Dropped dropped : simplified.dropped()) {
            if (indian.contains(dropped.key())) {   // elsewhere islands are only reported
                for (Geometry island : dropped.islands()) {
                    lostIsland(places.get(dropped.key()).name(), island, keptGeometry.get(dropped.key()))
                            .ifPresent(failures::add);
                }
            }
        }

        // Keep every country and Indian state; other states and districts only where they have an area.
        Set<String> withArea = new HashSet<>();
        result.pieces().forEach(p -> withArea.add(p.key()));
        List<Place> kept = places.values().stream()
                .filter(p -> !mergedInto.containsKey(p.key()))
                .filter(p -> p.level().equals("country") || p.parentKey().equals(INDIA) || withArea.contains(p.key()))
                .map(p -> p.parentKey() != null && mergedInto.containsKey(p.parentKey()) ? new Place(p.key(),
                        mergedInto.get(p.parentKey()), p.level(), p.name(), p.aliases(), p.codes()) : p)
                .toList();
        // Names last: the order (by the sources' names) and so the boundary file stay as they were.
        SiblingNames.Result named = SiblingNames.resolve(depthFirst(kept), naturalEarth, nameOverrides, extraAliases);
        return new Atlas(named.places(), leaves, result.gaps(), simplified.dropped(), match, failures, named.renames(),
                named.unusedOverrides(), named.unusedAliases());
    }

    /**
     * A failure when an Indian island that simplification dropped is larger than {@link #MAX_LOST_ISLAND_KM2} or more
     * than {@link Lookup#COAST_METRES} from the rest of its place. Area: the island's area in degree² times
     * cos(latitude of its centre) times 111,195.08² m² (the app's metres per degree). Distance: between the nearest
     * points of the two geometries in degrees, measured in metres on a flat projection at their mean latitude.
     */
    static Optional<String> lostIsland(String name, Geometry island, Geometry kept) {
        Coordinate centre = island.getEnvelopeInternal().centre();
        double areaKm2 = island.getArea() * Math.cos(Math.toRadians(centre.y))
                * Lookup.METRES_PER_DEGREE * Lookup.METRES_PER_DEGREE / 1e6;
        Coordinate[] nearest = DistanceOp.nearestPoints(island, kept);
        double dx = (nearest[1].x - nearest[0].x) * Math.cos(Math.toRadians((nearest[0].y + nearest[1].y) / 2));
        double dy = nearest[1].y - nearest[0].y;
        double metres = Math.hypot(dx, dy) * Lookup.METRES_PER_DEGREE;
        if (areaKm2 <= MAX_LOST_ISLAND_KM2 && metres <= Lookup.COAST_METRES) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "An island of %s (%.3f km² at %.5f, %.5f) is lost to simplification,"
                + " %.1f km from the rest of it (only islands up to %.2f km² within %.0f km may go)", name, areaKm2,
                centre.y, centre.x, metres / 1000, MAX_LOST_ISLAND_KM2, Lookup.COAST_METRES / 1000));
    }

    /** More than 10% of the unit in other countries (beyond 2 km of India), or no part of it in or near India. */
    private static Optional<String> outsideIndia(String unit, Geometry geometry, PolygonIndex<String> countries,
                                                 Predicate<Coordinate> nearIndia) {
        double other = Checks.share(geometry, c -> {
            String country = countries.first(GeoJson.FACTORY.createPoint(c));
            return country != null && !country.equals(INDIA) && !nearIndia.test(c);
        });
        if (other > MAX_IN_OTHER_COUNTRIES) {
            return Optional.of(String.format(Locale.ROOT, "%s lies outside India's outline (%.0f%% in other countries, at most %.0f%% allowed)",
                    unit, other * 100, MAX_IN_OTHER_COUNTRIES * 100));
        }
        if (Checks.share(geometry, nearIndia) == 0) {
            return Optional.of(unit + " lies outside India's outline (no part of it in India or within 2 km)");
        }
        return Optional.empty();
    }

    /**
     * {@code iso:<ISO_A2>} for a country with a valid two-letter code (ISO_A2, else ISO_A2_EH); when several share
     * one, the one whose own ISO_A2 is it, then a country over a dependency; the others (and those without a code)
     * get {@code ne0:<ADM0_A3>}.
     */
    private static Map<String, String> countryKeys(List<GeoJson.Feature> countries) {
        Map<String, List<Map<String, String>>> byIso = new TreeMap<>();
        Map<String, String> keys = new HashMap<>();
        for (GeoJson.Feature f : countries) {
            Map<String, String> p = f.properties();
            String iso = p.getOrDefault("ISO_A2", "-99").matches("[A-Z]{2}") ? p.get("ISO_A2") : p.getOrDefault("ISO_A2_EH", "-99");
            if (iso.matches("[A-Z]{2}")) {
                byIso.computeIfAbsent(iso, k -> new ArrayList<>()).add(p);
            } else {
                keys.put(p.get("ADM0_A3"), "ne0:" + p.get("ADM0_A3"));
            }
        }
        byIso.forEach((iso, candidates) -> {
            candidates.sort(Comparator
                    .comparing((Map<String, String> p) -> !iso.equals(p.get("ISO_A2")))
                    .thenComparing(p -> !Set.of("Sovereign country", "Country").contains(p.get("TYPE")))
                    .thenComparing(p -> p.get("ADM0_A3")));
            for (int i = 0; i < candidates.size(); i++) {
                String a3 = candidates.get(i).get("ADM0_A3");
                keys.put(a3, i == 0 ? "iso:" + iso : "ne0:" + a3);
            }
        });
        return keys;
    }

    /**
     * Other spellings: trimmed, not empty, not the name itself and distinct, ignoring case ("FRANCE" is France). Case
     * only: spellings with other accents or punctuation ("Ladākh", "Leh(Ladakh)") are kept, as search may need them,
     * and so are spellings with a dotless or dotted i ("Aydin" for Aydın, "Izmir" for İzmir): case is compared through
     * {@code toLowerCase(Locale.ROOT)}, not {@code equalsIgnoreCase}, which folds ı and İ into i. Garbled spellings
     * (see {@link #garbled}) are dropped.
     */
    static List<String> aliases(String name, List<String> candidates) {
        Map<String, String> aliases = new LinkedHashMap<>();
        String nameKey = name.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            String alias = candidate == null ? "" : candidate.trim();
            if (!alias.isEmpty() && !garbled(alias) && !alias.toLowerCase(Locale.ROOT).equals(nameKey)) {
                aliases.putIfAbsent(alias.toLowerCase(Locale.ROOT), alias);
            }
        }
        return List.copyOf(aliases.values());
    }

    /**
     * A spelling no one would search for: one with U+FFFD, a control character or a currency symbol (Natural Earth's
     * mis-decoded "Mosc£", "Yuk¢n", "Timbuct" with U+009D), or with fewer than 2 letters ("X", "K").
     */
    static boolean garbled(String alias) {
        return alias.codePoints().filter(Character::isLetter).count() < 2 || alias.indexOf('\uFFFD') >= 0
                || alias.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL
                || Character.getType(c) == Character.CURRENCY_SYMBOL);
    }

    /** Fails with the feature named when it lacks one of {@code fields} (its label is {@code labelField}). */
    private static void required(GeoJson.Feature f, String source, int index, String labelField, String... fields) {
        for (String field : fields) {
            String value = f.properties().get(field);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(source + " feature " + index + " ("
                        + f.properties().getOrDefault(labelField, "unnamed") + ") has no " + field);
            }
        }
    }

    /** India first, then the other countries by name; each place followed by its children, by name. */
    private static List<Place> depthFirst(List<Place> places) {
        Map<String, List<Place>> children = new HashMap<>();
        List<Place> roots = new ArrayList<>();
        for (Place p : places) {
            if (p.parentKey() == null) {
                roots.add(p);
            } else {
                children.computeIfAbsent(p.parentKey(), k -> new ArrayList<>()).add(p);
            }
        }
        Comparator<Place> byName = Comparator.comparing((Place p) -> Names.plain(p.name()).toLowerCase(Locale.ROOT))
                .thenComparing(Place::key);
        roots.sort(Comparator.comparing((Place p) -> !p.key().equals(INDIA)).thenComparing(byName));
        List<Place> ordered = new ArrayList<>();
        for (Place root : roots) {
            visit(root, children, byName, ordered);
        }
        return ordered;
    }

    private static void visit(Place place, Map<String, List<Place>> children, Comparator<Place> byName, List<Place> out) {
        out.add(place);
        List<Place> kids = new ArrayList<>(children.getOrDefault(place.key(), List.of()));
        kids.sort(byName);
        for (Place kid : kids) {
            visit(kid, children, byName, out);
        }
    }
}
