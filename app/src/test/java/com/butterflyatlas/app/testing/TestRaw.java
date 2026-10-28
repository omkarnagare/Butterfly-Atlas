package com.butterflyatlas.app.testing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Camera RAW files made in code, small enough for tests: TIFF-style files (little-endian, like every camera here but
 * old Olympus and Minolta) with directories and JPEG previews where the real ones keep them, ORF with an Olympus maker
 * note, RW2 with {@code JpgFromRaw}, CR3 boxes and a RAF header. The previews are {@link TestImages} JPEGs. Nothing here
 * decodes; the files only need the structure the readers walk.
 */
public final class TestRaw {

    public static final int BYTE = 1;
    public static final int ASCII = 2;
    public static final int SHORT = 3;
    public static final int LONG = 4;
    public static final int RATIONAL = 5;
    public static final int UNDEFINED = 7;

    private TestRaw() {
    }

    /** A 1024×768 JPEG (left half red, right half blue): the smallest usable preview. */
    public static byte[] preview() {
        return TestImages.jpeg(1024, 768);
    }

    /** A JPEG header with a lossless frame (SOF3), as a DNG's or CR2's sensor data starts: never a preview. */
    public static byte[] lossless(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC3, 0, 11, 8,
                (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 1, 1, 0x11, 0});
        out.writeBytes(new byte[64]);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    // ---- TIFF-style ----

    /** A little-endian TIFF ({@code II*\0}) with this IFD0. */
    public static byte[] tiff(Ifd ifd0) {
        return tiff(new byte[] {'I', 'I', 42, 0}, ifd0);
    }

    /** A CR2: TIFF with Canon's {@code CR} mark at byte 8 (the IFD0 offset then points past it). */
    public static byte[] cr2(Ifd ifd0) {
        return layout(new byte[] {'I', 'I', 42, 0, 0, 0, 0, 0, 'C', 'R', 2, 0, 0, 0, 0, 0}, 4, ifd0);
    }

    /** An ORF ({@code IIRO}) with this IFD0. */
    public static byte[] orf(Ifd ifd0) {
        return tiff(new byte[] {'I', 'I', 'R', 'O'}, ifd0);
    }

    /** An RW2 ({@code IIU\0}) with this IFD0. */
    public static byte[] rw2(Ifd ifd0) {
        return tiff(new byte[] {'I', 'I', 'U', 0}, ifd0);
    }

    /** A TIFF with these four header bytes (byte order and magic) and IFD0 right after the 8-byte header. */
    public static byte[] tiff(byte[] magic, Ifd ifd0) {
        return layout(new byte[] {magic[0], magic[1], magic[2], magic[3], 0, 0, 0, 0}, 4, ifd0);
    }

    /**
     * An Olympus maker note ({@code OLYMPUS\0II\3\0}, offsets from its own start) whose CameraSettings directory
     * (0x2020) points at this JPEG with PreviewImageValid/Start/Length (0x100–0x102).
     */
    public static byte[] olympusMakerNote(byte[] jpeg) {
        Ifd camera = new Ifd().longValue(0x0100, 1).blob(0x0101, jpeg).longValue(0x0102, jpeg.length);
        Ifd note = new Ifd().child(0x2020, camera);
        return layout("OLYMPUS\0II\u0003\0".getBytes(StandardCharsets.ISO_8859_1), -1, note);
    }

    /** Writes {@code prefix}, then the IFD tree after it (offsets from the start); patches IFD0's offset at {@code pointerAt} (-1: none). */
    private static byte[] layout(byte[] prefix, int pointerAt, Ifd ifd0) {
        Buf out = new Buf();
        out.put(prefix);
        int at = write(out, ifd0);
        if (pointerAt >= 0) {
            out.patch32(pointerAt, at);
        }
        return out.toArray();
    }

    private record Entry(int tag, int type, long count, byte[] inline, byte[] data, byte[] blob, List<Ifd> ifds) {
    }

    /** One directory: entries (sorted by tag when written), data blobs they point at, child directories and the next one. */
    public static final class Ifd {
        private final List<Entry> entries = new ArrayList<>();
        private Ifd next;

        public Ifd shortValue(int tag, int value) {
            entries.add(new Entry(tag, SHORT, 1, new byte[] {(byte) value, (byte) (value >> 8), 0, 0}, null, null, null));
            return this;
        }

        public Ifd longValue(int tag, long value) {
            entries.add(new Entry(tag, LONG, 1, u32(value), null, null, null));
            return this;
        }

        /** An entry whose own count and value field are exactly these (to make damaged files). */
        public Ifd raw(int tag, int type, long count, long field) {
            entries.add(new Entry(tag, type, count, u32(field), null, null, null));
            return this;
        }

