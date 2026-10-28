package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A new place list compared with the one it is based on (5c contract §3). */
class PlaceDiffTest {

    private static BundlePlace place(String id, String parent, String level, String name, String... aliases) {
        return new BundlePlace(id, parent, level, name, List.of(aliases), Map.of());
    }

    private static final List<BundlePlace> BEFORE = List.of(
            place("P1", null, "country", "Testland"),
            place("P2", "P1", "state", "Northstate", "North"),
            place("P3", "P1", "state", "Southstate"),
            place("P4", "P2", "district", "Alpha"),
            place("P5", "P2", "district", "Beta"),
            place("P6", "P2", "district", "Gamma"),
            place("P7", "P2", "district", "Delta"));

    private static PlaceIdRegistry registry() throws Exception {
        Path file = Files.createTempFile("place-ids", ".json");
        Files.writeString(file, "{ \"ne:P4\": \"P4\", \"ne:P5\": \"P5\", \"ne:P6\": \"P6\", \"ne:P7\": \"P7\","
                + " \"ne:P8\": \"P8\", \"ne:P9\": \"P9\", \"next\": 10 }");
        try {
            return PlaceIdRegistry.load(file);
        } finally {
            Files.delete(file);
        }
    }

    private static PlaceChanges.Line line(String type, List<String> from, List<String> to) {
        return new PlaceChanges.Line(type, from, to, "why");
    }

    @Test
    void renamesMovesAndAddsAreFoundAndTheDeclaredChangesComeInTheContractsOrder() throws Exception {
        List<BundlePlace> now = List.of(
                place("P1", null, "country", "Testland"),
                place("P2", "P1", "state", "Northstate", "North"),
                place("P3", "P1", "state", "Southstate"),
                place("P5", "P3", "district", "Betania", "Beta"),
                place("P8", "P2", "district", "Alpha North"),
                place("P9", "P2", "district", "Alpha South"));

        PlaceDiff.Result result = PlaceDiff.compare(new PlaceDiff.Previous("2026-10-13", BEFORE), now, List.of(
                line("retire", List.of("ne:P7"), List.of()),
                line("split", List.of("ne:P4"), List.of("ne:P8", "ne:P9")),
                line("merge", List.of("ne:P6"), List.of("ne:P5"))), registry());

        assertThat(result.failures()).isEmpty();
        assertThat(result.changes()).extracting(c -> c.type() + " " + c.from() + " " + c.to() + " " + c.detail())
                .containsExactly(
                        "merge [P6] [P5] Gamma merged into Betania",
                        "split [P4] [P8, P9] Alpha split into Alpha North, Alpha South",
                        "retire [P7] [] Delta retired",
                        "rename [P5] [P5] Beta → Betania",
                        "move [P5] [P5] Betania: Northstate → Southstate",
                        "add [] [P8] Alpha North added",
                        "add [] [P9] Alpha South added");
        assertThat(result.counts()).isEqualTo("1 merged, 1 split, 1 retired, 1 renamed, 1 moved, 2 added");
        assertThat(result.aliases().get("P5")).as("the old name and the merged place's").containsExactly("Beta", "Gamma");
        assertThat(result.aliases().get("P2")).containsExactly("North");
    }

    @Test
    void aPlaceGoneWithoutALineALineThatDisagreesAndALevelChangeStopTheBuildWithThePlaceNamed() throws Exception {
        List<BundlePlace> now = List.of(
                place("P1", null, "country", "Testland"),
                place("P2", "P1", "state", "Northstate"),
                place("P3", "P2", "district", "Southstate"),
                place("P4", "P2", "district", "Alpha"),
                place("P5", "P2", "district", "Beta"));

        PlaceDiff.Result result = PlaceDiff.compare(new PlaceDiff.Previous("2026-10-13", BEFORE), now, List.of(
                line("retire", List.of("ne:P4"), List.of()),
                line("merge", List.of("ne:P6"), List.of("ne:P9")),
                line("retire", List.of("ne:P1"), List.of())), registry());

        assertThat(result.failures()).containsExactly(
                "P4 Alpha is still in the new list, but place-changes.csv retires it",
                "place-changes.csv merges ne:P6 into P9, which is not in the new list",
                "place-changes.csv names ne:P1, which has no place id in the registry",
                "P7 Delta is not in the new list: add a merge, split or retire line to place-changes.csv",
                "P3 Southstate changes level from state to district");
    }

    @Test
    void aMergeOrSplitAcrossLevelsStopsTheBuild() throws Exception {
        List<BundlePlace> now = List.of(
                place("P1", null, "country", "Testland"),
                place("P2", "P1", "state", "Northstate"),
                place("P3", "P1", "state", "Southstate"),
                place("P5", "P2", "district", "Beta"),
                place("P6", "P2", "district", "Gamma"),
                place("P7", "P2", "district", "Delta"),
                place("P8", "P1", "state", "Alphastate"),
                place("P9", "P2", "district", "Alpha South"));
        Path file = Files.createTempFile("place-ids", ".json");
        Files.writeString(file, "{ \"ne:P4\": \"P4\", \"ne:P8\": \"P8\", \"ne:P9\": \"P9\", \"next\": 10 }");
        PlaceIdRegistry registry = PlaceIdRegistry.load(file);
        Files.delete(file);

        PlaceDiff.Result result = PlaceDiff.compare(new PlaceDiff.Previous("2026-10-13", BEFORE), now, List.of(
                line("split", List.of("ne:P4"), List.of("ne:P8", "ne:P9"))), registry);

        assertThat(result.failures()).containsExactly("place-changes.csv splits P4 Alpha (a district) into P8 Alphastate"
                + " (a state): a merge or split stays within one level");
    }
}
