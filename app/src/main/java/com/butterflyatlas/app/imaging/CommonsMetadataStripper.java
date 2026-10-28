package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.exports.MetadataStripper;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.bytesource.ByteSource;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageParser;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.jpeg.xmp.JpegRewriter;
import org.apache.commons.imaging.formats.tiff.AbstractTiffElement;
import org.apache.commons.imaging.formats.tiff.TiffDirectory;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.TiffImageParser;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffImageWriterLossless;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@link MetadataStripper} with Apache Commons Imaging. JPEG: anything after the primary image (the second images of a
 * multi-picture file, appended previews, which can carry their own GPS) is dropped with the MPF segment, and so are
 * every APP1 segment that does not start exactly {@code Exif\0\0} (the XMP packet and any extension, and an EXIF block
 * under another preamble, which other readers may still read) and every APP13 segment (Photoshop resources and IPTC, in
 * any version); then the EXIF block is rewritten losslessly without its GPS directory, its maker note (vendors such as
 * Panasonic store a city, state or landmark there) and any XMP, IPTC or Photoshop block an editor left inside EXIF.
 * Every other segment and the compressed image data are written back as they were. An EXIF block Commons cannot parse
 * is kept as it is when nothing in it is shaped like a GPS pointer, a maker note or an embedded XMP, IPTC or Photoshop
 * entry (the read-back then decides), and refuses the photo otherwise. TIFF: the directories rewritten losslessly
 * without the GPS directory, the maker note and the XMP (700), IPTC (33723) and Photoshop (34377) tags. PNG and WebP:
 * chunk by chunk ({@link MetadataChunks}). Each step runs only when its block is there, so a photo with nothing to
 * remove comes back unchanged; after a JPEG or TIFF rewrite the image data is compared with the original's, and any
 * difference refuses the photo. The read-back check ({@link #hasLocation}) uses metadata-extractor, the library's own
 * reader, strictly ({@link PhotoMetadata#strict}), and also fails a copy that still has a maker note.
 */
public final class CommonsMetadataStripper implements MetadataStripper {

    /** IFD0 tags that carry XMP (700), IPTC (33723) or Photoshop resources, which may hold either (34377). */
    private static final List<Integer> EMBEDDED = List.of(0x02BC, 0x83BB, 0x8649);
    /** The GPS pointer of IFD0. */
    private static final int GPS_POINTER = 0x8825;
    /** The maker note (of the Exif sub-IFD), where some vendors store a city, state or landmark: removed whole. */
    private static final int MAKER_NOTE = 0x927C;
    /** IFD entries that lead to a location: the GPS pointer, the maker note and the blocks of {@link #EMBEDDED}. */
    private static final List<Integer> LEADS =
            Stream.concat(Stream.of(GPS_POINTER, MAKER_NOTE), EMBEDDED.stream()).toList();

    private static final int APP1 = 0xFFE1;
    /**
     * The preamble of the one APP1 segment kept: EXIF, exactly. Every other APP1 is dropped (XMP, its extensions, and
     * any EXIF with another preamble, which Commons and other readers may still read as EXIF).
     */
    private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final int APP2 = 0xFFE2;
    /** Photoshop resources and IPTC ({@code Photoshop 3.0}, the older {@code Adobe_Photoshop2.5:}): always dropped. */
    private static final int APP13 = 0xFFED;
    /** The APP2 segment that points at the second images of a multi-picture JPEG, which are dropped with it. */
    private static final byte[] MPF = "MPF\0".getBytes(StandardCharsets.ISO_8859_1);

    private final UnaryOperator<byte[]> rewritten;

    public CommonsMetadataStripper() {
        this(UnaryOperator.identity());
    }

    /** A test seam: {@code rewritten} sees (and may change) every JPEG or TIFF rewrite before it is checked. */
    CommonsMetadataStripper(UnaryOperator<byte[]> rewritten) {
        this.rewritten = rewritten;
    }

    @Override
    public byte[] strip(byte[] photo) throws IOException {
        PhotoFormat format = PhotoFormat.detect(Arrays.copyOf(photo, Math.min(photo.length, PhotoFormat.HEAD_BYTES)))
                .orElseThrow(() -> new IOException("Not a photo Butterfly Atlas reads"));
        try {
            return switch (format) {
                case JPEG -> jpeg(photo);
                case TIFF -> tiff(photo);
                case PNG -> MetadataChunks.png(photo);
                case WEBP -> MetadataChunks.webp(photo);
                // Never asked: RAW photos are exported exactly (design §7), their maker notes are needed to use them.
                case RAW -> throw new IOException("RAW photos are exported exactly; their location cannot be removed");
                // Never asked either: HEIC photos are exported exactly (Phase 7b design §8).
                case HEIC -> throw new IOException("HEIC photos are exported exactly; their location cannot be removed");
            };
        } catch (ImagingException | RuntimeException e) {
            throw new IOException("Could not rewrite the metadata of a " + format, e);
        }
    }

    /**
     * Reads the file's metadata with metadata-extractor, as the library reads photos, strictly: a file that is not a
     * photo or whose metadata cannot be read is an error, not "no location".
     */
    @Override
    public boolean hasLocation(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            PhotoFormat format = PhotoFormat.detect(PhotoMetadata.head(channel))
                    .orElseThrow(() -> new IOException("Not a photo Butterfly Atlas reads: " + file));
            Metadata metadata = PhotoMetadata.strict(channel, format, file);
            try {
                return MetadataSnapshot.hasLocation(metadata) || hasMakerNote(metadata);
            } catch (RuntimeException e) {
                throw new IOException("Could not read the metadata of " + file, e);
            }
        }
    }

    /** Whether metadata-extractor read a maker note: one it knows (its own directory) or not (the raw tag). */
    private static boolean hasMakerNote(Metadata metadata) {
        for (Directory directory : metadata.getDirectories()) {
            if (directory.getClass().getSimpleName().contains("Makernote") || directory instanceof ExifDirectoryBase
                    && directory.containsTag(ExifDirectoryBase.TAG_MAKERNOTE)) {
                return true;
            }
        }
        return false;
    }

    private byte[] jpeg(byte[] photo) throws IOException {
        int end = primaryEnd(photo);
        byte[] primary = end == photo.length ? photo : Arrays.copyOf(photo, end); // an appended image may carry GPS
        // The segments go first, so Commons only ever sees standard EXIF segments.
        byte[] segments = new Segments().without(primary);
        byte[] data = segments;
        try {
            ImageMetadata metadata = new JpegImageParser().getMetadata(ByteSource.array(segments), null);
            TiffImageMetadata exif = metadata instanceof JpegImageMetadata jpeg ? jpeg.getExif() : null;
            if (exif != null && hasSomethingToRemove(exif)) {
                TiffOutputSet without = withoutLocation(exif.getOutputSet());
                data = written(out -> new ExifRewriter().updateExifMetadataLossless(segments, out, without));
            }
        } catch (IOException | RuntimeException e) {
            if (exifMayHoldLocation(segments)) {
                throw e;
            }
            // An EXIF Commons cannot parse, with no entry that could lead to a location: kept as it is (the read-back
            // decides).
            data = segments;
        }
        if (data == photo) {
            return photo;
        }
        data = rewritten.apply(data);
        if (!Arrays.equals(scan(data), scan(primary))) {
            throw new IOException("Rewriting the JPEG's metadata changed its image data");
        }
        return data;
    }

    /**
     * Whether any EXIF APP1 segment (every one) before the image data holds something shaped like an IFD entry of
     * {@link #LEADS} ({@code tag 00 type} big-endian or {@code tag type 00} little-endian, any type). Also true when
     * the segments cannot be walked: in doubt, the photo is refused.
     */
    private static boolean exifMayHoldLocation(byte[] jpeg) {
        int at = 2; // every APP1 left starts with EXIF exactly (Segments)
        while (at + 4 <= jpeg.length) {
            if ((jpeg[at] & 0xFF) != 0xFF) {
                return true;
            }
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++; // fill byte
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return false;
            }
            int end = at + 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
            if (end > jpeg.length) {
                return true;
            }
            if (marker == 0xE1 && end - (at + 4) >= EXIF.length
                    && Arrays.equals(jpeg, at + 4, at + 4 + EXIF.length, EXIF, 0, EXIF.length)) {
                for (int i = at + 4 + EXIF.length; i + 4 <= end; i++) {
                    if (leadAt(jpeg, i)) {
                        return true;
                    }
                }
            }
            at = end;
        }
        return true; // no image data found
    }

    private static boolean leadAt(byte[] b, int at) {
        int big = ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
        int little = ((b[at + 1] & 0xFF) << 8) | (b[at] & 0xFF);
        return LEADS.contains(big) && b[at + 2] == 0 || LEADS.contains(little) && b[at + 3] == 0;
    }

    /**
     * Where the primary image of a JPEG ends: just after its end-of-image marker, found by walking its segments and the
     * entropy-coded data of each scan (stuffed {@code FF 00}, restart and fill bytes skipped). What follows (the second
     * images of MPF, appended previews) is not part of it. The file's length when no end-of-image marker is found.
     */
    static int primaryEnd(byte[] jpeg) throws IOException {
        int at = 2;
        boolean inScan = false;
        while (at + 1 < jpeg.length) {
            if (inScan) {
                if ((jpeg[at] & 0xFF) != 0xFF) {
                    at++;
                    continue;
                }
                int next = jpeg[at + 1] & 0xFF;
                if (next == 0x00 || next == 0xFF || (next >= 0xD0 && next <= 0xD7)) {
                    at += next == 0xFF ? 1 : 2;
                    continue;
                }
                inScan = false;
            }
            if ((jpeg[at] & 0xFF) != 0xFF) {
                throw new IOException("Not a JPEG segment");
            }
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++; // fill byte
                continue;
            }
            if (marker == 0xD9) {
                return at + 2;
            }
            if (at + 4 > jpeg.length) {
                break;
            }
            at += 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
            inScan = marker == 0xDA;
        }
        return jpeg.length;
    }

    /** A JPEG's compressed image data: from its first start-of-scan marker to the end. */
    private static byte[] scan(byte[] jpeg) throws IOException {
        int at = 2;
        while (at + 4 <= jpeg.length) {
            if ((jpeg[at] & 0xFF) != 0xFF) {
                throw new IOException("Not a JPEG segment");
            }
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++; // fill byte
                continue;
            }
            if (marker == 0xDA) {
                return Arrays.copyOfRange(jpeg, at, jpeg.length);
            }
            at += 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
        }
        throw new IOException("The JPEG has no image data");
    }

    private byte[] tiff(byte[] photo) throws IOException {
        TiffImageMetadata metadata = tiffMetadata(photo);
        if (!hasSomethingToRemove(metadata)) {
            return photo;
        }
        TiffOutputSet without = withoutLocation(metadata.getOutputSet());
        byte[] data = rewritten.apply(
                written(out -> new TiffImageWriterLossless(without.byteOrder, photo).write(out, without)));
        if (!sameImageData(metadata, tiffMetadata(data))) {
            throw new IOException("Rewriting the TIFF's directories changed its image data");
        }
        return data;
    }

    private static TiffImageMetadata tiffMetadata(byte[] tiff) throws IOException {
        return (TiffImageMetadata) new TiffImageParser().getMetadata(ByteSource.array(tiff), null);
    }

    /** Whether two TIFFs hold the same image data (strips or tiles), in directory order. */
    private static boolean sameImageData(TiffImageMetadata a, TiffImageMetadata b) throws ImagingException {
        List<byte[]> da = imageData(a);
        List<byte[]> db = imageData(b);
        if (da.size() != db.size()) {
            return false;
        }
        for (int i = 0; i < da.size(); i++) {
            if (!Arrays.equals(da.get(i), db.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<byte[]> imageData(TiffImageMetadata metadata) throws ImagingException {
        List<byte[]> data = new ArrayList<>();
        for (TiffDirectory directory : metadata.contents.directories) {
            if (directory.hasTiffImageData()) {
                for (AbstractTiffElement.DataElement element : directory.getTiffImageData().getImageData()) {
                    data.add(element.getData());
                }
            }
        }
        return data;
    }

    /**
     * Whether the parsed EXIF or TIFF has anything {@link #withoutLocation} removes: a GPS directory, a GPS pointer, a
     * maker note or a block of {@link #EMBEDDED}, in any directory. Checked before the output set is built, since
     * building it can fail on a field Commons cannot write, which must not refuse a photo that has nothing to remove.
     */
    static boolean hasSomethingToRemove(TiffImageMetadata metadata) {
        for (TiffDirectory directory : metadata.contents.directories) {
            if (directory.type == TiffDirectoryConstants.DIRECTORY_TYPE_GPS) {
                return true;
            }
            for (TiffField field : directory.getDirectoryEntries()) {
                int tag = field.getTag();
                if (tag == GPS_POINTER || tag == MAKER_NOTE || EMBEDDED.contains(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The EXIF without its GPS directory, and without the maker note and the blocks of {@link #EMBEDDED} anywhere. */
    private static TiffOutputSet withoutLocation(TiffOutputSet set) throws ImagingException {
        TiffOutputSet without = new TiffOutputSet(set.byteOrder);
        for (TiffOutputDirectory directory : set) {
            if (directory.getType() != TiffDirectoryConstants.DIRECTORY_TYPE_GPS) {
                without.addDirectory(directory);
            }
        }
        without.removeField(ExifTagConstants.EXIF_TAG_GPSINFO);
        without.removeField(MAKER_NOTE);
        EMBEDDED.forEach(without::removeField); // from every directory: each page of a TIFF may carry its own
        return without;
    }

    /**
     * Drops every APP1 segment but standard EXIF ones (so the XMP packet and extensions), the MPF APP2 segment and
     * every APP13 segment (Photoshop, IPTC) of a JPEG.
     */
    private static final class Segments extends JpegRewriter {

        byte[] without(byte[] jpeg) throws IOException {
            JFIFPieces pieces = analyzeJfif(ByteSource.array(jpeg));
            List<JFIFPiece> kept = pieces.pieces.stream().filter(piece -> !dropped(piece)).toList();
            if (kept.size() == pieces.pieces.size()) {
                return jpeg;
            }
            return written(out -> writeSegments(out, kept));
        }

        private static boolean dropped(JFIFPiece piece) {
            if (!(piece instanceof JFIFPieceSegment segment)) {
                return false;
            }
            byte[] data = segment.getSegmentData();
            return segment.marker == APP1 && !startsWith(data, EXIF)
                    || segment.marker == APP2 && startsWith(data, MPF)
                    || segment.marker == APP13;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    @FunctionalInterface
    private interface Writing {
        void to(ByteArrayOutputStream out) throws IOException;
    }

    private static byte[] written(Writing writing) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writing.to(out);
        return out.toByteArray();
    }
}
