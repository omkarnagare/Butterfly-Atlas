package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;
import java.util.HexFormat;
import java.util.Set;

/**
 * The CR3 reader (Canon from 2018, design §2): an ISO-BMFF file with major brand {@code crx }. Each track's first
 * sample is a candidate (the first track holds the full-size JPEG; the others hold raw data, which is not a JPEG and
 * drops out), and so is the {@code PRVW} box's JPEG in the preview {@code uuid} box. It also notes the {@code CMT1}–
 * {@code CMT4} boxes (the EXIF metadata-extractor cannot find in a CR3 by itself) and reads the orientation from
 * {@code CMT1}. At most {@value #MAX_BOXES} boxes, {@value #MAX_DEPTH} levels deep.
 */
final class Cr3Previews {

    static final int MAX_BOXES = 4096;
    static final int MAX_DEPTH = 8;

    /** Canon's metadata box inside {@code moov} (CNCV, CTBO, CMT1–4, THMB). */
    private static final String CANON_UUID = "85c0b687820f11e08111f4ce462b6a48";
    /** The top-level box holding the {@code PRVW} preview, after 8 bytes of its own. */
    private static final String PREVIEW_UUID = "eaf42b5e1c984b88b9fbb7dc406e4d16";
    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "stbl");
    private static final Set<String> CMT = Set.of("CMT1", "CMT2", "CMT3", "CMT4");

    private final RawBytes in;
    private final Found out;
    private int boxes;
    private boolean stopped;
    /** The track being walked: its first sample's offset and size, -1 until known. */
    private long sampleOffset = -1;
    private long sampleSize = -1;

    private Cr3Previews(RawBytes in, Found out) {
        this.in = in;
        this.out = out;
    }

    static boolean recognises(byte[] head) {
        return RawBytes.ascii(head, 4, 4).equals("ftyp") && RawBytes.ascii(head, 8, 4).equals("crx ");
    }

    static void read(RawBytes in, Found out) throws IOException {
        out.family = "cr3";
        Cr3Previews walk = new Cr3Previews(in, out);
        walk.boxes(0, in.size(), 0);
        for (RawContents.Slice cmt : out.cmt) {
            if (cmt.name().equals("CMT1")) {
                out.orientation(TiffPreviews.orientation(in, cmt.offset()));
            }
        }
    }

    /** The boxes in {@code [start, end)}; a box whose size does not fit ends this level. */
    private void boxes(long start, long end, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            return;
        }
        long p = start;
        while (!stopped && p + 8 <= end) {
            if (++boxes > MAX_BOXES) {
                stopped = true;
                return;
            }
            byte[] header = in.read(p, 8);
            long size = RawBytes.u32(header, 0, false);
            String type = RawBytes.ascii(header, 4, 4);
            int headerLength = 8;
            if (size == 1) {
                if (p + 16 > end) {
                    return;
                }
                size = RawBytes.u64(in.read(p + 8, 8), 0);
                headerLength = 16;
            } else if (size == 0) {
                size = end - p; // to the end of its parent
            }
            if (size < headerLength || size > end - p) {
                return;
            }
            try {
                box(type, p + headerLength, p + size, depth);
            } catch (RawBytes.Damaged e) {
                // this box is damaged: the next one may still be fine
            }
            p += size;
        }
    }

    private void box(String type, long body, long end, int depth) throws IOException {
        if (CONTAINERS.contains(type)) {
            if (type.equals("trak")) {
                sampleOffset = -1;
                sampleSize = -1;
                boxes(body, end, depth + 1);
                if (sampleOffset >= 0 && sampleSize > 0) {
                    out.candidate(sampleOffset, sampleSize);
                }
                sampleOffset = -1;
                sampleSize = -1;
            } else {
                boxes(body, end, depth + 1);
            }
        } else if (type.equals("uuid") && end - body >= 16) {
            String uuid = HexFormat.of().formatHex(in.read(body, 16));
            if (uuid.equals(CANON_UUID)) {
                boxes(body + 16, end, depth + 1);
            } else if (uuid.equals(PREVIEW_UUID)) {
                boxes(body + 24, end, depth + 1);
            }
        } else if (CMT.contains(type)) {
            out.cmt.add(new RawContents.Slice(type, body, end - body));
        } else if (type.equals("PRVW") && end - body >= 16) {
            byte[] head = in.read(body, 16);
            out.candidate(body + 16, Math.min(RawBytes.u32(head, 12, false), end - body - 16));
        } else if (type.equals("stsz") && end - body >= 12) {
            byte[] head = in.read(body, 12);
            long uniform = RawBytes.u32(head, 4, false);
            long count = RawBytes.u32(head, 8, false);
            if (uniform != 0) {
                sampleSize = uniform;
            } else if (count >= 1 && end - body >= 16) {
                sampleSize = RawBytes.u32(in.read(body + 12, 4), 0, false);
            }
        } else if (type.equals("co64") && end - body >= 16) {
            byte[] head = in.read(body, 16);
            if (RawBytes.u32(head, 4, false) >= 1) {
                sampleOffset = RawBytes.u64(head, 8);
            }
        } else if (type.equals("stco") && end - body >= 12) {
            byte[] head = in.read(body, 12);
            if (RawBytes.u32(head, 4, false) >= 1) {
                sampleOffset = RawBytes.u32(head, 8, false);
            }
        }
    }
}
