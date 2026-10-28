package com.butterflyatlas.importer.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlasIdRegistryTest {

    @TempDir
    Path dir;

    @Test
    void assignsSequentialIdsOncePerTaxon() {
        AtlasIdRegistry registry = new AtlasIdRegistry();

        assertThat(registry.idFor(47223)).isEqualTo("A1");
        assertThat(registry.idFor(67835)).isEqualTo("A2");
        assertThat(registry.idFor(47223)).isEqualTo("A1");
    }

    @Test
    void keepsIdsAndTheCounterAcrossSaveAndLoad() throws Exception {
        Path file = dir.resolve("atlas-ids.json");
        AtlasIdRegistry registry = new AtlasIdRegistry();
        registry.idFor(500);
        registry.idFor(7);
        registry.save(file);

        AtlasIdRegistry reloaded = AtlasIdRegistry.load(file);

        assertThat(reloaded.idFor(500)).isEqualTo("A1");
        assertThat(reloaded.idFor(7)).isEqualTo("A2");
        assertThat(reloaded.idFor(9)).isEqualTo("A3");
    }

    @Test
    void startsEmptyWhenTheFileDoesNotExist() throws Exception {
        assertThat(AtlasIdRegistry.load(dir.resolve("missing.json")).idFor(1)).isEqualTo("A1");
    }

    @Test
    void writesIdsSortedByINaturalistIdForStableDiffs() throws Exception {
        Path file = dir.resolve("atlas-ids.json");
        AtlasIdRegistry registry = new AtlasIdRegistry();
        registry.idFor(500);
        registry.idFor(7);
        registry.save(file);

        String json = Files.readString(file);
        assertThat(json.indexOf("\"7\"")).isLessThan(json.indexOf("\"500\""));
    }

    @Test
    void lookupDoesNotAssign() {
        AtlasIdRegistry registry = new AtlasIdRegistry();

        assertThat(registry.find(1)).isEmpty();
        assertThat(registry.idFor(2)).isEqualTo("A1");
    }

    @Test
    void aliasGivesAReplacementTaxonTheOldAtlasId() {
        AtlasIdRegistry registry = new AtlasIdRegistry();
        String id = registry.idFor(1);

        registry.alias(99, id);

        assertThat(registry.idFor(99)).isEqualTo("A1");
    }

    @Test
    void refusesToReassignAnExistingMapping() {
        AtlasIdRegistry registry = new AtlasIdRegistry();
        registry.idFor(1);
        registry.idFor(2);

        assertThatThrownBy(() -> registry.alias(2, "A1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
    }

    private Path write(String json) throws Exception {
        Path file = dir.resolve("atlas-ids.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    void neverReusesIdsEvenIfNextWasEditedDown() throws Exception {
        Path file = write("{\"next\": 2, \"ids\": {\"1\": \"A5\"}}");

        assertThat(AtlasIdRegistry.load(file).idFor(2)).isEqualTo("A6");
    }

    @Test
    void rejectsAnEmptyFile() throws Exception {
        Path file = write("");

        assertThatThrownBy(() -> AtlasIdRegistry.load(file))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("is invalid");
    }

    @Test
    void rejectsAMissingIdsObject() throws Exception {
        Path file = write("{\"next\": 1}");

        assertThatThrownBy(() -> AtlasIdRegistry.load(file))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("is invalid");
    }

    @Test
    void rejectsABadAtlasIdValue() throws Exception {
        Path file = write("{\"next\": 8, \"ids\": {\"1\": \"B7\"}}");

        assertThatThrownBy(() -> AtlasIdRegistry.load(file))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("is invalid");
    }

    @Test
    void rejectsABadINaturalistIdKey() throws Exception {
        Path file = write("{\"next\": 2, \"ids\": {\"abc\": \"A1\"}}");

        assertThatThrownBy(() -> AtlasIdRegistry.load(file))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("is invalid");
    }

    @Test
    void aliasRejectsAnAtlasIdThatWasNeverIssued() {
        AtlasIdRegistry registry = new AtlasIdRegistry();
        registry.idFor(1);

        assertThatThrownBy(() -> registry.alias(2, "A500")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.alias(2, "bogus")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedIdenticalAliasIsFineAndSurvivesSaveAndLoad() throws Exception {
        AtlasIdRegistry registry = new AtlasIdRegistry();
        registry.idFor(1);
        registry.alias(99, "A1");
        registry.alias(99, "A1");
        Path file = dir.resolve("atlas-ids.json");
        registry.save(file);

        assertThat(AtlasIdRegistry.load(file).idFor(99)).isEqualTo("A1");
    }

    @Test
    void savesWithUnixLineEndings() throws Exception {
        AtlasIdRegistry registry = new AtlasIdRegistry();
        registry.idFor(1);
        registry.idFor(2);
        Path file = dir.resolve("atlas-ids.json");

        registry.save(file);

        String json = Files.readString(file);
        assertThat(json).doesNotContain("\r").endsWith("}\n");
        assertThat(AtlasIdRegistry.load(file).find(2)).contains("A2");
    }
}
