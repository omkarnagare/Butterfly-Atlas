package com.butterflyatlas.placeimporter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * An independent reader of the contract's {@code .bin} format (written from contract §2, not from {@link BinWriter}),
 * with the app's lookup rule: the entry containing the point (even-odd), else the nearest within 2 km.
 */
final class BinDecoder {

    record Entry(String id, int[] bbox, List<int[]> rings) {
    }

    static final double COAST_METRES = 2000;

    private BinDecoder() {
    }

    static List<Entry> decode(byte[] gzipped) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            DataInputStream data = new DataInputStream(in);
            byte[] magic = new byte[4];
            data.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("BAPL")) {
                throw new IOException("bad magic");
            }
            if (data.readUnsignedByte() != 1) {
                throw new IOException("bad format");
            }
            long count = uvarint(data);
            List<Entry> entries = new ArrayList<>();
            for (long e = 0; e < count; e++) {
                byte[] id = new byte[(int) uvarint(data)];
                data.readFully(id);
                int[] bbox = {svarint(data), svarint(data), svarint(data), svarint(data)};
                long ringCount = uvarint(data);
                List<int[]> rings = new ArrayList<>();
                for (long r = 0; r < ringCount; r++) {
                    int points = (int) uvarint(data);
                    int x = 0;
                    int y = 0;
                    if (points < 4) {
                        throw new IOException("ring with " + points + " points");
                    }
                    int[] ring = new int[points * 2];
                    for (int p = 0; p < points; p++) {
                        x += svarint(data);
                        y += svarint(data);
                        ring[2 * p] = x;
                        ring[2 * p + 1] = y;
                    }
                    if (ring[0] != ring[ring.length - 2] || ring[1] != ring[ring.length - 1]) {
                        throw new IOException("ring not closed in " + new String(id, StandardCharsets.UTF_8));
                    }
                    rings.add(ring);
                }
                entries.add(new Entry(new String(id, StandardCharsets.UTF_8), bbox, rings));
            }
            if (in.read() != -1) {
                throw new IOException("trailing bytes");
            }
            return entries;
        }
    }

    /** The id of the entry containing the point, else of the nearest entry within 2 km, else null. */
    static String lookup(List<Entry> entries, double lat, double lon) {
        long px = Math.round(lon * 1e5);
        long py = Math.round(lat * 1e5);
        for (Entry entry : entries) {
            int[] b = entry.bbox();
            if (px < b[0] || px > b[2] || py < b[1] || py > b[3]) {
                continue;
            }
            boolean inside = false;
            for (int[] ring : entry.rings()) {
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
            if (inside) {
                return entry.id();
            }
        }
        String nearest = null;
        double best = COAST_METRES;
        double metresPerUnitY = 111_195.08 / 1e5;
        double metresPerUnitX = metresPerUnitY * Math.cos(Math.toRadians(lat));
        for (Entry entry : entries) {
            for (int[] ring : entry.rings()) {
                for (int i = 0; i + 3 < ring.length; i += 2) {
                    double ax = shortWay(ring[i] - px) * metresPerUnitX, ay = (ring[i + 1] - py) * metresPerUnitY;
                    double bx = shortWay(ring[i + 2] - px) * metresPerUnitX, by = (ring[i + 3] - py) * metresPerUnitY;
                    double dx = bx - ax, dy = by - ay;
                    double len = dx * dx + dy * dy;
                    double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
                    double d = Math.hypot(ax + t * dx, ay + t * dy);
                    if (d < best || (d == best && nearest == null)) {
                        best = d;
                        nearest = entry.id();
                    }
                }
            }
        }
        return nearest;
    }

    private static long shortWay(long dx) {
        return dx > 18_000_000 ? dx - 36_000_000 : dx < -18_000_000 ? dx + 36_000_000 : dx;
    }

    private static long uvarint(DataInputStream in) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }

    private static int svarint(DataInputStream in) throws IOException {
        long u = uvarint(in);
        return (int) ((u >>> 1) ^ -(u & 1));
    }
}