        /** An entry holding these bytes (BYTE, ASCII or UNDEFINED; in the field when they fit, else pointed at). */
        public Ifd bytes(int tag, int type, byte[] data) {
            entries.add(new Entry(tag, type, data.length, null, data, null, null));
            return this;
        }

        /** An entry of {@code count} values of {@code type} held in {@code data} (little-endian), e.g. RATIONALs. */
        public Ifd data(int tag, int type, long count, byte[] data) {
            entries.add(new Entry(tag, type, count, null, data, null, null));
            return this;
        }

        /** An ASCII entry (NUL added). */
        public Ifd text(int tag, String text) {
            return bytes(tag, ASCII, (text + "\0").getBytes(StandardCharsets.ISO_8859_1));
        }

        /** A LONG entry whose value is the offset of {@code blob}, written after the directory. */
        public Ifd blob(int tag, byte[] blob) {
            entries.add(new Entry(tag, LONG, 1, null, null, blob, null));
            return this;
        }

        /** JPEGInterchangeFormat and its length (0x201/0x202), as NEF, ARW and PEF previews are kept. */
        public Ifd jpeg(byte[] jpeg) {
            return blob(0x0201, jpeg).longValue(0x0202, jpeg.length);
        }

        /** One strip compressed with {@code compression} (6: old JPEG, 7: JPEG), as CR2 and DNG previews are kept. */
        public Ifd strip(byte[] data, int compression) {
            return shortValue(0x0103, compression).blob(0x0111, data).longValue(0x0117, data.length);
        }

        /** A pointer to one directory (Exif 0x8769, Olympus 0x2020). */
        public Ifd child(int tag, Ifd ifd) {
            entries.add(new Entry(tag, LONG, 1, null, null, null, List.of(ifd)));
            return this;
        }

        /** SubIFDs (0x014A). */
        public Ifd subIfds(Ifd... ifds) {
            entries.add(new Entry(0x014A, LONG, ifds.length, null, null, null, List.of(ifds)));
            return this;
        }

        public Ifd next(Ifd ifd) {
            this.next = ifd;
            return this;
        }
    }

    /** Writes a directory and everything it points at; returns where it starts. */
    private static int write(Buf out, Ifd ifd) {
        List<Entry> all = new ArrayList<>(ifd.entries);
        all.sort(Comparator.comparingInt(Entry::tag));
        int start = out.pos();
        out.put(u16(all.size()));
        int[] fields = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            out.put(u16(e.tag()));
            out.put(u16(e.type()));
            out.put(u32(e.count()));
            fields[i] = out.pos();
            if (e.inline() != null) {
                out.put(e.inline());
            } else if (e.data() != null && e.data().length <= 4) {
                out.put(Arrays.copyOf(e.data(), 4));
            } else {
                out.put(new byte[4]);
            }
        }
        int nextAt = out.pos();
        out.put(u32(0));
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (e.data() != null && e.data().length > 4) {
                out.patch32(fields[i], out.pos());
                out.put(e.data());
                out.align();
            } else if (e.ifds() != null && e.ifds().size() > 1) {
                out.patch32(fields[i], out.pos());
                fields[i] = -out.pos() - 1; // the pointer array's position, patched below
                out.put(new byte[4 * e.ifds().size()]);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (e.blob() != null) {
                out.patch32(fields[i], out.pos());
                out.put(e.blob());
                out.align();
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (e.ifds() == null) {
                continue;
            }
            for (int k = 0; k < e.ifds().size(); k++) {
                int child = write(out, e.ifds().get(k));
                int field = fields[i] >= 0 ? fields[i] : -fields[i] - 1 + 4 * k;
                out.patch32(field, child);
            }
        }
        if (ifd.next != null) {
            out.patch32(nextAt, write(out, ifd.next));
        }
        return start;
    }

    // ---- CR3 ----

    /**
     * A CR3: {@code ftyp crx }, {@code moov} with Canon's metadata box ({@code CMT1}, {@code CMT2}, {@code CMT4} when
     * given) and one track whose first sample is {@code trackJpeg} (in {@code mdat}), and the preview box with
     * {@code PRVW} holding {@code prvwJpeg} (null: none). The CMT boxes hold TIFF structures (see {@link #cmt}).
     */
    public static byte[] cr3(byte[] trackJpeg, byte[] prvwJpeg, byte[] cmt1, byte[] cmt2, byte[] cmt4) {
        byte[] before = cr3Boxes(0, trackJpeg, prvwJpeg, cmt1, cmt2, cmt4);
        long sampleAt = before.length + 8L;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(cr3Boxes(sampleAt, trackJpeg, prvwJpeg, cmt1, cmt2, cmt4));
        out.writeBytes(box("mdat", trackJpeg == null ? new byte[0] : trackJpeg));
        return out.toByteArray();
    }

