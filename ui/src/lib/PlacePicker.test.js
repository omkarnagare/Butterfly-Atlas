import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import PlacePicker from './PlacePicker.svelte';
import { addPlace, getMyPlaces, getRecentPlaces, searchPlaces } from './api.js';

vi.mock('./api.js', () => ({
  addPlace: vi.fn(), deletePlace: vi.fn(), getMyPlaces: vi.fn(), getRecentPlaces: vi.fn(), renamePlace: vi.fn(), searchPlaces: vi.fn(),
}));

const place = (id, path, level, mine = false) => ({ id, name: path[0], path, level, mine });
const pune = place('P414', ['Pune', 'Maharashtra', 'India'], 'district');
const punjab = place('P31', ['Punjab', 'India'], 'state');
const tamhini = place('PU3', ['Tamhini', 'Maharashtra', 'India'], 'district', true);
const maharashtra = place('P20', ['Maharashtra', 'India'], 'state');
const kenya = place('P900', ['Kenya'], 'country');

beforeEach(() => {
  vi.resetAllMocks();
  getRecentPlaces.mockResolvedValue([tamhini, pune]);
  getMyPlaces.mockResolvedValue([{ ...tamhini, photos: 2 }]);
});

const field = () => screen.getByRole('combobox', { name: 'Place' });

