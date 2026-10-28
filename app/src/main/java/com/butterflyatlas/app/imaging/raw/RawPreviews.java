package com.butterflyatlas.app.imaging.raw;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The JPEG previews inside camera RAW files (design §2), found by four small readers chosen by the first bytes:
 * TIFF-style (with the ORF and RW2 variants), CR3, RAF. Every JPEG a reader finds is a candidate; the largest by pixel
 * count wins, if it is a baseline or progressive JPEG with a long side of at least {@value #MIN_LONG_SIDE} px and at
 * most {@value #MAX_PREVIEW_BYTES} bytes. The files come from anywhere, so the readers are written for damaged and
 * hostile input: offsets and lengths are checked against the file size before reading, walks are bounded, and anything
 * unexpected means "no usable preview". Only an IOException from the channel itself escapes.
 */
public final class RawPreviews {

    public static final int MIN_LONG_SIDE = 1024;
    public static final long MAX_PREVIEW_BYTES = 64L * 1024 * 1024;

    private RawPreviews() {
    }

    /** The preview of the RAW file, or empty when it has none usable (or is not a RAW file). */
    public static Optional<RawPreview> find(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return Optional.ofNullable(read(channel).preview());
        }
    }

    /** Everything a look inside the RAW file finds; its preview is null when none is usable. */
    public static RawContents read(SeekableByteChannel channel) throws IOException {
        RawBytes in = new RawBytes(channel, channel.size());
        Found found = new Found();
        try {
            byte[] head = in.readUpTo(0, 64);
            if (RafPreviews.recognises(head)) {
                RafPreviews.read(in, found);
            } else if (Cr3Previews.recognises(head)) {
                Cr3Previews.read(in, found);
            } else if (TiffPreviews.recognises(head)) {
                TiffPreviews.read(in, found);
            } else {
                return RawContents.NONE;
            }
        } catch (RawBytes.Damaged e) {
            // keep what was found before the damage
        } catch (RuntimeException e) {
            return new RawContents(found.family, null, null, List.of(), null); // unexpected: no usable preview
        }
        try {
            return found.contents(in);
        } catch (RuntimeException e) {
            return new RawContents(found.family, null, null, List.of(), null);
        }
    }

    /** Bytes to write over a preview's own bytes at {@code at} (absolute in the file) before it is served. */
    public record Patch(long at, byte[] bytes) {
    }

    /**
     * When the preview JPEG carries an EXIF orientation of its own other than 1 (a RAF's or an RW2's may), the patch that
     * sets it to 1, so a browser shows the stored pixels as they are and the page's turn (the RAW's orientation) is the
     * only one applied; null when nothing needs changing.
     */
    public static Patch uprightExif(SeekableByteChannel channel, long offset, long length) throws IOException {
        RawBytes in = new RawBytes(channel, channel.size());
        try {
            long exif = JpegCheck.exifTiff(in, offset, length);
            long[] field = exif < 0 ? null : TiffPreviews.orientationField(in, exif);
            if (field == null || field[2] == 1 || field[0] + 2 > offset + length) {
                return null;
            }
            return new Patch(field[0], field[1] == 1 ? new byte[] {1, 0} : new byte[] {0, 1});
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The RAW family the bytes say, as a lowercase extension: {@code raf}, {@code cr3}, {@code orf}, {@code rw2},
     * {@code cr2}, or {@code dng} for a TIFF whose IFD0 carries DNGVersion. Empty for anything else, including a
     * TIFF-shaped RAW that only its name tells apart (a NEF, an ARW) and a plain TIFF.
     */
    public static Optional<String> family(SeekableByteChannel channel) throws IOException {
        RawBytes in = new RawBytes(channel, channel.size());
        try {
            byte[] head = in.readUpTo(0, 64);
            if (RafPreviews.recognises(head)) {
                return Optional.of("raf");
            }
            if (Cr3Previews.recognises(head)) {
                return Optional.of("cr3");
            }
            if (TiffPreviews.recognises(head)) {
                return Optional.ofNullable(TiffPreviews.family(in));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** {@link #family(SeekableByteChannel)} of a file. */
    public static Optional<String> family(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return family(channel);
        }
    }
}
