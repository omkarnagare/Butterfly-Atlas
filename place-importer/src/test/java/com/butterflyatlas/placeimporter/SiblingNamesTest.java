package com.butterflyatlas.placeimporter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiblingNamesTest {

    private static AtlasBuilder.Place country(String key, String name) {
        return new AtlasBuilder.Place(key, null, "country", name, List.of(), Map.of());
    }

    private static AtlasBuilder.Place state(String key, String parent, String name, String... aliases) {
        return new AtlasBuilder.Place(key, parent, "state", name, List.of(aliases), Map.of());
    }

    private static Map<String, String> names(SiblingNames.Result result) {
        Map<String, String> names = new HashMap<>();
        result.places().forEach(p -> names.put(p.key(), p.name()));
        return names;
    }

    @Test
    void clashingForeignStatesTakeNaturalEarthsOwnNameWhenItIsUnique() {
        List<AtlasBuilder.Place> places = List.of(country("iso:US", "United States of America"),
                state("ne:USA-3519", "iso:US", "Washington"),
                state("ne:USA-3556", "iso:US", "Washington", "Washington DC"),
                state("ne:USA-3500", "iso:US", "Oregon"));
        Map<String, SiblingNames.Source> sources = Map.of(
                "ne:USA-3519", new SiblingNames.Source("Washington", "State"),
                "ne:USA-3556", new SiblingNames.Source("District of Columbia", "Federal District"),
                "ne:USA-3500", new SiblingNames.Source("Oregon", "State"));

        SiblingNames.Result result = SiblingNames.resolve(places, sources, List.of());

        assertThat(names(result)).containsEntry("ne:USA-3519", "Washington").containsEntry("ne:USA-3556", "District of Columbia");
        assertThat(result.places()).filteredOn(p -> p.key().equals("ne:USA-3556")).singleElement()
                .satisfies(p -> assertThat(p.aliases()).containsExactly("Washington", "Washington DC"));
        assertThat(result.renames()).containsExactly(
                new SiblingNames.Rename("ne:USA-3556", "iso:US", "Washington", "District of Columbia"));
        assertThat(result.places()).extracting(AtlasBuilder.Place::key)
                .containsExactly("iso:US", "ne:USA-3519", "ne:USA-3556", "ne:USA-3500");
    }

    @Test
    void whenNaturalEarthsNameDoesNotTellThemApartTheTypeQualifiesTheName() {
        List<AtlasBuilder.Place> places = List.of(country("iso:IE", "Ireland"),
                state("ne:IRL-5569", "iso:IE", "Cork"), state("ne:IRL-78", "iso:IE", "CORK"),
                state("ne:IRL-1", "iso:IE", "Kerry"));
        Map<String, SiblingNames.Source> sources = Map.of(
                "ne:IRL-5569", new SiblingNames.Source("Cork", "City"),
                "ne:IRL-78", new SiblingNames.Source("Cork", "County"),
                "ne:IRL-1", new SiblingNames.Source("Kerry", "County"));

        SiblingNames.Result result = SiblingNames.resolve(places, sources, List.of());

        assertThat(names(result)).containsEntry("ne:IRL-5569", "Cork (city)").containsEntry("ne:IRL-78", "CORK (county)")
                .containsEntry("ne:IRL-1", "Kerry");
        assertThat(result.places()).filteredOn(p -> p.key().equals("ne:IRL-5569")).singleElement()
                .satisfies(p -> assertThat(p.aliases()).containsExactly("Cork"));
    }

    @Test
    void aNaturalEarthNameThatAnotherSiblingAlreadyHasIsNotUnique() {
        List<AtlasBuilder.Place> places = List.of(country("iso:LV", "Latvia"),
                state("ne:LVA-1", "iso:LV", "Valmiera"), state("ne:LVA-2", "iso:LV", "Valmiera"),
                state("ne:LVA-3", "iso:LV", "Amatas"));
        Map<String, SiblingNames.Source> sources = Map.of(
                "ne:LVA-1", new SiblingNames.Source("Valmiera", "Republican City"),
                "ne:LVA-2", new SiblingNames.Source("Amatas", "Municipality"),
                "ne:LVA-3", new SiblingNames.Source("Amatas", "Municipality"));

        SiblingNames.Result result = SiblingNames.resolve(places, sources, List.of());

        assertThat(names(result)).containsEntry("ne:LVA-1", "Valmiera").containsEntry("ne:LVA-2", "Valmiera (municipality)")
                .containsEntry("ne:LVA-3", "Amatas");
    }

    @Test
    void whatNeitherRuleTellsApartKeepsItsNameAndTheOverrideTableDecides() {
        List<AtlasBuilder.Place> places = List.of(country("iso:MW", "Malawi"),
                state("ne:MWI-1194", "iso:MW", "Chitipa"), state("ne:MWI-1873", "iso:MW", "Chitipa"));
        Map<String, SiblingNames.Source> sources = Map.of(
                "ne:MWI-1194", new SiblingNames.Source("Chitipa", "District"),
                "ne:MWI-1873", new SiblingNames.Source("Chitipa", "District"));

        assertThat(names(SiblingNames.resolve(places, sources, List.of())))
                .containsEntry("ne:MWI-1194", "Chitipa").containsEntry("ne:MWI-1873", "Chitipa");

        SiblingNames.Result result = SiblingNames.resolve(places, sources, List.of(
                new SiblingNames.Override("ne:MWI-1194", "Karonga"), new SiblingNames.Override("ne:XXX-1", "Nowhere")));

        assertThat(names(result)).containsEntry("ne:MWI-1194", "Karonga").containsEntry("ne:MWI-1873", "Chitipa");
        assertThat(result.renames()).containsExactly(new SiblingNames.Rename("ne:MWI-1194", "iso:MW", "Chitipa", "Karonga"));
        assertThat(result.unusedOverrides()).containsExactly(new SiblingNames.Override("ne:XXX-1", "Nowhere"));
    }

    @Test
    void indianPlacesCountriesAndPlacesWithoutNaturalEarthDetailsAreNeverRenamedByTheRules() {
        List<AtlasBuilder.Place> places = List.of(country("iso:IN", "India"), country("iso:XX", "India"),
                state("iso:IN-AA", "iso:IN", "Goa"), state("iso:IN-BB", "iso:IN", "Goa"));

        SiblingNames.Result result = SiblingNames.resolve(places, Map.of(
                "iso:IN-AA", new SiblingNames.Source("Goa North", "State"),
                "iso:IN-BB", new SiblingNames.Source("Goa South", "State")), List.of());

        assertThat(result.places()).isEqualTo(places);
        assertThat(result.renames()).isEmpty();
    }

    @Test
    void reviewedOtherSpellingsAreAddedOnceAndUnknownKeysReported() {
        List<AtlasBuilder.Place> places = List.of(country("iso:IN", "India"),
                state("iso:IN-MH", "iso:IN", "Maharashtra"),
                new AtlasBuilder.Place("lgd:482", "iso:IN-MH", "district", "Mumbai", List.of("Mumbai City"), Map.of()));

        SiblingNames.Result result = SiblingNames.resolve(places, Map.of(), List.of(), List.of(
                new SiblingNames.Alias("lgd:482", "Bombay"), new SiblingNames.Alias("lgd:482", "mumbai city"),
                new SiblingNames.Alias("lgd:482", "MUMBAI"), new SiblingNames.Alias("lgd:999", "Nowhere")));

        assertThat(result.places().get(2).aliases()).containsExactly("Mumbai City", "Bombay");
        assertThat(result.places().get(2).name()).isEqualTo("Mumbai");
        assertThat(result.renames()).isEmpty();
        assertThat(result.unusedAliases()).containsExactly(new SiblingNames.Alias("lgd:999", "Nowhere"));
    }

    @Test
    void theBundledOtherSpellingsAreForIndianPlaces() throws IOException {
        List<SiblingNames.Alias> aliases;
        try (InputStream in = SiblingNames.class.getResourceAsStream("/extra-aliases.csv")) {
            aliases = SiblingNames.readAliases(in);
        }

        assertThat(aliases).contains(new SiblingNames.Alias("lgd:482", "Bombay"), new SiblingNames.Alias("lgd:490", "Poona"),
                new SiblingNames.Alias("lgd:483", "Bombay Suburban"), new SiblingNames.Alias("lgd:559", "Quilon"),
                new SiblingNames.Alias("lgd:554", "Alleppey"), new SiblingNames.Alias("lgd:563", "Palghat"),
                new SiblingNames.Alias("lgd:586", "Tanjore"), new SiblingNames.Alias("lgd:594", "Tuticorin"),
                new SiblingNames.Alias("lgd:504", "Cuddapah"), new SiblingNames.Alias("lgd:487", "Nasik"),
                new SiblingNames.Alias("lgd:618", "Gauhati"), new SiblingNames.Alias("lgd:187", "Benares"),
                new SiblingNames.Alias("lgd:187", "Banaras"));
        assertThat(aliases).extracting(SiblingNames.Alias::key).allSatisfy(k -> assertThat(k).matches("lgd:\\d+|iso:IN-[A-Z]{2}"));
        assertThat(aliases).doesNotHaveDuplicates();
    }

    @Test
    void siblingsWhoseNamesDifferOnlyInAccentsClashAsSearchIgnoresAccents() {
        List<AtlasBuilder.Place> places = List.of(country("iso:SI", "Slovenia"),
                state("ne:SVN-939", "iso:SI", "Krsko"), state("ne:SVN-958", "iso:SI", "Krško"),
                state("ne:SVN-938", "iso:SI", "Brežice"));
        Map<String, SiblingNames.Source> sources = Map.of(
                "ne:SVN-939", new SiblingNames.Source("Krsko", "Commune|Municipality"),
                "ne:SVN-958", new SiblingNames.Source("Krško", "Statistical Region"),
                "ne:SVN-938", new SiblingNames.Source("Brežice", "Commune|Municipality"));

        assertThat(names(SiblingNames.resolve(places, sources, List.of())))
                .containsEntry("ne:SVN-939", "Krsko (commune|municipality)")
                .containsEntry("ne:SVN-958", "Krško (statistical region)");

        SiblingNames.Result result = SiblingNames.resolve(places, sources,
                List.of(new SiblingNames.Override("ne:SVN-939", "Brežice (Pečice)")));

        assertThat(names(result)).containsEntry("ne:SVN-939", "Brežice (Pečice)").containsEntry("ne:SVN-958", "Krško")
                .containsEntry("ne:SVN-938", "Brežice");
        assertThat(result.renames()).containsExactly(
                new SiblingNames.Rename("ne:SVN-939", "iso:SI", "Krsko", "Brežice (Pečice)"));
    }

    @Test
    void anOverrideKeyGivenTwiceIsRefused() {
        InputStream in = new ByteArrayInputStream(("key,name,note\nne:MWI-1194,Karonga,\nne:MWI-1194,Chitipa,\n")
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> SiblingNames.readOverrides(in)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name-overrides.csv names ne:MWI-1194 twice");
    }

    @Test
    void overridesForPlacesNotInThePinnedDataAreFailures() {
        assertThat(SiblingNames.unknownOverrides(List.of(new SiblingNames.Override("ne:XXX-1", "Nowhere"),
                new SiblingNames.Override("ne:XXX-2", "Elsewhere")))).containsExactly(
                "name-overrides.csv names ne:XXX-1 (Nowhere), which is not in the data",
                "name-overrides.csv names ne:XXX-2 (Elsewhere), which is not in the data");
        assertThat(SiblingNames.unknownOverrides(List.of())).isEmpty();
    }

    @Test
    void readsTheOverrideTable() throws IOException {
        InputStream in = new ByteArrayInputStream(("key,name,note\nne:MWI-1194,Karonga,\"GeoNames: Karonga District\"\n"
                + "\n,,\n").getBytes(StandardCharsets.UTF_8));

        assertThat(SiblingNames.readOverrides(in)).containsExactly(new SiblingNames.Override("ne:MWI-1194", "Karonga"));
    }

    @Test
    void theBundledOverrideTableNamesNaturalEarthStatesOnce() throws IOException {
        List<SiblingNames.Override> overrides;
        try (InputStream in = SiblingNames.class.getResourceAsStream("/name-overrides.csv")) {
            overrides = SiblingNames.readOverrides(in);
        }

        assertThat(overrides).isNotEmpty();
        assertThat(overrides).extracting(SiblingNames.Override::key).doesNotHaveDuplicates()
                .allSatisfy(k -> assertThat(k).matches("ne:[A-Z]{3}-\\d+"));
        assertThat(overrides).extracting(SiblingNames.Override::name).allSatisfy(n -> assertThat(n).isNotBlank());
        assertThat(overrides).contains(
                new SiblingNames.Override("ne:UKR-4826", "Kyiv"), new SiblingNames.Override("ne:UKR-321", "Kyiv Oblast"),
                new SiblingNames.Override("ne:HRV-1589", "City of Zagreb"), new SiblingNames.Override("ne:HRV-1588", "Zagreb County"),
                new SiblingNames.Override("ne:BGR-2243", "Sofia City"), new SiblingNames.Override("ne:BGR-2244", "Sofia Province"),
                new SiblingNames.Override("ne:LVA-4851", "Rēzekne"), new SiblingNames.Override("ne:LVA-1066", "Rēzekne Municipality"));
        assertThat(overrides).as("Brežice (Pečice) is merged into Brežice (place-changes.csv), so needs no name of its own")
                .extracting(SiblingNames.Override::key).doesNotContain("ne:SVN-939");
    }
}