test('opens on the recent places with nothing chosen; arrows and Enter choose one', async () => {
  const onChoose = vi.fn();
  render(PlacePicker, { count: 3, onChoose, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Place for 3 photos' })).toBeTruthy();
  expect(document.activeElement).toBe(field());
  expect(field().getAttribute('placeholder')).toBe('Type a district, state or country');
  const list = await screen.findByRole('listbox', { name: 'Recent' });
  const options = screen.getAllByRole('option');
  expect(options.map((o) => o.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Tamhini · Maharashtra, India district added by you', 'Pune · Maharashtra, India district',
  ]);
  expect(list.querySelector('[aria-selected="true"]')).toBeNull();
  await fireEvent.keyDown(field(), { key: 'Enter' });
  expect(onChoose).not.toHaveBeenCalled();

  await fireEvent.keyDown(field(), { key: 'ArrowDown' });
  await fireEvent.keyDown(field(), { key: 'ArrowDown' });
  await fireEvent.keyDown(field(), { key: 'ArrowUp' });
  expect(field().getAttribute('aria-activedescendant')).toBe(options[0].id);
  await fireEvent.keyDown(field(), { key: 'Enter' });
  expect(onChoose).toHaveBeenCalledWith(tamhini);
});

test('one photo has its own title, and no Recent list shows when there is none', async () => {
  getRecentPlaces.mockResolvedValue([]);
  render(PlacePicker, { count: 1, onChoose: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('dialog', { name: 'Place for this photo' })).toBeTruthy();
  await vi.waitFor(() => expect(getRecentPlaces).toHaveBeenCalled());
  expect(screen.queryByText('Recent')).toBeNull();
  expect(screen.queryAllByRole('option')).toHaveLength(0);
  const list = screen.getByRole('listbox', { name: 'Places' });
  expect(list.getAttribute('aria-labelledby')).toBeNull();
});

test('typing searches after a pause; the first result is chosen with Enter, a click chooses any', async () => {
  searchPlaces.mockResolvedValue([pune, punjab, kenya]);
  const onChoose = vi.fn();
  render(PlacePicker, { count: 2, onChoose, onClose: vi.fn() });

  await fireEvent.input(field(), { target: { value: 'p' } });
  await fireEvent.input(field(), { target: { value: 'pun' } });
  expect(searchPlaces).not.toHaveBeenCalled();
  const first = await screen.findByRole('option', { name: /^Pune/ });
  expect(searchPlaces).toHaveBeenCalledOnce();
  expect(searchPlaces).toHaveBeenCalledWith('pun', expect.objectContaining({ signal: expect.any(AbortSignal) }));
  expect(screen.getByRole('listbox', { name: 'Places' })).toBeTruthy();
  expect(screen.getAllByRole('option').map((o) => o.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Pune · Maharashtra, India district', 'Punjab · India state', 'Kenya country', 'Add “pun” as a new place…',
  ]);
  expect(first.getAttribute('aria-selected')).toBe('true');
  expect(screen.getByRole('status').textContent).toBe('3 places');

  await fireEvent.keyDown(field(), { key: 'Enter' });
  expect(onChoose).toHaveBeenLastCalledWith(pune);
  await fireEvent.click(screen.getByRole('option', { name: /^Kenya/ }));
  expect(onChoose).toHaveBeenLastCalledWith(kenya);
});

test('no Add row for an exact name, or for fewer than 2 letters; it says when nothing matches', async () => {
  searchPlaces.mockResolvedValueOnce([pune]).mockResolvedValueOnce([]);
  render(PlacePicker, { onChoose: vi.fn(), onClose: vi.fn() });

  await fireEvent.input(field(), { target: { value: ' PUNE ' } });
  await screen.findByRole('option', { name: /^Pune/ });
  expect(screen.queryByRole('option', { name: /as a new place/ })).toBeNull();

  await fireEvent.input(field(), { target: { value: 'Tamhinii' } });
  expect(await screen.findByText('No place matches “Tamhinii”.')).toBeTruthy();
  expect(screen.getByRole('option', { name: 'Add “Tamhinii” as a new place…' })).toBeTruthy();

  await fireEvent.input(field(), { target: { value: 'T' } });
  await new Promise((resolve) => setTimeout(resolve, 200));
  expect(searchPlaces).toHaveBeenCalledTimes(2);
  expect(screen.queryAllByRole('option')).toHaveLength(0);
});

test('adds a new place inside a state and chooses it', async () => {
  searchPlaces.mockResolvedValueOnce([]).mockResolvedValueOnce([pune, maharashtra, punjab]);
  addPlace.mockResolvedValue(tamhini);
  const onChoose = vi.fn();
  render(PlacePicker, { count: 4, onChoose, onClose: vi.fn() });

  await fireEvent.input(field(), { target: { value: 'Tamhini' } });
  await screen.findByRole('option', { name: 'Add “Tamhini” as a new place…' });
  await fireEvent.keyDown(field(), { key: 'Enter' });

  const name = screen.getByRole('textbox', { name: 'Name' });
  expect(name.value).toBe('Tamhini');
  expect(document.activeElement).toBe(name);
  const inside = screen.getByRole('combobox', { name: 'Inside' });
  await fireEvent.input(inside, { target: { value: 'mah' } });
  const states = await screen.findAllByRole('option');
  expect(searchPlaces).toHaveBeenLastCalledWith('mah', expect.objectContaining({ level: 'state', signal: expect.any(AbortSignal) }));
  expect(states.map((o) => o.textContent.replace(/\s+/g, ' ').trim())).toEqual(['Maharashtra · India', 'Punjab · India']);
  await fireEvent.keyDown(inside, { key: 'Enter' });
  expect(inside.value).toBe('Maharashtra, India');
  expect(screen.queryAllByRole('option')).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: 'Add place' }));

  expect(addPlace).toHaveBeenCalledWith('Tamhini', 'P20');
  await vi.waitFor(() => expect(onChoose).toHaveBeenCalledWith(tamhini));
});

