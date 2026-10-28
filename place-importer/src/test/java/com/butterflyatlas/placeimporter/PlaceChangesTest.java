package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reviewed table of merges, splits and retirements ({@code place-changes.csv}, 5c contract §3). */
class PlaceChangesTest {

    private static List<PlaceChanges.Line> read(String csv, String version) throws Exception {
        return PlaceChanges.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), version);
    }

    private static final String HEADER = "version,type,from,to,reason\n";

    @Test
    void onlyTheLinesOfTheVersionBeingBuiltCount() throws Exception {
        List<PlaceChanges.Line> lines = read(HEADER
                + "2026-10-20,merge,ne:SVN-939,ne:SVN-938,\"Natural Earth splits Brežice municipality in two; Pečice is part of"
                + " Brežice\"\n"
                + "2026-10-20,split,lgd:600,lgd:601;lgd:602,Puducherry split\n"
                + "2026-10-20,retire,gb:X1,,Gone from the sources\n"
                + "2027-01-01,retire,ne:SVN-938,,Another version\n", "2026-10-20");

        assertThat(lines).containsExactly(
                new PlaceChanges.Line("merge", List.of("ne:SVN-939"), List.of("ne:SVN-938"),
                        "Natural Earth splits Brežice municipality in two; Pečice is part of Brežice"),
                new PlaceChanges.Line("split", List.of("lgd:600"), List.of("lgd:601", "lgd:602"), "Puducherry split"),
                new PlaceChanges.Line("retire", List.of("gb:X1"), List.of(), "Gone from the sources"));
        assertThat(read(HEADER, "2026-10-20")).isEmpty();
    }

    @Test
    void aLineThatBreaksTheFormatIsRefusedWithItsNumber() {
        assertThatThrownBy(() -> read("version,type\n", "2026-10-20")).hasMessageContaining("must start with the header");
        assertThatThrownBy(() -> read(HEADER + "2026-10-20,move,ne:A,ne:B,why\n", "2026-10-20"))
                .hasMessage("place-changes.csv line 2 has the type move; use merge, split or retire");
        assertThatThrownBy(() -> read(HEADER + "2026-10-20,merge,ne:A,ne:B,\n", "2026-10-20"))
                .hasMessage("place-changes.csv line 2 gives no reason");
        assertThatThrownBy(() -> read(HEADER + "2026-10-20,split,ne:A,ne:B,why\n", "2026-10-20"))
                .hasMessageContaining("place-changes.csv line 2: a merge names places and the one they merge into");
        assertThatThrownBy(() -> read(HEADER + "2026-10-20,retire,Brežice,,why\n", "2026-10-20"))
                .hasMessage("place-changes.csv line 2 names Brežice, which is not a registry key");
        assertThatThrownBy(() -> read(HEADER + "2026-10-20,merge,ne:A,P4187,why\n", "2026-10-20"))
                .hasMessage("place-changes.csv line 2 names P4187, which is not a registry key");
        assertThatThrownBy(() -> read(HEADER + "2026-10-20,retire,ne:A\n", "2026-10-20"))
                .hasMessage("place-changes.csv line 2 has 3 fields; it needs 5");
    }

    @Test
    void aMergeIntoAPlaceMergedTooEndsAtTheLastAndACircleIsRefused() throws Exception {
        assertThat(PlaceChanges.mergedInto(read(HEADER + "v,merge,ne:A,ne:B,x\nv,merge,ne:B;ne:C,ne:D,y\n", "v")))
                .isEqualTo(Map.of("ne:A", "ne:D", "ne:B", "ne:D", "ne:C", "ne:D"));
        assertThatThrownBy(() -> PlaceChanges.mergedInto(read(HEADER + "v,merge,ne:A,ne:B,x\nv,merge,ne:B,ne:A,y\n", "v")))
                .hasMessage("place-changes.csv merges ne:A in a circle");
    }
}
