package com.butterflyatlas.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImporterMainTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 8);

    @Test
    void defaultsToAllVerifiableIndianButterflies() {
        ImporterMain.Arguments args = ImporterMain.parse(new String[0], OCTOBER);

        Importer.Options options = args.options();
        assertThat(options.placeId()).isEqualTo(6681);
        assertThat(options.regionName()).isEqualTo("India");
        assertThat(options.groupTaxonId()).isEqualTo(47224);
        assertThat(options.quality()).isEqualTo(Quality.VERIFIABLE);
        assertThat(options.version()).isEqualTo("2026-10-08");
        assertThat(options.outputDirectory()).isEqualTo(Path.of("app/src/main/resources/taxonomy"));
        assertThat(options.registryFile()).isEqualTo(Path.of("taxonomy-importer/atlas-ids.json"));
        assertThat(args.cacheDirectory()).isEqualTo(Path.of("taxonomy-importer/.cache/2026-10-08"));
    }

    @Test
    void acceptsOverrides() {
        ImporterMain.Arguments args = ImporterMain.parse(
                new String[]{"--quality", "research", "--version", "2026-11-08", "--out", "/tmp/x"}, OCTOBER);

        assertThat(args.options().quality()).isEqualTo(Quality.RESEARCH);
        assertThat(args.options().version()).isEqualTo("2026-11-08");
        assertThat(args.options().outputDirectory()).isEqualTo(Path.of("/tmp/x"));
        assertThat(args.cacheDirectory()).isEqualTo(Path.of("taxonomy-importer/.cache/2026-11-08"));
    }

    @Test
    void keepsTheCacheNextToTheRegistry() {
        ImporterMain.Arguments args = ImporterMain.parse(
                new String[]{"--registry", "/work/ids/atlas-ids.json", "--version", "2026-11-08"}, OCTOBER);

        assertThat(args.cacheDirectory()).isEqualTo(Path.of("/work/ids/.cache/2026-11-08"));
    }

    @Test
    void rejectsBadVersions() {
        assertThatThrownBy(() -> ImporterMain.parse(new String[]{"--version", "2026-10"}, OCTOBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void rejectsUnknownOptions() {
        assertThatThrownBy(() -> ImporterMain.parse(new String[]{"--colour", "blue"}, OCTOBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--colour");
    }

    @Test
    void rejectsAnOptionWithoutAValue() {
        assertThatThrownBy(() -> ImporterMain.parse(new String[]{"--version"}, OCTOBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--version needs a value");
    }

    @TempDir
    Path dir;

    @Test
    void rejectsNonNumericPlaces() {
        assertThatThrownBy(() -> ImporterMain.parse(new String[]{"--place", "abc"}, OCTOBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--place must be a positive whole number, got: abc");
        assertThatThrownBy(() -> ImporterMain.parse(new String[]{"--taxon", "0"}, OCTOBER))
                .hasMessage("--taxon must be a positive whole number, got: 0");
    }

    @Test
    void unknownOptionListsKnownOptionsSorted() {
        assertThatThrownBy(() -> ImporterMain.parse(new String[]{"--colour", "blue"}, OCTOBER))
                .hasMessageContaining("  --group\n  --group-name\n  --out");
    }

    @Test
    void defaultPathsMustBeRunFromTheRepositoryRoot() throws Exception {
        ImporterMain.Arguments args = ImporterMain.parse(new String[0], OCTOBER);
        assertThatThrownBy(() -> ImporterMain.checkEnvironment(args, dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Run this from the repository root (taxonomy-importer/pom.xml not found in " + dir + ")");

        Files.createDirectories(dir.resolve("taxonomy-importer"));
        Files.writeString(dir.resolve("taxonomy-importer/pom.xml"), "<project/>");
        ImporterMain.checkEnvironment(args, dir);
    }

    @Test
    void refusesExistingBundlesWithoutARegistry() throws Exception {
        Path out = Files.createDirectories(dir.resolve("out"));
        Path registry = dir.resolve("atlas-ids.json");
        ImporterMain.Arguments args = ImporterMain.parse(
                new String[]{"--out", out.toString(), "--registry", registry.toString()}, OCTOBER);
        ImporterMain.checkEnvironment(args, dir);

        Files.writeString(out.resolve("taxonomy-india-2026-09-08.json"), "{}");
        assertThatThrownBy(() -> ImporterMain.checkEnvironment(args, dir))
                .hasMessage("Found existing bundles but no Atlas id registry at " + registry
                        + "; restore it from git before importing");

        Files.writeString(registry, "{}");
        ImporterMain.checkEnvironment(args, dir);
    }

    @Test
    void usageErrorsExitWithTwoAndPrintOnlyTheMessage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = ImporterMain.run(new String[]{"--place", "x"}, OCTOBER, dir,
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(exit).isEqualTo(2);
        assertThat(err.toString()).isEqualTo("--place must be a positive whole number, got: x" + System.lineSeparator());
    }
}
