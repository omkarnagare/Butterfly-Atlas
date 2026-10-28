/**
 * How a taxon is titled (Phase 2 design, D2). The scientific name is always shown; italics only
 * for genus and species names, the taxonomic convention.
 */
export function names(taxon) {
  const { rank, name, common } = taxon;
  if (rank === 'species') {
    return common
      ? { title: common, titleLatin: false, sub: name, subLatin: true }
      : { title: name, titleLatin: true, sub: null, subLatin: false };
  }
  return { title: name, titleLatin: rank === 'genus', sub: common || null, subLatin: false };
}

export const RANK_LABELS = {
  group: 'Group', additions: 'Custom additions', family: 'Family', subfamily: 'Subfamily', tribe: 'Tribe',
  genus: 'Genus', species: 'Species',
};

export const RANK_PLURALS = {
  family: 'Families', subfamily: 'Subfamilies', tribe: 'Tribes', genus: 'Genera', species: 'Species',
};
