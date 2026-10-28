package com.butterflyatlas.importer.inat;

import com.butterflyatlas.importer.Quality;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InatClientTest {

    private static final long INDIA = 6681;
    private static final long BUTTERFLIES = 47224;

    @Test
    void readsEveryPageOfTheSpeciesList() throws Exception {
        FakeTransport transport = new FakeTransport()
                .respond("&page=1&", """
                        {"total_results":3,"page":1,"per_page":2,"results":[
                          {"count":5,"taxon":{"id":1,"name":"Danaus chrysippus","rank":"species","is_active":true,
                            "ancestor_ids":[47224,10,20,1],"preferred_common_name":"Plain Tiger"}},
                          {"count":2,"taxon":{"id":2,"name":"Papilio polytes","rank":"species","is_active":true,
                            "ancestor_ids":[47224,30,2]}}]}""")
                .respond("&page=2&", """
                        {"total_results":3,"page":2,"per_page":2,"results":[
                          {"count":1,"taxon":{"id":3,"name":"Eurema hecabe","rank":"species","is_active":true,
                            "ancestor_ids":[47224,40,3],"preferred_common_name":"Common Grass Yellow"}}]}""");

        List<InatTaxon> species = new InatClient(transport, INDIA).speciesInPlace(INDIA, BUTTERFLIES, Quality.RESEARCH);

        assertThat(species).extracting(InatTaxon::name)
                .containsExactly("Danaus chrysippus", "Papilio polytes", "Eurema hecabe");
        assertThat(species.get(0).commonName()).isEqualTo("Plain Tiger");
        assertThat(species.get(1).commonName()).isNull();
        assertThat(species.get(0).ancestorIds()).containsExactly(47224L, 10L, 20L, 1L);
        assertThat(transport.requests()).hasSize(2).allSatisfy(url -> assertThat(url)
                .contains("/v2/observations/species_counts?")
                .contains("place_id=6681")
                .contains("taxon_id=47224")
                .contains("verifiable=true")
                .contains("quality_grade=research")
                .contains("preferred_place_id=6681"));
    }

    @Test
    void verifiableQualityLeavesOutTheResearchFilter() throws Exception {
        FakeTransport transport = new FakeTransport()
                .respond("species_counts", "{\"total_results\":0,\"results\":[]}");

        new InatClient(transport, INDIA).speciesInPlace(INDIA, BUTTERFLIES, Quality.VERIFIABLE);

        assertThat(transport.requests().get(0)).doesNotContain("quality_grade");
    }

    @Test
    void fetchesTaxaInBatchesOfThirty() throws Exception {
        FakeTransport transport = new FakeTransport().route("/v2/taxa/", uri -> {
            String ids = uri.getPath().substring("/v2/taxa/".length());
            String results = Arrays.stream(ids.split(","))
                    .map(id -> "{\"id\":" + id + ",\"name\":\"T" + id + "\",\"rank\":\"genus\",\"is_active\":true,"
                            + "\"ancestor_ids\":[" + id + "]}")
                    .collect(Collectors.joining(","));
            return "{\"results\":[" + results + "]}";
        });
        List<Long> ids = LongStream.rangeClosed(1, 31).boxed().toList();

        List<InatTaxon> taxa = new InatClient(transport, INDIA).taxa(ids);

        assertThat(taxa).hasSize(31);
        assertThat(transport.requests()).hasSize(2);
        assertThat(idsIn(transport.requests().get(0))).hasSize(30);
        assertThat(idsIn(transport.requests().get(1))).hasSize(1);
        assertThat(transport.requests().get(0)).contains("locale=en").contains("preferred_place_id=6681");
    }

    @Test
    void readsWhereARetiredTaxonWent() throws Exception {
        FakeTransport transport = new FakeTransport().respond("/v2/taxa/", """
                {"results":[{"id":9,"name":"Old name","rank":"species","is_active":false,
                  "ancestor_ids":[9],"current_synonymous_taxon_ids":[10,11]}]}""");

        InatTaxon retired = new InatClient(transport, INDIA).taxa(List.of(9L)).get(0);

        assertThat(retired.active()).isFalse();
        assertThat(retired.currentSynonymousIds()).containsExactly(10L, 11L);
    }

    @Test
    void collectsSynonymsFromInvalidScientificNames() throws Exception {
        FakeTransport transport = new FakeTransport().respond("/v1/taxa/", """
                {"results":[{"id":146903,"name":"Arhopala centaurus","names":[
                  {"name":"Arhopala centaurus","lexicon":"scientific-names","is_valid":true},
                  {"name":"Centaur Oakblue","lexicon":"english","is_valid":true},
                  {"name":"Arhopala pseudocentaurus","lexicon":"scientific-names","is_valid":false},
                  {"name":"Arhopala pseudocentaurus","lexicon":"scientific-names","is_valid":false},
                  {"name":"Dull Oakblue","lexicon":"english","is_valid":false}]}]}""");

        Map<Long, List<String>> synonyms = new InatClient(transport, INDIA).synonyms(List.of(146903L));

        assertThat(synonyms).containsExactly(Map.entry(146903L, List.of("Arhopala pseudocentaurus")));
        assertThat(transport.requests().get(0)).contains("/v1/taxa/146903?all_names=true");
    }

    private static String[] idsIn(String url) {
        String path = java.net.URI.create(url).getPath();
        return path.substring("/v2/taxa/".length()).split(",");
    }

    @Test
    void noTaxaMeansNoRequests() throws Exception {
        FakeTransport transport = new FakeTransport();

        assertThat(new InatClient(transport, INDIA).taxa(List.of())).isEmpty();
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    void speciesListWithoutTotalFails() {
        FakeTransport transport = new FakeTransport().respond("species_counts", "{\"results\":[]}");

        assertThatThrownBy(() -> new InatClient(transport, INDIA).speciesInPlace(INDIA, BUTTERFLIES, Quality.RESEARCH))
                .isInstanceOf(IOException.class).hasMessageContaining("no total_results");
    }

    @Test
    void speciesListThatEndsEarlyFails() {
        FakeTransport transport = new FakeTransport()
                .respond("&page=1&", """
                        {"total_results":3,"results":[
                          {"taxon":{"id":1,"name":"A b","rank":"species","is_active":true,"ancestor_ids":[1]}}]}""")
                .respond("&page=2&", "{\"total_results\":3,\"results\":[]}");

        assertThatThrownBy(() -> new InatClient(transport, INDIA).speciesInPlace(INDIA, BUTTERFLIES, Quality.RESEARCH))
                .isInstanceOf(IOException.class).hasMessage("iNaturalist returned 1 of 3 species");
    }

    @Test
    void missingTaxaFail() {
        FakeTransport transport = new FakeTransport().respond("/v2/taxa/", """
                {"results":[{"id":1,"name":"A","rank":"genus","is_active":true,"ancestor_ids":[1]}]}""");

        assertThatThrownBy(() -> new InatClient(transport, INDIA).taxa(List.of(1L, 2L)))
                .isInstanceOf(IOException.class).hasMessage("iNaturalist did not return taxa: [2]");
    }

    @Test
    void missingSynonymEntriesFail() {
        FakeTransport transport = new FakeTransport().respond("/v1/taxa/", """
                {"results":[{"id":1,"name":"A","names":[]}]}""");

        assertThatThrownBy(() -> new InatClient(transport, INDIA).synonyms(List.of(1L, 2L)))
                .isInstanceOf(IOException.class).hasMessage("iNaturalist did not return names for taxa: [2]");
    }

    @Test
    void taxonWithoutIdOrNameFails() {
        FakeTransport transport = new FakeTransport().respond("/v2/taxa/", """
                {"results":[{"id":1,"rank":"genus"}]}""");

        assertThatThrownBy(() -> new InatClient(transport, INDIA).taxa(List.of(1L)))
                .isInstanceOf(IOException.class).hasMessage("iNaturalist returned a taxon without id or name");
    }
}
