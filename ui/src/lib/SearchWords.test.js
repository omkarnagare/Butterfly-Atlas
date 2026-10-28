import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import SearchWords from './SearchWords.svelte';
import { addSearchWord, removeSearchWord } from './api.js';

vi.mock('./api.js', () => ({ addSearchWord: vi.fn(), removeSearchWord: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

test('adds a word', async () => {
  addSearchWord.mockResolvedValue(['Kaner']);
  render(SearchWords, { taxonId: 'A5', words: [] });

  await fireEvent.click(screen.getByRole('button', { name: /Search words/ }));
  await fireEvent.input(screen.getByLabelText('New search word'), { target: { value: ' Kaner ' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  expect(addSearchWord).toHaveBeenCalledWith('A5', 'Kaner');
  expect(await screen.findByText('Kaner')).toBeTruthy();
});

test('removes a word', async () => {
  removeSearchWord.mockResolvedValue([]);
  render(SearchWords, { taxonId: 'A5', words: ['Kaner'] });

  await fireEvent.click(screen.getByRole('button', { name: /Search words/ }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Kaner' }));

  expect(removeSearchWord).toHaveBeenCalledWith('A5', 'Kaner');
  await vi.waitFor(() => expect(screen.queryByText('Kaner')).toBeNull());
});

test('shows why a word was refused', async () => {
  addSearchWord.mockRejectedValue(new Error('Keep search words to 80 characters or fewer.'));
  render(SearchWords, { taxonId: 'A5', words: [] });

  await fireEvent.click(screen.getByRole('button', { name: /Search words/ }));
  await fireEvent.input(screen.getByLabelText('New search word'), { target: { value: 'x' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  expect((await screen.findByRole('alert')).textContent).toBe('Keep search words to 80 characters or fewer.');
});

test('moves focus into the field when the panel opens', async () => {
  render(SearchWords, { taxonId: 'A5', words: [] });

  await fireEvent.click(screen.getByRole('button', { name: /Search words/ }));

  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('New search word')));
});

test('keepsFocusInThePanelAfterRemovingTheLastWord', async () => {
  removeSearchWord.mockResolvedValue([]);
  render(SearchWords, { taxonId: 'A5', words: ['Kaner'] });

  await fireEvent.click(screen.getByRole('button', { name: /Search words/ }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Kaner' }));

  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('New search word')));
});
