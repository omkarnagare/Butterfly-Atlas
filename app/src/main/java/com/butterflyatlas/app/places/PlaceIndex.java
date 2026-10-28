package com.butterflyatlas.app.places;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Finds the leaf place (an Indian district; a state elsewhere; a country without states) a position lies in, offline,
 * from the bundled boundaries ({@code places-<date>.bin}, contract §2). Coordinates are integers in 1e-5 degree units;
 * each area's rings are read with the even-odd rule, so holes need no marker. Candidates come from a grid of 1° cells
 * listing the areas whose bounding box touches each cell. When no area contains the point, the area with the nearest
 * edge within {@link #COAST_METRES} wins (beaches, mangroves, simplified coasts), measured on a local flat projection
 * around the point, across the antimeridian too; further out there is no place. Areas are expected not to cross the
 * antimeridian (a polygon that does is split there by the tool, as Natural Earth's are).
 *
 * <p>A point exactly on an edge shared by two areas goes to one of them, always the same one (the half-open crossing
 * rule gives it to the area on its east or north side); a point on an outer edge is 0 m from its area.
 *
 * <p>An index made by {@link #lazy} reads its file on the first lookup; from then on it is immutable and lookups may
 * run on any number of threads. If the file cannot be read, every lookup fails with {@link IllegalStateException}
 * (the file is not read again).
 */
public final class PlaceIndex {

    public static final double COAST_METRES = 2000;

    /** Coordinate units per degree. */
    static final int UNITS = 100_000;
    /** Metres per degree of latitude (and of longitude at the equator), on a sphere of the mean Earth radius. */
    static final double METRES_PER_DEGREE = 111_195.08;

    private static final byte[] MAGIC = "BAPL".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT = 1;
    private static final int MAX_LON = 180 * UNITS;
    private static final int MAX_LAT = 90 * UNITS;
    private static final int COLUMNS = 360;
    private static final int ROWS = 180;
    private static final int[] NONE = new int[0];

    /** Opens the gzipped boundary file. */
    @FunctionalInterface
    interface Source {
        InputStream open() throws IOException;
    }

    /** The decoded areas: ids, boxes ({minX, minY, maxX, maxY}), rings (x, y, x, y, … closed) and the grid of cells. */
    private record Data(String[] ids, int[][] boxes, int[][][] rings, int[][] cells) {
    }

    private Source source;
    private final Set<String> known;
    private final Object lock = new Object();
    private volatile Data data;
    private IllegalStateException failure;

    private PlaceIndex(Source source, Set<String> known, Data data) {
        this.source = source;
        this.known = known;
        this.data = data;
    }

    /** Reads a whole boundary file now (tests build fixtures with it). */
    static PlaceIndex read(InputStream gzippedBin) throws IOException {
        return new PlaceIndex(null, null, decode(gzippedBin, null));
    }

    /**
     * An index that reads {@code source} on its first lookup. {@code known}: the ids the place tree holds (an area with
     * any other id makes the file unusable); null to accept any.
     */
    static PlaceIndex lazy(Source source, Set<String> known) {
        return new PlaceIndex(source, known == null ? null : Set.copyOf(known), null);
    }

    /** An index without areas: every lookup finds nothing. */
    public static PlaceIndex empty() {
        return new PlaceIndex(null, null, build(new String[0], new int[0][], new int[0][][]));
    }

    /** The leaf place containing the point, else the nearest leaf within {@link #COAST_METRES}, else empty. */
    public Optional<String> lookup(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return Optional.empty();
        }
        Data d = data();
        int x = (int) Math.round(lon * UNITS);
        int y = (int) Math.round(lat * UNITS);
        for (int area : d.cells()[cell(column(x), row(y))]) {
            int[] box = d.boxes()[area];
            if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3] && inside(d.rings()[area], x, y)) {
                return Optional.of(d.ids()[area]);
            }
        }
        return nearest(d, x, y, lat);
    }

    /**
     * Reads the boundaries now if they have not been read, so the first lookup does not (inside a caller's write
     * transaction, say). A file that cannot be read is left for every lookup to report.
     */
    public void warm() {
        try {
            data();
        } catch (IllegalStateException e) {
            // every lookup reports it
        }
    }

    /** Whether the file has been read (or found unreadable). */
    boolean decoded() {
        synchronized (lock) {
            return data != null || failure != null;
        }
    }

    private Data data() {
        Data loaded = data;
        if (loaded != null) {
            return loaded;
        }
        synchronized (lock) {
            if (data != null) {
                return data;
            }
            if (failure != null) {
                throw failure;
            }
            try (InputStream in = source.open()) {
                data = decode(in, known);
                source = null;
                return data;
            } catch (IOException | RuntimeException e) {
                failure = new IllegalStateException("The place boundaries could not be read: " + e.getMessage(), e);
                throw failure;
            }
        }
    }

    /** Even-odd rule on integers: whether a ray from the point towards the east crosses the rings an odd number of times. */
    static boolean inside(int[][] rings, long px, long py) {
        boolean in = false;
        for (int[] ring : rings) {
            for (int i = 0; i + 3 < ring.length; i += 2) {
                long x1 = ring[i];
                long y1 = ring[i + 1];
                long x2 = ring[i + 2];
                long y2 = ring[i + 3];
                if ((y1 > py) != (y2 > py)) {
                    long dy = y2 - y1;
                    long left = (px - x1) * dy;
                    long right = (x2 - x1) * (py - y1);
                    if (dy > 0 ? left < right : left > right) {
                        in = !in;
                    }
                }
            }
        }
        return in;
    }

    /** The area with the nearest edge within {@link #COAST_METRES} of the point, if any. */
    private static Optional<String> nearest(Data d, int x, int y, double lat) {
        double cos = Math.cos(Math.toRadians(lat));
        double latDegrees = COAST_METRES / METRES_PER_DEGREE;
        double lonDegrees = cos < 1e-9 ? 360 : Math.min(360, COAST_METRES / (METRES_PER_DEGREE * cos));
        int dy = (int) Math.ceil(latDegrees * UNITS);
        int dx = (int) Math.ceil(lonDegrees * UNITS);
        BitSet seen = new BitSet(d.ids().length);
        int best = -1;
        double bestMetres = COAST_METRES;
        int fromColumn = Math.floorDiv(x - dx, UNITS);
        int toColumn = Math.floorDiv(x + dx, UNITS);
        if (toColumn - fromColumn >= COLUMNS) {
            fromColumn = -180;
            toColumn = 179;
        }
        for (int r = row(y - dy); r <= row(y + dy); r++) {
            for (int c = fromColumn; c <= toColumn; c++) {
                for (int area : d.cells()[cell(Math.floorMod(c + 180, COLUMNS), r)]) {
                    if (seen.get(area)) {
                        continue;
                    }
                    seen.set(area);
                    if (!near(d.boxes()[area], x, y, dx, dy)) {
                        continue;
                    }
                    double metres = distance(d.rings()[area], x, y, cos);
                    if (metres < bestMetres || (metres == bestMetres && (best < 0 || area < best))) {
                        bestMetres = metres;
                        best = area;
                    }
                }
            }
        }
        return best < 0 ? Optional.empty() : Optional.of(d.ids()[best]);
    }

    /** Whether the box, grown by dx and dy, holds the point (or the point one turn of the globe east or west). */
    private static boolean near(int[] box, int x, int y, int dx, int dy) {
        if (y < box[1] - dy || y > box[3] + dy) {
            return false;
        }
        for (long shift : new long[] {0, 360L * UNITS, -360L * UNITS}) {
            long sx = x + shift;
            if (sx >= box[0] - (long) dx && sx <= box[2] + (long) dx) {
                return true;
            }
        }
        return false;
    }

    /** Metres from the point to the nearest edge of the rings, on a flat projection centred on the point. */
    private static double distance(int[][] rings, int x, int y, double cos) {
        double scaleX = cos * METRES_PER_DEGREE / UNITS;
        double scaleY = METRES_PER_DEGREE / UNITS;
        double best = Double.MAX_VALUE;
        for (int[] ring : rings) {
            double ax = wrap(ring[0] - x) * scaleX;
            double ay = (ring[1] - (long) y) * scaleY;
            for (int i = 2; i + 1 < ring.length; i += 2) {
                double bx = wrap(ring[i] - x) * scaleX;
                double by = (ring[i + 1] - (long) y) * scaleY;
                best = Math.min(best, toSegment(ax, ay, bx, by));
                ax = bx;
                ay = by;
            }
        }
        return best;
    }

    /** A longitude difference in units, taken the short way round (within ±180°). */
    private static long wrap(long dx) {
        if (dx > MAX_LON) {
            return dx - 2L * MAX_LON;
        }
        if (dx < -MAX_LON) {
            return dx + 2L * MAX_LON;
        }
        return dx;
    }

    /** Distance from the origin to the segment a–b. */
    private static double toSegment(double ax, double ay, double bx, double by) {
        double vx = bx - ax;
        double vy = by - ay;
        double length = vx * vx + vy * vy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax * vx + ay * vy) / length));
        return Math.hypot(ax + t * vx, ay + t * vy);
    }

    private static int column(int x) {
        return Math.min(COLUMNS - 1, Math.max(0, Math.floorDiv(x, UNITS) + 180));
    }

    private static int row(int y) {
        return Math.min(ROWS - 1, Math.max(0, Math.floorDiv(y, UNITS) + 90));
    }

    private static int cell(int column, int row) {
        return row * COLUMNS + column;
    }

    // ---- reading the file ----

    private static final int MAX_AREAS = 1_000_000;
    private static final int MAX_ID_BYTES = 64;
    private static final int MAX_RINGS = 1_000_000;
    private static final int MAX_POINTS = 50_000_000;

    private static Data decode(InputStream gzipped, Set<String> known) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(gzipped), 1 << 16))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("This is not a place boundary file.");
            }
            int format = in.read();
            if (format != FORMAT) {
                throw new IOException("The place boundary file has format " + format + "; this version reads format " + FORMAT + ".");
            }
            int count = (int) limited(uvarint(in), MAX_AREAS, "areas");
            String[] ids = new String[count];
            int[][] boxes = new int[count][];
            int[][][] rings = new int[count][][];
            Set<String> seen = new HashSet<>();
            for (int a = 0; a < count; a++) {
                int length = (int) limited(uvarint(in), MAX_ID_BYTES, "id bytes");
                byte[] id = in.readNBytes(length);
                if (id.length != length) {
                    throw new EOFException();
                }
                ids[a] = new String(id, StandardCharsets.UTF_8);
                if (!seen.add(ids[a])) {
                    throw new IOException("The place boundary file lists " + ids[a] + " twice.");
                }
                if (known != null && !known.contains(ids[a])) {
                    throw new IOException("The place boundary file has an area " + ids[a] + " the place list does not know.");
                }
                int[] box = {coordinate(in, MAX_LON), coordinate(in, MAX_LAT), coordinate(in, MAX_LON), coordinate(in, MAX_LAT)};
                if (box[0] > box[2] || box[1] > box[3]) {
                    throw new IOException("The box of " + ids[a] + " is empty.");
                }
                boxes[a] = box;
                int ringCount = (int) limited(uvarint(in), MAX_RINGS, "rings");
                if (ringCount < 1) {
                    throw new IOException(ids[a] + " has no rings.");
                }
                rings[a] = new int[ringCount][];
                for (int r = 0; r < ringCount; r++) {
                    rings[a][r] = ring(in, ids[a], box);
                }
            }
            if (in.read() != -1) {
                throw new IOException("The place boundary file goes on after the last area.");
            }
            return build(ids, boxes, rings);
        } catch (EOFException e) {
            throw new IOException("The place boundary file ends too early.", e);
        }
    }

    private static int[] ring(DataInputStream in, String id, int[] box) throws IOException {
        int points = (int) limited(uvarint(in), MAX_POINTS, "points");
        if (points < 4) {
            throw new IOException("A ring of " + id + " has fewer than 4 points.");
        }
        int[] ring = new int[Math.min(points, 1024) * 2];
        long x = 0;
        long y = 0;
        for (int p = 0; p < points; p++) {
            x += svarint(in);
            y += svarint(in);
            if (Math.abs(x) > MAX_LON || Math.abs(y) > MAX_LAT) {
                throw new IOException("A point of " + id + " lies outside the world.");
            }
            if (x < box[0] || x > box[2] || y < box[1] || y > box[3]) {
                throw new IOException("A point of " + id + " lies outside its box.");
            }
            if (2 * p + 1 >= ring.length) {
                ring = Arrays.copyOf(ring, (int) Math.min(2L * points, 2L * ring.length));
            }
            ring[2 * p] = (int) x;
            ring[2 * p + 1] = (int) y;
        }
        if (ring[0] != ring[ring.length - 2] || ring[1] != ring[ring.length - 1]) {
            throw new IOException("A ring of " + id + " is not closed.");
        }
        return ring;
    }

    /** The grid: for each 1° cell, the areas whose box touches it (counted first, then filled, so no lists are kept). */
    private static Data build(String[] ids, int[][] boxes, int[][][] rings) {
        int[] counts = new int[COLUMNS * ROWS];
        for (int[] box : boxes) {
            for (int r = row(box[1]); r <= row(box[3]); r++) {
                for (int c = column(box[0]); c <= column(box[2]); c++) {
                    counts[cell(c, r)]++;
                }
            }
        }
        int[][] cells = new int[COLUMNS * ROWS][];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = counts[i] == 0 ? NONE : new int[counts[i]];
            counts[i] = 0;
        }
        for (int a = 0; a < boxes.length; a++) {
            int[] box = boxes[a];
            for (int r = row(box[1]); r <= row(box[3]); r++) {
                for (int c = column(box[0]); c <= column(box[2]); c++) {
                    int at = cell(c, r);
                    cells[at][counts[at]++] = a;
                }
            }
        }
        return new Data(ids, boxes, rings, cells);
    }

    private static int coordinate(DataInputStream in, int max) throws IOException {
        long value = svarint(in);
        if (value < -max || value > max) {
            throw new IOException("A box lies outside the world.");
        }
        return (int) value;
    }

    private static long limited(long value, long max, String what) throws IOException {
        if (value < 0 || value > max) {
            throw new IOException("The place boundary file has too many " + what + " (" + value + ").");
        }
        return value;
    }

    private static long uvarint(DataInputStream in) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("The place boundary file holds a number that is too long.");
    }

    private static long svarint(DataInputStream in) throws IOException {
        long n = uvarint(in);
        return (n >>> 1) ^ -(n & 1);
    }
}
