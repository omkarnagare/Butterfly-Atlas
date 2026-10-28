package com.butterflyatlas.app.testing;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The real RAW samples (design §10): eleven CC0 files kept outside git in {@code app/.cache/raw-samples}, or wherever
 * the system property {@code atlas.rawSamples} says. Tests that need them are skipped, with this message, when they are
 * absent (CI never has them).
 */
public final class RawSamples {

    private RawSamples() {
    }

    /** The sample file of this name; skips the calling test when the samples are not on this computer. */
    public static Path file(String name) {
        Path folder = folder();
        Path file = folder.resolve(name);
        Assumptions.assumeTrue(Files.isRegularFile(file), "The RAW sample " + name + " is not in " + folder.toAbsolutePath()
                + "; real-sample tests are skipped (CI never has them)");
        return file;
    }

    private static Path folder() {
        String property = System.getProperty("atlas.rawSamples");
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        // Tests run in app/ under Maven, at the repository root in some IDEs.
        Path inApp = Path.of(".cache", "raw-samples");
        return Files.isDirectory(inApp) ? inApp : Path.of("app", ".cache", "raw-samples");
    }
}
