package com.butterflyatlas.importer.inat;

import java.util.List;

/**
 * A taxon as iNaturalist describes it.
 *
 * @param ancestorIds          root first, ending with this taxon's own id
 * @param commonName           regional English common name, or {@code null}
 * @param currentSynonymousIds for a retired taxon, the taxa that replaced it; otherwise empty
 */
public record InatTaxon(long id, String name, String rank, String commonName, List<Long> ancestorIds,
                        boolean active, List<Long> currentSynonymousIds) {
}
