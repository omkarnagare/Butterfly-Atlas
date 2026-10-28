package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LgdTest {

    @TempDir
    Path dir;

    private static final String CSV = """
            S.No.,State Code,State Name (In English),District Code,District Name(In English),Census 2001 Code,Census 2011 Code
            1,27,Maharashtra,490,Pune,25,521
            2,27,Maharashtra,466,Ahilyanagar,26,522
            3,27,Maharashtra,"488","Dharashiv",30,524
            4,23,Madhya Pradesh,405,Khandwa (East Nimar),33,436
            5,37,Ladakh,9,Leh Ladakh,7,3
            6,38,The Dadra And Nagar Haveli And Daman And Diu,465,Dadra And Nagar Haveli,0,0
            7,12,Arunachal Pradesh,787,Bichom,0,0
            """;

    private List<Lgd.District> districts() throws IOException {
        Path file = dir.resolve("districts.csv");
        Files.writeString(file, CSV);
        return Lgd.read(file);
    }

    @Test
    void readsTheDistrictListByItsHeaderNames() throws IOException {
        List<Lgd.District> districts = districts();

        assertThat(districts).hasSize(7);
        assertThat(districts.get(0)).isEqualTo(new Lgd.District("27", "Maharashtra", "490", "Pune"));
        assertThat(districts.get(2)).isEqualTo(new Lgd.District("27", "Maharashtra", "488", "Dharashiv"));
    }

    @Test
    void refusesAFileWithoutTheExpectedColumns() throws IOException {
        Path file = dir.resolve("other.csv");
        Files.writeString(file, "a,b\n1,2\n");

        assertThatThrownBy(() -> Lgd.read(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("State Code");
    }

    @Test
    void matchesStatesByNameAndDistrictsByStateAndNameOverridesAndParentheses() throws IOException {
        List<Lgd.Boundary> states = List.of(
                new Lgd.Boundary("IN-MH", List.of(), "Mahārāshtra"),
                new Lgd.Boundary("IN-MP", List.of(), "Madhya Pradesh"),
                new Lgd.Boundary("IN-LA", List.of(), "Ladākh"),
                new Lgd.Boundary("IN-DH", List.of(), "Dādra and Nagar Haveli and Damān and Diu"),
                new Lgd.Boundary("IN-AR", List.of(), "Arunāchal Pradesh"),
                new Lgd.Boundary("IN-GA", List.of(), "Goa"));
        List<Lgd.Boundary> boundaries = List.of(
                new Lgd.Boundary("gb1", List.of("IN-MH"), "Pune"),
                new Lgd.Boundary("gb2", List.of("IN-MH"), "Ahmadnagar"),
                new Lgd.Boundary("gb3", List.of("IN-MH"), "Osmanabad"),
                new Lgd.Boundary("gb4", List.of("IN-MP"), "East Nimar"),
                new Lgd.Boundary("gb5", List.of("IN-LA"), "Leh(Ladakh)"),
                new Lgd.Boundary("gb6", List.of("IN-DH"), "Dadra and Nagar Haveli"),
                new Lgd.Boundary("gb7", List.of("IN-GA"), "North Goa"));
        List<Lgd.Override> overrides = Lgd.readOverrides(new ByteArrayInputStream("""
                state_iso,geoboundaries_name,lgd_code,note
                IN-MH,Ahmadnagar,466,renamed Ahilyanagar in 2023
                """.getBytes(StandardCharsets.UTF_8)));

        Lgd.Match match = Lgd.match(states, boundaries, districts(), overrides);

        assertThat(match.stateCodes()).containsExactlyInAnyOrderEntriesOf(Map.of("IN-MH", "27", "IN-MP", "23", "IN-LA", "37",
                "IN-DH", "38", "IN-AR", "12"));
        assertThat(match.statesWithoutLgd()).containsExactly("IN-GA");
        assertThat(match.districts()).containsOnlyKeys("gb1", "gb2", "gb4", "gb5", "gb6");
        assertThat(match.districts().get("gb2").name()).isEqualTo("Ahilyanagar");
        assertThat(match.districts().get("gb4").code()).isEqualTo("405");
        assertThat(match.districts().get("gb5").code()).isEqualTo("9");
        assertThat(match.districtStates()).containsEntry("gb1", "IN-MH").containsEntry("gb5", "IN-LA");
        assertThat(match.boundariesWithoutLgd()).extracting(Lgd.Boundary::id).containsExactly("gb3", "gb7");
        assertThat(match.lgdWithoutBoundary()).extracting(Lgd.District::name).containsExactly("Dharashiv", "Bichom");
    }

    @Test
    void aDistrictIsMatchedInTheFirstOfItsPossibleStatesWhereItsNameIs() throws IOException {
        List<Lgd.District> lgd = List.of(new Lgd.District("28", "Andhra Pradesh", "523", "West Godavari"),
                new Lgd.District("34", "Puducherry", "636", "Yanam"));

        Lgd.Match match = Lgd.match(
                List.of(new Lgd.Boundary("IN-AP", List.of(), "Andhra Pradesh"), new Lgd.Boundary("IN-PY", List.of(), "Puducherry")),
                List.of(new Lgd.Boundary("gb9", List.of("IN-AP", "IN-PY"), "Yanam")), lgd, List.of());

        assertThat(match.districts().get("gb9").code()).isEqualTo("636");
        assertThat(match.districtStates()).containsEntry("gb9", "IN-PY");
    }

    @Test
    void theMostLikelyStateIsTriedWithEveryKindOfMatchBeforeTheNext() throws IOException {
        List<Lgd.District> lgd = List.of(new Lgd.District("36", "Telangana", "532", "Warangal"),
                new Lgd.District("28", "Andhra Pradesh", "999", "Warangal (U)"));

        Lgd.Match match = Lgd.match(
                List.of(new Lgd.Boundary("IN-TG", List.of(), "Telangana"), new Lgd.Boundary("IN-AP", List.of(), "Andhra Pradesh")),
                List.of(new Lgd.Boundary("gb1", List.of("IN-TG", "IN-AP"), "Warangal (U)")), lgd, List.of());

        assertThat(match.districts().get("gb1").code()).isEqualTo("532");
        assertThat(match.districtStates()).containsEntry("gb1", "IN-TG");
    }

    @Test
    void aRowShorterThanTheColumnsItNeedsIsSkipped() throws IOException {
        Path file = dir.resolve("reordered.csv");
        Files.writeString(file, """
                District Name(In English),State Code,State Name (In English),District Code
                Pune,27,Maharashtra
                Ahilyanagar,27,Maharashtra,466
                """);

        assertThat(Lgd.read(file)).containsExactly(new Lgd.District("27", "Maharashtra", "466", "Ahilyanagar"));
    }

    @Test
    void anOverrideForACodeMissingFromTheListIsIgnoredAndOneInAnotherStateFails() throws IOException {
        List<Lgd.Boundary> states = List.of(new Lgd.Boundary("IN-MH", List.of(), "Maharashtra"),
                new Lgd.Boundary("IN-LA", List.of(), "Ladakh"));
        List<Lgd.Boundary> pune = List.of(new Lgd.Boundary("gb1", List.of("IN-MH"), "Pune"));

        Lgd.Override missing = new Lgd.Override("IN-MH", "Pune", "999");
        Lgd.Match match = Lgd.match(states, pune, districts(), List.of(missing));
        assertThat(match.districts().get("gb1").code()).isEqualTo("490");
        assertThat(match.overridesWithoutLgd()).containsExactly(missing);

        assertThatThrownBy(() -> Lgd.match(states, pune, districts(), List.of(new Lgd.Override("IN-MH", "Pune", "9"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LGD district 9 (Leh Ladakh) is not in IN-MH");
    }
}
