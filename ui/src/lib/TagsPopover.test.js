import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import TagsPopover from './TagsPopover.svelte';
import { getTags } from './api.js';
import { EMPTY } from './filter.js';

vi.mock('./api.js', () => ({ getTags: vi.fn() }));

const tag = (key, label, source = 'builtin') => ({ id: key.length, key, label, source, photoCount: 1 });
const TAGS = [tag('mud', 'mud-puddling', 'user'), tag('egg', 'egg'), tag('larva', 'larva'), tag('pupa', 'pupa')];

beforeEach(() => {
  vi.resetAllMocks();
  getTags.mockResolvedValue(TAGS);
});

test('every tag as a toggle, built-in first; toggling sets the tags and passes their labels on', async () => {
  const onChange = vi.fn();
  const { rerender } = render(TagsPopover, { filter: EMPTY, onChange, onClose: vi.fn() });
  expect(screen.getByRole('dialog', { name: 'Tags' })).toBeTruthy();
  const group = await screen.findByRole('group', { name: 'Tags' });
  await vi.waitFor(() => expect(group.querySelectorAll('button')).toHaveLength(4));
  expect([...group.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['egg', 'larva', 'pupa', 'mud-puddling']);
  const labels = [TAGS[1], TAGS[2], TAGS[3], TAGS[0]].map(({ key, label }) => ({ key, label }));

  await fireEvent.click(screen.getByRole('button', { name: 'larva' }));
  expect(onChange).toHaveBeenCalledWith({ ...EMPTY, tags: ['larva'] }, { tags: labels });
  await rerender({ filter: { ...EMPTY, tags: ['larva'] } });
  expect(screen.getByRole('button', { name: 'larva' }).getAttribute('aria-pressed')).toBe('true');
  await fireEvent.click(screen.getByRole('button', { name: 'mud-puddling' }));
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, tags: ['larva', 'mud'] }, { tags: labels });
  await fireEvent.click(screen.getByRole('button', { name: 'larva' }));
  expect(onChange).toHaveBeenLastCalledWith(EMPTY, { tags: labels });
});

test('Match any / Match all set the tag mode', async () => {
  const onChange = vi.fn();
  render(TagsPopover, { filter: { ...EMPTY, tags: ['larva', 'pupa'] }, onChange, onClose: vi.fn() });
  const any = screen.getByRole('button', { name: 'Match any' });
  expect(any.getAttribute('aria-pressed')).toBe('true');
  await fireEvent.click(screen.getByRole('button', { name: 'Match all' }));
  expect(onChange).toHaveBeenCalledWith({ ...EMPTY, tags: ['larva', 'pupa'], tagMode: 'all' }, {});
});

test('with Missing life stages it says the tags are not used', async () => {
  render(TagsPopover, { filter: { ...EMPTY, stages: 'missing' }, onChange: vi.fn(), onClose: vi.fn() });
  expect(screen.getByText('Tags are not used with Missing life stages.')).toBeTruthy();
});

test('a failure to read the tags shows its message', async () => {
  getTags.mockRejectedValue(new Error('Butterfly Atlas could not reach its service.'));
  render(TagsPopover, { filter: EMPTY, onChange: vi.fn(), onClose: vi.fn() });
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
});

test('says it is loading the tags until they come; closed before then, it stops asking and changes nothing', async () => {
  let answer;
  getTags.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  const { unmount } = render(TagsPopover, { filter: EMPTY, onChange: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('status').textContent).toBe('Loading tags…');
  const { signal } = getTags.mock.calls[0][0];
  unmount();
  expect(signal.aborted).toBe(true);
  answer(TAGS);
  await Promise.resolve();
});

test('once the tags have come the loading line goes', async () => {
  render(TagsPopover, { filter: EMPTY, onChange: vi.fn(), onClose: vi.fn() });
  await screen.findByRole('button', { name: 'larva' });
  expect(screen.queryByText('Loading tags…')).toBeNull();
});

test('given the tags already read (the Export screen holds them), it shows them at once and asks for none', async () => {
  render(TagsPopover, { filter: EMPTY, tags: TAGS, onChange: vi.fn(), onClose: vi.fn() });
  const group = screen.getByRole('group', { name: 'Tags' });
  expect([...group.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['egg', 'larva', 'pupa', 'mud-puddling']);
  expect(screen.queryByText('Loading tags…')).toBeNull();
  expect(getTags).not.toHaveBeenCalled();
});
