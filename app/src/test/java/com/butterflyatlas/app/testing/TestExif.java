package com.butterflyatlas.app.testing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera metadata written by hand, so tests can check what the app reads from it: an EXIF block
 * (IFD0, the Exif sub-IFD, GPS, a maker note and an embedded thumbnail in IFD1) and an XMP packet
 * for a JPEG, or a small uncompressed RGB TIFF, optionally marked as a DNG. Big-endian TIFF
 * structures, built in one pass with offsets patched in afterwards.
 */
public final class TestExif {

    private static final int BYTE = 1;
    private static final int ASCII = 2;
    private static final int SHORT = 3;
    private static final int LONG = 4;
    private static final int RATIONAL = 5;
    private static final int UNDEFINED = 7;

    private String make = "Canon";
    private String model = "EOS R7";
    private String dateTimeOriginal;
    private String offsetTimeOriginal;
    private double[] gps;
    private byte[] makerNote;
    private boolean thumbnail;
    private String xmp;
    private boolean dng;
    private int leading;
    private int orientation = 1;

    private TestExif() {
    }

    public static TestExif camera() {
        return new TestExif();
    }

    /** {@code "2024:05:01 10:22:33"}, and optionally the offset {@code "+05:30"} (null for none). */
    public TestExif taken(String dateTimeOriginal, String offset) {
        this.dateTimeOriginal = dateTimeOriginal;
        this.offsetTimeOriginal = offset;
        return this;
    }

    /** Latitude and longitude in degrees (negative = S / W) and altitude in metres (negative = below sea level). */
    public TestExif gps(double lat, double lon, double altitude) {
        this.gps = new double[] {lat, lon, altitude};
        return this;
    }

    /** A maker note (Exif sub-IFD tag 0x927C) of this many {@code M} bytes. */
    public TestExif makerNote(int bytes) {
        byte[] note = new byte[bytes];
        Arrays.fill(note, (byte) 'M');
        this.makerNote = note;
        return this;
    }

    /** A maker note holding this text, as some vendors store a city, state or landmark there. */
    public TestExif makerNote(String text) {
        this.makerNote = text.getBytes(StandardCharsets.ISO_8859_1);
        return this;
    }

    public TestExif withThumbnail() {
        this.thumbnail = true;
        return this;
    }

    /** An XMP packet (APP1 "http://ns.adobe.com/xap/1.0/") with this RDF description body. */
    public TestExif xmp(String descriptionBody) {
        this.xmp = descriptionBody;
        return this;
    }

    /** For {@link #tiff}: this many filler bytes between the header and IFD0, so the directories sit far into the file. */
    public TestExif leadingBytes(int bytes) {
        this.leading = bytes;
        return this;
    }

    /** The EXIF orientation 1..8 in IFD0 (1 when not set). */
    public TestExif orientation(int orientation) {
        this.orientation = orientation;
        return this;
    }

    /** For {@link #tiff}: IFD0 carries DNGVersion 1.4.0.0, as every DNG does. */
    public TestExif dng() {
        this.dng = true;
        return this;
    }

