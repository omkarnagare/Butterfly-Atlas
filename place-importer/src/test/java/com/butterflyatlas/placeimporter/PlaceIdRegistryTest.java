package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceIdRegistryTest {

    @TempDir
    Path dir;

    @Test
    void assignsSequentialIdsOncePerSourceKey() {
        PlaceIdRegistry registry = new PlaceIdRegistry();

        assertThat(registry.idFor("iso:IN")).isEqualTo("P1");
        assertThat(registry.idFor("iso:IN-MH")).isEqualTo("P2");
        assertThat(registry.idFor("iso:IN")).isEqualTo("P1");
        assertThat(registry.next()).isEqualTo(3);
    }

    @Test
    void writesTheContractFormatSortedByKeyWithNextLast() throws Exception {
        Path file = dir.resolve("place-ids.json");
        PlaceIdRegistry registry = new PlaceIdRegistry();
        registry.idFor("lgd:521");
        registry.idFor("iso:IN");
        registry.save(file);

        assertThat(Files.readString(file)).isEqualTo("""
                {
                  "iso:IN" : "P2",
                  "lgd:521" : "P1",
                  "next" : 3
                }
                """);
    }

    @Test
    void keepsIdsAndTheCounterAcrossSaveAndLoad() throws Exception {
        Path file = dir.resolve("place-ids.json");
        PlaceIdRegistry registry = new PlaceIdRegistry();
        registry.idFor("iso:NP");
        registry.idFor("ne:NPL-1");
        registry.save(file);

        PlaceIdRegistry reloaded = PlaceIdRegistry.load(file);

        assertThat(reloaded.idFor("ne:NPL-1")).isEqualTo("P2");
        assertThat(reloaded.idFor("iso:NP")).isEqualTo("P1");
        assertThat(reloaded.idFor("gb:X")).isEqualTo("P3");
    }

    @Test
    void neverReusesANumberBelowNextEvenWhenNoKeyHoldsIt() throws Exception {
        Path file = dir.resolve("place-ids.json");
        Files.writeString(file, "{ \"iso:IN\": \"P1\", \"next\": 7 }");

        assertThat(PlaceIdRegistry.load(file).idFor("iso:NP")).isEqualTo("P7");
    }

    @Test
    void raisesATooLowCounterAboveTheHighestIssuedId() throws Exception {
        Path file = dir.resolve("place-ids.json");
        Files.writeString(file, "{ \"iso:IN\": \"P9\", \"next\": 2 }");

        assertThat(PlaceIdRegistry.load(file).idFor("iso:NP")).isEqualTo("P10");
    }

    @Test
    void startsEmptyWhenTheFileDoesNotExist() throws Exception {
        PlaceIdRegistry registry = PlaceIdRegistry.load(dir.resolve("missing.json"));

        assertThat(registry.find("iso:IN")).isEmpty();
        assertThat(registry.idFor("iso:IN")).isEqualTo("P1");
    }

    @Test
    void lookupDoesNotAssign() {
        PlaceIdRegistry registry = new PlaceIdRegistry();

        assertThat(registry.find("iso:IN")).isEmpty();
        assertThat(registry.idFor("iso:NP")).isEqualTo("P1");
    }

    @Test
    void rejectsKeysWithoutAKnownPrefix() {
        PlaceIdRegistry registry = new PlaceIdRegistry();

        assertThatThrownBy(() -> registry.idFor("India"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("India");
    }

    @Test
    void rejectsDamagedFiles() throws Exception {
        assertInvalid("[]", "not a JSON object");
        assertInvalid("{ \"iso:IN\": \"P1\" }", "\"next\" is missing");
        assertInvalid("{ \"iso:IN\": \"X1\", \"next\": 2 }", "not a place id");
        assertInvalid("{ \"iso:IN\": \"P0\", \"next\": 2 }", "not a place id");
        assertInvalid("{ \"India\": \"P1\", \"next\": 2 }", "not a source key");
        assertInvalid("{ \"iso:IN\": \"P1\", \"iso:NP\": \"P1\", \"next\": 2 }", "P1 is given to both");
        assertInvalid("{ \"iso:IN\": \"P1\", \"iso:IN\": \"P2\", \"next\": 3 }", "Duplicate field 'iso:IN'");
    }

    private void assertInvalid(String json, String reason) throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, json);
        assertThatThrownBy(() -> PlaceIdRegistry.load(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is invalid")
                .hasMessageContaining(reason);
    }

    @Test
    void oneIdMayHaveKeysOfDifferentKindsButNeverTwoOfOneKind() throws Exception {
        Path file = dir.resolve("ids.json");
        Files.writeString(file, "{ \"gb:X1\": \"P1\", \"lgd:9\": \"P1\", \"next\": 2 }");
        PlaceIdRegistry registry = PlaceIdRegistry.load(file);
        assertThat(registry.find("lgd:9")).contains("P1");

        registry.alias("ne:A", "P1");
        registry.alias("lgd:9", "P1");
        assertThat(registry.find("ne:A")).contains("P1");
        assertThatThrownBy(() -> registry.alias("lgd:10", "P1"))
                .hasMessage("P1 has the key lgd:9 already");
        assertThatThrownBy(() -> registry.alias("lgd:9", "P2"))
                .hasMessage("lgd:9 is P1 already, not P2");
        assertThat(registry.idFor("lgd:11")).as("new ids go on from next").isEqualTo("P2");
    }
}
