import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import TagNamesNote from './TagNamesNote.svelte';
import { dismissTagNames, getTagNames, getTags } from './api.js';

vi.mock('./api.js', () => ({
  getTagNames: vi.fn(), dismissTagNames: vi.fn(), getTags: vi.fn(), createTag: vi.fn(), renameTag: vi.fn(), deleteTag: vi.fn(),
}));

const empty = { merged: [], numbered: [], long: [], dismissed: false };
const full = {
  merged: [{ from: 'Mud-puddling', into: 'mud_puddling', photos: 12 }, { from: 'Upper-Wing!', into: 'upper_wing', photos: 1 }],
  numbered: [{ from: 'upper-wing', to: 'upper_wing_2' }],
  long: ['mud_puddling_on_wet_sand', 'basking_in_the_sun'],
  dismissed: false,
};

beforeEach(() => {
  vi.resetAllMocks();
  getTags.mockResolvedValue([]);
});

test('nothing to say: no note', async () => {
  getTagNames.mockResolvedValue(empty);
  const { container } = render(TagNamesNote);
  await vi.waitFor(() => expect(getTagNames).toHaveBeenCalled());
  await Promise.resolve();
  expect(container.textContent.trim()).toBe('');
});

test('says what was merged and numbered apart, and which tags have more than 3 words', async () => {
  getTagNames.mockResolvedValue(full);
  render(TagNamesNote);

  expect(await screen.findByText('Tag names now have one form.')).toBeTruthy();
  expect(screen.getByText('Merged ‘Mud-puddling’ into ‘mud_puddling’ (12 photos).')).toBeTruthy();
  expect(screen.getByText('Merged ‘Upper-Wing!’ into ‘upper_wing’ (1 photo).')).toBeTruthy();
  expect(screen.getByText('Renamed ‘upper-wing’ to ‘upper_wing_2’: two built-in tags had the same name.')).toBeTruthy();
  expect(screen.getByText('These tags have more than 3 words: mud_puddling_on_wet_sand, basking_in_the_sun. Rename them in Tags.'))
    .toBeTruthy();
});

test('Dismiss hides the merged part; the long tags stay until renamed', async () => {
  getTagNames.mockResolvedValue(full);
  dismissTagNames.mockResolvedValue({ ...full, dismissed: true });
  render(TagNamesNote);

  await fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }));

  await vi.waitFor(() => expect(screen.queryByText('Tag names now have one form.')).toBeNull());
  expect(screen.queryByRole('button', { name: 'Dismiss' })).toBeNull();
  expect(screen.getByText(/These tags have more than 3 words/)).toBeTruthy();
});

test('a Dismiss that fails says why and keeps the note', async () => {
  getTagNames.mockResolvedValue(full);
  dismissTagNames.mockRejectedValue(new Error('The library drive is not there.'));
  render(TagNamesNote);

  await fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }));

  expect((await screen.findByRole('alert')).textContent).toBe('The library drive is not there.');
  expect(screen.getByText('Tag names now have one form.')).toBeTruthy();
});

test('Tags opens the Tags sheet; a change there reads the note again', async () => {
  getTagNames.mockResolvedValue({ ...full, merged: [], numbered: [] });
  render(TagNamesNote);

  await fireEvent.click(await screen.findByRole('button', { name: 'Tags' }));

  expect(await screen.findByRole('dialog', { name: 'Tags' })).toBeTruthy();
  expect(getTagNames).toHaveBeenCalledTimes(1);
  await fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  expect(screen.queryByRole('dialog', { name: 'Tags' })).toBeNull();
  expect(getTagNames).toHaveBeenCalledTimes(2);
});

test('a note that cannot be read says so', async () => {
  getTagNames.mockRejectedValue(new Error('offline'));
  render(TagNamesNote);

  expect((await screen.findByRole('alert')).textContent).toBe('Could not read the tag names note: offline');
});
