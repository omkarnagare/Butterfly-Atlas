package com.butterflyatlas.placeimporter;

import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.coverage.CoverageValidator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** The checks that fail a build before anything is written (contract §5). */
public final class Checks {

    /** A point that must land on a place named one of {@code names} at {@code level}; no names: on no place. */
    public record TestPoint(String label, double lat, double lon, String level, Set<String> names) {
    }

    public static final List<TestPoint> CONTRACT_POINTS = List.of(
            new TestPoint("Leh", 34.1526, 77.5771, "state", Set.of("Ladakh")),
            new TestPoint("Tawang", 27.5860, 91.8594, "state", Set.of("Arunachal Pradesh")),
            new TestPoint("Pune city", 18.5204, 73.8567, "district", Set.of("Pune")),
            new TestPoint("Muzaffarabad", 34.3700, 73.4711, "country", Set.of("India")),
            new TestPoint("Kathmandu", 27.7172, 85.3240, "country", Set.of("Nepal")),
            // Colva beach runs north–south at 73.9046°E here; 0.0095° of longitude is 1 km at 15.3°N.
            new TestPoint("1 km off the Goa coast", 15.30, 73.895, "district", Set.of("North Goa", "South Goa")),
            new TestPoint("mid-Arabian Sea", 15.0, 65.0, null, Set.of()));

    public static final long MAX_BIN_BYTES = 4L * 1024 * 1024;

    private Checks() {
    }

    public static List<String> testPoints(List<BundlePlace> places, List<BinWriter.Entry> entries, List<TestPoint> points) {
        Map<String, BundlePlace> byId = new HashMap<>();
        places.forEach(p -> byId.put(p.id(), p));
        Lookup lookup = new Lookup(entries);
        List<String> failures = new ArrayList<>();
        for (TestPoint point : points) {
            List<BundlePlace> path = new ArrayList<>();
            for (String id = lookup.find(point.lat(), point.lon()); id != null; ) {
                BundlePlace place = byId.get(id);
                path.add(place);
                id = place.parent();
            }
            boolean ok = point.names().isEmpty() ? path.isEmpty() : path.stream()
                    .anyMatch(p -> p.level().equals(point.level()) && point.names().contains(p.name()));
            if (!ok) {
                String expected = point.names().isEmpty() ? "no place" : String.join(" or ", new TreeSet<>(point.names()));
                String actual = path.isEmpty() ? "no place"
                        : String.join(", ", path.stream().map(BundlePlace::name).toList());
                failures.add("Test point " + point.label() + " (" + point.lat() + ", " + point.lon() + ") should be in "
                        + expected + " but is in " + actual);
            }
        }
        return failures;
    }

    /** Leaves whose borders overlap a neighbour or do not match it vertex for vertex. */
    public static List<String> overlaps(List<Partition.Piece> leaves) {
        Geometry[] geometries = leaves.stream().map(Partition.Piece::geometry).toArray(Geometry[]::new);
        Geometry[] invalid = CoverageValidator.validate(geometries);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < invalid.length; i++) {
            if (invalid[i] != null) {
                keys.add(leaves.get(i).key());
            }
        }
        return keys.isEmpty() ? List.of()
                : List.of("Leaves overlap or do not share their border exactly: " + String.join(", ", keys));
    }

    /** About 2 km in degrees: the sources' coastlines and borders differ by up to this much. */
    public static final double NEAR_DEGREES = 0.018;

    /** Fails when less than {@code minShare} of the unit's area lies inside the container or within 2 km of it. */
    public static Optional<String> within(String unit, Geometry geometry, String containerName, Geometry container,
                                          double minShare) {
        return within(unit, geometry, containerName, near(container), minShare);
    }

    public static Optional<String> within(String unit, Geometry geometry, String containerName, Predicate<Coordinate> near,
                                          double minShare) {
        double share = share(geometry, near);
        if (share >= minShare) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "%s lies outside %s (%.0f%% inside or within 2 km, at least %.0f%% needed)",
                unit, containerName, share * 100, minShare * 100));
    }

    /** Inside the area or within {@link #NEAR_DEGREES} of its boundary. */
    public static Predicate<Coordinate> near(Geometry area) {
        IndexedPointInAreaLocator inside = new IndexedPointInAreaLocator(area);
        IndexedFacetDistance distance = new IndexedFacetDistance(area);
        return c -> inside.locate(c) != Location.EXTERIOR
                || distance.isWithinDistance(GeoJson.FACTORY.createPoint(c), NEAR_DEGREES);
    }

    /**
     * The share of the unit's area where {@code test} holds, measured on a 50 × 50 grid of points over the bounding
     * box of each of its parts, weighted by the parts' areas (exact overlays of boundaries with 100,000 vertices take
     * minutes; this takes milliseconds, and a small island still gets its own points).
     */
    public static double share(Geometry unit, Predicate<Coordinate> test) {
        double total = 0;
        double hit = 0;
        for (int g = 0; g < unit.getNumGeometries(); g++) {
            Geometry part = unit.getGeometryN(g);
            IndexedPointInAreaLocator inPart = new IndexedPointInAreaLocator(part);
            Envelope box = part.getEnvelopeInternal();
            int n = 50;
            int inside = 0;
            int both = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    Coordinate c = new Coordinate(box.getMinX() + (i + 0.5) * box.getWidth() / n,
                            box.getMinY() + (j + 0.5) * box.getHeight() / n);
                    if (inPart.locate(c) == Location.INTERIOR) {
                        inside++;
                        if (test.test(c)) {
                            both++;
                        }
                    }
                }
            }
            double fraction = inside == 0 ? (test.test(part.getInteriorPoint().getCoordinate()) ? 1 : 0)
                    : (double) both / inside;
            total += part.getArea();
            hit += part.getArea() * fraction;
        }
        return total == 0 ? 0 : hit / total;
    }

    public static Optional<String> size(long bytes, long max) {
        if (bytes <= max) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "The boundary file is %,d bytes, over the budget of %,d bytes", bytes, max));
    }
}
