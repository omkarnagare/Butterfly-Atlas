package com.butterflyatlas.app.imaging;

import com.butterflyatlas.app.imaging.heif.HeicDecoders;
import com.butterflyatlas.app.imaging.heif.HeifBoxes;
import com.butterflyatlas.app.imaging.heif.HeifInfo;
import com.butterflyatlas.app.imaging.raw.RawContents;
import com.butterflyatlas.app.imaging.raw.RawPreview;
import com.butterflyatlas.app.imaging.raw.RawPreviews;
import com.butterflyatlas.app.imaging.raw.SliceChannel;
import com.butterflyatlas.app.photos.HeicDecodeException;
import com.butterflyatlas.app.photos.PhotoFacts;
import com.butterflyatlas.app.photos.PhotoFormat;
import com.butterflyatlas.app.photos.PhotoProcessor;
import com.butterflyatlas.app.photos.UnsupportedPhotoException;
import com.drew.metadata.Metadata;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link PhotoProcessor} for the desktop: ImageIO with the TwelveMonkeys JPEG, TIFF and WebP
 * plugins, and metadata-extractor for EXIF, GPS and XMP. Large photos are decoded with source
 * subsampling, so a 100-megapixel TIFF never sits in memory at full size.
 */
public final class ImageIoPhotoProcessor implements PhotoProcessor {

    public static final float JPEG_QUALITY = 0.85f;

    private static final AtomicBoolean SCANNED = new AtomicBoolean();

    private final ZoneId zone;
    /** The computer's HEIC decoder; null where there is none (HEIC pictures are then not available). */
    private final HeicDecoders heic;

    /** Capture times without an offset are read in the computer's time zone; no HEIC decoder. */
    public ImageIoPhotoProcessor() {
        this(ZoneId.systemDefault());
    }

    /** With the computer's HEIC decoder (Phase 7b); capture times without an offset in the computer's time zone. */
    public ImageIoPhotoProcessor(HeicDecoders heic) {
        this(ZoneId.systemDefault(), heic);
    }

    /** @param zone the time zone of capture times the camera wrote without an offset */
    public ImageIoPhotoProcessor(ZoneId zone) {
        this(zone, null);
    }

    public ImageIoPhotoProcessor(ZoneId zone, HeicDecoders heic) {
        this.zone = zone;
        this.heic = heic;
        // Plugins on the class path register themselves, but only for the class loader that first
        // touched ImageIO; scanning again also finds them under exec:java and in tests. Once per JVM.
        ImageIO.setUseCache(false); // no temp-file cache; the channel stream seeks by itself
        if (SCANNED.compareAndSet(false, true)) {
            ImageIO.scanForPlugins();
        }
    }

    /**
     * Opens the file once: the first bytes give the format, metadata-extractor reads the metadata
     * (for TIFF through random access, so only the directories are read), and ImageIO reads the
     * size from the image header. No pixels are decoded.
     */
    @Override
    public PhotoFacts read(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            PhotoFormat format = PhotoFormat.detect(PhotoMetadata.head(channel))
                    .orElseThrow(() -> new IOException("Not a supported photo: " + file));
            if (format == PhotoFormat.RAW || (format == PhotoFormat.TIFF && RawPreviews.family(channel).isPresent())) {
                return raw(channel, file); // RAW by its bytes (a DNG named .tif included)
            }
            if (format == PhotoFormat.HEIC) {
                return heic(channel, file);
            }
            Metadata metadata = PhotoMetadata.lenient(channel, format);
            int width;
            int height;
            channel.position(0);
            try (ImageInputStream in = new ChannelImageInputStream(channel)) {
                ImageReader reader = reader(in, file);
                try {
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            }
            int orientation = guarded(() -> MetadataSnapshot.orientation(metadata), 1);
            boolean sideways = orientation >= 5;
            return PhotoFacts.of(format, sideways ? height : width, sideways ? width : height, orientation,
                    guarded(() -> MetadataSnapshot.capturedAt(metadata, zone), null), guarded(() -> MetadataSnapshot.gps(metadata), null),
                    guarded(() -> MetadataSnapshot.exif(metadata), Map.of()), guarded(() -> MetadataSnapshot.xmp(metadata), Map.of()));
        }
    }

