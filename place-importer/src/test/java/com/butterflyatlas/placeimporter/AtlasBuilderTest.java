package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.butterflyatlas.placeimporter.PartitionTest.wkt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlasBuilderTest {

    static Path fixtures() {
        try {
            return Path.of(AtlasBuilderTest.class.getResource("/fixtures").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static AtlasBuilder.Sources sources() throws IOException {
        Path dir = fixtures();
        return new AtlasBuilder.Sources(
                GeoJson.read(dir.resolve("ne_10m_admin_0_countries_ind.geojson")),
                GeoJson.read(dir.resolve("ne_10m_admin_1_states_provinces.geojson")),
                GeoJson.read(dir.resolve("geoBoundaries-IND-ADM1.geojson")),
                GeoJson.read(dir.resolve("geoBoundaries-IND-ADM2.geojson")));
    }

    private static List<String> tree(AtlasBuilder.Atlas atlas) {
        return atlas.places().stream()
                .map(p -> p.level() + " " + p.name() + " [" + p.key() + "] in " + p.parentKey())
                .toList();
    }

    @Test
    void buildsTheTreeParentsFirstIndiaFirstAndChildrenByName() throws IOException {
        AtlasBuilder.Atlas atlas = AtlasBuilder.build(sources(), null, List.of());

        assertThat(atlas.failures()).isEmpty();
        assertThat(tree(atlas)).containsExactly(
                "country India [iso:IN] in null",
                "state Arunachal Pradesh [iso:IN-AR] in iso:IN",
                "district Tawang [gb:ADM2-TAWANG] in iso:IN-AR",
                "district West Kameng [gb:ADM2-WKAMENG] in iso:IN-AR",
                "state Goa [iso:IN-GA] in iso:IN",
                "district North Goa [gb:ADM2-NGOA] in iso:IN-GA",
                "district South Goa [gb:ADM2-SGOA] in iso:IN-GA",
                "state Jammu and Kashmir [iso:IN-JK] in iso:IN",
                "district Srinagar [gb:ADM2-SRINAGAR] in iso:IN-JK",
                "state Ladakh [iso:IN-LA] in iso:IN",
                "district Kargil [gb:ADM2-KARGIL] in iso:IN-LA",
                "district Leh (Ladakh) [gb:ADM2-LEH] in iso:IN-LA",
                "state Maharashtra [iso:IN-MH] in iso:IN",
                "district Pune [gb:ADM2-PUNE] in iso:IN-MH",
                "country Clipperton Island [ne0:CLP] in null",
                "country France [iso:FR] in null",
                "country Nepal [iso:NP] in null",
                "state Bagmati [ne:NPL-2] in iso:NP",
                "state Gandaki [ne:NPL-1] in iso:NP",
                "country Pakistan [iso:PK] in null",
                "state Azad Kashmir [ne:PAK-1109] in iso:PK",
                "state Punjab [ne:PAK-1113] in iso:PK");
    }

    @Test
    void namesAliasesAndCodesFollowTheSources() throws IOException {
        AtlasBuilder.Atlas atlas = AtlasBuilder.build(sources(), null, List.of());
        Map<String, AtlasBuilder.Place> byKey = new HashMap<>();
        atlas.places().forEach(p -> byKey.put(p.key(), p));

        assertThat(byKey.get("iso:IN").aliases()).containsExactly("Bharat", "Republic of India");
        assertThat(byKey.get("iso:IN").codes()).containsExactlyEntriesOf(Map.of("iso", "IN"));
        assertThat(byKey.get("iso:IN-LA").aliases()).containsExactly("Ladākh");
        assertThat(byKey.get("iso:IN-LA").codes()).containsExactlyEntriesOf(Map.of("iso", "IN-LA"));
        assertThat(byKey.get("gb:ADM2-LEH").aliases()).containsExactly("Leh(Ladakh)");
        assertThat(byKey.get("gb:ADM2-LEH").codes()).containsExactlyEntriesOf(Map.of("gb", "ADM2-LEH"));
        assertThat(byKey.get("ne:PAK-1113").aliases()).containsExactly("Panjab", "Penjab");
        assertThat(byKey.get("ne:PAK-1113").codes()).containsExactlyInAnyOrderEntriesOf(Map.of("iso", "PK-PB", "ne", "PAK-1113"));
        assertThat(byKey.get("ne0:CLP").codes()).isEmpty();
        assertThat(byKey.get("iso:FR").aliases()).containsExactly("French Republic");
    }

    @Test
    void withTheLgdListIndianDistrictsTakeLgdNamesAndCodes() throws IOException {
        List<Lgd.District> lgd = Lgd.read(fixtures().resolve("lgd-districts.csv"));

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(sources(), lgd, List.of());
        Map<String, AtlasBuilder.Place> byKey = new HashMap<>();
        atlas.places().forEach(p -> byKey.put(p.key(), p));

        assertThat(byKey.get("lgd:9").name()).isEqualTo("Leh Ladakh");
        assertThat(byKey.get("lgd:9").aliases()).containsExactly("Leh(Ladakh)");
        assertThat(byKey.get("lgd:9").codes()).containsExactlyInAnyOrderEntriesOf(Map.of("lgd", "9", "gb", "ADM2-LEH"));
        assertThat(byKey.get("iso:IN-LA").codes()).containsExactlyInAnyOrderEntriesOf(Map.of("iso", "IN-LA", "lgd", "37"));
        assertThat(atlas.lgd().lgdWithoutBoundary()).extracting(Lgd.District::name).containsExactly("Bichom");
        assertThat(atlas.lgd().boundariesWithoutLgd()).isEmpty();
    }

    @Test
    void boundaryDistrictsThatAreOneReviewedLgdDistrictBecomeOnePlaceWithEverySpellingAsAnAlias() throws IOException {
        // Leh borrows Puducherry's code (600), the one reviewed merge.
        List<Lgd.District> lgd = Lgd.read(fixtures().resolve("lgd-districts.csv")).stream()
                .map(d -> d.code().equals("9") ? new Lgd.District("37", "Ladakh", "600", "Leh Ladakh") : d).toList();

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(withChangthang(), lgd, List.of(new Lgd.Override("IN-LA", "Changthang", "600")));

        assertThat(atlas.failures()).isEmpty();
        assertThat(atlas.places()).filteredOn(p -> p.key().equals("lgd:600")).singleElement().satisfies(p -> {
            assertThat(p.aliases()).containsExactly("Leh(Ladakh)", "Changthang");
            assertThat(p.codes()).containsEntry("gb", "ADM2-LEH");
            assertThat(p.parentKey()).isEqualTo("iso:IN-LA");
        });
        assertThat(atlas.leaves()).filteredOn(l -> l.key().equals("lgd:600")).singleElement()
                .satisfies(l -> assertThat(l.geometry().getArea()).isEqualTo(20.0));
    }

    @Test
    void boundaryDistrictsThatAreOneLgdDistrictOutsideTheReviewedListFailTheBuild() throws IOException {
        List<Lgd.District> lgd = Lgd.read(fixtures().resolve("lgd-districts.csv"));

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(withChangthang(), lgd, List.of(new Lgd.Override("IN-LA", "Changthang", "9")));

        assertThat(atlas.failures()).containsExactly("Boundary districts Leh(Ladakh), Changthang are all LGD district 9"
                + " (Leh Ladakh); only reviewed merges (600) may be one place");
    }

    @Test
    void aDistrictLgdPlacesInItsSecondCandidateStateGoesIntoThatState() throws IOException {
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> districts = new ArrayList<>(fixture.indiaDistricts());
        // 60% in Jammu and Kashmir (where it has no district), 40% in Ladakh (Kargil); LGD lists it in Ladakh.
        districts.add(new GeoJson.Feature(Map.of("shapeName", "Borderland", "shapeID", "ADM2-BORDER"),
                wkt("POLYGON ((74.4 34, 75.4 34, 75.4 34.2, 74.4 34.2, 74.4 34))")));
        List<Lgd.District> lgd = new ArrayList<>(Lgd.read(fixtures().resolve("lgd-districts.csv")));
        lgd.add(new Lgd.District("37", "Ladakh", "999", "Borderland"));

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(new AtlasBuilder.Sources(fixture.countries(), fixture.states(),
                fixture.indiaStates(), districts), lgd, List.of());

        assertThat(atlas.failures()).isEmpty();
        assertThat(atlas.lgd().districtStates()).containsEntry("ADM2-BORDER", "IN-LA");
        assertThat(atlas.places()).filteredOn(p -> p.key().equals("lgd:999")).singleElement()
                .extracting(AtlasBuilder.Place::parentKey).isEqualTo("iso:IN-LA");
    }

    @Test
    void aliasesThatDifferOnlyInCaseAreOne() throws IOException {
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> countries = fixture.countries().stream().map(f -> {
            if (!"FRA".equals(f.properties().get("ADM0_A3"))) {
                return f;
            }
            Map<String, String> p = new LinkedHashMap<>(f.properties());
            p.put("NAME_LONG", "FRANCE");
            p.put("FORMAL_EN", "French republic");
            return new GeoJson.Feature(p, f.geometry());
        }).toList();

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(new AtlasBuilder.Sources(countries, fixture.states(),
                fixture.indiaStates(), fixture.indiaDistricts()), null, List.of());

        assertThat(atlas.places()).filteredOn(p -> p.key().equals("iso:FR")).singleElement()
                .satisfies(p -> assertThat(p.aliases()).containsExactly("French republic"));
    }

    @Test
    void spellingsWithADottedOrDotlessIAreNotCaseVariants() throws IOException {
        Map<String, List<String>> names = Map.of("FRA", List.of("İzmir", "Izmir"), "NPL", List.of("Aydın", "Aydin"),
                "PAK", List.of("Eswatini", "ESWATINI"));
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> countries = fixture.countries().stream().map(f -> {
            List<String> spellings = names.get(f.properties().get("ADM0_A3"));
            if (spellings == null) {
                return f;
            }
            Map<String, String> p = new LinkedHashMap<>(f.properties());
            p.put("NAME_EN", spellings.get(0));
            p.put("NAME", spellings.get(1));
            p.put("NAME_LONG", "");
            p.put("ADMIN", "");
            p.put("FORMAL_EN", "");
            return new GeoJson.Feature(p, f.geometry());
        }).toList();

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(new AtlasBuilder.Sources(countries, fixture.states(),
                fixture.indiaStates(), fixture.indiaDistricts()), null, List.of());

        Map<String, List<String>> aliasesByName = new HashMap<>();
        atlas.places().stream().filter(p -> p.level().equals("country"))
                .forEach(p -> aliasesByName.put(p.name(), p.aliases()));
        assertThat(aliasesByName.get("İzmir")).containsExactly("Izmir");
        assertThat(aliasesByName.get("Aydın")).containsExactly("Aydin");
        assertThat(aliasesByName.get("Eswatini")).isEmpty();
    }

    @Test
    void garbledAndOneLetterAliasesAreDropped() throws IOException {
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> states = fixture.states().stream().map(f -> {
            if (!"PAK-1113".equals(f.properties().get("adm1_code"))) {
                return f;
            }
            Map<String, String> p = new LinkedHashMap<>(f.properties());
            p.put("name_alt", "Panjab|Panj\u00a3b|Penjab|X|Pun\ufffdjab|Pan\u009djab|7|Pe");
            return new GeoJson.Feature(p, f.geometry());
        }).toList();

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(new AtlasBuilder.Sources(fixture.countries(), states,
                fixture.indiaStates(), fixture.indiaDistricts()), null, List.of());

        assertThat(atlas.places()).filteredOn(p -> p.key().equals("ne:PAK-1113")).singleElement()
                .satisfies(p -> assertThat(p.aliases()).containsExactly("Panjab", "Penjab", "Pe"));
    }

    @Test
    void aForeignStateNamedLikeASiblingTakesNaturalEarthsOwnNameKeepingItsPlaceInTheTree() throws IOException {
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> states = fixture.states().stream().map(f -> {
            if (!"NPL-1".equals(f.properties().get("adm1_code"))) {
                return f;
            }
            Map<String, String> p = new LinkedHashMap<>(f.properties());
            p.put("name_en", "Bagmati");
            return new GeoJson.Feature(p, f.geometry());
        }).toList();

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(new AtlasBuilder.Sources(fixture.countries(), states,
                fixture.indiaStates(), fixture.indiaDistricts()), null, List.of(), List.of());

        assertThat(tree(atlas)).containsSubsequence("country Nepal [iso:NP] in null",
                "state Gandaki [ne:NPL-1] in iso:NP", "state Bagmati [ne:NPL-2] in iso:NP");
        assertThat(atlas.places()).filteredOn(p -> p.key().equals("ne:NPL-1")).singleElement()
                .satisfies(p -> assertThat(p.aliases()).containsExactly("Bagmati", "Gandaki Pradesh"));
        assertThat(atlas.renames()).containsExactly(new SiblingNames.Rename("ne:NPL-1", "iso:NP", "Bagmati", "Gandaki"));
    }

    @Test
    void reviewedOtherSpellingsJoinThePlacesAliases() throws IOException {
        AtlasBuilder.Atlas atlas = AtlasBuilder.build(sources(), null, List.of(), List.of(),
                List.of(new SiblingNames.Alias("gb:ADM2-PUNE", "Poona"), new SiblingNames.Alias("lgd:1", "Nowhere")));

        assertThat(atlas.places()).filteredOn(p -> p.key().equals("gb:ADM2-PUNE")).singleElement()
                .satisfies(p -> assertThat(p.aliases()).containsExactly("Poona"));
        assertThat(atlas.unusedAliases()).containsExactly(new SiblingNames.Alias("lgd:1", "Nowhere"));
    }

    @Test
    void theNameOverridesComeFirstAndUnknownKeysAreReported() throws IOException {
        AtlasBuilder.Atlas atlas = AtlasBuilder.build(sources(), null, List.of(), List.of(
                new SiblingNames.Override("ne:PAK-1113", "Punjab Province"), new SiblingNames.Override("ne:ZZZ-1", "Atlantis")));

        assertThat(atlas.places()).filteredOn(p -> p.key().equals("ne:PAK-1113")).singleElement()
                .satisfies(p -> {
                    assertThat(p.name()).isEqualTo("Punjab Province");
                    assertThat(p.aliases()).containsExactly("Punjab", "Panjab", "Penjab");
                });
        assertThat(atlas.renames()).containsExactly(
                new SiblingNames.Rename("ne:PAK-1113", "iso:PK", "Punjab", "Punjab Province"));
        assertThat(atlas.unusedNameOverrides()).containsExactly(new SiblingNames.Override("ne:ZZZ-1", "Atlantis"));
    }

    @Test
    void aSourceFeatureWithoutAFieldTheBuildNeedsIsNamedInTheError() throws IOException {
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> countries = new ArrayList<>(fixture.countries());
        countries.add(new GeoJson.Feature(Map.of("NAME", "Atlantis"), wkt("POLYGON ((-30 0, -29 0, -29 1, -30 1, -30 0))")));
        List<GeoJson.Feature> states = new ArrayList<>(fixture.states());
        states.add(new GeoJson.Feature(Map.of("name", "Lemuria", "adm1_code", "LEM-1"), wkt("POLYGON ((50 0, 51 0, 51 1, 50 1, 50 0))")));
        List<GeoJson.Feature> districts = new ArrayList<>(fixture.indiaDistricts());
        districts.add(new GeoJson.Feature(Map.of("shapeID", "ADM2-NONAME"), wkt("POLYGON ((76 33, 77 33, 77 34, 76 34, 76 33))")));

        assertThatThrownBy(() -> AtlasBuilder.build(new AtlasBuilder.Sources(countries, fixture.states(),
                fixture.indiaStates(), fixture.indiaDistricts()), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Natural Earth country feature 5 (Atlantis) has no ADM0_A3");
        assertThatThrownBy(() -> AtlasBuilder.build(new AtlasBuilder.Sources(fixture.countries(), states,
                fixture.indiaStates(), fixture.indiaDistricts()), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Natural Earth state feature 6 (Lemuria) has no adm0_a3");
        assertThatThrownBy(() -> AtlasBuilder.build(new AtlasBuilder.Sources(fixture.countries(), fixture.states(),
                fixture.indiaStates(), districts), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("geoBoundaries district feature 9 (ADM2-NONAME) has no shapeName");
    }

    @Test
    void aSmallIndianIslandNearTheRestOfItsPlaceMayBeSimplifiedAway() throws IOException {
        // About 1 km off the coast of its district, 0.003 km²: below the India tolerance's area, so dropped.
        AtlasBuilder.Atlas atlas = AtlasBuilder.build(withCoastalIsland(73.895), null, List.of());

        assertThat(atlas.failures()).isEmpty();
        assertThat(atlas.dropped()).singleElement().satisfies(d -> {
            assertThat(d.key()).isEqualTo("gb:ADM2-COASTAL");
            assertThat(d.parts()).isOne();
        });
    }

    @Test
    void anIndianIslandFarFromTheRestOfItsPlaceMayNotBeSimplifiedAway() throws IOException {
        // The same island 11 km out: a photo taken there would find no place.
        AtlasBuilder.Atlas atlas = AtlasBuilder.build(withCoastalIsland(73.80), null, List.of());

        assertThat(atlas.failures()).singleElement().asString()
                .startsWith("An island of Coastal (0.003 km² at 14.85025, 73.80025) is lost to simplification, 11.")
                .endsWith(" km from the rest of it (only islands up to 0.05 km² within 2 km may go)");
    }

    @Test
    void anIndianIslandLargerThanAFewHectaresMayNotBeSimplifiedAwayEvenNearTheRest() {
        Geometry kept = wkt("POLYGON ((73.9046 14.8, 74.3 14.8, 74.3 14.9, 73.9046 14.9, 73.9046 14.8))");

        assertThat(AtlasBuilder.lostIsland("Coastal", wkt("POLYGON ((73.895 14.85, 73.898 14.85, 73.898 14.853, 73.895 14.853, 73.895 14.85))"), kept))
                .hasValueSatisfying(m -> assertThat(m).startsWith("An island of Coastal (0.108 km² at 14.85150, 73.89650) is lost to simplification, 0.7 km "));
        assertThat(AtlasBuilder.lostIsland("Coastal", wkt("POLYGON ((73.895 14.85, 73.8955 14.85, 73.8955 14.8505, 73.895 14.8505, 73.895 14.85))"), kept))
                .isEmpty();
    }

    @Test
    void aReviewedMergeOfDistrictsInDifferentStatesFailsTheBuild() throws IOException {
        // A second geoBoundaries state whose name is also LGD's Ladakh (state 37), with a district LGD calls 600 too.
        AtlasBuilder.Sources fixture = withChangthang();
        List<GeoJson.Feature> states = new ArrayList<>(fixture.indiaStates());
        states.add(new GeoJson.Feature(Map.of("shapeName", "Ladakh", "shapeISO", "IN-LX", "shapeID", "ADM1-LX"),
                wkt("POLYGON ((90 20, 92 20, 92 22, 90 22, 90 20))")));
        List<GeoJson.Feature> districts = new ArrayList<>(fixture.indiaDistricts());
        districts.add(new GeoJson.Feature(Map.of("shapeName", "Farside", "shapeID", "ADM2-FARSIDE"),
                wkt("POLYGON ((90 20, 92 20, 92 22, 90 22, 90 20))")));
        List<Lgd.District> lgd = Lgd.read(fixtures().resolve("lgd-districts.csv")).stream()
                .map(d -> d.code().equals("9") ? new Lgd.District("37", "Ladakh", "600", "Leh Ladakh") : d).toList();

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(new AtlasBuilder.Sources(fixture.countries(), fixture.states(), states,
                districts), lgd, List.of(new Lgd.Override("IN-LX", "Farside", "600")));

        assertThat(atlas.failures()).containsExactly("Boundary districts Leh(Ladakh), Farside are all LGD district 600"
                + " (Leh Ladakh) but lie in different states (iso:IN-LA, iso:IN-LX)");
    }

    /** Goa's south coast as a district (Coastal) with a 0.0005° island at {@code islandX}, 14.85; India holds the island. */
    private static AtlasBuilder.Sources withCoastalIsland(double islandX) throws IOException {
        AtlasBuilder.Sources fixture = sources();
        Geometry island = wkt(String.format(java.util.Locale.ROOT, "POLYGON ((%s 14.85, %s 14.85, %s 14.8505, %s 14.8505, %s 14.85))",
                islandX, islandX + 0.0005, islandX + 0.0005, islandX, islandX));
        List<GeoJson.Feature> countries = fixture.countries().stream()
                .map(f -> "IND".equals(f.properties().get("ADM0_A3")) ? new GeoJson.Feature(f.properties(), f.geometry().union(island)) : f)
                .toList();
        List<GeoJson.Feature> districts = new ArrayList<>(fixture.indiaDistricts());
        districts.add(new GeoJson.Feature(Map.of("shapeName", "Coastal", "shapeID", "ADM2-COASTAL"),
                wkt("POLYGON ((73.9046 14.8, 74.3 14.8, 74.3 14.9, 73.9046 14.9, 73.9046 14.8))").union(island)));
        return new AtlasBuilder.Sources(countries, fixture.states(), fixture.indiaStates(), districts);
    }

    private static AtlasBuilder.Sources withChangthang() throws IOException {
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> districts = new ArrayList<>(fixture.indiaDistricts());
        districts.add(new GeoJson.Feature(Map.of("shapeName", "Changthang", "shapeID", "ADM2-CHANGTHANG"),
                wkt("POLYGON ((79 36, 80 36, 80 37, 79 37, 79 36))")));
        return new AtlasBuilder.Sources(fixture.countries(), fixture.states(), fixture.indiaStates(), districts);
    }

    @Test
    void leavesCoverIndiaWithDistrictsAndStateAreasWhereNoDistrictIsAndTheWorldWithStatesOrCountries() throws IOException {
        AtlasBuilder.Atlas atlas = AtlasBuilder.build(sources(), null, List.of());

        assertThat(atlas.leaves()).extracting(Partition.Piece::key).containsExactlyInAnyOrder(
                "gb:ADM2-TAWANG", "gb:ADM2-WKAMENG", "gb:ADM2-NGOA", "gb:ADM2-SGOA", "gb:ADM2-SRINAGAR",
                "gb:ADM2-KARGIL", "gb:ADM2-LEH", "gb:ADM2-PUNE",
                "iso:IN-AR", "iso:IN-GA", "iso:IN-JK", "iso:IN-MH",        // India's outline beyond the districts
                "ne:NPL-1", "ne:NPL-2", "ne:PAK-1109", "ne:PAK-1113", "ne0:CLP", "iso:FR");
        Map<String, Geometry> leaves = new HashMap<>();
        atlas.leaves().forEach(p -> leaves.put(p.key(), p.geometry()));
        // India's view: Azad Kashmir's part east of 68°E is India's (Jammu and Kashmir), Siachen's too (Ladakh).
        assertThat(leaves.get("ne:PAK-1109").getEnvelopeInternal().getMaxX()).isEqualTo(68.0);
        assertThat(leaves.get("iso:IN-JK").contains(wkt("POINT (73.4711 34.37)"))).isTrue();
        assertThat(leaves.get("gb:ADM2-LEH").contains(wkt("POINT (76.5 35.5)"))).isTrue();
        assertThat(Checks.overlaps(atlas.leaves())).isEmpty();
    }

    @Test
    void districtsOutsideEveryStateOrIndiaFailTheBuild() throws IOException {
        AtlasBuilder.Sources fixture = sources();
        List<GeoJson.Feature> districts = new ArrayList<>(fixture.indiaDistricts());
        districts.add(new GeoJson.Feature(Map.of("shapeName", "Straddle", "shapeID", "ADM2-X"),
                wkt("POLYGON ((79 20, 85 20, 85 21, 79 21, 79 20))")));
        districts.add(new GeoJson.Feature(Map.of("shapeName", "Ghost", "shapeID", "ADM2-G"),
                wkt("POLYGON ((60 10, 61 10, 61 11, 60 11, 60 10))")));

        AtlasBuilder.Atlas atlas = AtlasBuilder.build(new AtlasBuilder.Sources(fixture.countries(), fixture.states(),
                fixture.indiaStates(), districts), null, List.of());

        assertThat(atlas.failures()).containsExactly(
                "District Straddle lies outside every state (none holds 20% of it)",
                "District Ghost lies outside every state (none holds 20% of it)",
                "District Ghost lies outside India's outline (no part of it in India or within 2 km)");
    }
}
