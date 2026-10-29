import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import OtherNames from './OtherNames.svelte';
import { addSearchWord, removeSearchWord } from './api.js';

vi.mock('./api.js', () => ({ addSearchWord: vi.fn(), removeSearchWord: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

const field = () => screen.getByLabelText('New other name');
const addButton = () => screen.getByRole('button', { name: '+ Add a name' });

test('with no names only + Add a name shows', () => {
  const { container } = render(OtherNames, { taxonId: 'A5', words: [] });

  expect(addButton()).toBeTruthy();
  expect(container.textContent).not.toContain('Other names:');
  expect(screen.queryByLabelText('New other name')).toBeNull();
});

test('saved names show as chips that can be removed, without opening anything', () => {
  const { container } = render(OtherNames, { taxonId: 'A5', words: ['Kaner', 'Rui tiger'] });

  expect(container.textContent).toContain('Other names:');
  expect(screen.getByRole('button', { name: 'Remove Kaner' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Remove Rui tiger' })).toBeTruthy();
  expect(addButton()).toBeTruthy();
});

test('+ Add a name opens the field in place, focused, with the hint', async () => {
  const { container } = render(OtherNames, { taxonId: 'A5', words: [] });

  await fireEvent.click(addButton());

  await vi.waitFor(() => expect(document.activeElement).toBe(field()));
  expect(screen.queryByRole('button', { name: '+ Add a name' })).toBeNull();
  expect(container.textContent).toContain('Search finds this species by these names too, such as a local name or a nickname.');
  expect(screen.getByRole('button', { name: 'Add' }).disabled).toBe(true);
});

test('adds a name and keeps the field open for another', async () => {
  addSearchWord.mockResolvedValue(['Kaner']);
  render(OtherNames, { taxonId: 'A5', words: [] });

  await fireEvent.click(addButton());
  await fireEvent.input(field(), { target: { value: ' Kaner ' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  expect(addSearchWord).toHaveBeenCalledWith('A5', 'Kaner');
  expect(await screen.findByRole('button', { name: 'Remove Kaner' })).toBeTruthy();
  expect(field().value).toBe('');
  await vi.waitFor(() => expect(document.activeElement).toBe(field()));
});

test('Cancel and Escape close the field and return focus to + Add a name', async () => {
  render(OtherNames, { taxonId: 'A5', words: [] });

  await fireEvent.click(addButton());
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByLabelText('New other name')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(addButton()));

  await fireEvent.click(addButton());
  await vi.waitFor(() => expect(document.activeElement).toBe(field()));
  await fireEvent.keyDown(field(), { key: 'Escape' });
  expect(screen.queryByLabelText('New other name')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(addButton()));
});

test('removes a name and moves focus to the next one', async () => {
  removeSearchWord.mockResolvedValue(['Rui tiger']);
  render(OtherNames, { taxonId: 'A5', words: ['Kaner', 'Rui tiger'] });

  await fireEvent.click(screen.getByRole('button', { name: 'Remove Kaner' }));

  expect(removeSearchWord).toHaveBeenCalledWith('A5', 'Kaner');
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'Remove Kaner' })).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Remove Rui tiger' })));
});

test('removing the last name moves focus to + Add a name', async () => {
  removeSearchWord.mockResolvedValue([]);
  render(OtherNames, { taxonId: 'A5', words: ['Kaner'] });

  await fireEvent.click(screen.getByRole('button', { name: 'Remove Kaner' }));

  await vi.waitFor(() => expect(document.activeElement).toBe(addButton()));
});

test('shows why a name was refused and keeps it in the field', async () => {
  addSearchWord.mockRejectedValue(new Error('The library could not be saved.'));
  render(OtherNames, { taxonId: 'A5', words: [] });

  await fireEvent.click(addButton());
  await fireEvent.input(field(), { target: { value: 'Kaner' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  expect((await screen.findByRole('alert')).textContent).toBe('The library could not be saved.');
  expect(field().value).toBe('Kaner');
  await fireEvent.input(field(), { target: { value: 'Kane' } });
  expect(screen.queryByRole('alert')).toBeNull();
});

test('a name the species already has says so and stays in the field', async () => {
  addSearchWord.mockResolvedValue(['Kaner']);
  render(OtherNames, { taxonId: 'A5', words: ['Kaner'] });

  await fireEvent.click(addButton());
  await fireEvent.input(field(), { target: { value: 'kaner' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe('“kaner” is already a name of this species.'));
  expect(field().value).toBe('kaner');
  expect(screen.queryByRole('alert')).toBeNull();
});

test('an answer that comes after a newer one is ignored', async () => {
  let first;
  addSearchWord.mockReturnValueOnce(new Promise((resolve) => (first = resolve))).mockResolvedValueOnce(['Rui tiger']);
  render(OtherNames, { taxonId: 'A5', words: [] });

  await fireEvent.click(addButton());
  await fireEvent.input(field(), { target: { value: 'Kaner' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));
  await fireEvent.input(field(), { target: { value: 'Rui tiger' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));
  await screen.findByRole('button', { name: 'Remove Rui tiger' });
  first(['Kaner']);
  await new Promise((resolve) => setTimeout(resolve));

  expect(screen.queryByRole('button', { name: 'Remove Kaner' })).toBeNull();
});