    /** The JPEG with this EXIF block (and XMP packet, if any) inserted after its JFIF header. */
    public byte[] jpeg(byte[] jpeg) {
        Ifd ifd0 = cameraIfd();
        ByteArrayOutputStream segments = new ByteArrayOutputStream();
        segments.writeBytes(app1("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiffBytes(ifd0, 0)));
        if (xmp != null) {
            segments.writeBytes(app1("http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1),
                    xmpPacket(xmp).getBytes(StandardCharsets.UTF_8)));
        }
        int at = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        out.writeBytes(segments.toByteArray());
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }

    /** Only the EXIF block's TIFF structure (IFD0 and what it points to), as a HEIF file's Exif item holds it. */
    public byte[] tiffBlock() {
        return tiffBytes(cameraIfd(), 0);
    }

    /** An uncompressed 8-bit RGB TIFF of {@code width}×{@code height}, left half red, right half blue, with this metadata in IFD0. */
    public byte[] tiff(int width, int height) {
        Ifd ifd0 = cameraIfd();
        ifd0.add(256, SHORT, 1, u16(width));
        ifd0.add(257, SHORT, 1, u16(height));
        ifd0.add(258, SHORT, 3, concat(u16(8), u16(8), u16(8)));
        ifd0.add(259, SHORT, 1, u16(1));       // no compression
        ifd0.add(262, SHORT, 1, u16(2));       // RGB
        ifd0.add(277, SHORT, 1, u16(3));       // samples per pixel
        ifd0.add(278, SHORT, 1, u16(height));  // one strip
        ifd0.add(284, SHORT, 1, u16(1));       // chunky
        if (dng) {
            ifd0.add(0xC612, BYTE, 4, new byte[] {1, 4, 0, 0});
        }
        byte[] pixels = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 3;
                boolean left = x < width / 2;
                pixels[i] = (byte) (left ? 0xE0 : 0x10);
                pixels[i + 1] = 0x10;
                pixels[i + 2] = (byte) (left ? 0x10 : 0xE0);
            }
        }
        ifd0.blob(273, 279, pixels);
        return tiffBytes(ifd0, leading);
    }

    private Ifd cameraIfd() {
        Ifd ifd0 = new Ifd();
        ifd0.add(0x010F, ASCII, make);
        ifd0.add(0x0110, ASCII, model);
        ifd0.add(0x0112, SHORT, 1, u16(orientation));
        Ifd exif = new Ifd();
        exif.add(0x829A, RATIONAL, 1, rational(1, 500));     // exposure time
        exif.add(0x829D, RATIONAL, 1, rational(56, 10));     // f-number
        exif.add(0x8827, SHORT, 1, u16(400));                // ISO
        if (dateTimeOriginal != null) {
            exif.add(0x9003, ASCII, dateTimeOriginal);
        }
        if (offsetTimeOriginal != null) {
            exif.add(0x9011, ASCII, offsetTimeOriginal);
        }
        if (makerNote != null) {
            exif.add(0x927C, UNDEFINED, makerNote.length, makerNote);
        }
        ifd0.child(0x8769, exif);
        if (gps != null) {
            Ifd g = new Ifd();
            g.add(0x0000, BYTE, 4, new byte[] {2, 3, 0, 0});
            g.add(0x0001, ASCII, gps[0] < 0 ? "S" : "N");
            g.add(0x0002, RATIONAL, 3, dms(Math.abs(gps[0])));
            g.add(0x0003, ASCII, gps[1] < 0 ? "W" : "E");
            g.add(0x0004, RATIONAL, 3, dms(Math.abs(gps[1])));
            g.add(0x0005, BYTE, 1, new byte[] {(byte) (gps[2] < 0 ? 1 : 0)});
            g.add(0x0006, RATIONAL, 1, rational(Math.round(Math.abs(gps[2]) * 10), 10));
            ifd0.child(0x8825, g);
        }
        if (thumbnail) {
            Ifd ifd1 = new Ifd();
            ifd1.add(0x0103, SHORT, 1, u16(6)); // JPEG-compressed thumbnail
            ifd1.blob(0x0201, 0x0202, TestImages.jpeg(160, 120));
            ifd0.next = ifd1;
        }
        return ifd0;
    }

    private static String xmpPacket(String body) {
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\">"
                + body + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
    }

