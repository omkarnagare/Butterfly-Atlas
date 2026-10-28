package com.butterflyatlas.placeimporter;

import java.util.List;

/**
 * The app's lookup rule over the {@code .bin} entries, for the tool's own checks: the entry containing the point
 * (even-odd rule over all its rings), else the nearest entry within {@link #COAST_METRES}, else none.
 */
public final class Lookup {

    public static final double COAST_METRES = 2000;
    static final double METRES_PER_DEGREE = 111_195.08;

    private final List<BinWriter.Entry> entries;
    private final int[][] boxes;

    public Lookup(List<BinWriter.Entry> entries) {
        this.entries = entries;
        this.boxes = new int[entries.size()][];
        for (int e = 0; e < entries.size(); e++) {
            int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int[] ring : entries.get(e).rings()) {
                for (int i = 0; i < ring.length; i += 2) {
                    box[0] = Math.min(box[0], ring[i]);
                    box[1] = Math.min(box[1], ring[i + 1]);
                    box[2] = Math.max(box[2], ring[i]);
                    box[3] = Math.max(box[3], ring[i + 1]);
                }
            }
            boxes[e] = box;
        }
    }

    /** The id of the entry for this point, or null. */
    public String find(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return null;
        }
        int px = (int) Math.round(lon * 1e5);
        int py = (int) Math.round(lat * 1e5);
        for (int e = 0; e < entries.size(); e++) {
            int[] b = boxes[e];
            if (px >= b[0] && px <= b[2] && py >= b[1] && py <= b[3] && contains(entries.get(e).rings(), px, py)) {
                return entries.get(e).id();
            }
        }
        double cos = Math.cos(Math.toRadians(lat));
        double scaleX = cos * METRES_PER_DEGREE / 1e5;
        double scaleY = METRES_PER_DEGREE / 1e5;
        int dy = (int) Math.ceil(COAST_METRES / METRES_PER_DEGREE * 1e5);
        double lonDegrees = cos < 1e-9 ? 360 : Math.min(360, COAST_METRES / (METRES_PER_DEGREE * cos));
        int dx = (int) Math.ceil(lonDegrees * 1e5);
        String nearest = null;
        double best = COAST_METRES;
        for (int e = 0; e < entries.size(); e++) {
            if (!near(boxes[e], px, py, dx, dy)) {
                continue;
            }
            for (int[] ring : entries.get(e).rings()) {
                double ax = wrap(ring[0] - px) * scaleX;
                double ay = (ring[1] - (long) py) * scaleY;
                for (int i = 2; i + 1 < ring.length; i += 2) {
                    double bx = wrap(ring[i] - px) * scaleX;
                    double by = (ring[i + 1] - (long) py) * scaleY;
                    double d = segmentDistance(ax, ay, bx, by);
                    if (d < best || (d == best && nearest == null)) {
                        best = d;
                        nearest = entries.get(e).id();
                    }
                    ax = bx;
                    ay = by;
                }
            }
        }
        return nearest;
    }

    /** Whether the box, grown by dx and dy, holds the point (or the point one turn of the globe east or west). */
    private static boolean near(int[] box, int x, int y, int dx, int dy) {
        if (y < box[1] - dy || y > box[3] + dy) {
            return false;
        }
        for (long shift : new long[]{0, 36_000_000L, -36_000_000L}) {
            long sx = x + shift;
            if (sx >= box[0] - (long) dx && sx <= box[2] + (long) dx) {
                return true;
            }
        }
        return false;
    }

    /** Even-odd rule on integers, as the app: a ray from the point towards the east crossing the rings an odd number of times. */
    static boolean contains(List<int[]> rings, long px, long py) {
        boolean inside = false;
        for (int[] ring : rings) {
            for (int i = 0; i + 3 < ring.length; i += 2) {
                long x1 = ring[i], y1 = ring[i + 1], x2 = ring[i + 2], y2 = ring[i + 3];
                if ((y1 > py) != (y2 > py)) {
                    long dy = y2 - y1;
                    long left = (px - x1) * dy;
                    long right = (x2 - x1) * (py - y1);
                    if (dy > 0 ? left < right : left > right) {
                        inside = !inside;
                    }
                }
            }
        }
        return inside;
    }

    /** A longitude difference in units, taken the short way round (within ±180°). */
    private static long wrap(long dx) {
        if (dx > 18_000_000) {
            return dx - 36_000_000L;
        }
        if (dx < -18_000_000) {
            return dx + 36_000_000L;
        }
        return dx;
    }

    /** Distance from the origin to the segment a–b (metres in, metres out). */
    private static double segmentDistance(double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }
}
