package com.butterflyatlas.app.testing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-made HEIF files for tests (box structure only; the "coded" picture bytes are filler, nothing decodes them):
 * a primary item that is a single image or a grid of tiles, with {@code ispe}, {@code irot}, {@code imir} and
 * {@code pixi} as asked, an Exif item stored in {@code mdat} (through {@code iloc}) or in {@code idat}, auxiliary images,
 * and any brands. Item 1 is the primary; tiles, the Exif item and an auxiliary item follow.
 */
public final class TestHeif {

    private String major = "heic";
    private List<String> compatible = new ArrayList<>(List.of("mif1", "heic"));
    private String primaryType = "hvc1";
    private String tileType = "hvc1";
    private int width = 64;
    private int height = 32;
    private int tilesAcross;
    private int tilesDown;
    private Integer turn;
    private Integer mirror;
    private boolean mirrorFirst;
    private Integer bitDepth;
    private byte[] exif;
    private boolean exifInIdat;
    private boolean auxiliary;
    private boolean primaryItem = true;
    private boolean ispe = true;
    private boolean moov;

    public static TestHeif still(int width, int height) {
        TestHeif heif = new TestHeif();
        heif.width = width;
        heif.height = height;
        return heif;
    }

    public TestHeif brands(String major, String... compatible) {
        this.major = major;
        this.compatible = new ArrayList<>(List.of(compatible));
        return this;
    }

    public TestHeif primaryType(String type) {
        this.primaryType = type;
        return this;
    }

    /** The primary item becomes a {@code grid} of {@code across × down} tiles of {@code tileType}. */
    public TestHeif grid(int across, int down, String tileType) {
        this.primaryType = "grid";
        this.tilesAcross = across;
        this.tilesDown = down;
        this.tileType = tileType;
        return this;
    }

    public TestHeif turn(int quarterTurnsAnticlockwise) {
        this.turn = quarterTurnsAnticlockwise;
        return this;
    }

    public TestHeif mirror(int axis) {
        this.mirror = axis;
        return this;
    }

    /** {@code imir} listed before {@code irot} among the primary's properties. */
    public TestHeif mirrorFirst() {
        this.mirrorFirst = true;
        return this;
    }

    public TestHeif bitDepth(int bits) {
        this.bitDepth = bits;
        return this;
    }

    /**
     * An Exif item: {@code tiff} is a TIFF structure ({@link TestExif#tiffBlock}), stored as Apple's encoder stores it:
     * a 4-byte offset of 6, {@code Exif\0\0}, then the TIFF structure.
     */
    public TestHeif exif(byte[] tiff) {
        this.exif = tiff;
        return this;
    }

    public TestHeif exifInIdat() {
        this.exifInIdat = true;
        return this;
    }

    /** An auxiliary image (a depth map) with an {@code auxC} property and an {@code auxl} reference to the primary. */
    public TestHeif auxiliary() {
        this.auxiliary = true;
        return this;
    }

    /** No {@code pitm} box (as an image sequence). */
    public TestHeif withoutPrimary() {
        this.primaryItem = false;
        return this;
    }

    public TestHeif withoutSize() {
        this.ispe = false;
        return this;
    }

    /** A {@code moov} box after {@code meta} (as an image sequence). */
    public TestHeif withMoov() {
        this.moov = true;
        return this;
    }

