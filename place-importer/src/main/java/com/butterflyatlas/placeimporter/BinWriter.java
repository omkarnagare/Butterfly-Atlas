package com.butterflyatlas.placeimporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the boundary file of contract §2: gzip of magic {@code BAPL}, format 1, the entry count, then per entry its
 * id, bounding box and rings. Coordinates are integers in 1e-5 degree (longitude first), each point a zig-zag varint
 * delta from the previous point of its ring; the first point of every ring is a delta from 0,0 (its plain value).
 */
public final class BinWriter {

    /** A leaf's boundary: its place id and closed rings as {@code x0, y0, x1, y1, …} (outer rings and holes alike). */
    public record Entry(String id, List<int[]> rings) {
    }

    private BinWriter() {
    }

    public static byte[] write(List<Entry> entries) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.writeBytes("BAPL".getBytes(StandardCharsets.US_ASCII));
        raw.write(1);
        uvarint(raw, entries.size());
        for (Entry entry : entries) {
            if (entry.rings().isEmpty()) {
                throw new IllegalArgumentException(entry.id() + " has no rings");
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int[] ring : entry.rings()) {
                if (ring.length % 2 != 0) {
                    throw new IllegalArgumentException("A ring of " + entry.id() + " has an odd number of coordinates ("
                            + ring.length + ")");
                }
                if (ring.length < 8) {
                    throw new IllegalArgumentException("A ring of " + entry.id() + " needs at least 4 points");
                }
                if (ring[0] != ring[ring.length - 2] || ring[1] != ring[ring.length - 1]) {
                    throw new IllegalArgumentException("A ring of " + entry.id() + " is not closed");
                }
                for (int i = 0; i < ring.length; i += 2) {
                    minX = Math.min(minX, ring[i]);
                    maxX = Math.max(maxX, ring[i]);
                    minY = Math.min(minY, ring[i + 1]);
                    maxY = Math.max(maxY, ring[i + 1]);
                }
            }
            byte[] id = entry.id().getBytes(StandardCharsets.UTF_8);
            uvarint(raw, id.length);
            raw.writeBytes(id);
            svarint(raw, minX);
            svarint(raw, minY);
            svarint(raw, maxX);
            svarint(raw, maxY);
            uvarint(raw, entry.rings().size());
            for (int[] ring : entry.rings()) {
                uvarint(raw, ring.length / 2);
                long x = 0;
                long y = 0;
                for (int i = 0; i < ring.length; i += 2) {
                    svarint(raw, ring[i] - x);
                    svarint(raw, ring[i + 1] - y);
                    x = ring[i];
                    y = ring[i + 1];
                }
            }
        }
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            raw.writeTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return gzipped.toByteArray();
    }

    static void uvarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    static void svarint(ByteArrayOutputStream out, long value) {
        uvarint(out, (value << 1) ^ (value >> 63));
    }
}
