package com.butterflyatlas.app.imaging.heif;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * HEIC decoders for tests in other packages: a fake one ({@link FakeDecoder}) that passes the probes as a decoder that
 * leaves pictures as stored and answers each photo as {@code photoMode} says when it starts, and the real {@code sips}.
 */
public final class TestDecoders {

    private TestDecoders() {
    }

    /**
     * A fake decoder: the probes answered as stored, a photo by the mode {@code photoMode} gives at its start (a picture
     * of {@code width × height}); decodes time out after {@code runTimeout}.
     */
    public static HeicDecoders fake(Supplier<Path> folder, Supplier<String> photoMode, int width, int height,
                                    Duration runTimeout) {
        byte[] rot = resource("probe-rot.heic");
        byte[] mirror = resource("probe-mirror.heic");
        DecoderRun.Command command = (in, out, side) -> {
            String mode;
            try {
                byte[] bytes = Files.readAllBytes(in);
                mode = Arrays.equals(bytes, rot) || Arrays.equals(bytes, mirror) ? "rot-stored" : photoMode.get();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return FakeDecoder.command(mode, width, height).arguments(in, out, side);
        };
        HeicDecoders.Program program = new HeicDecoders.Program("fake", Path.of(FakeDecoder.javaPath()), command, false);
        return new HeicDecoders("mac", () -> Optional.of(program), folder, runTimeout, Duration.ofSeconds(20),
                Duration.ofSeconds(20), System::nanoTime);
    }

    /** The real {@code /usr/bin/sips} (callers assume it exists), or the forced none. */
    public static HeicDecoders sips(Supplier<Path> folder) {
        return new HeicDecoders("mac", folder, "sips");
    }

    /** No decoder at all (as {@code -Datlas.heicDecoder=none}) on {@code system}. */
    public static HeicDecoders none(String system, Supplier<Path> folder) {
        return new HeicDecoders(system, folder, "none");
    }

    private static byte[] resource(String name) {
        try (InputStream in = HeicDecoders.class.getResourceAsStream("/heif/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
