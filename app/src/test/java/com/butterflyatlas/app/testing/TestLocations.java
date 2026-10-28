package com.butterflyatlas.app.testing;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.RationalNumber;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.jpeg.iptc.IptcRecord;
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes;
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter;
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data;
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter;
import org.apache.commons.imaging.formats.tiff.AbstractTiffElement;
import org.apache.commons.imaging.formats.tiff.TiffDirectory;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.fieldtypes.AbstractFieldType;
import org.apache.commons.imaging.formats.tiff.write.TiffImageWriterLossless;
import org.apache.commons.imaging.formats.tiff.write.TiffImageWriterLossy;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Sample photos that carry a location in every place a camera or editor puts one, written with Apache Commons Imaging:
 * EXIF (camera, date, orientation, copyright and a GPS block), an XMP packet with GPS, and an IPTC city (APP13 in a
 * JPEG, tag 33723 in a TIFF); PNG and WebP samples with EXIF, XMP and text chunks, put together chunk by chunk.
 */
public final class TestLocations {

    /** Latitude and longitude of the samples (Pune). */
    public static final double LAT = 18.5204;
    public static final double LON = 73.8567;
    public static final String COPYRIGHT = "Omkar Nagare";
    public static final String TAKEN = "2024:05:01 10:22:33";

    /** XMP with a position, as Lightroom writes it. */
    public static final String XMP = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\""
            + " xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\" exif:GPSLatitude=\"18,31.224N\""
            + " exif:GPSLongitude=\"73,51.402E\" photoshop:City=\"Pune\"/>"
            + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

    private TestLocations() {
    }