test('the new place form says what is missing or refused, and Escape goes back to the search', async () => {
  searchPlaces.mockResolvedValueOnce([]).mockResolvedValueOnce([maharashtra]).mockResolvedValue([]);
  addPlace.mockRejectedValue(Object.assign(new Error('Pune is already in Maharashtra.'), { code: 'place_exists' }));
  const onClose = vi.fn();
  render(PlacePicker, { onChoose: vi.fn(), onClose });

  await fireEvent.input(field(), { target: { value: 'Pune city' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a new place/ }));
  await fireEvent.click(screen.getByRole('button', { name: 'Add place' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Choose the state it is in.');
  expect(addPlace).not.toHaveBeenCalled();

  await fireEvent.input(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Pune' } });
  const inside = screen.getByRole('combobox', { name: 'Inside' });
  await fireEvent.input(inside, { target: { value: 'maha' } });
  await fireEvent.click(await screen.findByRole('option', { name: /^Maharashtra/ }));
  await fireEvent.click(screen.getByRole('button', { name: 'Add place' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Pune is already in Maharashtra.');

  await fireEvent.keyDown(inside, { key: 'Escape' });
  expect(onClose).not.toHaveBeenCalled();
  expect(document.activeElement).toBe(field());
  expect(field().value).toBe('Pune city');
});

test('Your places… opens the Your places sheet over the picker; Escape closes only the sheet', async () => {
  const onClose = vi.fn();
  const onPlacesChanged = vi.fn();
  render(PlacePicker, { onChoose: vi.fn(), onClose, onPlacesChanged });

  await fireEvent.click(screen.getByRole('button', { name: 'Your places…' }));
  expect(await screen.findByRole('dialog', { name: 'Your places' })).toBeTruthy();
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: 'Your places' })).toBeNull();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Your places…' }));
  expect(onClose).not.toHaveBeenCalled();
  expect(getRecentPlaces).toHaveBeenCalledTimes(2);

  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();
});

test('a refusal while choosing is shown and the picker stays', async () => {
  const onChoose = vi.fn().mockRejectedValue(new Error('That photo is in Recently deleted.'));
  render(PlacePicker, { onChoose, onClose: vi.fn() });
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));
  expect((await screen.findByRole('alert')).textContent).toBe('That photo is in Recently deleted.');
});

test('both fields are list comboboxes', async () => {
  searchPlaces.mockResolvedValue([]);
  render(PlacePicker, { onChoose: vi.fn(), onClose: vi.fn() });
  expect(field().getAttribute('aria-autocomplete')).toBe('list');
  await fireEvent.input(field(), { target: { value: 'Tamhini' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a new place/ }));
  expect(screen.getByRole('combobox', { name: 'Inside' }).getAttribute('aria-autocomplete')).toBe('list');
});

test('ArrowUp with nothing highlighted highlights nothing', async () => {
  const onChoose = vi.fn();
  render(PlacePicker, { onChoose, onClose: vi.fn() });
  await screen.findByRole('option', { name: /^Tamhini/ });
  await fireEvent.keyDown(field(), { key: 'ArrowUp' });
  expect(field().getAttribute('aria-activedescendant')).toBeNull();
  await fireEvent.keyDown(field(), { key: 'Enter' });
  expect(onChoose).not.toHaveBeenCalled();
});

test('a slow answer to older text never replaces the newer results', async () => {
  let answerOld;
  searchPlaces.mockImplementation((text) => (text === 'pun' ? new Promise((resolve) => { answerOld = resolve; }) : Promise.resolve([maharashtra])));
  render(PlacePicker, { onChoose: vi.fn(), onClose: vi.fn() });

  await fireEvent.input(field(), { target: { value: 'pun' } });
  await vi.waitFor(() => expect(answerOld).toBeTypeOf('function'));
  await fireEvent.input(field(), { target: { value: 'maha' } });
  await screen.findByRole('option', { name: /^Maharashtra/ });
  answerOld([pune, punjab]);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(screen.getAllByRole('option').map((o) => o.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Maharashtra · India state', 'Add “maha” as a new place…',
  ]);
});

test('Inside says when no state matches', async () => {
  searchPlaces.mockResolvedValue([]);
  render(PlacePicker, { onChoose: vi.fn(), onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'Tamhini' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a new place/ }));
  await fireEvent.input(screen.getByRole('combobox', { name: 'Inside' }), { target: { value: 'zzq' } });
  expect((await screen.findByText('No state matches “zzq”.')).getAttribute('role')).toBe('status');
  await fireEvent.input(screen.getByRole('combobox', { name: 'Inside' }), { target: { value: 'zzqx' } });
  expect(screen.queryByText(/No state matches/)).toBeNull();
});

test.each(['Back', 'Escape'])('%s while a new place is being added goes back without choosing it', async (how) => {
  searchPlaces.mockResolvedValueOnce([]).mockResolvedValueOnce([maharashtra]).mockResolvedValue([tamhini]);
  let answer;
  addPlace.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
  const onChoose = vi.fn();
  render(PlacePicker, { onChoose, onClose: vi.fn() });

  await fireEvent.input(field(), { target: { value: 'Tamhini' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a new place/ }));
  const inside = screen.getByRole('combobox', { name: 'Inside' });
  await fireEvent.input(inside, { target: { value: 'maha' } });
  await fireEvent.click(await screen.findByRole('option', { name: /^Maharashtra/ }));
  await fireEvent.click(screen.getByRole('button', { name: 'Add place' }));
  expect(addPlace).toHaveBeenCalledWith('Tamhini', 'P20');
  if (how === 'Back') await fireEvent.click(screen.getByRole('button', { name: 'Back' }));
  else await fireEvent.keyDown(inside, { key: 'Escape' });
  expect(document.activeElement).toBe(field());

  answer(tamhini);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(onChoose).not.toHaveBeenCalled();
  // The place was added: the search shows it now instead of offering to add it again.
  expect(await screen.findByRole('option', { name: /^Tamhini/ })).toBeTruthy();
  expect(screen.queryByRole('option', { name: /as a new place/ })).toBeNull();
});

test('a place added but refused when chosen goes back to the search with the refusal shown', async () => {
  searchPlaces.mockResolvedValueOnce([]).mockResolvedValueOnce([maharashtra]).mockResolvedValue([tamhini]);
  addPlace.mockResolvedValue(tamhini);
  const onChoose = vi.fn().mockRejectedValue(new Error('That photo is in Recently deleted.'));
  render(PlacePicker, { onChoose, onClose: vi.fn() });

  await fireEvent.input(field(), { target: { value: 'Tamhini' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a new place/ }));
  await fireEvent.input(screen.getByRole('combobox', { name: 'Inside' }), { target: { value: 'maha' } });
  await fireEvent.click(await screen.findByRole('option', { name: /^Maharashtra/ }));
  await fireEvent.click(screen.getByRole('button', { name: 'Add place' }));

  await vi.waitFor(() => expect(onChoose).toHaveBeenCalledWith(tamhini));
  await vi.waitFor(() => expect(screen.queryByRole('textbox', { name: 'Name' })).toBeNull());
  expect(document.activeElement).toBe(field());
  expect((await screen.findByRole('alert')).textContent).toBe('That photo is in Recently deleted.');
  expect(await screen.findByRole('option', { name: /^Tamhini/ })).toBeTruthy();
});

const formerResults = (places, formerMatches) => Object.assign([...places], { formerMatches });

test('former places that match are lines, not choices: what they became, each choosable from the line (contract §10)', async () => {
  const chittoor = { id: 'P300', name: 'Chittoor (former district)', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], level: 'district', former: true };
  const old = { id: 'P305', name: 'Oldpur (former district)', path: ['Oldpur (former district)', 'Andhra Pradesh', 'India'], level: 'district', former: true };
  const tirupati = place('P301', ['Tirupati', 'Andhra Pradesh', 'India'], 'district');
  const annamayya = place('P302', ['Annamayya', 'Andhra Pradesh', 'India'], 'district');
  searchPlaces.mockResolvedValue(formerResults([], [{ place: chittoor, now: [tirupati, annamayya] }, { place: old, now: [] }]));
  const onChoose = vi.fn();
  render(PlacePicker, { count: 1, onChoose, onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'chit' } });
  const line = await screen.findByText(/Chittoor is a former district/);
  expect(line.closest('p').textContent.replace(/\s+/g, ' ').trim()).toBe('Chittoor is a former district (place names were updated). Choose Tirupati or Annamayya.');
  expect(screen.getByText(/Oldpur is a former district/).closest('p').textContent.replace(/\s+/g, ' ').trim())
    .toBe('Oldpur is a former district (place names were updated) and no longer listed.');
  // not options of the list
  expect(screen.queryAllByRole('option').map((o) => o.textContent)).not.toContainEqual(expect.stringContaining('Chittoor'));
  expect(screen.getByRole('status').textContent).toBe('No current place matches “chit”.');
  // each named with where it is, as the list's rows are
  const button = screen.getByRole('button', { name: 'Annamayya, Andhra Pradesh, India' });
  expect(button.textContent).toBe('Annamayya');
  expect(button.getAttribute('title')).toBe('Annamayya, Andhra Pradesh, India');
  await fireEvent.click(button);
  expect(onChoose).toHaveBeenCalledWith(annamayya);
});

