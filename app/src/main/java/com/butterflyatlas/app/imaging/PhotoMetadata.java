package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.imaging.heif.HeifInfo;
import com.butterflyatlas.app.imaging.raw.RawContents;
import com.butterflyatlas.app.imaging.raw.SliceChannel;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.tiff.TiffMetadataReader;
import com.drew.imaging.tiff.TiffProcessingException;
import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Directory;
import com.drew.metadata.ErrorDirectory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A photo's metadata as metadata-extractor reads it from an open channel (NIO; a TIFF by random access, so only its
 * directories are read). Leniently on import, where broken metadata still leaves a readable photo; strictly for the
 * read-back of an export, where metadata that cannot be read must not pass for "no location" (though an error that
 * cannot be hiding a position, in a file that holds no entry leading to one, does not fail it).
 */
final class PhotoMetadata {

    /** Directories whose recorded errors always fail a strict read: those a position is read from. */
    private static final List<Class<? extends Directory>> LOCATED = List.of(GpsDirectory.class, XmpDirectory.class);
    /**
     * Directories whose recorded errors fail a strict read only while the file still holds an entry that leads to a
     * location ({@link #holdsLocationEntry}), which the error may have kept from being followed: IFD0, which holds the
     * pointer, and the one metadata-extractor records errors in when no directory was open yet.
     */
    private static final List<Class<? extends Directory>> POINTING = List.of(ExifIFD0Directory.class, ErrorDirectory.class);
    /** How much of the file the scan for location entries reads at a time. */
    private static final int SCAN_CHUNK = 64 * 1024;

    private PhotoMetadata() {
    }

