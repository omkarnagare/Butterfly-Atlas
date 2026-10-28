package com.butterflyatlas.app.testing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes place boundary files ({@code places-<date>.bin}, contract §2) for tests: gzip, magic {@code BAPL}, format 1,
 * then per area its id, bounding box and rings, coordinates in 1e-5 degree units (longitude first), each ring's points
 * as deltas from the previous point, the first from 0,0; and reads them back ({@link #decode}).
 */
public final class PlaceBin {

    /** One leaf area: its place id and its rings (outer rings and holes alike), each a list of {lon, lat} in degrees. */
    public record Area(String id, List<double[][]> rings) {
    }

    private PlaceBin() {
    }

    /** A rectangle. */
    public static Area box(String id, double minLon, double minLat, double maxLon, double maxLat) {
        return new Area(id, List.<double[][]>of(rectangle(minLon, minLat, maxLon, maxLat)));
    }

    /** The corners of a rectangle, counter-clockwise from the south-west (not closed; {@link #encode} closes rings). */
    public static double[][] rectangle(double minLon, double minLat, double maxLon, double maxLat) {
        return new double[][] {{minLon, minLat}, {maxLon, minLat}, {maxLon, maxLat}, {minLon, maxLat}};
    }

    /** A circle of {@code points} corners around {@code lon, lat} with a radius in degrees. */
    public static double[][] circle(double lon, double lat, double radius, int points) {
        double[][] ring = new double[points][];
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            ring[i] = new double[] {lon + radius * Math.cos(a), lat + radius * Math.sin(a)};
        }
        return ring;
    }

    public static int units(double degrees) {
        return (int) Math.round(degrees * 100_000);
    }

    /** The gzipped file for these areas, each ring closed (its first point repeated) if it is not already. */
    public static byte[] encode(List<Area> areas) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.writeBytes("BAPL".getBytes(StandardCharsets.US_ASCII));
        raw.write(1);
        uvarint(raw, areas.size());
        for (Area area : areas) {
            List<int[][]> rings = new ArrayList<>();
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (double[][] ring : area.rings()) {
                List<int[]> points = new ArrayList<>();
                for (double[] point : ring) {
                    points.add(new int[] {units(point[0]), units(point[1])});
                }
                if (!Arrays.equals(points.get(0), points.get(points.size() - 1))) {
                    points.add(points.get(0));
                }
                for (int[] p : points) {
                    minX = Math.min(minX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxX = Math.max(maxX, p[0]);
                    maxY = Math.max(maxY, p[1]);
                }
                rings.add(points.toArray(new int[0][]));
            }
            byte[] id = area.id().getBytes(StandardCharsets.UTF_8);
            uvarint(raw, id.length);
            raw.writeBytes(id);
            svarint(raw, minX);
            svarint(raw, minY);
            svarint(raw, maxX);
            svarint(raw, maxY);
            uvarint(raw, rings.size());
            for (int[][] ring : rings) {
                uvarint(raw, ring.length);
                int x = 0;
                int y = 0;
                for (int[] p : ring) {
                    svarint(raw, p[0] - x);
                    svarint(raw, p[1] - y);
                    x = p[0];
                    y = p[1];
                }
            }
        }
        return gzip(raw.toByteArray());
    }

    /** The areas of a boundary file, in its order, coordinates in degrees (each ring closed, as written). */
    public static List<Area> decode(byte[] gzipped) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipped)))) {
            byte[] magic = in.readNBytes(4);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("BAPL") || in.read() != 1) {
                throw new IllegalArgumentException("not a format 1 place boundary file");
            }
            long count = readUvarint(in);
            List<Area> areas = new ArrayList<>();
            for (long a = 0; a < count; a++) {
                String id = new String(in.readNBytes((int) readUvarint(in)), StandardCharsets.UTF_8);
                for (int i = 0; i < 4; i++) {
                    readSvarint(in); // the bounding box: worked out again when encoded
                }
                long ringCount = readUvarint(in);
                List<double[][]> rings = new ArrayList<>();
                for (long r = 0; r < ringCount; r++) {
                    int points = (int) readUvarint(in);
                    double[][] ring = new double[points][];
                    long x = 0;
                    long y = 0;
                    for (int p = 0; p < points; p++) {
                        x += readSvarint(in);
                        y += readSvarint(in);
                        ring[p] = new double[] {x / 100_000.0, y / 100_000.0};
                    }
                    rings.add(ring);
                }
                areas.add(new Area(id, rings));
            }
            return areas;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long readUvarint(InputStream in) throws IOException {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("the boundary file ends early");
            }
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    private static long readSvarint(InputStream in) throws IOException {
        long raw = readUvarint(in);
        return (raw >>> 1) ^ -(raw & 1);
    }

    /** Any bytes, gzipped (for damaged files). */
    public static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static void uvarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    public static void svarint(ByteArrayOutputStream out, long value) {
        uvarint(out, (value << 1) ^ (value >> 63));
    }
}
