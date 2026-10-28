package com.butterflyatlas.app.imaging.heif;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The committed HEIC files ({@link HeicFixtures}) are what the decoder check and the smoke test count on. */
class HeicFixturesTest {

    private static HeifInfo read(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return HeifBoxes.read(channel, channel.size());
        }
    }

    private static HeifInfo resource(String name) throws IOException {
        Path file = Files.createTempFile("probe", ".heic");
        try (InputStream in = HeicDecoders.class.getResourceAsStream("/heif/" + name)) {
            Files.write(file, in.readAllBytes());
            return read(file);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void theRotationProbeIs64By32TurnedAQuarter() throws IOException {
        HeifInfo info = resource("probe-rot.heic");

        assertThat(info.kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(info.width()).isEqualTo(64);
        assertThat(info.height()).isEqualTo(32);
        assertThat(info.turn()).isEqualTo(1);
        assertThat(info.mirror()).isNull();
    }

    @Test
    void theMirrorProbeIs64By32MirroredLeftAndRight() throws IOException {
        HeifInfo info = resource("probe-mirror.heic");

        assertThat(info.kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(info.width()).isEqualTo(64);
        assertThat(info.height()).isEqualTo(32);
        assertThat(info.turn()).isZero();
        assertThat(info.mirror()).isEqualTo(1);
    }

    @Test
    void theSmokeFixtureIsA1024By768GridTurnedAQuarterWithExif() throws IOException {
        Path smoke = Path.of("..", "packaging", "smoke", "fixtures", "heic.heic");
        if (!Files.exists(smoke)) {
            smoke = Path.of("packaging", "smoke", "fixtures", "heic.heic");
        }
        HeifInfo info = read(smoke);

        assertThat(info.kind()).isEqualTo(HeifInfo.Kind.HEIC);
        assertThat(info.primaryType()).isEqualTo("grid");
        assertThat(info.width()).isEqualTo(1024);
        assertThat(info.height()).isEqualTo(768);
        assertThat(info.turn()).isEqualTo(1);
        assertThat(info.exif()).isNotEmpty();
        assertThat(Files.size(smoke)).isLessThan(64 * 1024);
    }
}
