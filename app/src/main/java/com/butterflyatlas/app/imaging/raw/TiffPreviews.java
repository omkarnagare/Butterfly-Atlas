package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The TIFF-style reader (design §2): NEF, NRW, CR2, ARW, SRF, SR2, PEF, SRW, DNG, 3FR, IIQ, RWL, and the variants ORF
 * ({@code IIRO}, {@code IIRS}, {@code MMOR}) and RW2 ({@code IIU\0}). It walks IFD0 and its chain, SubIFDs and the Exif
 * sub-IFD, and lists every JPEG they point at: {@code JPEGInterchangeFormat}/{@code Length}, a single strip or tile
 * compressed as JPEG (6 or 7), RW2's {@code JpgFromRaw} (0x002E) and ORF's maker-note preview. At most
 * {@value #MAX_IFDS} directories, nesting depth {@value #MAX_DEPTH}; a directory seen twice stops the walk.
 */
final class TiffPreviews {

    static final int MAX_IFDS = 64;
    static final int MAX_DEPTH = 4;
    /** SubIFD pointers followed from one directory. */
    static final int MAX_SUB_IFDS = 16;

    private static final int TIFF = 42;
    private static final int ORF = 0x4F52;  // "RO" (IIRO, MMOR)
    private static final int ORF_S = 0x5352; // "RS" (IIRS)
    private static final int RW2 = 0x0055;
    private static final int DNG_VERSION = 0xC612;

    private enum Kind { MAIN, EXIF, OLYMPUS, OLYMPUS_CAMERA }

    /** How a directory's offsets are read: relative to {@code base}, in this byte order. */
    private record Frame(long base, boolean little) {
    }

    private record Entry(int tag, int type, long count, long field) {
    }

    private final RawBytes in;
    private final Found out;
    private final Set<Long> seen = new HashSet<>();
    private int ifds;
    private boolean stopped;
    private boolean firstDone;

    private TiffPreviews(RawBytes in, Found out) {
        this.in = in;
        this.out = out;
    }

    /** Whether the first bytes are a TIFF header or one of the ORF and RW2 variants. */
    static boolean recognises(byte[] head) {
        if (head.length < 8) {
            return false;
        }
        boolean little = head[0] == 'I' && head[1] == 'I';
        if (!little && !(head[0] == 'M' && head[1] == 'M')) {
            return false;
        }
        int magic = RawBytes.u16(head, 2, little);
        return magic == TIFF || magic == ORF || magic == ORF_S || (little && magic == RW2);
    }

    /** Collects the file's candidates, orientation, sensor size and family. */
    static void read(RawBytes in, Found out) throws IOException {
        byte[] head = in.readUpTo(0, 16);
        boolean little = head[0] == 'I';
        out.family = family(head, little);
        TiffPreviews walk = new TiffPreviews(in, out);
        walk.chain(RawBytes.u32(head, 4, little), new Frame(0, little));
    }

    /**
     * The family the bytes alone say ({@code orf}, {@code rw2}, {@code cr2}, {@code dng} by DNGVersion in IFD0), or
     * null for a TIFF-shaped file that does not say (a NEF, an ARW, or a plain TIFF): only IFD0 is read.
     */
    static String family(RawBytes in) throws IOException {
        byte[] head = in.readUpTo(0, 16);
        boolean little = head[0] == 'I';
        String family = family(head, little);
        if (family != null) {
            return family;
        }
        try {
            Map<Integer, Entry> ifd0 = new TiffPreviews(in, new Found()).entries(RawBytes.u32(head, 4, little),
                    new Frame(0, little));
            return ifd0 != null && ifd0.containsKey(DNG_VERSION) ? "dng" : null;
        } catch (RawBytes.Damaged e) {
            return null;
        }
    }

    /** The orientation in IFD0 of the TIFF structure starting at {@code start} (a CR3's CMT1, a JPEG's EXIF); 1 when absent. */
    static int orientation(RawBytes in, long start) throws IOException {
        try {
            byte[] head = in.read(start, 8);
            boolean little = head[0] == 'I' && head[1] == 'I';
            if (!little && !(head[0] == 'M' && head[1] == 'M')) {
                return 1;
            }
            TiffPreviews walk = new TiffPreviews(in, new Found());
            Frame frame = new Frame(start, little);
            Map<Integer, Entry> ifd0 = walk.entries(start + RawBytes.u32(head, 4, little), frame);
            if (ifd0 == null || !ifd0.containsKey(0x0112)) {
                return 1;
            }
            long value = walk.value(ifd0.get(0x0112), frame);
            return value >= 1 && value <= 8 ? (int) value : 1;
        } catch (RawBytes.Damaged e) {
            return 1;
        }
    }

    /**
     * Where the orientation value of IFD0 is in the TIFF structure at {@code start}, as {@code {position, little ? 1 :
     * 0, value}}; null when IFD0 has no SHORT orientation held in its entry.
     */
    static long[] orientationField(RawBytes in, long start) throws IOException {
        try {
            byte[] head = in.read(start, 8);
            boolean little = head[0] == 'I' && head[1] == 'I';
            if (!little && !(head[0] == 'M' && head[1] == 'M')) {
                return null;
            }
            Map<Integer, Entry> ifd0 = new TiffPreviews(in, new Found()).entries(start + RawBytes.u32(head, 4, little),
                    new Frame(start, little));
            Entry e = ifd0 == null ? null : ifd0.get(0x0112);
            if (e == null || e.type() != 3 || e.count() != 1) {
                return null;
            }
            return new long[] {e.field(), little ? 1 : 0, RawBytes.u16(in.read(e.field(), 2), 0, little)};
        } catch (RawBytes.Damaged e) {
            return null;
        }
    }

    private static String family(byte[] head, boolean little) {
        int magic = RawBytes.u16(head, 2, little);
        if (magic == ORF || magic == ORF_S) {
            return "orf";
        }
        if (magic == RW2) {
            return "rw2";
        }
        return little && RawBytes.ascii(head, 8, 2).equals("CR") ? "cr2" : null;
    }

    /** Walks a chain of directories from {@code first} (relative to the frame's base); 0 ends it. */
    private void chain(long first, Frame frame) throws IOException {
        long next = first;
        while (next != 0 && !stopped) {
            next = directory(frame.base() + next, Kind.MAIN, 0, frame);
        }
    }

    /**
     * Reads one directory at {@code at} (absolute) and what it points at; returns its next directory's offset
     * (relative to the frame's base), or 0. A damaged directory is left out, with what it points at.
     */
    private long directory(long at, Kind kind, int depth, Frame frame) throws IOException {
        if (stopped || depth > MAX_DEPTH) {
            return 0;
        }
        if (!seen.add(at) || ++ifds > MAX_IFDS) {
            stopped = true; // a loop, or more directories than any camera writes
            return 0;
        }
        try {
            Map<Integer, Entry> entries = entries(at, frame);
            if (entries == null) {
                return 0;
            }
            switch (kind) {
                case MAIN -> main(entries, depth, frame);
                case EXIF -> exif(entries, depth, frame);
                case OLYMPUS -> olympus(entries, depth, frame);
                case OLYMPUS_CAMERA -> olympusCamera(entries, frame);
            }
            return entries.containsKey(-1) ? entries.get(-1).field() : 0;
        } catch (RawBytes.Damaged e) {
            return 0;
        }
    }

    /** A directory's entries by tag; the next directory's offset is kept under tag -1 (its field). Null when empty. */
    private Map<Integer, Entry> entries(long at, Frame frame) throws IOException {
        boolean little = frame.little();
        int count = RawBytes.u16(in.read(at, 2), 0, little);
        if (count == 0) {
            return null;
        }
        if (count > RawBytes.MAX_ENTRIES) {
            throw new RawBytes.Damaged("A directory of " + count + " entries");
        }
        byte[] table = in.read(at + 2, count * 12 + 4);
        Map<Integer, Entry> entries = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int p = i * 12;
            int tag = RawBytes.u16(table, p, little);
            entries.putIfAbsent(tag, new Entry(tag, RawBytes.u16(table, p + 2, little), RawBytes.u32(table, p + 4, little),
                    at + 2 + p + 8));
        }
        long next = RawBytes.u32(table, count * 12, little);
        if (next != 0) {
            entries.put(-1, new Entry(-1, 0, 0, next));
        }
        return entries;
    }

    private void main(Map<Integer, Entry> e, int depth, Frame frame) throws IOException {
        boolean first = !firstDone && depth == 0;
        firstDone = true;
        if (first && e.containsKey(0x0112)) {
            out.orientation(value(e.get(0x0112), frame));
        }
        if (first && e.containsKey(DNG_VERSION) && out.family == null) {
            out.family = "dng";
        }
        if (e.containsKey(0x0201) && e.containsKey(0x0202)) {
            out.candidate(frame.base() + value(e.get(0x0201), frame), value(e.get(0x0202), frame));
        }
        long compression = e.containsKey(0x0103) ? value(e.get(0x0103), frame) : 0;
        if (compression == 6 || compression == 7) {
            single(e, 0x0111, 0x0117, frame); // one strip
            single(e, 0x0144, 0x0145, frame); // one tile
        }
        Entry jpgFromRaw = e.get(0x002E);
        if (jpgFromRaw != null && jpgFromRaw.count() > 4) {
            out.candidate(dataOffset(jpgFromRaw, frame), jpgFromRaw.count());
        }
        Long subfile = e.containsKey(0x00FE) ? value(e.get(0x00FE), frame) : null;
        if ((subfile == null || subfile == 0) && e.containsKey(0x0100) && e.containsKey(0x0101)) {
            out.sensor(value(e.get(0x0100), frame), value(e.get(0x0101), frame));
        }
        if (e.containsKey(0x014A)) {
            for (long sub : values(e.get(0x014A), frame, MAX_SUB_IFDS)) {
                directory(frame.base() + sub, Kind.MAIN, depth + 1, frame);
            }
        }
        if (e.containsKey(0x8769)) {
            directory(frame.base() + value(e.get(0x8769), frame), Kind.EXIF, depth + 1, frame);
        }
    }

    private void single(Map<Integer, Entry> e, int offsetTag, int lengthTag, Frame frame) throws IOException {
        Entry offsets = e.get(offsetTag);
        Entry lengths = e.get(lengthTag);
        if (offsets != null && lengths != null && offsets.count() == 1 && lengths.count() == 1) {
            out.candidate(frame.base() + value(offsets, frame), value(lengths, frame));
        }
    }

    private void exif(Map<Integer, Entry> e, int depth, Frame frame) throws IOException {
        Entry note = e.get(0x927C);
        if (note == null || note.count() < 16) {
            return;
        }
        long at = dataOffset(note, frame);
        byte[] head = in.read(at, 16);
        if (RawBytes.ascii(head, 0, 8).equals("OLYMPUS\0")) {
            directory(at + 12, Kind.OLYMPUS, depth + 1, new Frame(at, head[8] == 'I'));
        } else if (RawBytes.ascii(head, 0, 12).equals("OM SYSTEM\0\0\0")) {
            directory(at + 16, Kind.OLYMPUS, depth + 1, new Frame(at, head[12] == 'I'));
        } else if (RawBytes.ascii(head, 0, 6).equals("OLYMP\0")) {
            directory(at + 8, Kind.OLYMPUS, depth + 1, frame); // the older notes: offsets from the TIFF header
        }
    }

    private void olympus(Map<Integer, Entry> e, int depth, Frame frame) throws IOException {
        Entry camera = e.get(0x2020);
        if (camera != null) {
            directory(frame.base() + RawBytes.u32(in.read(camera.field(), 4), 0, frame.little()), Kind.OLYMPUS_CAMERA,
                    depth + 1, frame);
        }
    }

    private void olympusCamera(Map<Integer, Entry> e, Frame frame) throws IOException {
        boolean valid = !e.containsKey(0x0100) || value(e.get(0x0100), frame) != 0;
        if (valid && e.containsKey(0x0101) && e.containsKey(0x0102)) {
            out.candidate(frame.base() + value(e.get(0x0101), frame), value(e.get(0x0102), frame));
        }
    }

    /** An entry's first value: a SHORT, or a LONG (or IFD pointer) for every other type. */
    private long value(Entry e, Frame frame) throws IOException {
        if (e.count() < 1) {
            throw new RawBytes.Damaged("An entry with no value");
        }
        byte[] field = in.read(e.field(), 4);
        if (e.type() == 3 || e.type() == 8) {
            if (e.count() > 2) {
                return RawBytes.u16(in.read(frame.base() + RawBytes.u32(field, 0, frame.little()), 2), 0, frame.little());
            }
            return RawBytes.u16(field, 0, frame.little());
        }
        if (e.type() == 1 || e.type() == 6 || e.type() == 7) {
            return e.count() <= 4 ? field[0] & 0xFF : in.read(frame.base() + RawBytes.u32(field, 0, frame.little()), 1)[0] & 0xFF;
        }
        if (e.count() > 1) {
            return RawBytes.u32(in.read(frame.base() + RawBytes.u32(field, 0, frame.little()), 4), 0, frame.little());
        }
        return RawBytes.u32(field, 0, frame.little());
    }

    /** Up to {@code max} LONG values of an entry (in its field when one fits, else where the field points). */
    private long[] values(Entry e, Frame frame, int max) throws IOException {
        int n = (int) Math.min(e.count(), max);
        if (n <= 0) {
            return new long[0];
        }
        byte[] field = in.read(e.field(), 4);
        byte[] data = e.count() == 1 ? field : in.read(frame.base() + RawBytes.u32(field, 0, frame.little()), n * 4);
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = RawBytes.u32(data, i * 4, frame.little());
        }
        return values;
    }

    /** Where an entry's data is: in its field when it fits (4 bytes), else where the field points. */
    private long dataOffset(Entry e, Frame frame) throws IOException {
        if (e.count() <= 4) {
            return e.field();
        }
        return frame.base() + RawBytes.u32(in.read(e.field(), 4), 0, frame.little());
    }
}
