package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.imaging.raw.RawPreviews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * One pass over one file (design §3.1), for every way a photo comes in: its bytes are streamed once
 * through SHA-256 and a byte counter, the format is checked from the first bytes, an upload is
 * written to its target on the way, and then the facts are read once ({@link PhotoProcessor#read}).
 * A file is RAW by its bytes (its own header, CR2's mark, a DNGVersion tag) or by a RAW extension on a TIFF-shaped
 * file; its facts come through its preview ({@link PhotoProcessor#readRaw}), and a RAW without a usable preview is
 * refused. Refusals are UNSUPPORTED with a plain-language reason: HEIC, AVIF, a RAW without a preview, or not a
 * photo. Catch-up of photo details, which needs no hash, uses {@link #facts(Path)}: the first bytes and the facts only.
 */
public final class PhotoIngest {

    /**
     * What the pass found. {@code facts} is null only for a file in place whose details cannot be read. {@code extension}
     * is the one the app gives the file: the format's, or for RAW the family's own (from the name when it is a RAW
     * extension, else from the bytes: {@code dng} for a DNG named {@code .tif}).
     */
    public record Result(String sha256, long size, PhotoFormat format, PhotoFacts facts, String extension) {

        /** For a file in place whose format is known (or null: no longer one the app supports); a RAW one as a DNG. */
        public Result(String sha256, long size, PhotoFormat format, PhotoFacts facts) {
            this(sha256, size, format, facts, format == null ? null
                    : format == PhotoFormat.RAW ? "dng" : format.extension());
        }
    }

    /** The format a file's first bytes, name and (for a TIFF) IFD0 say, and the extension the app gives it. */
    private record Checked(PhotoFormat format, String extension) {
    }

    /** How a file in place is opened; tests count the opens. */
    @FunctionalInterface
    interface Opener {
        InputStream open(Path file) throws IOException;
    }

    private record Pass(String sha256, long size, byte[] head) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PhotoIngest.class);

    private final PhotoProcessor processor;
    private final long maxBytes;
    private final Opener opener;

    public PhotoIngest(PhotoProcessor processor) {
        this(processor, PhotoStore.MAX_BYTES);
    }

    public PhotoIngest(PhotoProcessor processor, long maxBytes) {
        // NIO, not java.io: on Windows that handle lets the file be moved or deleted while it is read.
        this(processor, maxBytes, Files::newInputStream);
    }

    PhotoIngest(PhotoProcessor processor, long maxBytes, Opener opener) {
        this.processor = processor;
        this.maxBytes = maxBytes;
        this.opener = opener;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** "This file is larger than 300 MB, ..." for this limit. */
    public String tooLargeMessage() {
        return "This file is larger than " + Math.max(1, maxBytes / (1024 * 1024))
                + " MB, which is more than Butterfly Atlas accepts for one photo.";
    }

    /**
     * Streams an upload to {@code target} (which must not exist yet) while hashing it, refusing more than
     * {@link #maxBytes} (TOO_LARGE), then reads its facts from the copy. A photo whose details cannot be read
     * is refused as unreadable; a sender that stops part-way is INTERRUPTED. The caller removes {@code target}
     * when this throws. {@code originalName} only decides the RAW-extension check.
     */
    public Result ingest(InputStream in, Path target, String originalName) throws IOException {
        Pass pass = stream(in, target);
        Checked checked = check(pass.head(), originalName, target, false);
        PhotoFacts facts;
        try {
            facts = read(checked.format(), target);
        } catch (UnsupportedPhotoException e) {
            throw AtlasException.unsupported(e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw AtlasException.unsupported(PhotoFormat.UNREADABLE);
        }
        return new Result(pass.sha256(), pass.size(), checked.format(), facts, checked.extension());
    }

    /**
     * For a file already in place (rescan, catch-up): one streaming pass through NIO, then the facts.
     * A photo whose details cannot be read comes back with null facts (the rescan still registers it,
     * as in 3a); an unsupported one is refused. Files in place have no size limit on purpose
     * (rescan and catch-up register what the user already has). Fails with an IOException if the file cannot be read.
     */
    public Result ingest(Path file) throws IOException {
        return inPlace(file, false);
    }

    /**
     * As {@link #ingest(Path)}, for the rescan (fixes J1): a RAW file without a usable preview is not refused but comes
     * back with null facts, so the rescan registers it and the gallery says it can't be shown (instead of only a log
     * line). Every other refusal stands.
     */
    public Result ingestKeepingRaw(Path file) throws IOException {
        return inPlace(file, true);
    }

    private Result inPlace(Path file, boolean keepRaw) throws IOException {
        Pass pass;
        try (InputStream in = opener.open(file)) {
            pass = stream(in, null);
        }
        Checked checked = check(pass.head(), file.getFileName().toString(), file, keepRaw);
        PhotoFacts facts;
        try {
            facts = checked.extension() == null ? null : read(checked.format(), file);
        } catch (UnsupportedPhotoException e) {
            if (!keepRaw || checked.format() != PhotoFormat.RAW) {
                throw AtlasException.unsupported(e.getMessage());
            }
            LOG.info("{} has no preview Butterfly Atlas can show; registering it as a photo that can't be shown", file);
            facts = null;
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not read the details of {}", file, e);
            facts = null;
        }
        return new Result(pass.sha256(), pass.size(), checked.format(), facts, checked.extension());
    }

    /**
     * The facts of a file already in place, for catch-up of photo details: unlike {@link #ingest(Path)} it does not
     * stream (or hash) the whole file, only its first bytes for the format check and the RAW-extension rule, then the
     * facts are read once. Null for a supported photo whose details cannot be read; an unsupported one is refused.
     * Fails with an IOException if the file cannot be opened.
     */
    public PhotoFacts facts(Path file) throws IOException {
        byte[] head;
        try (InputStream in = opener.open(file)) {
            head = in.readNBytes(PhotoFormat.HEAD_BYTES);
        }
        Checked checked = check(head, file.getFileName().toString(), file, false);
        try {
            return read(checked.format(), file);
        } catch (UnsupportedPhotoException e) {
            throw AtlasException.unsupported(e.getMessage());
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not read the details of {}", file, e);
            return null;
        }
    }

    private PhotoFacts read(PhotoFormat format, Path file) throws IOException {
        return format == PhotoFormat.RAW ? processor.readRaw(file) : processor.read(file);
    }

    /**
     * The format from the first bytes, or the refusal; a TIFF with a RAW extension, or whose IFD0 carries DNGVersion, is
     * a RAW file. With {@code keepRaw}, a RAW whose family cannot be told is not refused: its extension is null.
     */
    private static Checked check(byte[] head, String name, Path file, boolean keepRaw) throws IOException {
        PhotoFormat format = PhotoFormat.detect(head)
                .orElseThrow(() -> AtlasException.unsupported(PhotoFormat.refusal(head)));
        Optional<String> family = Optional.empty();
        if (format == PhotoFormat.RAW || format == PhotoFormat.TIFF) {
            family = RawPreviews.family(file);
            if (format == PhotoFormat.TIFF && (PhotoFormat.isRawFileName(name) || family.isPresent())) {
                format = PhotoFormat.RAW;
            }
        }
        if (format != PhotoFormat.RAW) {
            return new Checked(format, format.extension());
        }
        Optional<String> bytes = family;
        Optional<String> extension = PhotoFormat.rawExtension(name).or(() -> bytes);
        if (extension.isEmpty() && keepRaw) {
            return new Checked(format, null);
        }
        return new Checked(format, extension.orElseThrow(() -> AtlasException.unsupported(PhotoFormat.RAW_NO_PREVIEW)));
    }

    /** Hashes and counts {@code in}, keeping its first bytes, and copies it to {@code target} unless that is null. */
    private Pass stream(InputStream in, Path target) throws IOException {
        MessageDigest digest = PhotoFiles.sha256();
        byte[] head = new byte[PhotoFormat.HEAD_BYTES];
        int headLength = 0;
        long size = 0;
        try (OutputStream out = target == null ? OutputStream.nullOutputStream()
                : Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = target != null ? readUpload(in, buffer) : in.read(buffer)) != -1) {
                size += n;
                if (target != null && size > maxBytes) {
                    throw AtlasException.tooLarge(tooLargeMessage());
                }
                if (headLength < head.length) {
                    int take = Math.min(n, head.length - headLength);
                    System.arraycopy(buffer, 0, head, headLength, take);
                    headLength += take;
                }
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        }
        return new Pass(HexFormat.of().formatHex(digest.digest()), size, Arrays.copyOf(head, headLength));
    }

    /** One read of an upload; a sender that went away part-way is an everyday event, not an error. */
    private static int readUpload(InputStream body, byte[] buffer) {
        try {
            return body.read(buffer);
        } catch (IOException | UncheckedIOException e) {
            LOG.info("An upload ended before it was complete: {}", e.toString());
            throw AtlasException.interrupted("The upload was interrupted.");
        }
    }
}
