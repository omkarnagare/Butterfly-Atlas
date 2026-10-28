package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LookupTest {

    // P1: 1° square at 0..1 with a hole 0.2..0.8; P2: the square east of it (1..2).
    private static final int[] OUTER = {0, 0, 100_000, 0, 100_000, 100_000, 0, 100_000, 0, 0};
    private static final int[] HOLE = {20_000, 20_000, 80_000, 20_000, 80_000, 80_000, 20_000, 80_000, 20_000, 20_000};
    private static final int[] EAST = {100_000, 0, 200_000, 0, 200_000, 100_000, 100_000, 100_000, 100_000, 0};
    private static final List<BinWriter.Entry> ENTRIES = List.of(
            new BinWriter.Entry("P1", List.of(OUTER, HOLE)),
            new BinWriter.Entry("P2", List.of(EAST)));

    private final Lookup lookup = new Lookup(ENTRIES);

    @Test
    void findsTheEntryContainingThePointWithTheEvenOddRule() {
        assertThat(lookup.find(0.1, 0.1)).isEqualTo("P1");
        assertThat(lookup.find(0.5, 1.5)).isEqualTo("P2");
        assertThat(lookup.find(0.5, 0.5)).isNull();              // in the hole, 33 km from any edge
    }

    @Test
    void takesTheNearestEntryWithinTwoKilometres() {
        assertThat(lookup.find(-0.015, 0.5)).isEqualTo("P1");   // 1.7 km south of P1
        assertThat(lookup.find(-0.019, 0.5)).isNull();          // 2.1 km south
        assertThat(lookup.find(0.5, 2.01)).isEqualTo("P2");     // 1.1 km east of P2
        assertThat(lookup.find(0.21, 0.5)).isEqualTo("P1");     // in the hole, 1.1 km from its edge
    }

    @Test
    void aPointOnADiagonalSharedEdgeGoesToExactlyOneAreaTheEastOneAndTheReaderAgrees() throws IOException {
        // A square 0..1 cut along its diagonal: NW is the upper-left triangle, SE the lower-right one.
        List<BinWriter.Entry> entries = List.of(
                new BinWriter.Entry("NW", List.of(new int[]{0, 0, 100_000, 100_000, 0, 100_000, 0, 0})),
                new BinWriter.Entry("SE", List.of(new int[]{0, 0, 100_000, 0, 100_000, 100_000, 0, 0})));
        Lookup diagonal = new Lookup(entries);
        List<BinDecoder.Entry> decoded = BinDecoder.decode(BinWriter.write(entries));

        for (double t : new double[]{0.25, 0.5, 0.77777}) {
            assertThat(diagonal.find(t, t)).as("on the diagonal at %s", t).isEqualTo("SE");
            assertThat(BinDecoder.lookup(decoded, t, t)).isEqualTo("SE");
        }
        assertThat(Lookup.contains(entries.get(0).rings(), 50_000, 50_000)).isFalse();
        assertThat(Lookup.contains(entries.get(1).rings(), 50_000, 50_000)).isTrue();
    }

    @Test
    void anExactTieOnTheCoastGoesToTheLowestEntryInBothOrders() throws IOException {
        // Two squares meeting at the vertex 1.0E, 0.0N; a point 1.1 km south of it is exactly as far from both.
        BinWriter.Entry east = new BinWriter.Entry("EAST", List.of(new int[]{100_000, 0, 200_000, 0, 200_000, 100_000, 100_000, 100_000, 100_000, 0}));
        BinWriter.Entry west = new BinWriter.Entry("WEST", List.of(new int[]{0, 0, 100_000, 0, 100_000, 100_000, 0, 100_000, 0, 0}));

        for (List<BinWriter.Entry> entries : List.of(List.of(east, west), List.of(west, east))) {
            String lowest = entries.get(0).id();
            assertThat(new Lookup(entries).find(-0.01, 1.0)).isEqualTo(lowest);
            assertThat(BinDecoder.lookup(BinDecoder.decode(BinWriter.write(entries)), -0.01, 1.0)).isEqualTo(lowest);
        }
    }

    @Test
    void theCoastRuleWorksAcrossTheAntimeridian() throws IOException {
        List<BinWriter.Entry> entries = List.of(
                new BinWriter.Entry("WEST", List.of(new int[]{-18_000_000, 0, -17_900_000, 0, -17_900_000, 100_000,
                        -18_000_000, 100_000, -18_000_000, 0})));
        Lookup across = new Lookup(entries);
        List<BinDecoder.Entry> decoded = BinDecoder.decode(BinWriter.write(entries));

        // 179.99°E is 0.01° (1.1 km) west of the entry's edge at -180° going the short way round.
        assertThat(across.find(0.5, 179.99)).isEqualTo("WEST");
        assertThat(BinDecoder.lookup(decoded, 0.5, 179.99)).isEqualTo("WEST");
        assertThat(across.find(0.5, 179.97)).isNull();          // 3.3 km
    }

    @Test
    void usesTheAppsMetresPerDegreeAndAnInclusiveTwoKilometres() throws IOException {
        // 1,798 units of latitude south of the square's edge are 1,999.4 m at 111,195.08 m per degree (2,001.6 m at 111,320).
        assertThat(lookup.find(-0.01798, 0.5)).isEqualTo("P1");
        assertThat(lookup.find(-0.01799, 0.5)).isNull();        // 2,000.5 m
        List<BinDecoder.Entry> decoded = BinDecoder.decode(BinWriter.write(ENTRIES));
        assertThat(BinDecoder.lookup(decoded, -0.01798, 0.5)).isEqualTo("P1");
        assertThat(BinDecoder.lookup(decoded, -0.01799, 0.5)).isNull();
    }

    @Test
    void answersExactlyAsTheIndependentReaderDoes() throws IOException {
        List<BinDecoder.Entry> decoded = BinDecoder.decode(BinWriter.write(ENTRIES));
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            double lat = -0.05 + random.nextDouble() * 1.1;
            double lon = -0.05 + random.nextDouble() * 2.1;
            assertThat(lookup.find(lat, lon)).as("%s, %s", lat, lon).isEqualTo(BinDecoder.lookup(decoded, lat, lon));
        }
    }
}
