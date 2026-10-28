package com.butterflyatlas.importer.bundle;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** One taxon in the bundle. {@code parent} is null for families. */
@JsonPropertyOrder({"id", "parent", "rank", "name", "common", "inatId", "synonyms"})
public record BundleTaxon(String id, String parent, String rank, String name,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String common,
                          long inatId, List<String> synonyms) {

    public BundleTaxon {
        synonyms = synonyms == null ? List.of() : synonyms;
    }
}