    private static byte[] cr3Boxes(long sampleAt, byte[] trackJpeg, byte[] prvwJpeg, byte[] cmt1, byte[] cmt2, byte[] cmt4) {
        ByteArrayOutputStream canon = new ByteArrayOutputStream();
        canon.writeBytes(hex("85c0b687820f11e08111f4ce462b6a48"));
        if (cmt1 != null) {
            canon.writeBytes(box("CMT1", cmt1));
        }
        if (cmt2 != null) {
            canon.writeBytes(box("CMT2", cmt2));
        }
        if (cmt4 != null) {
            canon.writeBytes(box("CMT4", cmt4));
        }
        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        moov.writeBytes(box("uuid", canon.toByteArray()));
        if (trackJpeg != null) {
            byte[] stsz = concat(new byte[4], be32(0), be32(1), be32(trackJpeg.length));
            byte[] co64 = concat(new byte[4], be32(1), be32(sampleAt >>> 32), be32(sampleAt));
            moov.writeBytes(box("trak", box("mdia", box("minf", box("stbl", concat(box("stsz", stsz), box("co64", co64)))))));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(box("ftyp", "crx \0\0\0\u0001crx isom".getBytes(StandardCharsets.ISO_8859_1)));
        out.writeBytes(box("moov", moov.toByteArray()));
        if (prvwJpeg != null) {
            byte[] prvw = concat(new byte[4], new byte[] {0, 1, 0x06, 0x54, 0x04, 0x38, 0, 1}, be32(prvwJpeg.length), prvwJpeg);
            out.writeBytes(box("uuid", concat(hex("eaf42b5e1c984b88b9fbb7dc406e4d16"), new byte[] {0, 0, 0, 0, 0, 0, 0, 1},
                    box("PRVW", prvw))));
        }
        return out.toByteArray();
    }

    /** A box: its 32-bit size, its type and its content. */
    public static byte[] box(String type, byte[] content) {
        return concat(be32(8L + content.length), type.getBytes(StandardCharsets.ISO_8859_1), content);
    }

    /** Degrees as three RATIONALs (degrees, minutes, hundredths of seconds), little-endian, for GPS entries. */
    public static byte[] dms(double degrees) {
        int d = (int) degrees;
        double minutesFull = (degrees - d) * 60;
        int m = (int) minutesFull;
        long seconds = Math.round((minutesFull - m) * 60 * 100);
        return concat(u32(d), u32(1), u32(m), u32(1), u32(seconds), u32(100));
    }

    /** A GPS directory at this latitude and longitude (negative = S / W). */
    public static Ifd gps(double lat, double lon) {
        return new Ifd().bytes(0x0000, BYTE, new byte[] {2, 3, 0, 0}).text(0x0001, lat < 0 ? "S" : "N")
                .data(0x0002, RATIONAL, 3, dms(Math.abs(lat))).text(0x0003, lon < 0 ? "W" : "E")
                .data(0x0004, RATIONAL, 3, dms(Math.abs(lon)));
    }

    /** A CMT box's content: a TIFF ({@code II*\0}) whose IFD0 is this directory, offsets from its own start. */
    public static byte[] cmt(Ifd ifd0) {
        return tiff(ifd0);
    }

    // ---- RAF ----

    /** A RAF: the {@code FUJIFILMCCD-RAW} header with this JPEG's offset and length at bytes 84 and 88, then the JPEG. */
    public static byte[] raf(byte[] jpeg) {
        byte[] header = new byte[160];
        byte[] id = "FUJIFILMCCD-RAW 0201FF159506X-S10".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(id, 0, header, 0, id.length);
        System.arraycopy(be32(header.length), 0, header, 84, 4);
        System.arraycopy(be32(jpeg.length), 0, header, 88, 4);
        return concat(header, jpeg);
    }

    // ---- bytes ----

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Little-endian. */
    public static byte[] u32(long value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8)};
    }

    public static byte[] be32(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] hex(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }

    /** Overwrites four little-endian bytes of {@code file} at {@code at} (to damage a file). */
    public static byte[] patch32(byte[] file, int at, long value) {
        byte[] copy = file.clone();
        System.arraycopy(u32(value), 0, copy, at, 4);
        return copy;
    }

    private static final class Buf {
        private byte[] bytes = new byte[4096];
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

        void align() {
            if (size % 2 == 1) {
                put(new byte[1]);
            }
        }

        void patch32(int at, long value) {
            System.arraycopy(u32(value), 0, bytes, at, 4);
        }

        byte[] toArray() {
            return Arrays.copyOf(bytes, size);
        }
    }
}