    public byte[] build() {
        byte[] ftyp = ftyp();
        byte[] picture = "not a real HEVC picture".getBytes(StandardCharsets.ISO_8859_1);
        byte[] exifItem = exif == null ? null : concat(u32(6), "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), exif);
        // Two passes: the mdat offsets in iloc depend on the meta box's size, which does not change between them.
        byte[] meta = meta(0, picture, exifItem);
        long mdatData = ftyp.length + meta.length + 8L;
        meta = meta(mdatData, picture, exifItem);
        byte[] mdatBody = exifItem == null || exifInIdat ? picture : concat(picture, exifItem);
        byte[] file = concat(ftyp, meta, box("mdat", mdatBody));
        return moov ? concat(file, box("moov", box("mvhd", new byte[100]))) : file;
    }

    private byte[] ftyp() {
        StringBuilder b = new StringBuilder(major).append("\0\0\0\0");
        compatible.forEach(b::append);
        return box("ftyp", b.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private int tiles() {
        return primaryType.equals("grid") ? tilesAcross * tilesDown : 0;
    }

    private byte[] meta(long mdatData, byte[] picture, byte[] exifItem) {
        int tiles = tiles();
        int exifId = exifItem == null ? -1 : 2 + tiles;
        int auxId = auxiliary ? 2 + tiles + (exifItem == null ? 0 : 1) : -1;
        List<byte[]> infes = new ArrayList<>();
        infes.add(infe(1, primaryType));
        for (int t = 0; t < tiles; t++) {
            infes.add(infe(2 + t, tileType));
        }
        if (exifId > 0) {
            infes.add(infe(exifId, "Exif"));
        }
        if (auxId > 0) {
            infes.add(infe(auxId, "hvc1"));
        }
        byte[] iinf = fullBox("iinf", 0, 0, concat(u16(infes.size()), concat(infes.toArray(byte[][]::new))));

        List<byte[]> refs = new ArrayList<>();
        if (tiles > 0) {
            ByteArrayOutputStream ids = new ByteArrayOutputStream();
            for (int t = 0; t < tiles; t++) {
                ids.writeBytes(u16(2 + t));
            }
            refs.add(box("dimg", concat(u16(1), u16(tiles), ids.toByteArray())));
        }
        if (exifId > 0) {
            refs.add(box("cdsc", concat(u16(exifId), u16(1), u16(1))));
        }
        if (auxId > 0) {
            refs.add(box("auxl", concat(u16(auxId), u16(1), u16(1))));
        }
        byte[] iref = refs.isEmpty() ? new byte[0] : fullBox("iref", 0, 0, concat(refs.toArray(byte[][]::new)));

        // properties: 1 ispe, 2 irot, 3 imir, 4 pixi, 5 auxC, 6 tile ispe
        List<byte[]> props = new ArrayList<>();
        props.add(fullBox("ispe", 0, 0, concat(u32(width), u32(height))));
        props.add(box("irot", new byte[] {(byte) (turn == null ? 0 : turn)}));
        props.add(box("imir", new byte[] {(byte) (mirror == null ? 0 : mirror)}));
        props.add(fullBox("pixi", 0, 0, new byte[] {3, b(bitDepth), b(bitDepth), b(bitDepth)}));
        props.add(fullBox("auxC", 0, 0, "urn:mpeg:hevc:2015:auxid:2\0".getBytes(StandardCharsets.ISO_8859_1)));
        props.add(fullBox("ispe", 0, 0, concat(u32(512), u32(512))));
        byte[] ipco = box("ipco", concat(props.toArray(byte[][]::new)));

        List<byte[]> entries = new ArrayList<>();
        ByteArrayOutputStream primary = new ByteArrayOutputStream();
        if (ispe) {
            primary.write(1);
        }
        if (bitDepth != null && tiles == 0) {
            primary.write(4);
        }
        if (mirrorFirst && mirror != null) {
            primary.write(0x83);
        }
        if (turn != null) {
            primary.write(0x82);
        }
        if (!mirrorFirst && mirror != null) {
            primary.write(0x83);
        }
        entries.add(concat(u16(1), new byte[] {(byte) primary.size()}, primary.toByteArray()));
        for (int t = 0; t < tiles; t++) {
            entries.add(concat(u16(2 + t), bitDepth == null ? new byte[] {1, 6} : new byte[] {2, 6, 4}));
        }
        if (auxId > 0) {
            entries.add(concat(u16(auxId), new byte[] {1, 5}));
        }
        byte[] ipma = fullBox("ipma", 0, 0, concat(u32(entries.size()), concat(entries.toArray(byte[][]::new))));
        byte[] iprp = box("iprp", concat(ipco, ipma));

        // iloc version 1: offset 4, length 4, base offset 0, index 0
        List<byte[]> locs = new ArrayList<>();
        byte[] gridData = tiles > 0 ? concat(new byte[] {0, 0, (byte) (tilesDown - 1), (byte) (tilesAcross - 1)},
                u16(width), u16(height)) : null;
        long idatAt = 0;
        if (gridData != null) {
            locs.add(loc(1, 1, idatAt, gridData.length));
            idatAt += gridData.length;
        } else {
            locs.add(loc(1, 0, mdatData, picture.length));
        }
        for (int t = 0; t < tiles; t++) {
            locs.add(loc(2 + t, 0, mdatData, picture.length));
        }
        byte[] idatBody = gridData == null ? new byte[0] : gridData;
        if (exifId > 0) {
            if (exifInIdat) {
                locs.add(loc(exifId, 1, idatAt, exifItem.length));
                idatBody = concat(idatBody, exifItem);
            } else {
                locs.add(loc(exifId, 0, mdatData + picture.length, exifItem.length));
            }
        }
        if (auxId > 0) {
            locs.add(loc(auxId, 0, mdatData, picture.length));
        }
        byte[] iloc = fullBox("iloc", 1, 0, concat(new byte[] {0x44, 0x00}, u16(locs.size()),
                concat(locs.toArray(byte[][]::new))));
        byte[] idat = idatBody.length == 0 ? new byte[0] : box("idat", idatBody);

        byte[] hdlr = fullBox("hdlr", 0, 0, concat(new byte[4], "pict".getBytes(StandardCharsets.ISO_8859_1), new byte[13]));
        byte[] pitm = primaryItem ? fullBox("pitm", 0, 0, u16(1)) : new byte[0];
        return fullBox("meta", 0, 0, concat(hdlr, pitm, iinf, iref, iprp, iloc, idat));
    }

    private static byte b(Integer bits) {
        return (byte) (bits == null ? 8 : bits);
    }

    private static byte[] loc(int id, int method, long offset, long length) {
        return concat(u16(id), u16(method), u16(0), u16(1), u32(offset), u32(length));
    }

    private static byte[] infe(int id, String type) {
        byte[] body = concat(u16(id), u16(0), type.getBytes(StandardCharsets.ISO_8859_1), new byte[] {0});
        if (type.equals("mime")) {
            body = concat(body, "application/rdf+xml\0".getBytes(StandardCharsets.ISO_8859_1));
        }
        return fullBox("infe", 2, 0, body);
    }

    public static byte[] box(String type, byte[] body) {
        return concat(u32(8L + body.length), type.getBytes(StandardCharsets.ISO_8859_1), body);
    }

    public static byte[] fullBox(String type, int version, int flags, byte[] body) {
        return box(type, concat(new byte[] {(byte) version, (byte) (flags >> 16), (byte) (flags >> 8), (byte) flags}, body));
    }

    public static byte[] u16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    public static byte[] u32(long value) {
        return ByteBuffer.allocate(4).putInt((int) value).array();
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