    private static byte[] app1(byte[] identifier, byte[] payload) {
        int length = 2 + identifier.length + payload.length;
        if (length > 0xFFFF) {
            throw new IllegalArgumentException("APP1 segment too long: " + length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(identifier);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] dms(double degrees) {
        int d = (int) degrees;
        double minutesFull = (degrees - d) * 60;
        int m = (int) minutesFull;
        long secondsHundredths = Math.round((minutesFull - m) * 60 * 100);
        return concat(rational(d, 1), rational(m, 1), rational(secondsHundredths, 100));
    }

    private static byte[] rational(long numerator, long denominator) {
        return concat(u32(numerator), u32(denominator));
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] u32(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private record Entry(int tag, int type, int count, byte[] value) {
    }

    /** One image file directory: plain entries, sub-IFDs behind pointer tags, at most one data blob, and the next IFD. */
    private static final class Ifd {
        final List<Entry> entries = new ArrayList<>();
        final Map<Integer, Ifd> children = new LinkedHashMap<>();
        int blobOffsetTag;
        int blobLengthTag;
        byte[] blob;
        Ifd next;

        void add(int tag, int type, int count, byte[] value) {
            entries.add(new Entry(tag, type, count, value));
        }

        void add(int tag, int type, String text) {
            byte[] bytes = (text + "\0").getBytes(StandardCharsets.ISO_8859_1);
            entries.add(new Entry(tag, type, bytes.length, bytes));
        }

        void child(int pointerTag, Ifd ifd) {
            children.put(pointerTag, ifd);
        }

        void blob(int offsetTag, int lengthTag, byte[] data) {
            blobOffsetTag = offsetTag;
            blobLengthTag = lengthTag;
            blob = data;
        }
    }

    /** A growable big-endian buffer whose earlier 32-bit values can be patched. */
    private static final class Out {
        private byte[] bytes = new byte[1024];
        private int size;

        int pos() {
            return size;
        }

        void put(byte[] data) {
            if (size + data.length > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + data.length));
            }
            System.arraycopy(data, 0, bytes, size, data.length);
            size += data.length;
        }

        void patch32(int at, long value) {
            byte[] v = u32(value);
            System.arraycopy(v, 0, bytes, at, 4);
        }

        byte[] toArray() {
            return Arrays.copyOf(bytes, size);
        }
    }

    private static byte[] tiffBytes(Ifd ifd0, int leading) {
        Out out = new Out();
        out.put(new byte[] {'M', 'M', 0, 42});
        int first = out.pos();
        out.put(u32(0));
        out.put(new byte[leading]);
        out.patch32(first, writeIfd(out, ifd0));
        return out.toArray();
    }

    private static int writeIfd(Out out, Ifd ifd) {
        List<Entry> all = new ArrayList<>(ifd.entries);
        for (int tag : ifd.children.keySet()) {
            all.add(new Entry(tag, LONG, 1, u32(0)));
        }
        if (ifd.blob != null) {
            all.add(new Entry(ifd.blobOffsetTag, LONG, 1, u32(0)));
            all.add(new Entry(ifd.blobLengthTag, LONG, 1, u32(ifd.blob.length)));
        }
        all.sort(Comparator.comparingInt(Entry::tag));
        int start = out.pos();
        out.put(u16(all.size()));
        Map<Integer, Integer> valueAt = new LinkedHashMap<>(); // tag → position of its value field
        List<Entry> big = new ArrayList<>();
        for (Entry e : all) {
            out.put(u16(e.tag()));
            out.put(u16(e.type()));
            out.put(u32(e.count()));
            valueAt.put(e.tag(), out.pos());
            out.put(Arrays.copyOf(e.value(), 4)); // the value itself when it fits, else a placeholder for its offset
            if (e.value().length > 4) {
                big.add(e);
            }
        }
        int nextAt = out.pos();
        out.put(u32(0));
        for (Entry e : big) {
            out.patch32(valueAt.get(e.tag()), out.pos());
            out.put(e.value());
            if (e.value().length % 2 == 1) {
                out.put(new byte[1]);
            }
        }
        for (Map.Entry<Integer, Ifd> child : ifd.children.entrySet()) {
            out.patch32(valueAt.get(child.getKey()), writeIfd(out, child.getValue()));
        }
        if (ifd.blob != null) {
            out.patch32(valueAt.get(ifd.blobOffsetTag), out.pos());
            out.put(ifd.blob);
        }
        if (ifd.next != null) {
            out.patch32(nextAt, writeIfd(out, ifd.next));
        }
        return start;
    }
}