    /** The first bytes of the file, which tell its format. */
    static byte[] head(SeekableByteChannel channel) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(PhotoFormat.HEAD_BYTES);
        channel.position(0);
        while (head.hasRemaining() && channel.read(head) > 0) {
            // keep reading: a channel may return fewer bytes than asked
        }
        return Arrays.copyOf(head.array(), head.position());
    }

    /** The metadata; empty when there is none or it cannot be read (the photo is upright, with no time or place). */
    static Metadata lenient(SeekableByteChannel channel, PhotoFormat format) {
        try {
            return read(channel, format);
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            return new Metadata();
        }
    }

    /**
     * The metadata, or an IOException when it cannot be read: when metadata-extractor fails, when it records an error in
     * a GPS or XMP directory, and when it records one in IFD0 or outside any directory while the file's bytes still hold
     * an entry that leads to a location ({@link #holdsLocationEntry}). It records most errors instead of throwing.
     */
    static Metadata strict(SeekableByteChannel channel, PhotoFormat format, Path file) throws IOException {
        Metadata metadata;
        try {
            metadata = read(channel, format);
        } catch (ImageProcessingException | RuntimeException e) {
            throw new IOException("Could not read the metadata of " + file, e);
        }
        Directory broken = firstWithErrors(metadata, LOCATED);
        if (broken == null) {
            broken = firstWithErrors(metadata, POINTING);
            if (broken != null && !holdsLocationEntry(channel, SCAN_CHUNK)) {
                broken = null; // nothing in the file that could point at a position
            }
        }
        if (broken != null) {
            throw new IOException("Could not read the " + broken.getName() + " metadata of " + file + ": "
                    + broken.getErrors().iterator().next());
        }
        return metadata;
    }

    private static Directory firstWithErrors(Metadata metadata, List<Class<? extends Directory>> types) {
        for (Class<? extends Directory> type : types) {
            for (Directory directory : metadata.getDirectoriesOfType(type)) {
                if (directory.hasErrors()) {
                    return directory;
                }
            }
        }
        return null;
    }

    /**
     * Whether the file's bytes hold anything shaped like an IFD entry that leads to a location, anywhere, big-endian
     * ({@code tag 00 type}) or little-endian ({@code tag type 00}): the GPS pointer (0x8825) of type LONG, SLONG, FLOAT
     * or IFD (4, 9, 11, 13), or an XMP (0x02BC) or Photoshop (0x8649) block of type BYTE or UNDEFINED (1, 7), either of
     * which may hold a position. Read {@code chunk} bytes at a time, each chunk overlapping the last by 3 bytes.
     */
    static boolean holdsLocationEntry(SeekableByteChannel channel, int chunk) throws IOException {
        channel.position(0);
        byte[] bytes = new byte[chunk + 3];
        int kept = 0;
        while (true) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, kept, chunk);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading: a channel may return fewer bytes than asked
            }
            int length = buffer.position();
            if (length == kept) {
                return false; // the end of the file
            }
            for (int at = 0; at + 4 <= length; at++) {
                if (locationEntryAt(bytes, at)) {
                    return true;
                }
            }
            kept = Math.min(3, length);
            System.arraycopy(bytes, length - kept, bytes, 0, kept);
        }
    }

    private static boolean locationEntryAt(byte[] b, int at) {
        int big = ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
        int little = ((b[at + 1] & 0xFF) << 8) | (b[at] & 0xFF);
        return b[at + 2] == 0 && leads(big, b[at + 3]) || b[at + 3] == 0 && leads(little, b[at + 2]);
    }

    private static boolean leads(int tag, int type) {
        return switch (tag) {
            case 0x8825 -> type == 0x04 || type == 0x09 || type == 0x0B || type == 0x0D;
            case 0x02BC, 0x8649 -> type == 0x01 || type == 0x07;
            default -> false;
        };
    }

    /**
     * A RAW file's metadata (design §3, audit 3): TIFF-shaped families by random access, RAF and CR3 through
     * metadata-extractor's own file-type dispatch. When that finds no EXIF, a CR3's {@code CMT1}–{@code CMT4} boxes
     * (IFD0, the Exif sub-IFD, GPS; each a TIFF structure of its own) or a RAF's embedded JPEG are read instead. Empty
     * when nothing can be read (the photo is upright, with no time or place).
     */
    static Metadata raw(SeekableByteChannel channel, RawContents contents) {
        Metadata metadata;
        try {
            channel.position(0);
            if ("raf".equals(contents.family()) || "cr3".equals(contents.family())) {
                // Not closed: that would close the channel.
                metadata = ImageMetadataReader.readMetadata(new BufferedInputStream(Channels.newInputStream(channel)),
                        channel.size());
            } else {
                metadata = TiffMetadataReader.readMetadata(new ChannelRandomAccessReader(channel));
            }
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            metadata = new Metadata();
        }
        if (hasExif(metadata)) {
            return metadata;
        }
        try {
            if (!contents.cmt().isEmpty()) {
                Metadata boxes = cmt(channel, contents.cmt());
                return hasExif(boxes) ? boxes : metadata;
            }
            RawContents.Slice jpeg = contents.embeddedJpeg();
            if (jpeg != null) {
                // Not closed: the slice would close the channel.
                Metadata embedded = JpegMetadataReader.readMetadata(new BufferedInputStream(Channels.newInputStream(
                        new SliceChannel(channel, jpeg.offset(), jpeg.length()))));
                return hasExif(embedded) ? embedded : metadata;
            }
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            // keep what the first read found
        }
        return metadata;
    }

    /** The largest Exif item read from a HEIC (an iPhone's is a few KB to some tens of KB). */
    private static final int MAX_HEIF_EXIF = 4 * 1024 * 1024;

    /**
     * A HEIC photo's metadata (Phase 7b design §3): through metadata-extractor's HEIF reader; when that finds no EXIF, the
     * Exif item the boxes point to is read here (its first 4 bytes say where its TIFF structure starts) and handed to the
     * TIFF reader. Empty when nothing can be read (the photo has no time or place).
     */
    static Metadata heif(SeekableByteChannel channel, HeifInfo info) {
        Metadata metadata;
        try {
            channel.position(0);
            // Not closed: that would close the channel.
            metadata = ImageMetadataReader.readMetadata(new BufferedInputStream(Channels.newInputStream(channel)),
                    channel.size());
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            metadata = new Metadata();
        }
        if (hasExif(metadata) || info.exif().isEmpty()) {
            return metadata;
        }
        try {
            long total = 0;
            for (HeifInfo.Extent e : info.exif()) {
                total += e.length();
            }
            if (total > MAX_HEIF_EXIF || total < 4) {
                return metadata;
            }
            byte[] item = new byte[(int) total];
            ByteBuffer buffer = ByteBuffer.wrap(item);
            for (HeifInfo.Extent e : info.exif()) {
                channel.position(e.offset());
                buffer.limit(buffer.position() + (int) e.length());
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // keep reading: a channel may return fewer bytes than asked
                }
            }
            long start = 4 + ((item[0] & 0xFFL) << 24 | (item[1] & 0xFFL) << 16 | (item[2] & 0xFFL) << 8 | (item[3] & 0xFFL));
            if (start >= item.length) {
                return metadata;
            }
            Metadata exif = TiffMetadataReader.readMetadata(new ByteArrayReader(Arrays.copyOfRange(item, (int) start,
                    item.length)));
            return hasExif(exif) ? exif : metadata;
        } catch (TiffProcessingException | IOException | RuntimeException e) {
            return metadata;
        }
    }

    /** The largest CMT box read (real ones are a few KB; the maker note box, CMT3, is not read). */
    private static final int MAX_CMT = 1024 * 1024;

    /** IFD0 from {@code CMT1}, the Exif sub-IFD from {@code CMT2} and GPS from {@code CMT4}, as metadata-extractor keeps them. */
    private static Metadata cmt(SeekableByteChannel channel, List<RawContents.Slice> boxes) throws IOException {
        Metadata metadata = new Metadata();
        for (RawContents.Slice box : boxes) {
            if (box.length() > MAX_CMT || box.name().equals("CMT3")) {
                continue;
            }
            byte[] bytes = new byte[(int) box.length()];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            channel.position(box.offset());
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading: a channel may return fewer bytes than asked
            }
            ExifIFD0Directory read;
            try {
                read = TiffMetadataReader.readMetadata(new ByteArrayReader(bytes)).getFirstDirectoryOfType(ExifIFD0Directory.class);
            } catch (TiffProcessingException | IOException | RuntimeException e) {
                continue;
            }
            if (read == null) {
                continue;
            }
            Directory into = switch (box.name()) {
                case "CMT1" -> new ExifIFD0Directory();
                case "CMT2" -> new ExifSubIFDDirectory();
                default -> new GpsDirectory();
            };
            for (Tag tag : read.getTags()) {
                into.setObject(tag.getTagType(), read.getObject(tag.getTagType()));
            }
            metadata.addDirectory(into);
        }
        return metadata;
    }

    private static boolean hasExif(Metadata metadata) {
        return metadata.getFirstDirectoryOfType(ExifIFD0Directory.class) != null
                || metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class) != null;
    }

    private static Metadata read(SeekableByteChannel channel, PhotoFormat format)
            throws ImageProcessingException, IOException {
        channel.position(0);
        if (format == PhotoFormat.TIFF) {
            return TiffMetadataReader.readMetadata(new ChannelRandomAccessReader(channel));
        }
        // Not closed: that would close the channel, which the caller may read next (ImageIO on import).
        InputStream in = new BufferedInputStream(Channels.newInputStream(channel));
        return ImageMetadataReader.readMetadata(in, channel.size());
    }
}
