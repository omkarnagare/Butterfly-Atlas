package com.butterflyatlas.app.imaging.heif;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a HEIF file's boxes (ISO/IEC 23008-12; contract §8): the {@code ftyp} brands, and inside {@code meta} the
 * primary item ({@code pitm}), the items ({@code iinf}), their references ({@code iref}), their properties
 * ({@code iprp}: {@code ispe}, {@code irot}, {@code imir}, {@code pixi}, {@code auxC}) and where their data is
 * ({@code iloc}, {@code idat}). No picture is decoded.
 *
 * <p>Bounded as 7a's readers: every offset and length is checked against the file's size, the {@code meta} box is read
 * into memory only up to {@value #MAX_META} bytes, boxes are at most {@value #MAX_DEPTH} deep and {@value #MAX_BOXES}
 * in all, at most {@value #MAX_ITEMS} items and properties, and any count that needs more bytes than its box holds is
 * refused; at most {@value #MAX_ITEMS} extents in all, and an extent without bytes only alone. Anything unexpected gives an unreadable {@link HeifInfo}; the only exception is the channel's own
 * {@link IOException}.
 */
public final class HeifBoxes {

    static final int MAX_DEPTH = 8;
    static final int MAX_BOXES = 4096;
    static final int MAX_ITEMS = 4096;
    /** The largest {@code meta} box read (an iPhone's is a few KB, even with a 48-tile grid). */
    static final int MAX_META = 4 * 1024 * 1024;

    /** Major brands of HEIF image sequences (several frames in one file). */
    private static final Set<String> SEQUENCE_BRANDS = Set.of("msf1", "hevc", "hevx");
    /** Derived image items whose picture comes from their {@code dimg} inputs. */
    private static final Set<String> DERIVED = Set.of("grid", "iovl", "iden");

    /** Something in the boxes that cannot be right; caught by {@link #read}, never thrown out of it. */
    private static final class Damaged extends RuntimeException {
        Damaged(String what) {
            super(what, null, false, false);
        }
    }

    /** An item's location: how it is stored (0 file, 1 idat) and its extents relative to that. */
    private record Location(int method, List<HeifInfo.Extent> extents) {
    }

    /** One item property's association with an item, in the item's order. */
    private record Association(int index) {
    }

    private final SeekableByteChannel channel;
    private final long size;
    private int boxes;
    /** Extents of every item so far: at most {@value #MAX_ITEMS} (fixes J1). */
    private long allExtents;

    private String majorBrand = "";
    private final List<String> brands = new ArrayList<>();
    private boolean hasMeta;
    private boolean hasMoov;
    private int primary = -1;
    private final Map<Integer, String> types = new LinkedHashMap<>();
    private final Map<Integer, String> contentTypes = new HashMap<>();
    private final Map<Integer, List<Integer>> dimg = new HashMap<>();
    private final Map<Integer, List<Integer>> cdsc = new HashMap<>();
    private boolean auxl;
    private final List<byte[]> properties = new ArrayList<>();
    private final List<String> propertyTypes = new ArrayList<>();
    private final Map<Integer, List<Association>> associations = new HashMap<>();
    private final Map<Integer, Location> locations = new HashMap<>();
    /** Where the {@code idat} box's data starts in the file, and how long it is; -1 when there is none. */
    private long idatOffset = -1;
    private long idatLength;

    private HeifBoxes(SeekableByteChannel channel, long size) {
        this.channel = channel;
        this.size = size;
    }

    /** The boxes of the {@code size}-byte file open on {@code channel}. */
    public static HeifInfo read(SeekableByteChannel channel, long size) throws IOException {
        HeifBoxes boxes = new HeifBoxes(channel, size);
        try {
            return boxes.walk();
        } catch (RuntimeException | OutOfMemoryError e) {
            // Damaged, and anything else a hostile file could provoke (an overflow, a bad index, an allocation too large
            // for memory, fixes J1): never thrown out
            return HeifInfo.unreadable(boxes.majorBrand, boxes.brands);
        }
    }

    private HeifInfo walk() throws IOException {
        long p = 0;
        boolean first = true;
        while (p < size) {
            if (size - p < 8) {
                throw new Damaged("A box header past the end");
            }
            count();
            byte[] header = readFile(p, (int) Math.min(16, size - p));
            long boxSize = u32(header, 0);
            String type = ascii(header, 4, 4);
            int headerLength = 8;
            if (boxSize == 1) {
                if (header.length < 16) {
                    throw new Damaged("A large box header past the end");
                }
                boxSize = u64(header, 8);
                headerLength = 16;
            } else if (boxSize == 0) {
                boxSize = size - p;
            }
            if (boxSize < headerLength || boxSize > size - p) {
                throw new Damaged("A " + type + " box whose size does not fit the file");
            }
            if (first && !type.equals("ftyp")) {
                throw new Damaged("No ftyp box first");
            }
            first = false;
            long body = p + headerLength;
            long bodyLength = boxSize - headerLength;
            if (type.equals("ftyp")) {
                ftyp(readFile(body, (int) Math.min(bodyLength, 4096)));
            } else if (type.equals("moov")) {
                hasMoov = true;
            } else if (type.equals("meta")) {
                if (hasMeta) {
                    throw new Damaged("Two meta boxes");
                }
                hasMeta = true;
                if (bodyLength > MAX_META) {
                    throw new Damaged("A meta box too large");
                }
                meta(readFile(body, (int) bodyLength), body);
            }
            p += boxSize;
        }
        if (first) {
            throw new Damaged("No boxes at all");
        }
        return result();
    }

    private void ftyp(byte[] b) {
        if (b.length < 8) {
            throw new Damaged("A short ftyp box");
        }
        majorBrand = ascii(b, 0, 4);
        for (int at = 8; at + 4 <= b.length; at += 4) {
            brands.add(ascii(b, at, 4));
        }
    }

    /** The {@code meta} full box's children; {@code fileOffset} is where {@code b} starts in the file. */
    private void meta(byte[] b, long fileOffset) {
        Set<String> seen = new java.util.HashSet<>();
        children(b, 4, b.length, 2, (type, from, to) -> {
            if (Set.of("pitm", "iinf", "iref", "iprp", "iloc", "idat").contains(type) && !seen.add(type)) {
                throw new Damaged("Two " + type + " boxes");
            }
            switch (type) {
                case "pitm" -> primary = (int) (b[from] == 0 ? u16(b, from + 4) : u32(b, from + 4));
                case "iinf" -> iinf(b, from, to);
                case "iref" -> iref(b, from, to);
                case "iprp" -> children(b, from, to, 3, (t, f, e) -> {
                    if (t.equals("ipco")) {
                        children(b, f, e, 4, (pt, pf, pe) -> {
                            if (properties.size() >= MAX_ITEMS) {
                                throw new Damaged("Too many properties");
                            }
                            properties.add(java.util.Arrays.copyOfRange(b, pf, pe));
                            propertyTypes.add(pt);
                        });
                    } else if (t.equals("ipma")) {
                        ipma(b, f, e);
                    }
                });
                case "iloc" -> iloc(b, from, to);
                case "idat" -> {
                    idatOffset = fileOffset + from;
                    idatLength = to - from;
                }
                default -> {
                    // hdlr, dinf, grpl …: nothing the app needs
                }
            }
        });
    }

    @FunctionalInterface
    private interface Child {
        void box(String type, int from, int to);
    }

    /** The boxes in {@code b[start, end)} at {@code depth}, each handed its body's range. */
    private void children(byte[] b, int start, int end, int depth, Child child) {
        if (depth > MAX_DEPTH) {
            throw new Damaged("Boxes nested too deep");
        }
        int p = start;
        while (p < end) {
            if (end - p < 8) {
                throw new Damaged("A box header past its parent's end");
            }
            count();
            long boxSize = u32(b, p);
            int headerLength = 8;
            if (boxSize == 1) {
                if (end - p < 16) {
                    throw new Damaged("A large box header past its parent's end");
                }
                boxSize = u64(b, p + 8);
                headerLength = 16;
            } else if (boxSize == 0) {
                boxSize = end - p;
            }
            if (boxSize < headerLength || boxSize > end - p) {
                throw new Damaged("A box whose size does not fit its parent");
            }
            child.box(ascii(b, p + 4, 4), p + headerLength, (int) (p + boxSize));
            p += (int) boxSize;
        }
    }

    private void iinf(byte[] b, int from, int to) {
        int version = b[from] & 0xFF;
        long count = version == 0 ? u16(b, from + 4) : u32(b, from + 4);
        int start = from + (version == 0 ? 6 : 8);
        // each infe is at least 12 bytes (header, version and flags, an id)
        if (count > MAX_ITEMS || count * 12 > to - start) {
            throw new Damaged("More items than the iinf box holds");
        }
        children(b, start, to, 3, (type, f, e) -> {
            if (!type.equals("infe")) {
                return;
            }
            int v = b[f] & 0xFF;
            if (v < 2) {
                return; // versions 0 and 1 name no item type: not an image item the app needs
            }
            int at = f + 4;
            int id = (int) (v == 2 ? u16(b, at) : u32(b, at));
            at += v == 2 ? 2 : 4;
            at += 2; // protection index
            String itemType = ascii(b, at, 4);
            at += 4;
            if (types.size() >= MAX_ITEMS) {
                throw new Damaged("Too many items");
            }
            types.put(id, itemType);
            if (itemType.equals("mime")) {
                int nameEnd = terminator(b, at, e);
                int typeStart = Math.min(nameEnd + 1, e);
                contentTypes.put(id, new String(b, typeStart, terminator(b, typeStart, e) - typeStart,
                        StandardCharsets.UTF_8));
            }
        });
    }

    private void iref(byte[] b, int from, int to) {
        int version = b[from] & 0xFF;
        int idBytes = version == 0 ? 2 : 4;
        children(b, from + 4, to, 3, (type, f, e) -> {
            int at = f;
            int fromId = (int) id(b, at, idBytes);
            at += idBytes;
            int count = (int) u16(b, at);
            at += 2;
            if ((long) count * idBytes > e - at) {
                throw new Damaged("More references than the box holds");
            }
            List<Integer> to2 = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                to2.add((int) id(b, at, idBytes));
                at += idBytes;
            }
            switch (type) {
                case "dimg" -> dimg.computeIfAbsent(fromId, k -> new ArrayList<>()).addAll(to2);
                case "cdsc" -> cdsc.computeIfAbsent(fromId, k -> new ArrayList<>()).addAll(to2);
                case "auxl" -> auxl = true;
                default -> {
                    // thmb and others: not needed
                }
            }
        });
    }

    private void ipma(byte[] b, int from, int to) {
        int version = b[from] & 0xFF;
        boolean wide = (b[from + 3] & 1) != 0;
        long count = u32(b, from + 4);
        int at = from + 8;
        int idBytes = version < 1 ? 2 : 4;
        if (count > MAX_ITEMS || count * (idBytes + 1) > to - at) {
            throw new Damaged("More entries than the ipma box holds");
        }
        for (long i = 0; i < count; i++) {
            int id = (int) id(b, at, idBytes);
            at += idBytes;
            int n = b[at] & 0xFF;
            at += 1;
            if ((long) n * (wide ? 2 : 1) > to - at) {
                throw new Damaged("More associations than the ipma box holds");
            }
            List<Association> list = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                int index = wide ? (int) (u16(b, at) & 0x7FFF) : b[at] & 0x7F;
                at += wide ? 2 : 1;
                list.add(new Association(index));
            }
            associations.put(id, list);
        }
    }

    private void iloc(byte[] b, int from, int to) {
        int version = b[from] & 0xFF;
        if (version > 2) {
            throw new Damaged("An iloc version the app does not know");
        }
        int at = from + 4;
        int offsetSize = (b[at] & 0xFF) >> 4;
        int lengthSize = b[at] & 0x0F;
        int baseOffsetSize = (b[at + 1] & 0xFF) >> 4;
        int indexSize = version >= 1 ? b[at + 1] & 0x0F : 0;
        for (int s : new int[] {offsetSize, lengthSize, baseOffsetSize, indexSize}) {
            if (s != 0 && s != 4 && s != 8) {
                throw new Damaged("An iloc field size the format does not allow");
            }
        }
        at += 2;
        long count = version < 2 ? u16(b, at) : u32(b, at);
        at += version < 2 ? 2 : 4;
        int idBytes = version < 2 ? 2 : 4;
        int fixed = idBytes + (version >= 1 ? 2 : 0) + 2 + baseOffsetSize + 2;
        if (count > MAX_ITEMS || count * fixed > to - at) {
            throw new Damaged("More locations than the iloc box holds");
        }
        for (long i = 0; i < count; i++) {
            int id = (int) id(b, at, idBytes);
            at += idBytes;
            int method = 0;
            if (version >= 1) {
                method = (int) (u16(b, at) & 0x0F);
                at += 2;
            }
            at += 2; // data reference index
            long base = sized(b, at, baseOffsetSize);
            at += baseOffsetSize;
            int extents = (int) u16(b, at);
            at += 2;
            int extentBytes = indexSize + offsetSize + lengthSize;
            if ((long) extents * extentBytes > to - at || (extentBytes == 0 && extents > 1)) {
                throw new Damaged("More extents than the iloc box holds");
            }
            allExtents += extents;
            if (allExtents > MAX_ITEMS) {
                throw new Damaged("More extents in all than the app reads");
            }
            List<HeifInfo.Extent> list = new ArrayList<>();
            for (int j = 0; j < extents; j++) {
                at += indexSize;
                long offset = sized(b, at, offsetSize);
                at += offsetSize;
                long length = sized(b, at, lengthSize);
                at += lengthSize;
                count(); // each extent counts as a box: a file of empty extents ends at the box limit
                list.add(new HeifInfo.Extent(Math.addExact(base, offset), length));
            }
            locations.put(id, new Location(method, list));
        }
    }

    private HeifInfo result() {
        // A sequence by its brand, or a file with frames (moov) or items but no primary item; neither is damaged.
        boolean sequence = SEQUENCE_BRANDS.contains(majorBrand) || (primary < 0 && (hasMeta || hasMoov));
        if (!sequence && !hasMeta) {
            throw new Damaged("No meta box");
        }
        if (sequence) {
            return new HeifInfo(true, majorBrand, brands, true, primary, null, null, null, null, 0, null, false, 8,
                    false, List.of(), List.of());
        }
        String primaryType = types.get(primary);
        if (primaryType == null) {
            throw new Damaged("The primary item is not listed");
        }
        String codedAs = primaryType;
        if (DERIVED.contains(primaryType)) {
            List<Integer> inputs = dimg.getOrDefault(primary, List.of());
            codedAs = inputs.isEmpty() ? null : types.get(inputs.get(0));
            for (int input : inputs) {
                if (codedAs == null || !codedAs.equals(types.get(input))) {
                    codedAs = "mixed";
                    break;
                }
            }
        }
        Integer width = null;
        Integer height = null;
        int turn = 0;
        Integer mirror = null;
        boolean mirrorFirst = false;
        int bitDepth = 0;
        boolean auxiliary = auxl;
        for (Association a : associations.getOrDefault(primary, List.of())) {
            byte[] p = property(a.index());
            String type = a.index() == 0 ? "" : propertyTypes.get(a.index() - 1);
            switch (type) {
                case "ispe" -> {
                    long w = u32(p, 4);
                    long h = u32(p, 8);
                    if (w <= 0 || h <= 0 || w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) {
                        throw new Damaged("An image size that cannot be");
                    }
                    width = (int) w;
                    height = (int) h;
                }
                case "irot" -> turn = p[0] & 0x03;
                case "imir" -> {
                    mirror = p[0] & 0x01;
                    mirrorFirst = !hasTurn(primary, a);
                }
                case "pixi" -> bitDepth = (p[4] & 0xFF) > 0 ? p[5] & 0xFF : 0;
                default -> {
                    // colr, hvcC, clli …
                }
            }
        }
        if (bitDepth == 0 && DERIVED.contains(primaryType)) {
            List<Integer> inputs = dimg.getOrDefault(primary, List.of());
            for (Association a : inputs.isEmpty() ? List.<Association>of() : associations.getOrDefault(inputs.get(0), List.of())) {
                if (a.index() > 0 && propertyTypes.get(a.index() - 1).equals("pixi")) {
                    byte[] p = property(a.index());
                    bitDepth = (p[4] & 0xFF) > 0 ? p[5] & 0xFF : 0;
                }
            }
        }
        for (List<Association> list : associations.values()) {
            for (Association a : list) {
                auxiliary |= a.index() > 0 && a.index() <= propertyTypes.size()
                        && propertyTypes.get(a.index() - 1).equals("auxC");
            }
        }
        return new HeifInfo(true, majorBrand, brands, false, primary, primaryType, codedAs, width, height, turn, mirror,
                mirrorFirst, bitDepth == 0 ? 8 : bitDepth, auxiliary, extents(exifItem()), extents(xmpItem()));
    }

    /** Whether an {@code irot} comes before {@code mirror} among the item's associations. */
    private boolean hasTurn(int item, Association mirror) {
        for (Association a : associations.getOrDefault(item, List.of())) {
            if (a == mirror) {
                return false;
            }
            if (a.index() > 0 && propertyTypes.get(a.index() - 1).equals("irot")) {
                return true;
            }
        }
        return false;
    }

    /** The property at a 1-based ipco index; index 0 means none. */
    private byte[] property(int index) {
        if (index == 0) {
            return new byte[0];
        }
        if (index > properties.size()) {
            throw new Damaged("A property index past the ipco box");
        }
        byte[] p = properties.get(index - 1);
        String type = propertyTypes.get(index - 1);
        int needed = switch (type) {
            case "ispe" -> 12;
            case "irot", "imir" -> 1;
            case "pixi" -> 5;
            default -> 0;
        };
        if (p.length < needed || (type.equals("pixi") && p.length < 5 + (p[4] & 0xFF))) {
            throw new Damaged("A short " + type + " property");
        }
        return p;
    }

    /** The Exif item describing the primary item ({@code cdsc}), else the first Exif item; -1 when none. */
    private int exifItem() {
        int first = -1;
        for (Map.Entry<Integer, String> item : types.entrySet()) {
            if (item.getValue().equals("Exif")) {
                if (cdsc.getOrDefault(item.getKey(), List.of()).contains(primary)) {
                    return item.getKey();
                }
                first = first < 0 ? item.getKey() : first;
            }
        }
        return first;
    }

    private int xmpItem() {
        for (Map.Entry<Integer, String> type : contentTypes.entrySet()) {
            if (type.getValue().equals("application/rdf+xml")) {
                return type.getKey();
            }
        }
        return -1;
    }

    /** An item's data as ranges of the file; empty when it has none, or any part lies outside the file or its idat. */
    private List<HeifInfo.Extent> extents(int item) {
        Location location = item < 0 ? null : locations.get(item);
        if (location == null || location.extents().isEmpty() || location.method() > 1) {
            return List.of();
        }
        List<HeifInfo.Extent> inFile = new ArrayList<>();
        for (HeifInfo.Extent e : location.extents()) {
            long within = location.method() == 0 ? size : idatLength;
            if (e.length() <= 0 || e.offset() < 0 || e.offset() > within || e.length() > within - e.offset()
                    || (location.method() == 1 && idatOffset < 0)) {
                return List.of();
            }
            inFile.add(new HeifInfo.Extent(location.method() == 0 ? e.offset() : idatOffset + e.offset(), e.length()));
        }
        return inFile;
    }

    private void count() {
        if (++boxes > MAX_BOXES) {
            throw new Damaged("Too many boxes");
        }
    }

    private byte[] readFile(long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new Damaged("A read outside the file");
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new Damaged("The file ended early");
            }
        }
        return buffer.array();
    }

    private static int terminator(byte[] b, int from, int end) {
        int at = from;
        while (at < end && b[at] != 0) {
            at++;
        }
        return at;
    }

    private static long id(byte[] b, int at, int bytes) {
        return bytes == 2 ? u16(b, at) : u32(b, at);
    }

    private static long sized(byte[] b, int at, int bytes) {
        return switch (bytes) {
            case 0 -> 0;
            case 4 -> u32(b, at);
            default -> u64(b, at);
        };
    }

    private static long u16(byte[] b, int at) {
        check(b, at, 2);
        return (b[at] & 0xFFL) << 8 | (b[at + 1] & 0xFFL);
    }

    private static long u32(byte[] b, int at) {
        check(b, at, 4);
        return (b[at] & 0xFFL) << 24 | (b[at + 1] & 0xFFL) << 16 | (b[at + 2] & 0xFFL) << 8 | (b[at + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int at) {
        long high = u32(b, at);
        if (high > Integer.MAX_VALUE) {
            throw new Damaged("A 64-bit value that cannot be");
        }
        return high << 32 | u32(b, at + 4);
    }

    private static String ascii(byte[] b, int at, int length) {
        check(b, at, length);
        return new String(b, at, length, StandardCharsets.ISO_8859_1);
    }

    private static void check(byte[] b, int at, int length) {
        if (at < 0 || at > b.length - length) {
            throw new Damaged("A field past its box's end");
        }
    }
}