    /** The camera EXIF of the samples, with a GPS block when {@code gps}. */
    public static TiffOutputSet exif(boolean gps) {
        try {
            TiffOutputSet set = new TiffOutputSet();
            TiffOutputDirectory root = set.getOrCreateRootDirectory();
            root.add(TiffTagConstants.TIFF_TAG_MAKE, "Canon");
            root.add(TiffTagConstants.TIFF_TAG_MODEL, "EOS R7");
            root.add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) 6);
            root.add(TiffTagConstants.TIFF_TAG_COPYRIGHT, COPYRIGHT);
            TiffOutputDirectory exif = set.getOrCreateExifDirectory();
            exif.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, TAKEN);
            exif.add(ExifTagConstants.EXIF_TAG_EXPOSURE_TIME, RationalNumber.valueOf(1 / 500.0));
            if (gps) {
                set.setGpsInDegrees(LON, LAT);
            }
            return set;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A JPEG with camera EXIF and GPS, XMP with GPS and an IPTC city. */
    public static byte[] jpeg() {
        return iptc(xmp(exif(TestImages.jpeg(64, 48), exif(true))));
    }

    /** The JPEG with this EXIF (replacing any it has). */
    public static byte[] exif(byte[] jpeg, TiffOutputSet set) {
        return write(out -> new ExifRewriter().updateExifMetadataLossless(jpeg, out, set));
    }

    /** The JPEG with {@link #XMP}. */
    public static byte[] xmp(byte[] jpeg) {
        return xmp(jpeg, XMP);
    }

    /** The JPEG with this XMP packet. */
    public static byte[] xmp(byte[] jpeg, String packet) {
        return write(out -> new JpegXmpRewriter().updateXmpXml(jpeg, out, packet));
    }

    /** The JPEG with an IPTC block (APP13) naming the city. */
    public static byte[] iptc(byte[] jpeg) {
        return write(out -> new JpegIptcRewriter().writeIptc(jpeg, out,
                new PhotoshopApp13Data(List.of(new IptcRecord(IptcTypes.CITY, "Pune"),
                        new IptcRecord(IptcTypes.SUBLOCATION, "Tamhini")), List.of())));
    }

    /** An uncompressed TIFF with camera EXIF and GPS, an XMP packet (tag 700) and an IPTC block (tag 33723). */
    public static byte[] tiff() {
        byte[] plain = TestExif.camera().taken(TAKEN, null).gps(LAT, LON, 560).tiff(16, 12);
        try {
            TiffImageMetadata metadata = (TiffImageMetadata) Imaging.getMetadata(plain);
            TiffOutputSet set = metadata.getOutputSet();
            TiffOutputDirectory root = set.getRootDirectory();
            root.add(TiffTagConstants.TIFF_TAG_COPYRIGHT, COPYRIGHT);
            root.add(TiffTagConstants.TIFF_TAG_XMP, XMP.getBytes(StandardCharsets.UTF_8));
            byte[] iptc = {0x1C, 0x02, 0x5A, 0x00, 0x04, 'P', 'u', 'n', 'e'}; // record 2, dataset 90 (City)
            root.add(new TiffOutputField(0x83BB, ExifTagConstants.EXIF_TAG_IPTC_NAA, AbstractFieldType.UNDEFINED, iptc.length,
                    iptc));
            return write(out -> new TiffImageWriterLossless(set.byteOrder, plain).write(out, set));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A two-page TIFF: the first page as {@link #tiff()} (EXIF with GPS, XMP, IPTC), the second a plain page that carries
     * its own XMP packet (tag 700) with GPS.
     */
    public static byte[] twoPageTiff() {
        try {
            TiffOutputSet set = ((TiffImageMetadata) Imaging.getMetadata(tiff())).getOutputSet();
            TiffOutputDirectory plain = ((TiffImageMetadata) Imaging.getMetadata(TestExif.camera().tiff(8, 6)))
                    .getOutputSet().getRootDirectory();
            TiffOutputDirectory page = new TiffOutputDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_DIR_1, set.byteOrder);
            for (TiffOutputField field : plain.getFields()) {
                page.add(field);
            }
            page.setTiffImageData(plain.getRawTiffImageData());
            page.add(TiffTagConstants.TIFF_TAG_XMP, XMP.getBytes(StandardCharsets.UTF_8));
            set.addDirectory(page);
            return write(out -> new TiffImageWriterLossy(set.byteOrder).write(out, set));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A JPEG as phones write them with a second image: camera EXIF without GPS, an APP2 MPF segment, and after the
     * primary image's end the second image, which carries its own EXIF with GPS, XMP and IPTC ({@link #jpeg()}).
     */
    public static byte[] jpegWithAppendedImage() {
        byte[] primary = exif(TestImages.jpeg(64, 48, 7), exif(false));
        byte[] mpf = "MPF\0MM\0*\0\0\0\b\0\0".getBytes(StandardCharsets.ISO_8859_1);
        return concat(segment(primary, 0xE2, mpf), jpeg());
    }

    /** The JPEG with one more segment ({@code FF} {@code marker}, then this payload) after its first one. */
    public static byte[] segment(byte[] jpeg, int marker, byte[] payload) {
        int at = 2 + 2 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF)); // after SOI and the first segment
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.writeBytes(payload);
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }

    /** The image data (strips or tiles) of every directory of a TIFF, in order. */
    public static List<byte[]> tiffImageData(byte[] tiff) {
        try {
            List<byte[]> data = new ArrayList<>();
            for (TiffDirectory directory : ((TiffImageMetadata) Imaging.getMetadata(tiff)).contents.directories) {
                if (directory.hasTiffImageData()) {
                    for (AbstractTiffElement.DataElement element : directory.getTiffImageData().getImageData()) {
                        data.add(element.getData());
                    }
                }
            }
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A PNG chunk: its type and data. */
    public record Chunk(String type, byte[] data) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Chunk c && c.type.equals(type) && Arrays.equals(c.data, data);
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return type + "(" + data.length + ")";
        }
    }

    /** A PNG with a pHYs chunk and, after it, EXIF with GPS (eXIf), XMP (iTXt) and two text chunks (tEXt, zTXt). */
    public static byte[] png() {
        byte[] exif = TestExif.camera().gps(LAT, LON, 560).tiff(2, 2);
        List<Chunk> extra = List.of(new Chunk("pHYs", new byte[] {0, 0, 0x0B, 0x13, 0, 0, 0x0B, 0x13, 1}),
                new Chunk("eXIf", exif),
                new Chunk("iTXt", concat("XML:com.adobe.xmp\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1),
                        XMP.getBytes(StandardCharsets.UTF_8))),
                new Chunk("tEXt", "Comment\0Taken in Pune".getBytes(StandardCharsets.ISO_8859_1)),
                new Chunk("zTXt", concat("Location\0\0".getBytes(StandardCharsets.ISO_8859_1), deflate("Pune, India"))));
        List<Chunk> chunks = new ArrayList<>(pngChunks(TestImages.png(16, 12)));
        chunks.addAll(1, extra);
        return pngOf(chunks);
    }

    /** The chunks of a PNG, in order. */
    public static List<Chunk> pngChunks(byte[] png) {
        List<Chunk> chunks = new ArrayList<>();
        ByteBuffer in = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        in.position(8);
        while (in.remaining() >= 12) {
            int length = in.getInt();
            byte[] type = new byte[4];
            in.get(type);
            byte[] data = new byte[length];
            in.get(data);
            in.getInt(); // CRC
            chunks.add(new Chunk(new String(type, StandardCharsets.ISO_8859_1), data));
        }
        return chunks;
    }

    /** A PNG made of these chunks (CRCs computed). */
    public static byte[] pngOf(List<Chunk> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        for (Chunk chunk : chunks) {
            byte[] type = chunk.type().getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(ByteBuffer.allocate(4).putInt(chunk.data().length).array());
            out.writeBytes(type);
            out.writeBytes(chunk.data());
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(chunk.data());
            out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        }
        return out.toByteArray();
    }

    /**
     * An extended WebP: a VP8X chunk with the EXIF and XMP flags set, the 2×2 lossless image of {@link TestImages#webp},
     * then EXIF with GPS (odd length, so padded) and XMP.
     */
    public static byte[] webp() {
        List<Chunk> chunks = webpChunks(TestImages.webp());
        byte[] vp8x = {0x0C, 0, 0, 0, 1, 0, 0, 1, 0, 0}; // flags EXIF | XMP; canvas 2×2 (stored minus one)
        byte[] exif = TestExif.camera().gps(LAT, LON, 560).tiff(1, 1);
        if (exif.length % 2 == 0) {
            exif = Arrays.copyOf(exif, exif.length + 1);
        }
        List<Chunk> all = new ArrayList<>();
        all.add(new Chunk("VP8X", vp8x));
        all.addAll(chunks);
        all.add(new Chunk("EXIF", exif));
        all.add(new Chunk("XMP ", XMP.getBytes(StandardCharsets.UTF_8)));
        return webpOf(all);
    }

    /** The chunks of a WebP (RIFF), in order. */
    public static List<Chunk> webpChunks(byte[] webp) {
        List<Chunk> chunks = new ArrayList<>();
        ByteBuffer in = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
        in.position(12);
        while (in.remaining() >= 8) {
            byte[] type = new byte[4];
            in.get(type);
            int size = in.getInt();
            byte[] data = new byte[size];
            in.get(data);
            if (size % 2 == 1 && in.hasRemaining()) {
                in.get();
            }
            chunks.add(new Chunk(new String(type, StandardCharsets.ISO_8859_1), data));
        }
        return chunks;
    }

    /** A WebP made of these chunks (padded, with the RIFF size). */
    public static byte[] webpOf(List<Chunk> chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        for (Chunk chunk : chunks) {
            body.writeBytes(chunk.type().getBytes(StandardCharsets.ISO_8859_1));
            body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunk.data().length).array());
            body.writeBytes(chunk.data());
            if (chunk.data().length % 2 == 1) {
                body.write(0);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size()).array());
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static byte[] deflate(String text) {
        Deflater deflater = new Deflater();
        deflater.setInput(text.getBytes(StandardCharsets.ISO_8859_1));
        deflater.finish();
        byte[] buffer = new byte[256];
        int n = deflater.deflate(buffer);
        deflater.end();
        return Arrays.copyOf(buffer, n);
    }

    /** {@code a} then {@code b}. */
    public static byte[] concat(byte[] a, byte[] b) {
        byte[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    /** The bytes from a JPEG's first start-of-scan marker to its end: the compressed image data. */
    public static byte[] jpegImageData(byte[] jpeg) {
        int at = 2;
        while (at + 4 <= jpeg.length) {
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA) {
                return Arrays.copyOfRange(jpeg, at, jpeg.length);
            }
            at += 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
        }
        throw new IllegalArgumentException("No start of scan");
    }

    /** The raw value of the GPS latitude (three rationals) of a photo's EXIF, as stored. */
    public static byte[] gpsLatitudeBytes(byte[] photo) {
        try {
            TiffImageMetadata exif = Imaging.getMetadata(photo) instanceof JpegImageMetadata jpeg ? jpeg.getExif()
                    : (TiffImageMetadata) Imaging.getMetadata(photo);
            return exif.findDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_GPS)
                    .findField(GpsTagConstants.GPS_TAG_GPS_LATITUDE).getByteArrayValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether {@code part} appears in the bytes. */
    public static boolean contains(byte[] bytes, byte[] part) {
        return contains(bytes, new String(part, StandardCharsets.ISO_8859_1));
    }

    /** Whether {@code text} appears in the bytes (ISO-8859-1). */
    public static boolean contains(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(text);
    }

    @FunctionalInterface
    private interface Writing {
        void to(ByteArrayOutputStream out) throws IOException;
    }

    private static byte[] write(Writing writing) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writing.to(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
