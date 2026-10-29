import { render, screen } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import TaxonHeader from './TaxonHeader.svelte';

const taxon = (over) => ({
  id: 'A2', rank: 'family', name: 'Nymphalidae', common: 'Brush-footed butterflies', family: 'Nymphalidae', active: true,
  path: [{ id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null }], species: { got: 1, total: 3 },
  synonyms: [], searchWords: ['Brush-foots'], folder: null, ...over,
});

test.each(['family', 'subfamily', 'tribe', 'genus'])('a %s page has no search words or other names', (rank) => {
  const { container } = render(TaxonHeader, { taxon: taxon({ rank }) });

  expect(screen.queryByRole('button', { name: /Search words|Add a name/ })).toBeNull();
  expect(container.textContent).not.toContain('Brush-foots');
  expect(container.textContent).not.toContain('Other names');
});
