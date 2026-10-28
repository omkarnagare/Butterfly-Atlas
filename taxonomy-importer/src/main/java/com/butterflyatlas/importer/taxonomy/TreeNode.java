package com.butterflyatlas.importer.taxonomy;

import java.util.List;

/** One kept taxon, identified by its iNaturalist id. {@code parentInatId} is null for families. */
public record TreeNode(long inatId, Long parentInatId, String rank, String name, String commonName,
                       List<String> synonyms) {
}
