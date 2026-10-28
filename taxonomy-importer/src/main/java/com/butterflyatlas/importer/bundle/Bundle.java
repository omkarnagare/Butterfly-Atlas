package com.butterflyatlas.importer.bundle;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The taxonomy file bundled with the app. See docs/PLAN.md §5.2. */
@JsonPropertyOrder({"format", "version", "basedOn", "generatedAt", "ranks", "region", "group", "source", "taxa",
        "changes"})
public record Bundle(int format, String version, String basedOn, String generatedAt, List<String> ranks,
                     Region region, Group group, Source source, List<BundleTaxon> taxa, List<Change> changes) {

    public static final int FORMAT = 1;

    /**
     * @param basedOn version of the previous bundle the change list is relative to; null for the first bundle
     * @param ranks   the taxon ranks in use, top-down
     */
    public Bundle {
        ranks = ranks == null ? List.of() : ranks;
        taxa = taxa == null ? List.of() : taxa;
        changes = changes == null ? List.of() : changes;
    }

    public record Region(String name, long inatPlaceId) {
    }

    public record Group(String key, String name, long inatTaxonId) {
    }

    @JsonPropertyOrder({"name", "url", "quality", "note"})
    public record Source(String name, String url, String quality, String note) {
    }
}
