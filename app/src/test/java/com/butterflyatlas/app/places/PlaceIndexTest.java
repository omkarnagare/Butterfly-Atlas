package com.butterflyatlas.app.places;

import com.butterflyatlas.app.testing.PlaceBin;
import com.butterflyatlas.app.testing.TestPlaces;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceIndexTest {

    private static PlaceIndex fixture() throws IOException {
        return PlaceIndex.read(new ByteArrayInputStream(TestPlaces.bin()));
    }

    /** Metres east of {@code lon} at {@code lat}, in degrees of longitude (for points a known distance off a coast). */
    private static double east(double lat, double metres) {
        return metres / (111_195.08 * Math.cos(Math.toRadians(lat)));
    }

    @Test
    void aPointInsideAnAreaFindsThatArea() throws IOException {
        PlaceIndex index = fixture();

        assertThat(index.lookup(18.5, 73.5)).contains("P4");
        assertThat(index.lookup(18.5, 74.5)).contains("P5");
        assertThat(index.lookup(18.1, 76.1)).contains("P6");
        assertThat(index.lookup(10.5, 80.5)).contains("P3");
        assertThat(index.lookup(-16.5, 179.5)).contains("P8");
        assertThat(index.lookup(-14.5, -179.5)).contains("P9");
    }

    @Test
    void ringsAreReadEvenOddSoAHoleIsOutsideItsAreaAndAnAreaInTheHoleIsFound() throws IOException {
        PlaceIndex index = fixture();

        assertThat(index.lookup(18.5, 76.375)).as("in the lake, about 8 km from any shore").isEmpty();
        assertThat(index.lookup(18.5, 76.5)).as("the island in the lake").contains("P7");
        assertThat(index.lookup(18.5, 76.3 + east(18.5, 1000))).as("1 km off Lakeland's lake shore").contains("P6");
    }

    @Test
    void aPointOnABorderGoesToExactlyOneSideAlwaysTheSameOne() throws IOException {
        PlaceIndex index = fixture();

        Optional<String> onEdge = index.lookup(18.5, 74.0);
        assertThat(onEdge).as("the shared edge belongs to one side").isPresent();
        assertThat(onEdge.get()).isIn("P4", "P5");
        for (int i = 0; i < 10; i++) {
            assertThat(index.lookup(18.5, 74.0)).isEqualTo(onEdge);
        }
        assertThat(index.lookup(18.0, 74.0)).as("a shared corner on the coast").isPresent();
        assertThat(index.lookup(19.0, 73.5)).as("on the outer edge").contains("P4");
    }

    @Test
    void aPointOffTheCoastGetsTheNearestAreaWithin2Km() throws IOException {
        PlaceIndex index = fixture();

        assertThat(index.lookup(18.5, 73.0 - east(18.5, 1000))).contains("P4");
        assertThat(index.lookup(18.5, 73.0 - east(18.5, 1900))).contains("P4");
        assertThat(index.lookup(19.0 + 1800 / 111_195.08, 74.6)).as("1.8 km north of Beta").contains("P5");
        // 556 m south of Alpha's southern edge, 1.2 km from Beta's corner.
        assertThat(index.lookup(17.995, 73.99)).contains("P4");
    }

    @Test
    void anExactTieOnTheCoastGoesToTheLowestAreaWhateverTheGridVisitOrder() throws IOException {
        // Two squares meeting at the vertex 1.0E, 0.0N; a point 1.1 km south of it is exactly as far from both.
        PlaceBin.Area east = PlaceBin.box("EAST", 1.0, 0.0, 2.0, 1.0);
        PlaceBin.Area west = PlaceBin.box("WEST", 0.0, 0.0, 1.0, 1.0);

        PlaceIndex eastFirst = PlaceIndex.read(new ByteArrayInputStream(PlaceBin.encode(List.of(east, west))));
        PlaceIndex westFirst = PlaceIndex.read(new ByteArrayInputStream(PlaceBin.encode(List.of(west, east))));

        assertThat(eastFirst.lookup(-0.01, 1.0)).as("lower index EAST starts in a later column").contains("EAST");
        assertThat(westFirst.lookup(-0.01, 1.0)).as("lower index WEST starts in an earlier column").contains("WEST");
    }

    @Test
    void aPointFurtherThan2KmFromEveryAreaHasNoPlace() throws IOException {
        PlaceIndex index = fixture();

        assertThat(index.lookup(18.5, 73.0 - east(18.5, 2100))).isEmpty();
        assertThat(index.lookup(18.5, 72.0)).isEmpty();
        assertThat(index.lookup(15.0, 65.0)).as("mid-sea").isEmpty();
        assertThat(index.lookup(0, 0)).isEmpty();
    }

    @Test
    void theCoastRuleWorksAcrossTheAntimeridian() throws IOException {
        PlaceIndex index = fixture();

        assertThat(index.lookup(-16.5, -180.0 + east(-16.5, 1000))).as("1 km east of Farland, over the line")
                .contains("P8");
        assertThat(index.lookup(-14.5, 180.0 - east(-14.5, 1000))).as("1 km west of Westfar, over the line")
                .contains("P9");
        assertThat(index.lookup(-16.5, -180.0 + east(-16.5, 3000))).isEmpty();
    }

    @Test
    void positionsOutsideTheWorldHaveNoPlace() throws IOException {
        PlaceIndex index = fixture();

        assertThat(index.lookup(91, 73.5)).isEmpty();
        assertThat(index.lookup(18.5, 181)).isEmpty();
        assertThat(index.lookup(Double.NaN, 73.5)).isEmpty();
        assertThat(index.lookup(18.5, Double.POSITIVE_INFINITY)).isEmpty();
        assertThat(index.lookup(90, 0)).isEmpty();
        assertThat(index.lookup(-90, 180)).isEmpty();
    }

    @Test
    void lookupsAgreeWithCheckingEveryAreaAndFiftyThousandTakeUnderFiveSeconds() throws IOException {
        List<PlaceBin.Area> areas = new ArrayList<>(TestPlaces.areas());
        areas.add(new PlaceBin.Area("P10", List.<double[][]>of(PlaceBin.circle(78.5, 18.5, 0.4, 20_000))));
        PlaceIndex index = PlaceIndex.read(new ByteArrayInputStream(PlaceBin.encode(areas)));
        Random random = new Random(42);
        int found = 0;
        for (int i = 0; i < 2_000; i++) {
            double lat = 17.9 + random.nextDouble() * 1.2;
            double lon = 72.9 + random.nextDouble() * 6.2;
            Optional<String> hit = index.lookup(lat, lon);
            String naive = naive(areas, lat, lon);
            if (naive != null) {
                assertThat(hit).as(lat + ", " + lon).contains(naive);
                found++;
            }
        }
        assertThat(found).isGreaterThan(600);

        long start = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < 50_000; i++) {
            if (index.lookup(17.5 + random.nextDouble() * 2, 72.5 + random.nextDouble() * 7).isPresent()) {
                hits++;
            }
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(hits).isGreaterThan(10_000);
        assertThat(millis).as("50,000 lookups, many near a 20,000-point coast").isLessThan(5_000);
    }

    /** The area containing the point by the even-odd rule, checking every area in turn (the slow, obvious way). */
    private static String naive(List<PlaceBin.Area> areas, double lat, double lon) {
        for (PlaceBin.Area area : areas) {
            boolean in = false;
            for (double[][] ring : area.rings()) {
                for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                    double xi = ring[i][0];
                    double yi = ring[i][1];
                    double xj = ring[j][0];
                    double yj = ring[j][1];
                    if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                        in = !in;
                    }
                }
            }
            if (in) {
                return area.id();
            }
        }
        return null;
    }

    @Test
    void aLazyIndexReadsItsFileOnceOnTheFirstLookupFromManyThreads() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        byte[] bin = TestPlaces.bin();
        PlaceIndex index = PlaceIndex.lazy(() -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(bin);
        }, null);
        assertThat(opened).as("nothing read before the first lookup").hasValue(0);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Optional<String>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return index.lookup(18.5, 73.5);
                }));
            }
            go.countDown();
            for (Future<Optional<String>> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).contains("P4");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(opened).hasValue(1);
    }

    @Test
    void aLazyIndexWhoseFileIsDamagedFailsEveryLookupWithoutReadingItAgain() {
        AtomicInteger opened = new AtomicInteger();
        PlaceIndex index = PlaceIndex.lazy(() -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(PlaceBin.gzip("nope".getBytes(StandardCharsets.US_ASCII)));
        }, null);

        assertThatThrownBy(() -> index.lookup(18.5, 73.5)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("place boundaries");
        assertThatThrownBy(() -> index.lookup(18.5, 73.5)).isInstanceOf(IllegalStateException.class);
        assertThat(opened).hasValue(1);
    }

    @Test
    void warmingReadsTheFileOnceSoLookupsDoNotAndADamagedFileIsLeftForTheLookupsToReport() {
        AtomicInteger opened = new AtomicInteger();
        byte[] bin = TestPlaces.bin();
        PlaceIndex index = PlaceIndex.lazy(() -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(bin);
        }, null);
        assertThat(index.decoded()).isFalse();

        index.warm();

        assertThat(index.decoded()).isTrue();
        assertThat(opened).hasValue(1);
        index.warm();
        assertThat(index.lookup(18.5, 73.5)).contains("P4");
        assertThat(opened).as("read once").hasValue(1);

        PlaceIndex damaged = PlaceIndex.lazy(() -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(PlaceBin.gzip("nope".getBytes(StandardCharsets.US_ASCII)));
        }, null);
        damaged.warm();
        assertThat(damaged.decoded()).isTrue();
        assertThatThrownBy(() -> damaged.lookup(18.5, 73.5)).isInstanceOf(IllegalStateException.class);
        assertThat(opened).hasValue(2);
    }

    @Test
    void anEmptyIndexFindsNothing() {
        assertThat(PlaceIndex.empty().lookup(18.5, 73.5)).isEmpty();
    }

    @Test
    void anAreaWhoseIdTheTreeDoesNotKnowIsRefused() {
        PlaceIndex index = PlaceIndex.lazy(() -> new ByteArrayInputStream(TestPlaces.bin()), Set.of("P4", "P5"));

        assertThatThrownBy(() -> index.lookup(18.5, 73.5)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P6");
    }

    @Test
    void damagedFilesAreRefused() {
        assertRefused(PlaceBin.gzip("BAPX\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1)), "not a place boundary file");
        assertRefused(PlaceBin.gzip("BAPL\u0002\u0000".getBytes(StandardCharsets.ISO_8859_1)), "format 2");
        assertRefused("BAPL".getBytes(StandardCharsets.US_ASCII), "");
        byte[] whole = unzip(TestPlaces.bin());
        assertRefused(PlaceBin.gzip(Arrays.copyOf(whole, whole.length - 3)), "");
        byte[] longer = Arrays.copyOf(whole, whole.length + 1);
        assertRefused(PlaceBin.gzip(longer), "after the last area");
        assertRefused(raw("P1", new int[] {0, 0, 10, 10}, new int[][] {{0, 0, 10, 0, 10, 10, 0, 10}}), "not closed");
        assertRefused(raw("P1", new int[] {0, 0, 5, 5}, new int[][] {{0, 0, 10, 0, 10, 10, 0, 10, 0, 0}}), "outside its box");
        assertRefused(raw("P1", new int[] {0, 0, 10, 10}, new int[][] {{0, 0, 10, 0, 0, 0}}), "fewer than 4");
        assertRefused(raw("P1", new int[] {0, 0, 18_000_001, 10}, new int[][] {{0, 0, 18_000_001, 0, 0, 10, 0, 0}}),
                "outside the world");
    }

    private static void assertRefused(byte[] bytes, String message) {
        assertThatThrownBy(() -> PlaceIndex.read(new ByteArrayInputStream(bytes))).isInstanceOf(IOException.class)
                .hasMessageContaining(message);
    }

    /** One area with this box and rings (points as flat x, y lists in units, written as they are). */
    private static byte[] raw(String id, int[] box, int[][] rings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("BAPL".getBytes(StandardCharsets.US_ASCII));
        out.write(1);
        PlaceBin.uvarint(out, 1);
        byte[] name = id.getBytes(StandardCharsets.UTF_8);
        PlaceBin.uvarint(out, name.length);
        out.writeBytes(name);
        for (int v : box) {
            PlaceBin.svarint(out, v);
        }
        PlaceBin.uvarint(out, rings.length);
        for (int[] ring : rings) {
            PlaceBin.uvarint(out, ring.length / 2);
            int x = 0;
            int y = 0;
            for (int i = 0; i < ring.length; i += 2) {
                PlaceBin.svarint(out, ring[i] - x);
                PlaceBin.svarint(out, ring[i + 1] - y);
                x = ring[i];
                y = ring[i + 1];
            }
        }
        return PlaceBin.gzip(out.toByteArray());
    }

    private static byte[] unzip(byte[] gz) {
        try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
