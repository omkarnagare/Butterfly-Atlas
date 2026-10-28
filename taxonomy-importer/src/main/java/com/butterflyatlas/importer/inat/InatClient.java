package com.butterflyatlas.importer.inat;

import com.butterflyatlas.importer.Quality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads taxonomy from iNaturalist: v2 for the species list and taxa, v1 for name lists
 * (v2 does not return synonyms).
 */
public final class InatClient {

    static final String V1 = "https://api.inaturalist.org/v1/";
    static final String V2 = "https://api.inaturalist.org/v2/";
    static final int PAGE_SIZE = 500;
    static final int BATCH_SIZE = 30;
    private static final String TAXON_FIELDS = "(id:!t,name:!t,rank:!t,is_active:!t,ancestor_ids:!t,"
            + "preferred_common_name:!t,current_synonymous_taxon_ids:!t)";

    private final Transport transport;
    private final long preferredPlaceId;
    private final ObjectMapper json = new ObjectMapper();

    /** @param preferredPlaceId place whose regional common names are preferred (6681 = India) */
    public InatClient(Transport transport, long preferredPlaceId) {
        this.transport = transport;
        this.preferredPlaceId = preferredPlaceId;
    }

    /** Every species of {@code taxonId} observed in {@code placeId}. */
    public List<InatTaxon> speciesInPlace(long placeId, long taxonId, Quality quality) throws IOException {
        List<InatTaxon> species = new ArrayList<>();
        for (int page = 1; ; page++) {
            String url = V2 + "observations/species_counts?place_id=" + placeId + "&taxon_id=" + taxonId
                    + "&verifiable=true" + quality.queryParam() + "&" + nameParams()
                    + "&per_page=" + PAGE_SIZE + "&page=" + page + "&fields=(count:!t,taxon:" + TAXON_FIELDS + ")";
            JsonNode root = read(url);
            if (!root.hasNonNull("total_results")) {
                throw new IOException("iNaturalist species list response has no total_results: " + url);
            }
            int total = root.get("total_results").asInt();
            JsonNode results = root.path("results");
            for (JsonNode result : results) {
                species.add(taxon(result.path("taxon")));
            }
            if (species.size() >= total) {
                return species;
            }
            if (results.isEmpty()) {
                throw new IOException("iNaturalist returned " + species.size() + " of " + total + " species");
            }
        }
    }

    /** The given taxa (fetched {@value #BATCH_SIZE} at a time). */
    public List<InatTaxon> taxa(Collection<Long> ids) throws IOException {
        List<InatTaxon> taxa = new ArrayList<>();
        for (List<Long> batch : batches(ids)) {
            JsonNode root = read(V2 + "taxa/" + join(batch) + "?" + nameParams() + "&fields=" + TAXON_FIELDS);
            List<Long> returned = new ArrayList<>();
            for (JsonNode node : root.path("results")) {
                InatTaxon taxon = taxon(node);
                returned.add(taxon.id());
                taxa.add(taxon);
            }
            List<Long> missing = batch.stream().filter(id -> !returned.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new IOException("iNaturalist did not return taxa: " + missing);
            }
        }
        return taxa;
    }

    /** Former scientific names per taxon id, in iNaturalist's order, without duplicates. */
    public Map<Long, List<String>> synonyms(Collection<Long> ids) throws IOException {
        Map<Long, List<String>> synonyms = new LinkedHashMap<>();
        for (List<Long> batch : batches(ids)) {
            JsonNode root = read(V1 + "taxa/" + join(batch) + "?all_names=true");
            for (JsonNode taxon : root.path("results")) {
                String accepted = taxon.path("name").asText();
                List<String> names = new ArrayList<>();
                for (JsonNode name : taxon.path("names")) {
                    String text = name.path("name").asText();
                    boolean formerScientificName = "scientific-names".equals(name.path("lexicon").asText())
                            && !name.path("is_valid").asBoolean(true);
                    if (formerScientificName && !text.equals(accepted) && !names.contains(text)) {
                        names.add(text);
                    }
                }
                synonyms.put(taxon.path("id").asLong(), List.copyOf(names));
            }
            List<Long> missing = batch.stream().filter(id -> !synonyms.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                throw new IOException("iNaturalist did not return names for taxa: " + missing);
            }
        }
        return synonyms;
    }

    private String nameParams() {
        return "locale=en&preferred_place_id=" + preferredPlaceId;
    }

    private JsonNode read(String url) throws IOException {
        return json.readTree(transport.get(URI.create(url)));
    }

    private static InatTaxon taxon(JsonNode node) throws IOException {
        if (node.path("id").asLong() <= 0 || node.path("name").asText().isBlank()) {
            throw new IOException("iNaturalist returned a taxon without id or name");
        }
        return new InatTaxon(
                node.path("id").asLong(),
                node.path("name").asText(),
                node.path("rank").asText(),
                node.hasNonNull("preferred_common_name") ? node.get("preferred_common_name").asText() : null,
                longs(node.path("ancestor_ids")),
                node.path("is_active").asBoolean(true),
                longs(node.path("current_synonymous_taxon_ids")));
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asLong());
        }
        return List.copyOf(values);
    }

    private static List<List<Long>> batches(Collection<Long> ids) {
        List<Long> sorted = ids.stream().distinct().sorted().toList();
        List<List<Long>> batches = new ArrayList<>();
        for (int start = 0; start < sorted.size(); start += BATCH_SIZE) {
            batches.add(sorted.subList(start, Math.min(start + BATCH_SIZE, sorted.size())));
        }
        return batches;
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
