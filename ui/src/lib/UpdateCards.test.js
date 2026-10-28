import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import UpdateCards from './UpdateCards.svelte';
import { declineCustomMatch, dismissTaxonomyUpdate, getCustomMatches, getSplits, getStatus, mergeCustomMatch, retryUpdate } from './api.js';
import { refreshStatus, resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({
  getSplits: vi.fn(), getCustomMatches: vi.fn(), getStatus: vi.fn(), retryUpdate: vi.fn(), mergeCustomMatch: vi.fn(), declineCustomMatch: vi.fn(),
  dismissTaxonomyUpdate: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  getStatus.mockImplementation(() => new Promise(() => {}));
});

const brief = (over = {}) => ({ state: 'pending', kind: 'bundle', later: true, done: 0, total: 0, step: 0, steps: 0, continuing: false, waitingFor: null, notice: null, ...over });
const split = (id, name, common, photos) => ({ id, name, common, family: 'Nymphalidae', photos, version: '2026-12-01', targets: [] });
const match = (customId, name, photos, officialId) => ({
  custom: { id: customId, name, common: null, photos }, official: { id: officialId, name, common: null, family: 'Nymphalidae' }, offeredVersion: '2026-12-01',
});

test('nothing to show: no cards, and nothing is asked', () => {
  const { container } = render(UpdateCards);
  expect(container.querySelector('section')).toBeNull();
  expect(getSplits).not.toHaveBeenCalled();
  expect(getCustomMatches).not.toHaveBeenCalled();
});

test('an update put off with Later: "A taxonomy update is ready" with Review', () => {
  status.taxonomyUpdate = brief();
  render(UpdateCards);
  const card = screen.getByRole('region', { name: 'Taxonomy update' });
  expect(card.textContent).toContain('A taxonomy update is ready.');
  expect(within(card).getByRole('link', { name: 'Review' }).getAttribute('href')).toBe('#/update');
});

test('a paused update: its notice and Retry', async () => {
  status.taxonomyUpdate = brief({ state: 'paused', later: false, notice: { code: 'drive_gone', message: "The drive with your library can't be found. Reconnect it, then choose Retry." } });
  retryUpdate.mockResolvedValue({ state: 'running', kind: 'bundle', progress: { step: 1, steps: 1, stage: 'moves', done: 5, total: 9 } });
  render(UpdateCards);
  const card = screen.getByRole('region', { name: 'Paused taxonomy update' });
  expect(card.textContent).toContain('Taxonomy update paused');
  expect(card.textContent).toContain("The drive with your library can't be found. Reconnect it, then choose Retry.");
  await fireEvent.click(within(card).getByRole('button', { name: 'Retry' }));
  expect(retryUpdate).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Paused taxonomy update' })).toBeNull());
});

test('one split to sort links to it; several are listed, each with its link; read again when the count changes', async () => {
  getSplits.mockResolvedValueOnce([split('A5', 'Danaus chrysippus', 'Plain Tiger', 28)])
    .mockResolvedValueOnce([split('A5', 'Danaus chrysippus', 'Plain Tiger', 28), split('A7', 'Euploea core', null, 1)]);
  status.splitsToSort = 1;
  render(UpdateCards);
  const card = await screen.findByRole('region', { name: 'Split species to sort' });
  expect(card.textContent.replace(/\s+/g, ' ')).toContain('1 split species has photos to sort: Danaus chrysippus (Plain Tiger), 28 photos.');
  expect(within(card).getByRole('link', { name: 'Sort photos' }).getAttribute('href')).toBe('#/split/A5');

  status.splitsToSort = 2;
  await vi.waitFor(() => expect(screen.getByRole('region', { name: 'Split species to sort' }).textContent).toContain('2 split species have photos to sort.'));
  const links = within(screen.getByRole('region', { name: 'Split species to sort' })).getAllByRole('link');
  expect(links.map((a) => [a.getAttribute('aria-label'), a.getAttribute('href')])).toEqual([
    ['Sort photos of Danaus chrysippus', '#/split/A5'], ['Sort photos of Euploea core', '#/split/A7'],
  ]);
  expect(links[1].closest('li').textContent).toContain('1 photo to sort');

  status.splitsToSort = 0;
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Split species to sort' })).toBeNull());
  expect(getSplits).toHaveBeenCalledTimes(2);
});

test('each open match with Merge and Not now; a decided one goes and the status is read again', async () => {
  getCustomMatches.mockResolvedValue([match('U3', 'Neptis sankara', 4, 'A90'), match('U4', 'Neptis soma', 2, 'A91')]);
  declineCustomMatch.mockResolvedValue(null);
  status.customMatches = 2;
  render(UpdateCards);
  const card = await screen.findByRole('region', { name: 'Custom species now in the list' });
  const prompts = await within(card).findAllByRole('group');
  expect(prompts.map((p) => p.getAttribute('aria-label'))).toEqual([
    'Custom species Neptis sankara matches an official one', 'Custom species Neptis soma matches an official one',
  ]);
  await fireEvent.click(within(prompts[0]).getByRole('button', { name: 'Not now' }));
  await vi.waitFor(() => expect(within(card).getAllByRole('group')).toHaveLength(1));
  expect(getStatus).toHaveBeenCalled();
  expect(mergeCustomMatch).not.toHaveBeenCalled();
});

test('splits and matches that cannot be read keep their cards: the count, why, and Try again', async () => {
  getSplits.mockRejectedValueOnce(new Error('offline')).mockResolvedValue([split('A5', 'Danaus chrysippus', 'Plain Tiger', 28), split('A7', 'Euploea core', null, 1)]);
  getCustomMatches.mockRejectedValueOnce(new Error('offline')).mockResolvedValue([match('U3', 'Neptis sankara', 4, 'A90')]);
  status.splitsToSort = 2;
  status.customMatches = 1;
  render(UpdateCards);
  const splits = await screen.findByRole('region', { name: 'Split species to sort' });
  await vi.waitFor(() => expect(splits.textContent).toContain("2 split species have photos to sort — the list couldn't be loaded."));
  const matches = screen.getByRole('region', { name: 'Custom species now in the list' });
  await vi.waitFor(() => expect(matches.textContent).toContain("1 custom species now matches an official one — the list couldn't be loaded."));

  await fireEvent.click(within(splits).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(screen.getByRole('region', { name: 'Split species to sort' }).textContent).toContain('2 split species have photos to sort.'));
  await fireEvent.click(within(screen.getByRole('region', { name: 'Custom species now in the list' })).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(screen.getAllByRole('group', { name: /matches an official one/ })).toHaveLength(1));
  expect(getSplits).toHaveBeenCalledTimes(2);
  expect(getCustomMatches).toHaveBeenCalledTimes(2);
});

test('Home\'s paused card shows a notice of a code it does not know as sent, with Retry', () => {
  status.taxonomyUpdate = brief({ state: 'paused', later: false, notice: { code: 'something_new', message: 'Something new happened. Choose Retry to try again.' } });
  render(UpdateCards);
  const card = screen.getByRole('region', { name: 'Paused taxonomy update' });
  expect(card.textContent).toContain('Something new happened. Choose Retry to try again.');
  expect(within(card).getByRole('button', { name: 'Retry' })).toBeTruthy();
});

test('a library whose taxonomy this copy does not have: a card like the others, its title and the notice', () => {
  const message = 'This library was last used with a newer version of Butterfly Atlas (its species list is from 2027-02-01). '
    + 'Your photos and folders are safe and everything else works as usual. Install that version or a newer one to keep the species list up to date.';
  status.taxonomyUpdate = brief({ state: 'unavailable', later: false, notice: { code: 'unavailable', message } });
  const { unmount } = render(UpdateCards);
  const card = screen.getByRole('region', { name: "Taxonomy updates can't be applied" });
  expect(card.className).toBe('stop-card');
  expect(card.querySelector('b').textContent).toBe("Taxonomy updates can't be applied");
  expect(card.textContent).toContain(message);
  unmount();

  // without a notice it still says so
  status.taxonomyUpdate = brief({ state: 'unavailable', later: false, notice: null });
  render(UpdateCards);
  expect(screen.getByRole('region', { name: "Taxonomy updates can't be applied" }).textContent)
    .toContain("This library's species list is not in this copy of Butterfly Atlas.");
});

test('an update done in this run: "The taxonomy was updated" with See what changed, until Dismiss', async () => {
  status.taxonomyUpdate = brief({ state: 'done', later: false });
  const { unmount } = render(UpdateCards);
  const card = screen.getByRole('region', { name: 'Taxonomy updated' });
  expect(card.textContent).toContain('The taxonomy was updated.');
  expect(within(card).getByRole('link', { name: 'See what changed' }).getAttribute('href')).toBe('#/update');
  dismissTaxonomyUpdate.mockResolvedValue(undefined);
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Taxonomy updated' })).toBeNull());
  expect(dismissTaxonomyUpdate).toHaveBeenCalledTimes(1);
  unmount();
  // dismissed for the run: Home opened again does not show it
  render(UpdateCards);
  expect(screen.queryByRole('region', { name: 'Taxonomy updated' })).toBeNull();
});

test('a Dismiss the library holds: after a page reload (or a restart) the status says so and the card stays away', () => {
  status.taxonomyUpdate = brief({ state: 'done', later: false, dismissed: true });
  render(UpdateCards);
  expect(screen.queryByRole('region', { name: 'Taxonomy updated' })).toBeNull();
});

test('a Dismiss that could not be saved keeps the card and says why', async () => {
  status.taxonomyUpdate = brief({ state: 'done', later: false });
  dismissTaxonomyUpdate.mockRejectedValue(new Error('The drive with your library is full. Free some space, then try again.'));
  render(UpdateCards);
  const card = screen.getByRole('region', { name: 'Taxonomy updated' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  expect((await within(card).findByRole('alert')).textContent)
    .toBe("Dismiss couldn't be saved: The drive with your library is full. Free some space, then try again.");
  expect(screen.getByRole('region', { name: 'Taxonomy updated' })).toBe(card);
});

test('a merge done in this run reads as one', () => {
  status.taxonomyUpdate = brief({ state: 'done', kind: 'match', later: false });
  render(UpdateCards);
  expect(screen.getByRole('region', { name: 'Custom species merged' }).textContent).toContain('A custom species was merged.');
});

test('Dismiss sends focus to the page; a later finish in the same run shows the card again', async () => {
  const main = document.createElement('main');
  main.tabIndex = -1;
  document.body.append(main);
  status.taxonomyUpdate = brief({ state: 'done', later: false });
  dismissTaxonomyUpdate.mockResolvedValue(undefined);
  render(UpdateCards);
  await fireEvent.click(within(screen.getByRole('region', { name: 'Taxonomy updated' })).getByRole('button', { name: 'Dismiss' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(main));

  getStatus.mockResolvedValueOnce({ taxonomyUpdate: brief({ state: 'running', kind: 'match', later: false }) })
    .mockResolvedValueOnce({ taxonomyUpdate: brief({ state: 'done', kind: 'match', later: false }) });
  await refreshStatus();
  await refreshStatus();
  expect(await screen.findByRole('region', { name: 'Custom species merged' })).toBeTruthy();
  main.remove();
});

test('Try again that works moves focus to the card that now lists them', async () => {
  getSplits.mockRejectedValueOnce(new Error('offline')).mockResolvedValue([split('A5', 'Danaus chrysippus', 'Plain Tiger', 28)]);
  status.splitsToSort = 1;
  render(UpdateCards);
  const again = await screen.findByRole('button', { name: 'Try again' });
  again.focus();
  await fireEvent.click(again);
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('region', { name: 'Split species to sort' })));
  expect(document.activeElement.textContent).toContain('1 split species has photos to sort');
});

test('a merge finished while a bundle update is pending: its card beside "A taxonomy update is ready."', () => {
  status.taxonomyUpdate = brief({ state: 'pending', later: true });
  status.finished = { kind: 'match' };
  render(UpdateCards);
  expect(screen.getByRole('region', { name: 'Custom species merged' }).textContent).toContain('A custom species was merged.');
  expect(screen.getByRole('region', { name: 'Taxonomy update' }).textContent).toContain('A taxonomy update is ready.');
});
