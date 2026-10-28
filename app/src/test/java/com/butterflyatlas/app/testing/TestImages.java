package com.butterflyatlas.app.testing;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Sample photos made in code: the left half red, the right half blue, so a test can tell which
 * way up a thumbnail came out. Only WebP comes from a committed file, since nothing on the class
 * path writes WebP.
 */
public final class TestImages {

    public static final int RED = 0xE01010;
    public static final int BLUE = 0x1010E0;

    private TestImages() {
    }

    /** Left half red, right half blue. */
    public static BufferedImage halves(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, x < width / 2 ? RED : BLUE);
            }
        }
        return img;
    }

    public static byte[] jpeg(int width, int height) {
        return encode(halves(width, height), "jpeg");
    }

    /** A different JPEG for every {@code seed}: duplicate detection compares bytes. */
    public static byte[] jpeg(int width, int height, int seed) {
        BufferedImage img = halves(width, height);
        img.setRGB(0, 0, seed & 0xFFFFFF);
        img.setRGB(1, 0, (seed * 7919) & 0xFFFFFF);
        return encode(img, "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(halves(width, height), "png");
    }

    public static byte[] tiff(int width, int height) {
        return encode(halves(width, height), "tiff");
    }

    /** A 2×2 lossless WebP: red, green / blue, white (src/test/resources/photos/tiny.webp). */
    public static byte[] webp() {
        try (InputStream in = TestImages.class.getResourceAsStream("/photos/tiny.webp")) {
            return Objects.requireNonNull(in, "missing test resource /photos/tiny.webp").readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encode(BufferedImage img, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(img, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * The JPEG with an EXIF block saying how the camera was held (1..8), inserted after the JFIF
     * header: APP1 "Exif\0\0", a big-endian TIFF header and one IFD entry, tag 0x0112 (SHORT).
     */
    public static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {'M', 'M', 0, 42, 0, 0, 0, 8,                          // header, first IFD at offset 8
                0, 1,                                                         // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,    // Orientation, SHORT, count 1, value
                0, 0, 0, 0};                                                  // no next IFD
        int length = 2 + 6 + tiff.length;
        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write(0xFF);
        app1.write(0xE1);
        app1.write(length >> 8);
        app1.write(length & 0xFF);
        app1.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        app1.writeBytes(tiff);
        int at = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        out.writeBytes(app1.toByteArray());
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }
}
