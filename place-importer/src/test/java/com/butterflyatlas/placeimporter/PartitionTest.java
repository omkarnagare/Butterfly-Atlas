package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.coverage.CoverageValidator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PartitionTest {

    private static final WKTReader WKT = new WKTReader(GeoJson.FACTORY);

    static Geometry wkt(String text) {
        try {
            return WKT.read(text);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Leaves A, B, C under parent S: the first source containing a face's point wins; inside "region" only. */
    private static Partition.Result partition(Geometry region, Map<String, Geometry> leaves, double largeGapKm2) {
        PolygonIndex<String> index = new PolygonIndex<>();
        leaves.forEach((key, geometry) -> index.add(geometry, key));
        List<Geometry> lines = new ArrayList<>();
        lines.add(region.getBoundary());
        leaves.values().forEach(g -> lines.add(g.getBoundary()));
        return Partition.build(lines, point -> {
            if (!region.contains(point)) {
                return null;
            }
            String leaf = index.first(point);
            return new Partition.Label(leaf, "S");
        }, largeGapKm2);
    }

    private static Map<String, Geometry> byKey(Partition.Result result) {
        Map<String, Geometry> map = new java.util.TreeMap<>();
        result.pieces().forEach(p -> map.put(p.key(), p.geometry()));
        return map;
    }

    @Test
    void overlappingSourcesBecomeNeighboursThatShareEveryVertexOfTheirBorder() {
        Geometry region = wkt("POLYGON ((0 0, 0.2 0, 0.2 0.1, 0 0.1, 0 0))");
        Geometry a = wkt("POLYGON ((0 0, 0.10003 0, 0.10003 0.1, 0 0.1, 0 0))");                 // overlaps B by 3e-5°
        Geometry b = wkt("POLYGON ((0.1 0, 0.2 0, 0.2 0.1, 0.1 0.1, 0.1 0.05, 0.1 0))");

        Map<String, Geometry> pieces = byKey(partition(region, Map.of("A", a, "B", b), 1000));

        assertThat(pieces.keySet()).containsExactly("A", "B");
        assertThat(pieces.get("A").intersection(pieces.get("B")).getArea()).isZero();
        assertThat(pieces.get("A").getArea() + pieces.get("B").getArea()).isCloseTo(region.getArea(), within(1e-12));
        assertThat(CoverageValidator.isValid(pieces.values().toArray(Geometry[]::new))).isTrue();
    }

    @Test
    void aPieceIsTheSameWhateverTheOrderAndRingStartsOfItsFaces() {
        Geometry left = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        Geometry right = wkt("POLYGON ((1 0, 2 0, 2 1, 1 1, 1 0))");
        Geometry island = wkt("POLYGON ((3 0, 3.5 0, 3.5 0.5, 3 0))");
        Geometry rightTurned = wkt("POLYGON ((2 1, 1 1, 1 0, 2 0, 2 1))");
        Geometry islandTurned = wkt("POLYGON ((3.5 0.5, 3 0, 3.5 0, 3.5 0.5))");

        Geometry one = Partition.piece(List.of(left, right, island));
        Geometry other = Partition.piece(List.of(islandTurned, rightTurned, left));

        assertThat(other.equalsExact(one)).isTrue();
        assertThat(one.getArea()).isEqualTo(2.125);
    }

    @Test
    void aSmallGapJoinsTheNeighbourWithTheLongestSharedBorder() {
        Geometry region = wkt("POLYGON ((0 0, 0.3 0, 0.3 0.1, 0 0.1, 0 0))");
        Geometry a = wkt("POLYGON ((0 0, 0.1 0, 0.1 0.1, 0 0.1, 0 0))");
        Geometry b = wkt("POLYGON ((0.11 0, 0.3 0, 0.3 0.1, 0.11 0.1, 0.11 0))");   // a 0.01° (≈1 km) strip between them
        Geometry c = wkt("POLYGON ((0.1 0.09, 0.11 0.09, 0.11 0.1, 0.1 0.1, 0.1 0.09))");   // C caps the strip's top

        Partition.Result result = partition(region, Map.of("A", a, "B", b, "C", c), 1000);
        Map<String, Geometry> pieces = byKey(result);

        assertThat(pieces.get("A").getArea()).isCloseTo(0.1 * 0.1 + 0.01 * 0.09, within(1e-12));
        assertThat(result.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.parent()).isEqualTo("S");
            assertThat(gap.assignedTo()).isEqualTo("A");    // A and B both touch it along 0.09°; A is first by key
            assertThat(gap.areaKm2()).isCloseTo(11.1, within(0.1));
        });
    }

    @Test
    void aLargeGapBecomesAnAreaOfTheParentItself() {
        Geometry region = wkt("POLYGON ((0 0, 2 0, 2 1, 0 1, 0 0))");
        Geometry a = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");

        Partition.Result result = partition(region, Map.of("A", a), 1000);

        assertThat(byKey(result).keySet()).containsExactly("A", "S");
        assertThat(byKey(result).get("S").getArea()).isCloseTo(1.0, within(1e-12));
        assertThat(result.gaps()).singleElement().extracting(Partition.Gap::assignedTo).isEqualTo("S");
    }

    @Test
    void aSmallIslandTouchingNothingJoinsTheNearestLeafOfItsParent() {
        Geometry region = wkt("MULTIPOLYGON (((0 0, 1 0, 1 1, 0 1, 0 0)), ((1.05 0, 1.06 0, 1.06 0.01, 1.05 0.01, 1.05 0)),"
                + " ((3 0, 4 0, 4 1, 3 1, 3 0)))");
        Geometry a = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        Geometry b = wkt("POLYGON ((3 0, 4 0, 4 1, 3 1, 3 0))");

        Map<String, Geometry> pieces = byKey(partition(region, Map.of("A", a, "B", b), 1000));

        assertThat(pieces.get("A").getNumGeometries()).isEqualTo(2);
        assertThat(pieces.get("B").getArea()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void facesOutsideEveryPlaceAreDropped() {
        Geometry region = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        Geometry a = wkt("POLYGON ((-1 0, 1 0, 1 1, -1 1, -1 0))");

        Map<String, Geometry> pieces = byKey(partition(region, Map.of("A", a), 1000));

        assertThat(pieces.get("A").getArea()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void sharedBordersAreSimplifiedOnceSoNeighboursNeitherOverlapNorLeaveSlivers() {
        StringBuilder wiggle = new StringBuilder();
        for (int i = 0; i <= 100; i++) {
            wiggle.append(i % 2 == 0 ? "0.5" : "0.5001").append(' ').append(i / 100.0).append(", ");
        }
        Geometry region = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        Geometry a = wkt("POLYGON ((0 0, " + wiggle + "0 1, 0 0))");
        Geometry b = region.difference(a);
        Partition.Result result = partition(region, Map.of("A", a, "B", b), 1000);

        List<Partition.Piece> simplified = Partition.simplify(result.pieces(), key -> 0.01).pieces();

        Geometry sa = simplified.get(0).geometry();
        Geometry sb = simplified.get(1).geometry();
        assertThat(sa.getNumPoints()).isLessThan(10);
        assertThat(sa.intersection(sb).getArea()).isZero();
        assertThat(sa.getArea() + sb.getArea()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void eachPieceIsSimplifiedWithItsOwnTolerance() {
        Geometry a = wkt("POLYGON ((" + wigglyBottom(0) + "1 1, 0 1, 0 0))");
        Geometry b = wkt("POLYGON ((" + wigglyBottom(2) + "3 1, 2 1, 2 0))");
        Partition.Result result = partition(a.union(b), Map.of("A", a, "B", b), 1000);

        List<Partition.Piece> simplified = Partition.simplify(result.pieces(), key -> key.equals("A") ? 0.00001 : 0.01).pieces();

        assertThat(simplified).extracting(Partition.Piece::key).containsExactly("A", "B");
        assertThat(simplified.get(0).geometry().getNumPoints()).isGreaterThan(100);
        assertThat(simplified.get(1).geometry().getNumPoints()).isLessThan(10);
    }

    @Test
    void ringsThePolygonizerCannotUseFailTheBuildInsteadOfLosingTheirArea() {
        Geometry bowtie = wkt("LINESTRING (0 0, 1 1, 1 0, 0 1, 0 0)");    // not noded where it crosses itself

        assertThatThrownBy(() -> Partition.faces(bowtie))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 invalid ring");
    }

    @Test
    void aSmallGapOfAParentWithoutLeavesStaysWithThatParentRatherThanAnotherParentsLeaf() {
        Geometry region = wkt("POLYGON ((0 0, 2 0, 2 1, 0 1, 0 0))");
        Geometry a = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");            // leaf of S
        Geometry t = wkt("POLYGON ((1 0, 1.01 0, 1.01 0.01, 1 0.01, 1 0))");  // a sliver of T, which has no leaves
        List<Geometry> lines = List.of(region.getBoundary(), a.getBoundary(), t.getBoundary());

        Partition.Result result = Partition.build(lines, point -> {
            if (a.contains(point)) {
                return new Partition.Label("A", "S");
            }
            return t.contains(point) ? new Partition.Label(null, "T") : null;
        }, 1000);

        assertThat(result.gaps()).singleElement().extracting(Partition.Gap::assignedTo).isEqualTo("T");
        assertThat(byKey(result).keySet()).containsExactly("A", "T");
    }

    @Test
    void aSharedBorderIsSimplifiedWithTheSmallerToleranceOfItsTwoPieces() {
        // India (0.001°) and a neighbour (0.015°) share a border wiggling by 0.0005° every 0.01°; the neighbour's
        // bottom edge wiggles the same way but is its own.
        String border = wiggle(0, 1, 0);
        Geometry india = wkt("POLYGON ((" + border + "1 1, 0 1, 0 0))");
        Geometry neighbour = wkt("POLYGON ((" + border + wiggle(1, 0, -1) + "0 0))");
        List<Partition.Piece> pieces = List.of(new Partition.Piece("A", india), new Partition.Piece("B", neighbour));

        List<Partition.Piece> mixed = Partition.simplify(pieces,
                key -> key.equals("A") ? AtlasBuilder.INDIA_TOLERANCE : AtlasBuilder.WORLD_TOLERANCE).pieces();
        List<Partition.Piece> fine = Partition.simplify(pieces, key -> AtlasBuilder.INDIA_TOLERANCE).pieces();

        assertThat(mixed.get(0).geometry().getNumPoints()).isEqualTo(104);   // every wiggle of the border kept
        assertThat(mixed.get(0).geometry().equalsExact(fine.get(0).geometry())).isTrue();
        assertThat(fine.get(1).geometry().getNumPoints()).isGreaterThan(200);
        assertThat(mixed.get(1).geometry().getNumPoints()).isLessThan(110);  // its own edge simplified at 0.015°
    }

    @Test
    void anIslandThatSimplifiesAwayIsReportedAndThePlaceKeepsItsOtherParts() {
        Geometry withIsland = wkt("MULTIPOLYGON (((0 0, 1 0, 1 1, 0 1, 0 0)), ((5 5, 5.001 5, 5.001 5.001, 5 5.001, 5 5)))");

        Partition.Simplified simplified = Partition.simplify(List.of(new Partition.Piece("A", withIsland)), key -> 0.015);

        assertThat(simplified.pieces()).singleElement().satisfies(p -> assertThat(p.geometry().getArea()).isEqualTo(1.0));
        assertThat(simplified.dropped()).singleElement().satisfies(d -> {
            assertThat(d.key()).isEqualTo("A");
            assertThat(d.parts()).isEqualTo(1);
            assertThat(d.areaKm2()).isCloseTo(0.0122, within(0.0005));
            assertThat(d.islands()).singleElement().satisfies(g -> assertThat(g.getCoordinate().x).isEqualTo(5.0));
        });
    }

    @Test
    void aPlaceOfSmallIslandsKeepsOneAndReportsTheOthers() {
        Geometry islands = wkt("MULTIPOLYGON (((5 5, 5.001 5, 5.001 5.001, 5 5.001, 5 5)),"
                + " ((6 5, 6.001 5, 6.001 5.001, 6 5.001, 6 5)))");

        Partition.Simplified simplified = Partition.simplify(List.of(new Partition.Piece("Isles", islands)), key -> 0.015);

        assertThat(simplified.pieces()).singleElement().satisfies(p -> assertThat(p.geometry().getNumGeometries()).isOne());
        assertThat(simplified.dropped()).singleElement().extracting(Partition.Dropped::parts).isEqualTo(1);
    }

    @Test
    void aPlaceWhoseEveryPartSimplifiesAwayFailsTheBuild() {
        Geometry islands = wkt("MULTIPOLYGON (((5 5, 5.001 5, 5.001 5.001, 5 5.001, 5 5)),"
                + " ((6 5, 6.001 5, 6.001 5.001, 6 5.001, 6 5)))");
        Geometry big = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        List<Partition.Piece> pieces = List.of(new Partition.Piece("A", big), new Partition.Piece("Isles", islands));

        assertThatThrownBy(() -> Partition.compare(pieces, new Geometry[] {big, GeoJson.FACTORY.createPolygon()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("These places simplify to nothing (too small for their tolerance): Isles");
        List<Partition.Piece> twoPieces = List.of(new Partition.Piece("Isles", islands), new Partition.Piece("Isles", big));
        assertThat(Partition.compare(twoPieces, new Geometry[] {null, big}).dropped())    // it keeps another piece
                .containsExactly(new Partition.Dropped("Isles", List.of(islands.getGeometryN(0), islands.getGeometryN(1))));
    }

    /** "from y, …" to "to y": 101 points 0.01° apart along the line y, every other one 0.0005° north of it. */
    private static String wiggle(double from, double to, double y) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i <= 100; i++) {
            double x = from + (to - from) * i / 100.0;
            points.append(x).append(' ').append(i % 2 == 0 ? y : y + 0.0005).append(", ");
        }
        return points.toString();
    }

    /** "x 0, x+0.01 0.0001, x+0.02 0, …" up to x+1: a bottom edge wiggling by 0.0001° every 0.01°. */
    private static String wigglyBottom(double x) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i <= 100; i++) {
            points.append(x + i / 100.0).append(' ').append(i % 2 == 0 ? "0" : "0.0001").append(", ");
        }
        return points.toString();
    }
}
