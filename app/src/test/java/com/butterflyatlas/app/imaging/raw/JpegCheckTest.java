package com.butterflyatlas.app.imaging.raw;

import com.butterflyatlas.app.testing.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** What counts as a preview's JPEG (fixes J10: 8-bit samples, 1 or 3 components). */
class JpegCheckTest {

    @TempDir
    Path dir;

    private JpegCheck.Size size(byte[] jpeg) throws Exception {
        Path file = Files.write(dir.resolve("p" + System.nanoTime() + ".jpg"), jpeg);
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return JpegCheck.size(new RawBytes(channel, channel.size()), 0, jpeg.length);
        }
    }

    /** The JPEG with its frame header's precision and component count changed. */
    private static byte[] frame(byte[] jpeg, int precision, int components) {
        byte[] changed = jpeg.clone();
        for (int i = 2; i + 10 < changed.length; i++) {
            if ((changed[i] & 0xFF) == 0xFF && (changed[i + 1] & 0xFF) == 0xC0) {
                changed[i + 4] = (byte) precision;
                changed[i + 9] = (byte) components;
                return changed;
            }
        }
        throw new IllegalArgumentException("no SOF0");
    }

    @Test
    void onlyEightBitJpegsOfOneOrThreeComponentsArePreviews() throws Exception {
        byte[] jpeg = TestImages.jpeg(1024, 768);

        assertThat(size(jpeg)).isEqualTo(new JpegCheck.Size(1024, 768));
        assertThat(size(frame(jpeg, 8, 1))).as("greyscale").isEqualTo(new JpegCheck.Size(1024, 768));
        assertThat(size(frame(jpeg, 12, 3))).as("12-bit").isNull();
        assertThat(size(frame(jpeg, 8, 4))).as("CMYK").isNull();
        assertThat(size(frame(jpeg, 8, 2))).isNull();
    }
}