test('a former first-level match outside India is "a former region"', async () => {
  const gorenjska = { id: 'P900', name: 'Gorenjska (former region)', path: ['Gorenjska (former region)', 'Slovenia'], level: 'state', former: true };
  searchPlaces.mockResolvedValue(formerResults([], [{ place: gorenjska, now: [] }]));
  render(PlacePicker, { count: 1, onChoose: vi.fn(), onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'gor' } });
  expect((await screen.findByText(/Gorenjska is a former/)).closest('p').textContent.replace(/\s+/g, ' ').trim())
    .toBe('Gorenjska is a former region (place names were updated) and no longer listed.');
});

test('a former match\'s level comes from its level, not its name', async () => {
  const chittoor = { id: 'P300', name: 'Chittoor', path: ['Chittoor', 'Andhra Pradesh', 'India'], level: 'district', former: true };
  searchPlaces.mockResolvedValue(formerResults([], [{ place: chittoor, now: [] }]));
  render(PlacePicker, { count: 1, onChoose: vi.fn(), onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'chit' } });
  expect((await screen.findByText(/Chittoor is a former/)).textContent).toContain('Chittoor is a former district');
});

test('former matches are said to a screen reader: their lines are in a polite live region that is there before they come', async () => {
  const chittoor = { id: 'P300', name: 'Chittoor (former district)', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], level: 'district', former: true };
  searchPlaces.mockResolvedValue(formerResults([], [{ place: chittoor, now: [] }]));
  const { container } = render(PlacePicker, { count: 1, onChoose: vi.fn(), onClose: vi.fn() });
  const region = container.ownerDocument.querySelector('.pl-formers');
  expect(region.getAttribute('aria-live')).toBe('polite');
  expect(region.textContent.trim()).toBe('');
  await fireEvent.input(field(), { target: { value: 'chit' } });
  await vi.waitFor(() => expect(region.textContent).toContain('Chittoor is a former district'));
  expect(region.isConnected).toBe(true);
});

test('a query matching a former place shows its line before the list, so "Add … as a new place…" comes after it (real app)', async () => {
  const chittoor = { id: 'P300', name: 'Chittoor (former district)', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], level: 'district', former: true };
  const tirupati = place('P301', ['Tirupati', 'Andhra Pradesh', 'India'], 'district');
  searchPlaces.mockResolvedValue(formerResults([], [{ place: chittoor, now: [tirupati] }]));
  render(PlacePicker, { count: 1, onChoose: vi.fn(), onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'Chittoor' } });
  const add = await screen.findByRole('option', { name: /Add “Chittoor” as a new place/ });
  const line = screen.getByText(/Chittoor is a former district/).closest('p');
  expect(line.compareDocumentPosition(add) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
});
