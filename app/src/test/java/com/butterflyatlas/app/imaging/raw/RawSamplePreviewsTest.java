package com.butterflyatlas.app.imaging.raw;

import com.butterflyatlas.app.testing.RawSamples;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The preview chosen in each real sample (design §10); skipped when the samples are not on this computer. */
class RawSamplePreviewsTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // file,                       family, stored width × height, orientation
            "canon-eos-7d-mark-ii.cr2,     cr2,    5472, 3648, 1",
            "canon-eos-r7.cr3,             cr3,    6960, 4640, 1",
            "fujifilm-x-s10.raf,           raf,    4416, 2944, 1",
            "google-pixel-7-pro.dng,       dng,    1280,  964, 3",
            "nikon-d500.nef,               ,       2784, 1856, 1",
            "nikon-z63.nef,                ,       3984, 2656, 1",
            "olympus-e-m1markiii.orf,      orf,    3200, 2400, 1",
            "panasonic-dc-g9.rw2,          rw2,    1920, 1440, 1",
            "pentax-k-1-mark-ii.pef,       ,       4800, 3200, 1",
            "pentax-k-3-mark-iii.dng,      dng,    6192, 4128, 1",
            "sony-ilce-6400a.arw,          ,       1616, 1080, 8",
    })
    void choosesTheLargestUsablePreview(String name, String family, int width, int height, int orientation) throws IOException {
        Path file = RawSamples.file(name);

        RawContents found;
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            found = RawPreviews.read(channel);
        }

        assertThat(found.family()).isEqualTo(family);
        assertThat(found.preview()).isNotNull();
        assertThat(found.preview().storedWidth()).isEqualTo(width);
        assertThat(found.preview().storedHeight()).isEqualTo(height);
        assertThat(found.preview().orientation()).isEqualTo(orientation);
        byte[] start = new byte[2];
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(found.preview().offset());
            channel.read(java.nio.ByteBuffer.wrap(start));
        }
        assertThat(start).containsExactly(0xFF, 0xD8);
    }
}
