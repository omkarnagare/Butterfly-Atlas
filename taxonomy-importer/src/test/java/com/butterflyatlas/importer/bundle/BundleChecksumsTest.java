package com.butterflyatlas.importer.bundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleChecksumsTest {

    // SHA-256 of the three bytes "abc" and of "xyz".
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String XYZ = "3608bca1e44ea6c4d268eb6db02260269892c0b42b86bbf1e77a6fa16c3c9282";

    @TempDir
    Path dir;

    @Test
    void writesOneSortedLinePerBundle() throws Exception {
        Files.writeString(dir.resolve("taxonomy-india-2026-11-01.json"), "xyz");
        Files.writeString(dir.resolve("taxonomy-india-2026-10-08.json"), "abc");

        Path file = BundleChecksums.write(dir);

        assertThat(file).isEqualTo(dir.resolve("bundles.sha256"));
        assertThat(Files.readString(file)).isEqualTo(
                ABC + "  taxonomy-india-2026-10-08.json\n"
                        + XYZ + "  taxonomy-india-2026-11-01.json\n");
    }

    @Test
    void ignoresFilesThatAreNotBundles() throws Exception {
        Files.writeString(dir.resolve("taxonomy-india-2026-10-08.json"), "abc");
        Files.writeString(dir.resolve("notes.txt"), "abc");
        Files.writeString(dir.resolve("taxonomy-india-2026-10-08.json.tmp"), "abc");

        BundleChecksums.write(dir);

        assertThat(Files.readAllLines(dir.resolve("bundles.sha256")))
                .containsExactly(ABC + "  taxonomy-india-2026-10-08.json");
    }

    @Test
    void rewritesTheListAndLeavesNoTemporaryFile() throws Exception {
        Files.writeString(dir.resolve("taxonomy-india-2026-10-08.json"), "abc");
        BundleChecksums.write(dir);
        Files.writeString(dir.resolve("taxonomy-india-2026-10-08.json"), "xyz");

        BundleChecksums.write(dir);

        assertThat(Files.readAllLines(dir.resolve("bundles.sha256")))
                .containsExactly(XYZ + "  taxonomy-india-2026-10-08.json");
        assertThat(dir.resolve("bundles.sha256.tmp")).doesNotExist();
    }
}