    /** A RAW file (whatever its bytes look like: PhotoIngest decides by name too), through its preview. */
    @Override
    public PhotoFacts readRaw(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return raw(channel, file);
        }
    }

    /**
     * Finds the preview (no usable one: {@link PhotoFormat#RAW_NO_PREVIEW}), checks ImageIO reads it as the JPEG of the
     * size its header says, and reads the metadata from the RAW file itself. The facts' size is the preview's as
     * displayed, their orientation the turn that shows it upright.
     */
    private PhotoFacts raw(SeekableByteChannel channel, Path file) throws IOException {
        RawContents contents = RawPreviews.read(channel);
        RawPreview preview = contents.preview();
        if (preview == null) {
            throw new UnsupportedPhotoException(PhotoFormat.RAW_NO_PREVIEW);
        }
        try (ImageInputStream in = new ChannelImageInputStream(new SliceChannel(Files.newByteChannel(file), preview.offset(),
                preview.length()))) {
            ImageReader reader = reader(in, file);
            try {
                if (reader.getWidth(0) != preview.storedWidth() || reader.getHeight(0) != preview.storedHeight()) {
                    throw new IOException("The preview's size is not what its header says");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new UnsupportedPhotoException(PhotoFormat.RAW_NO_PREVIEW);
        }
        Metadata metadata = PhotoMetadata.raw(channel, contents);
        RawContents.Size sensor = contents.sensor();
        return PhotoFacts.raw(new PhotoFacts.Preview(preview.offset(), preview.length(), preview.width(), preview.height(),
                        preview.orientation(), preview.storedWidth(), preview.storedHeight()),
                sensor == null ? null : sensor.width(), sensor == null ? null : sensor.height(),
                guarded(() -> MetadataSnapshot.capturedAt(metadata, zone), null), guarded(() -> MetadataSnapshot.gps(metadata), null),
                guarded(() -> MetadataSnapshot.exif(metadata), Map.of()), guarded(() -> MetadataSnapshot.xmp(metadata), Map.of()));
    }

    /**
     * A HEIC photo, in pure Java (Phase 7b design §3): its size and turn from the HEIF boxes ({@code ispe}, {@code irot},
     * {@code imir}; the EXIF orientation inside is never applied), its metadata from the Exif item. A sequence, a primary
     * item coded otherwise, or AV1 inside is refused in the contract's words; boxes that make no sense are an IOException.
     */
    private PhotoFacts heic(SeekableByteChannel channel, Path file) throws IOException {
        HeifInfo info = HeifBoxes.read(channel, channel.size());
        switch (info.kind()) {
            case SEQUENCE -> throw new UnsupportedPhotoException(PhotoFormat.HEIC_SEQUENCE);
            case AVIF -> throw new UnsupportedPhotoException(PhotoFormat.AVIF);
            case OTHER_CODING -> throw new UnsupportedPhotoException(PhotoFormat.HEIF_OTHER);
            case UNREADABLE -> throw new IOException("Not a HEIF file Butterfly Atlas can read: " + file);
            default -> {
                // a still HEVC image
            }
        }
        Metadata metadata = PhotoMetadata.heif(channel, info);
        return PhotoFacts.heic(new PhotoFacts.Heif(info.turn(), info.mirror(), info.mirrorFirst(), info.bitDepth(),
                        info.auxiliary(), info.width(), info.height()), info.orientation(),
                guarded(() -> MetadataSnapshot.capturedAt(metadata, zone), null), guarded(() -> MetadataSnapshot.gps(metadata), null),
                guarded(() -> MetadataSnapshot.exif(metadata), Map.of()), guarded(() -> MetadataSnapshot.xmp(metadata), Map.of()));
    }

    /** A part of the snapshot, or {@code fallback} when metadata-extractor cannot make sense of it: the photo is still readable. */
    private static <T> T guarded(Supplier<T> part, T fallback) {
        try {
            return part.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Override
    public void thumbnail(Path source, int orientation, int longSide, Path target) throws IOException {
        thumbnail(source, orientation, longSide, target, Use.CATCH_UP);
    }

    @Override
    public void thumbnail(Path source, int orientation, int longSide, Path target, Use use) throws IOException {
        HeifInfo info = heicInfo(source);
        if (info != null) {
            heicThumbnail(source, info, longSide, target, use);
            return;
        }
        BufferedImage img = decode(source, longSide);
        // Reduce first, so the sRGB copy is made on the small image, never on a full-size one.
        if (img.getColorModel().getColorSpace().isCS_sRGB()) {
            img = halved(img, longSide);
        }
        writeJpeg(render(srgb(img), orientation, longSide), target);
    }

    /** The boxes of a HEIC file (by its bytes); null for any other file. A HEIC whose boxes are not a still HEVC image fails. */
    private static HeifInfo heicInfo(Path source) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(source)) {
            if (PhotoFormat.detect(PhotoMetadata.head(channel)).orElse(null) != PhotoFormat.HEIC) {
                return null;
            }
            HeifInfo info = HeifBoxes.read(channel, channel.size());
            if (info.kind() != HeifInfo.Kind.HEIC) {
                throw new IOException("Not a HEIC photo Butterfly Atlas can show: " + info.kind());
            }
            return info;
        }
    }

    /**
     * A HEIC photo's copy (Phase 7b design §4): decoded by the computer's decoder at twice the size asked (at most 2560
     * px), turned as its boxes say where the decoder did not, then made as any other copy.
     */
    private void heicThumbnail(Path source, HeifInfo info, int longSide, Path target, Use use) throws IOException {
        if (heic == null) {
            throw new HeicDecodeException(HeicDecodeException.Kind.UNAVAILABLE, "No HEIC decoder on this computer");
        }
        HeicDecoders.Decoded decoded = heic.decode(source, Math.min(2 * longSide, 2560), info, use);
        BufferedImage img = decoded.image();
        if (img.getColorModel().getColorSpace().isCS_sRGB()) {
            img = halved(img, longSide);
        }
        writeJpeg(render(srgb(img), decoded.orientation(), longSide), target);
    }

    /**
     * Decodes {@code source} with source subsampling, to between 1x and 2x {@code longSide} on its long side; for a RAW
     * file, its preview (read through a slice of the file, so nothing around it is decoded).
     */
    static BufferedImage decode(Path source, int longSide) throws IOException {
        try (ImageInputStream in = isRaw(source) ? openPreview(source) : open(source)) {
            ImageReader reader = reader(in, source);
            try {
                int step = subsampling(Math.max(reader.getWidth(0), reader.getHeight(0)), longSide);
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    @Override
    public String dominantColor(Path image) throws IOException {
        BufferedImage img = readAll(image);
        if (img == null) {
            throw new IOException("Cannot read " + image);
        }
        // 4 bits per channel: 4,096 buckets; the fullest bucket's average colour wins.
        int[] counts = new int[4096];
        long[][] sums = new long[4096][3];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int bucket = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
                counts[bucket]++;
                sums[bucket][0] += r;
                sums[bucket][1] += g;
                sums[bucket][2] += b;
            }
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        int n = Math.max(1, counts[best]);
        return String.format(Locale.ROOT, "#%02x%02x%02x", sums[best][0] / n, sums[best][1] / n, sums[best][2] / n);
    }

    /** Decode every {@code n}th pixel: the decoded long side lands between 1x and 2x {@code target}. */
    static int subsampling(int longSide, int target) {
        return Math.max(1, longSide / target);
    }

    /** The transform that turns a stored {@code w}×{@code h} image upright for an EXIF orientation. */
    static AffineTransform orientationTransform(int orientation, int w, int h) {
        // new AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00·x + m01·y + m02, y' = m10·x + m11·y + m12
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirrored
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirrored, upside down
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // mirrored, on its side
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // turned: rotate 90° clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // mirrored, on its other side
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // turned: rotate 90° anticlockwise
            default -> new AffineTransform();
        };
    }

    private static BufferedImage render(BufferedImage img, int orientation, int longSide) {
        BufferedImage current = halved(img, longSide);
        boolean sideways = orientation >= 5 && orientation <= 8;
        int shownW = sideways ? current.getHeight() : current.getWidth();
        int shownH = sideways ? current.getWidth() : current.getHeight();
        double scale = Math.min(1.0, (double) longSide / Math.max(shownW, shownH));
        int outW = Math.max(1, (int) Math.round(shownW * scale));
        int outH = Math.max(1, (int) Math.round(shownH * scale));
        AffineTransform transform = AffineTransform.getScaleInstance((double) outW / shownW, (double) outH / shownH);
        transform.concatenate(orientationTransform(orientation, current.getWidth(), current.getHeight()));
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, transform, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Halve while more than twice too big: bilinear steps keep fine detail such as antennae. */
    private static BufferedImage halved(BufferedImage img, int longSide) {
        BufferedImage current = img;
        while (Math.max(current.getWidth(), current.getHeight()) / 2 >= longSide) {
            current = scaled(current, Math.max(1, current.getWidth() / 2), Math.max(1, current.getHeight() / 2));
        }
        return current;
    }

    private static BufferedImage scaled(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** An opaque sRGB copy: transparency on white, other colour spaces (Adobe RGB, grey, CMYK) converted. */
    private static BufferedImage srgb(BufferedImage img) {
        BufferedImage source = img;
        if (!img.getColorModel().getColorSpace().isCS_sRGB()) {
            // Other colour spaces (Adobe RGB, grey, CMYK) go to sRGB first, keeping any alpha.
            BufferedImage converted = new BufferedImage(img.getWidth(), img.getHeight(),
                    img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            source = new ColorConvertOp(null).filter(img, converted);
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void writeJpeg(BufferedImage img, Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try (OutputStream out = Files.newOutputStream(target);
             MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static ImageInputStream open(Path file) throws IOException {
        // Opened through NIO (not java.io.File): on Windows that handle allows the file to be
        // moved or deleted while it is read, which the app itself does to photos.
        return new ChannelImageInputStream(file);
    }

    /** Whether the file is RAW: by its bytes, by a RAW extension on a TIFF-shaped file, or by DNGVersion. */
    public static boolean isRaw(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            PhotoFormat format = PhotoFormat.detect(PhotoMetadata.head(channel)).orElse(null);
            return format == PhotoFormat.RAW || (format == PhotoFormat.TIFF
                    && (PhotoFormat.isRawFileName(file.getFileName().toString()) || RawPreviews.family(channel).isPresent()));
        }
    }

    /** The RAW file's preview as an ImageIO input; no usable preview is an IOException. */
    private static ImageInputStream openPreview(Path file) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(file);
        try {
            RawPreview preview = RawPreviews.read(channel).preview();
            if (preview == null) {
                throw new IOException("No usable preview in " + file);
            }
            return new ChannelImageInputStream(new SliceChannel(channel, preview.offset(), preview.length()));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static BufferedImage readAll(Path file) throws IOException {
        try (ImageInputStream in = open(file)) {
            return ImageIO.read(in);
        }
    }

    private static ImageReader reader(ImageInputStream in, Path file) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) {
            throw new IOException("No reader for " + file);
        }
        ImageReader reader = readers.next();
        reader.setInput(in, true, true);
        return reader;
    }
}
