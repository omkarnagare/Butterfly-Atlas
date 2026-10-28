package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.butterflyatlas.placeimporter.PartitionTest.wkt;
import static org.assertj.core.api.Assertions.assertThat;

class ChecksTest {

    private static final List<BundlePlace> PLACES = List.of(
            new BundlePlace("P1", null, "country", "India", List.of(), Map.of("iso", "IN")),
            new BundlePlace("P2", "P1", "state", "Goa", List.of(), Map.of("iso", "IN-GA")),
            new BundlePlace("P3", "P2", "district", "North Goa", List.of(), Map.of()),
            new BundlePlace("P4", null, "country", "Nepal", List.of(), Map.of("iso", "NP")));

    private static final List<BinWriter.Entry> ENTRIES = List.of(
            new BinWriter.Entry("P3", List.of(new int[]{0, 0, 100_000, 0, 100_000, 100_000, 0, 100_000, 0, 0})),
            new BinWriter.Entry("P4", List.of(new int[]{200_000, 0, 300_000, 0, 300_000, 100_000, 200_000, 100_000, 200_000, 0})));

    @Test
    void testPointsPassWhenTheyLandOnTheExpectedPlaceAtTheGivenLevel() {
        List<Checks.TestPoint> points = List.of(
                new Checks.TestPoint("in North Goa", 0.5, 0.5, "district", Set.of("North Goa", "South Goa")),
                new Checks.TestPoint("in Goa", 0.5, 0.5, "state", Set.of("Goa")),
                new Checks.TestPoint("in Nepal", 0.5, 2.5, "country", Set.of("Nepal")),
                new Checks.TestPoint("at sea", 0.5, 1.5, null, Set.of()));

        assertThat(Checks.testPoints(PLACES, ENTRIES, points)).isEmpty();
    }

    @Test
    void testPointsFailWithWhereTheyLanded() {
        List<Checks.TestPoint> points = List.of(
                new Checks.TestPoint("Leh", 0.5, 0.5, "state", Set.of("Ladakh")),
                new Checks.TestPoint("sea", 0.5, 2.5, null, Set.of()),
                new Checks.TestPoint("Pune", 0.5, 1.5, "district", Set.of("Pune")),
                new Checks.TestPoint("Kathmandu", 0.5, 0.5, "district", Set.of("Nepal")));

        assertThat(Checks.testPoints(PLACES, ENTRIES, points)).containsExactly(
                "Test point Leh (0.5, 0.5) should be in Ladakh but is in North Goa, Goa, India",
                "Test point sea (0.5, 2.5) should be in no place but is in Nepal",
                "Test point Pune (0.5, 1.5) should be in Pune but is in no place",
                "Test point Kathmandu (0.5, 0.5) should be in Nepal but is in North Goa, Goa, India");
    }

    @Test
    void theContractTestPointsAreListed() {
        assertThat(Checks.CONTRACT_POINTS).extracting(Checks.TestPoint::label).containsExactly(
                "Leh", "Tawang", "Pune city", "Muzaffarabad", "Kathmandu", "1 km off the Goa coast", "mid-Arabian Sea");
    }

    @Test
    void overlappingLeavesAreReportedByKey() {
        Geometry a = wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        Geometry b = wkt("POLYGON ((0.9 0, 2 0, 2 1, 0.9 1, 0.9 0))");
        Geometry c = wkt("POLYGON ((2 0, 3 0, 3 1, 2 1, 2 0))");

        assertThat(Checks.overlaps(List.of(new Partition.Piece("A", a), new Partition.Piece("B", b),
                new Partition.Piece("C", c)))).containsExactly("Leaves overlap or do not share their border exactly: A, B");
        assertThat(Checks.overlaps(List.of(new Partition.Piece("A", a), new Partition.Piece("C", c)))).isEmpty();
    }

    @Test
    void aUnitMostlyOutsideItsContainerFails() {
        Geometry container = wkt("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");

        assertThat(Checks.within("District Pune", wkt("POLYGON ((9 0, 11 0, 11 1, 9 1, 9 0))"), "Maharashtra", container, 0.8))
                .contains("District Pune lies outside Maharashtra (50% inside or within 2 km, at least 80% needed)");
        assertThat(Checks.within("District Pune", wkt("POLYGON ((1 1, 2 1, 2 2, 1 2, 1 1))"), "Maharashtra", container, 0.8))
                .isEmpty();
    }

    @Test
    void aBinOverTheBudgetFails() {
        assertThat(Checks.size(4 * 1024 * 1024, Checks.MAX_BIN_BYTES)).isEmpty();
        assertThat(Checks.size(4 * 1024 * 1024 + 1, Checks.MAX_BIN_BYTES))
                .contains("The boundary file is 4,194,305 bytes, over the budget of 4,194,304 bytes");
    }
}
