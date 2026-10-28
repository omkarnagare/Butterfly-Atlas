package com.butterflyatlas.placeimporter;

import org.locationtech.jts.coverage.CoverageSimplifier;
import org.locationtech.jts.coverage.CoverageUnion;
import org.locationtech.jts.coverage.CoverageValidator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.overlayng.UnaryUnionNG;
import org.locationtech.jts.operation.polygonize.Polygonizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * Turns overlapping, misaligned source boundaries into a clean coverage: every boundary is noded on the 1e-5 degree
 * grid, the faces between the lines are labelled with the place that contains them, and each place becomes the union
 * of its faces. Neighbours then share every vertex of their common border, so the border can be simplified once.
 * Faces inside a place that no leaf covers (gaps) join the neighbouring leaf of the same parent with the longest shared
 * border, or the nearest leaf of the same parent; a gap of {@code largeGapKm2} or more, or of a parent without leaves,
 * becomes an area of the parent itself (never another parent's leaf: that would put the area in the wrong place).
 */
public final class Partition {

    /** A face's place: the leaf containing it (null in a gap) and that leaf's or gap's parent. */
    public record Label(String leaf, String parent) {
    }

    /** Labels a face from a point inside it; null when the face lies outside every place. */
    @FunctionalInterface
    public interface Labeller {
        Label label(Point point);
    }

    public record Piece(String key, Geometry geometry) {
    }

    /** A gap (connected faces of one parent that no leaf covers), its size, where it is and who got it. */
    public record Gap(String parent, double areaKm2, String assignedTo, Coordinate at) {
    }

    public record Result(List<Piece> pieces, List<Gap> gaps) {
    }

    /** Parts (islands) of a piece that simplified away, while the piece kept others. */
    public record Dropped(String key, List<Geometry> islands) {
        public int parts() {
            return islands.size();
        }

        public double areaKm2() {
            return islands.stream().mapToDouble(Partition::areaKm2).sum();
        }
    }

    public record Simplified(List<Piece> pieces, List<Dropped> dropped) {
    }

    private static final double KM_PER_DEGREE = 111.32;

    private Partition() {
    }

    @SuppressWarnings("unchecked")
    public static Result build(List<Geometry> lines, Labeller labeller, double largeGapKm2) {
        Geometry noded = UnaryUnionNG.union(GeoJson.FACTORY.buildGeometry(lines), GeoJson.GRID);
        List<Polygon> faces = faces(noded);
        faces.sort(Comparator.comparingDouble((Polygon f) -> f.getEnvelopeInternal().getMinX())
                .thenComparingDouble(f -> f.getEnvelopeInternal().getMinY())
                .thenComparingDouble(Geometry::getArea));
        Label[] labels = new Label[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            labels[i] = labeller.label(faces.get(i).getInteriorPoint());
        }

        // Gap faces: connect neighbours of the same parent, and measure their borders with leaves.
        Map<Segment, Integer> gapSegments = new HashMap<>();
        int[] root = new int[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            root[i] = i;
        }
        for (int i = 0; i < faces.size(); i++) {
            if (labels[i] == null || labels[i].leaf() != null) {
                continue;
            }
            for (Segment segment : segments(faces.get(i))) {
                Integer other = gapSegments.putIfAbsent(segment, i);
                if (other != null && labels[other].parent().equals(labels[i].parent())) {
                    root[find(root, other)] = find(root, i);
                }
            }
        }
        Map<Integer, Map<String, Double>> sameParentBorder = new HashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            if (labels[i] == null || labels[i].leaf() == null) {
                continue;
            }
            for (LineString line : rings(faces.get(i))) {
                Coordinate[] ring = line.getCoordinates();
                for (int k = 0; k + 1 < ring.length; k++) {
                    Integer gap = gapSegments.get(key(ring[k], ring[k + 1]));
                    if (gap != null && labels[i].parent().equals(labels[gap].parent())) {
                        sameParentBorder.computeIfAbsent(find(root, gap), c -> new TreeMap<>())
                                .merge(labels[i].leaf(), ring[k].distance(ring[k + 1]), Double::sum);
                    }
                }
            }
        }

        Map<Integer, List<Integer>> components = new TreeMap<>();
        for (int i = 0; i < faces.size(); i++) {
            if (labels[i] != null && labels[i].leaf() == null) {
                components.computeIfAbsent(find(root, i), c -> new ArrayList<>()).add(i);
            }
        }
        String[] keys = new String[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            keys[i] = labels[i] == null ? null : labels[i].leaf();
        }
        List<Gap> gaps = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> component : components.entrySet()) {
            List<Integer> members = component.getValue();
            String parent = labels[members.get(0)].parent();
            double area = 0;
            Polygon largest = null;
            for (int member : members) {
                area += areaKm2(faces.get(member));
                if (largest == null || faces.get(member).getArea() > largest.getArea()) {
                    largest = faces.get(member);
                }
            }
            String assigned;
            if (area >= largeGapKm2) {
                assigned = parent;
            } else {
                assigned = longest(sameParentBorder.get(component.getKey()));
                if (assigned == null) {
                    assigned = nearest(faces, labels, largest, parent);
                }
                if (assigned == null) {
                    assigned = parent;
                }
            }
            for (int member : members) {
                keys[member] = assigned;
            }
            gaps.add(new Gap(parent, area, assigned, largest.getInteriorPoint().getCoordinate()));
        }

        Map<String, List<Polygon>> facesByKey = new TreeMap<>();
        for (int i = 0; i < faces.size(); i++) {
            if (keys[i] != null) {
                facesByKey.computeIfAbsent(keys[i], k -> new ArrayList<>()).add(faces.get(i));
            }
        }
        List<Piece> pieces = new ArrayList<>();
        facesByKey.forEach((key, list) -> pieces.add(new Piece(key, piece(list))));
        return new Result(pieces, gaps);
    }

    /**
     * The faces between noded lines. A ring the polygonizer cannot make into a valid face would silently lose its
     * area, so it fails the build. (Checked this way rather than by comparing the faces' area with the union of the
     * sources: that union is the very overlay of 100,000-vertex polygons this class avoids, and needs a tolerance.)
     */
    @SuppressWarnings("unchecked")
    static List<Polygon> faces(Geometry noded) {
        Polygonizer polygonizer = new Polygonizer();
        polygonizer.add(noded);
        List<Polygon> faces = new ArrayList<>((Collection<Polygon>) polygonizer.getPolygons());
        Collection<?> invalid = polygonizer.getInvalidRingLines();
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("The boundaries could not be split into faces: " + invalid.size()
                    + " invalid ring(s), first near " + ((Geometry) invalid.iterator().next()).getCoordinate());
        }
        return faces;
    }

    /**
     * The union of a place's faces in JTS's canonical form. The overlay hands back the same area with its parts and
     * ring starts in an order that varies from run to run, and the simplifier keeps the start of a ring that touches
     * no other piece (an island), so without this the same sources would give slightly different boundaries.
     */
    static Geometry piece(List<? extends Geometry> faces) {
        return CoverageUnion.union(faces.toArray(Geometry[]::new)).norm();
    }

    /**
     * Simplifies the shared borders of a coverage once (topology-preserving Visvalingam–Whyatt), each piece with its
     * own distance tolerance in degrees (a shared border takes the smaller of its two pieces' tolerances). Parts of a
     * piece that simplify away (small islands) are reported in {@code dropped}; a place left with no area at all fails.
     */
    public static Simplified simplify(List<Piece> pieces, ToDoubleFunction<String> tolerance) {
        Geometry[] geometries = pieces.stream().map(Piece::geometry).toArray(Geometry[]::new);
        if (!CoverageValidator.isValid(geometries)) {
            throw new IllegalStateException("The pieces do not form a clean coverage");
        }
        double[] tolerances = pieces.stream().mapToDouble(p -> tolerance.applyAsDouble(p.key())).toArray();
        return compare(pieces, CoverageSimplifier.simplify(geometries, tolerances));
    }

    /**
     * The simplified pieces and the parts each lost. JTS 1.20 keeps at least one part of every polygon, so a place
     * cannot vanish today; the check guards against a simplifier that would drop it.
     */
    static Simplified compare(List<Piece> pieces, Geometry[] simplified) {
        List<Piece> result = new ArrayList<>();
        Map<String, Dropped> dropped = new LinkedHashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            Geometry before = pieces.get(i).geometry();
            Geometry after = simplified[i] == null ? GeoJson.FACTORY.createPolygon() : simplified[i];
            if (!after.isEmpty()) {
                result.add(new Piece(pieces.get(i).key(), after));
            }
            if (after.getNumGeometries() < before.getNumGeometries() || after.isEmpty()) {
                PreparedGeometry kept = PreparedGeometryFactory.prepare(after);
                List<Geometry> lost = new ArrayList<>();
                for (int k = 0; k < before.getNumGeometries(); k++) {
                    Geometry part = before.getGeometryN(k);
                    if (after.isEmpty() || !kept.intersects(part)) {
                        lost.add(part);
                    }
                }
                dropped.merge(pieces.get(i).key(), new Dropped(pieces.get(i).key(), List.copyOf(lost)), (a, b) -> {
                    List<Geometry> both = new ArrayList<>(a.islands());
                    both.addAll(b.islands());
                    return new Dropped(a.key(), List.copyOf(both));
                });
            }
        }
        List<String> vanished = new ArrayList<>(dropped.keySet());
        result.forEach(p -> vanished.remove(p.key()));
        if (!vanished.isEmpty()) {
            throw new IllegalStateException("These places simplify to nothing (too small for their tolerance): "
                    + String.join(", ", vanished));
        }
        return new Simplified(result, List.copyOf(dropped.values()));
    }

    /** Area in km², for a geometry in degrees (equirectangular at its centre's latitude). */
    public static double areaKm2(Geometry geometry) {
        double lat = geometry.getEnvelopeInternal().centre().y;
        return geometry.getArea() * KM_PER_DEGREE * KM_PER_DEGREE * Math.cos(Math.toRadians(lat));
    }

    private static String longest(Map<String, Double> borders) {
        if (borders == null) {
            return null;
        }
        String best = null;
        double bestLength = -1;
        for (Map.Entry<String, Double> e : borders.entrySet()) {   // TreeMap: ties go to the first key
            if (e.getValue() > bestLength) {
                bestLength = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static String nearest(List<Polygon> faces, Label[] labels, Polygon gap, String parent) {
        String best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < faces.size(); i++) {
            if (labels[i] == null || labels[i].leaf() == null || !labels[i].parent().equals(parent)) {
                continue;
            }
            if (faces.get(i).getEnvelopeInternal().distance(gap.getEnvelopeInternal()) > bestDistance) {
                continue;   // cannot be nearer: a face is never nearer than its envelope
            }
            double distance = faces.get(i).distance(gap);
            if (distance < bestDistance || (distance == bestDistance && labels[i].leaf().compareTo(best) < 0)) {
                bestDistance = distance;
                best = labels[i].leaf();
            }
        }
        return best;
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) {
            root[i] = root[root[i]];
            i = root[i];
        }
        return i;
    }

    private static List<LineString> rings(Polygon polygon) {
        List<LineString> rings = new ArrayList<>();
        rings.add(polygon.getExteriorRing());
        for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
            rings.add(polygon.getInteriorRingN(h));
        }
        return rings;
    }

    private static List<Segment> segments(Polygon polygon) {
        List<Segment> segments = new ArrayList<>();
        for (LineString ring : rings(polygon)) {
            Coordinate[] c = ring.getCoordinates();
            for (int k = 0; k + 1 < c.length; k++) {
                segments.add(key(c[k], c[k + 1]));
            }
        }
        return segments;
    }

    /** An undirected segment between two grid points. */
    private record Segment(long from, long to) {
    }

    private static Segment key(Coordinate a, Coordinate b) {
        long pa = pack(a);
        long pb = pack(b);
        return new Segment(Math.min(pa, pb), Math.max(pa, pb));
    }

    private static long pack(Coordinate c) {
        long x = Math.round(c.x * 1e5);
        long y = Math.round(c.y * 1e5);
        return (x << 32) ^ (y & 0xFFFFFFFFL);
    }
}
