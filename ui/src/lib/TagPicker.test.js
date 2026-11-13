import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { tick } from 'svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import TagPicker from './TagPicker.svelte';
import { createTag } from './api.js';
import { TAG_NAME_TOO_LONG, TAG_NAME_TOO_MANY_WORDS } from './messages.js';

vi.mock('./api.js', () => ({ createTag: vi.fn() }));

const builtin = (key, label = key) => ({ id: key.length, key, label, source: 'builtin', photoCount: 0 });
const user = (key, label = key) => ({ id: 100 + key.length, key, label, source: 'user', photoCount: 0 });
// As GET /api/tags answers them: built-in tags in their order, then the user's A–Z.
const TAGS = [
  builtin('egg'), builtin('larva'), builtin('dsf', 'DSF'), builtin('nectaring'), builtin('unconfirmed'),
  user('basking'), user('basking_spot'), user('nectaring_on_lantana'), user('on_lantana'), user('wet_sand'),
];

let listener;
beforeEach(() => {
  vi.resetAllMocks();
  listener = vi.fn();
  window.addEventListener('keydown', listener);
});
afterEach(() => window.removeEventListener('keydown', listener));

/** A picker over a set of chosen keys, the way a screen holds them: pressing changes the set and the picker sees it. */
function setup({ chosen = [], ...props } = {}) {
  const on = new Set(chosen);
  const stateOf = () => (key) => (on.has(key) ? 'on' : 'off');
  const onToggle = vi.fn((key) => {
    if (on.has(key)) on.delete(key);
    else on.add(key);
    view.rerender({ stateOf: stateOf() });
  });
  const view = render(TagPicker, { tags: TAGS, stateOf: stateOf(), onToggle, ...props });
  return { ...view, on, onToggle };
}

const box = () => screen.getByRole('searchbox');
const chips = () => within(screen.getByRole('group', { name: 'Tags' }));
const chipNames = () => [...screen.getByRole('group', { name: 'Tags' }).querySelectorAll('button')].map((b) => b.textContent.trim());
const matches = () => screen.queryByRole('group', { name: 'Matching tags' });
const matchNames = () => [...(matches()?.querySelectorAll('button') ?? [])].map((b) => b.textContent.trim());
const type = (value) => fireEvent.input(box(), { target: { value } });
const key = (k, more = {}) => fireEvent.keyDown(box(), { key: k, ...more });

test('shows every built-in tag in tag order, then the chosen user tags, then the box', () => {
  setup({ chosen: ['larva', 'on_lantana'] });

  expect(chipNames()).toEqual(['egg', 'larva', 'DSF', 'nectaring', 'unconfirmed', 'on_lantana']);
  expect(chips().getByRole('button', { name: 'larva' }).getAttribute('aria-pressed')).toBe('true');
  expect(chips().getByRole('button', { name: 'larva' }).classList.contains('on')).toBe(true);
  expect(chips().getByRole('button', { name: 'egg' }).getAttribute('aria-pressed')).toBe('false');
  expect(chips().getByRole('button', { name: 'on_lantana' }).getAttribute('aria-pressed')).toBe('true');
  expect(box().getAttribute('placeholder')).toBe('Find or add a tag');
  expect(screen.getByRole('searchbox', { name: 'Find or add a tag' })).toBeTruthy();
  expect(matches()).toBeNull();
});

test('the chip group takes the screen\'s name', () => {
  render(TagPicker, { tags: TAGS, stateOf: () => 'off', onToggle: vi.fn(), label: 'Tags on this photo' });
  expect(screen.getByRole('group', { name: 'Tags on this photo' })).toBeTruthy();
});

test('pressing a chip toggles it, and the chips never move', async () => {
  const { onToggle } = setup({ chosen: ['on_lantana'] });

  await fireEvent.click(chips().getByRole('button', { name: 'DSF' }));
  await tick();
  expect(onToggle).toHaveBeenCalledWith('dsf');
  expect(chips().getByRole('button', { name: 'DSF' }).getAttribute('aria-pressed')).toBe('true');
  expect(chipNames()).toEqual(['egg', 'larva', 'DSF', 'nectaring', 'unconfirmed', 'on_lantana']);
});

test('a user tag unchosen here stays, unpressed, until the scope changes', async () => {
  const { rerender } = setup({ chosen: ['on_lantana'], scope: 7 });

  await fireEvent.click(chips().getByRole('button', { name: 'on_lantana' }));
  await tick();
  expect(chips().getByRole('button', { name: 'on_lantana' }).getAttribute('aria-pressed')).toBe('false');
  // one click undoes the slip
  await fireEvent.click(chips().getByRole('button', { name: 'on_lantana' }));
  await tick();
  expect(chips().getByRole('button', { name: 'on_lantana' }).getAttribute('aria-pressed')).toBe('true');
  await fireEvent.click(chips().getByRole('button', { name: 'on_lantana' }));
  await tick();

  await rerender({ scope: 8 });
  expect(chipNames()).not.toContain('on_lantana');
});

test('a chosen tag that is not in the list (deleted elsewhere, an unknown key) is not shown', () => {
  setup({ chosen: ['gone', 'larva'] });
  expect(chipNames()).toEqual(['egg', 'larva', 'DSF', 'nectaring', 'unconfirmed']);
});

test('searching: without case, spaces and _ alike, in tag order, chosen tags left out, unchosen built-in tags in', async () => {
  setup({ chosen: ['nectaring_on_lantana'] });

  await type('ON LANTANA');
  expect(matchNames()).toEqual(['on_lantana']);
  await type('nect');
  expect(matchNames()).toEqual(['nectaring', 'Create "nect"']);
  await type('  basking ');
  expect(matchNames()).toEqual(['basking', 'basking_spot']);
  await type('');
  expect(matches()).toBeNull();
});

test('a renamed built-in tag is found by its name, not its key', async () => {
  setup({ tags: [builtin('dsf', 'dry_season'), user('basking')] });
  await type('dry season');
  expect(matchNames()).toEqual(['dry_season']);
  await type('dsf');
  expect(matchNames()).toEqual(['Create "dsf"']);
});

test('at most 12 matches, then "and N more: keep typing"', async () => {
  const many = Array.from({ length: 15 }, (_, i) => user(`site_${String(i).padStart(2, '0')}`));
  setup({ tags: [builtin('egg'), ...many] });

  await type('site');
  expect(matchNames().slice(0, 12)).toEqual(many.slice(0, 12).map((t) => t.label));
  expect(matchNames()).toHaveLength(13); // the 12 and Create "site"
  expect(screen.getByText('and 3 more: keep typing')).toBeTruthy();
  await type('site_1');
  expect(screen.queryByText(/more: keep typing/)).toBeNull();
});

test('choosing a match toggles it, clears the box and keeps focus in the box', async () => {
  const { onToggle } = setup();

  await type('lantana');
  await fireEvent.click(within(matches()).getByRole('button', { name: 'on_lantana' }));
  await tick();
  expect(onToggle).toHaveBeenCalledWith('on_lantana');
  expect(box().value).toBe('');
  expect(matches()).toBeNull();
  expect(document.activeElement).toBe(box());
  expect(chips().getByRole('button', { name: 'on_lantana' }).getAttribute('aria-pressed')).toBe('true');
});

test('choosing a built-in tag from the matches is the same as pressing its chip', async () => {
  const { onToggle } = setup();
  await type('egg');
  await fireEvent.click(within(matches()).getByRole('button', { name: 'egg' }));
  await tick();
  expect(onToggle).toHaveBeenCalledWith('egg');
  expect(chips().getByRole('button', { name: 'egg' }).getAttribute('aria-pressed')).toBe('true');
});

test('Enter chooses the tag with exactly the typed name first, else the first match, and clears the box', async () => {
  const { onToggle } = setup();

  await type('Basking spot');
  await key('Enter');
  expect(onToggle).toHaveBeenLastCalledWith('basking_spot');
  expect(box().value).toBe('');

  await type('basking'); // "basking" itself, though basking_spot (already chosen) also matches
  await key('Enter');
  expect(onToggle).toHaveBeenLastCalledWith('basking');

  await type('lant');
  await key('Enter');
  expect(onToggle).toHaveBeenLastCalledWith('nectaring_on_lantana');
  expect(box().value).toBe('');
  expect(createTag).not.toHaveBeenCalled();
});

test('Enter on the exact name of a tag already chosen keeps it chosen and clears the box', async () => {
  const { onToggle } = setup({ chosen: ['larva'] });
  await type('Larva');
  await key('Enter');
  expect(onToggle).not.toHaveBeenCalled();
  expect(box().value).toBe('');
});

test('a name only chosen tags match says they are already chosen, and offers no Create', async () => {
  setup({ chosen: ['wet_sand', 'on_lantana', 'nectaring_on_lantana'] });
  await type('wet sand');
  expect(matchNames()).toEqual([]);
  expect(screen.queryByText(/No tag called/)).toBeNull();
  expect(screen.getByText('Already chosen: wet_sand')).toBeTruthy();
  await type('lantana');
  expect(screen.getByText('Already chosen: nectaring_on_lantana, on_lantana')).toBeTruthy();
  await type('lant');
  expect(matchNames()).toEqual(['Create "lant"']);
  expect(screen.getByText('Already chosen: nectaring_on_lantana, on_lantana')).toBeTruthy();
  await type('bask');
  expect(screen.queryByText(/Already chosen/)).toBeNull();
});

test('Esc with text clears the box and goes no further', async () => {
  setup();
  await type('bask');
  await key('Escape');
  expect(box().value).toBe('');
  expect(listener).not.toHaveBeenCalled();
  expect(document.activeElement === box() || document.activeElement === document.body).toBe(true);
});

test('Esc in an empty box is left alone: the dialog or popover behind closes as before', async () => {
  setup();
  box().focus();
  await key('Escape');
  expect(listener).toHaveBeenCalledOnce();
  expect(document.activeElement).toBe(box());
});

test('in a photo viewer, Esc in an empty box moves focus out of it, so the next Esc closes the viewer', async () => {
  setup({ blurOnEmptyEscape: true });
  await type('bask');
  box().focus();
  await key('Escape');
  expect(document.activeElement).toBe(box()); // the first Esc only clears
  await key('Escape');
  expect(document.activeElement).not.toBe(box());
});

test('Esc and Enter while an input method is composing are left to it', async () => {
  const { onToggle } = setup();
  await type('bask');
  await key('Escape', { isComposing: true });
  await key('Enter', { isComposing: true });
  expect(box().value).toBe('bask');
  expect(onToggle).not.toHaveBeenCalled();
});

test('no match: says so and offers Create with the formed name; Enter creates, the screen re-reads, then the tag is chosen', async () => {
  const made = user('mud_puddling_site');
  createTag.mockResolvedValue(made);
  const order = [];
  const { onToggle, rerender } = setup();
  const onCreated = vi.fn(async (tag) => {
    order.push(`created ${tag.key}`);
    await rerender({ tags: [...TAGS, made] });
  });
  onToggle.mockImplementation((key) => order.push(`toggle ${key}`));
  await rerender({ onCreated, onToggle });

  await type('  Mud-puddling site ');
  expect(screen.getByText('No tag called "Mud-puddling site".')).toBeTruthy();
  expect(within(matches()).getByRole('button', { name: 'Create "mud_puddling_site"' })).toBeTruthy();
  await key('Enter');

  await vi.waitFor(() => expect(order).toEqual(['created mud_puddling_site', 'toggle mud_puddling_site']));
  expect(createTag).toHaveBeenCalledWith('mud_puddling_site');
  expect(box().value).toBe('');
  await vi.waitFor(() => expect(document.activeElement).toBe(box()));
});

test('with matches but no exact name, Create comes after them and Enter still chooses the first match', async () => {
  const { onToggle } = setup();
  await type('lantana bush');
  expect(matchNames()).toEqual(['Create "lantana_bush"']);
  await type('lantana');
  expect(matchNames()).toEqual(['nectaring_on_lantana', 'on_lantana', 'Create "lantana"']);
  expect(screen.queryByText(/No tag called/)).toBeNull();
  await key('Enter');
  expect(onToggle).toHaveBeenCalledWith('nectaring_on_lantana');
  expect(createTag).not.toHaveBeenCalled();
});

test('clicking Create creates the tag and chooses it', async () => {
  const made = user('roosting');
  createTag.mockResolvedValue(made);
  const onCreated = vi.fn(async () => {});
  const { onToggle } = setup({ onCreated });
  await type('roosting');
  await fireEvent.click(within(matches()).getByRole('button', { name: 'Create "roosting"' }));
  await vi.waitFor(() => expect(onToggle).toHaveBeenCalledWith('roosting'));
  expect(onCreated).toHaveBeenCalledWith(made);
  await vi.waitFor(() => expect(document.activeElement).toBe(box()));
});

test('a name the server would refuse says why in place of Create, and Enter does nothing', async () => {
  setup({ onCreated: vi.fn() });
  await type('mud puddling on sand');
  expect(screen.getByText(TAG_NAME_TOO_MANY_WORDS)).toBeTruthy();
  expect(screen.queryByRole('button', { name: /^Create/ })).toBeNull();
  await key('Enter');
  expect(createTag).not.toHaveBeenCalled();
  expect(box().value).toBe('mud puddling on sand');

  await type('a'.repeat(41));
  expect(screen.getByText(TAG_NAME_TOO_LONG)).toBeTruthy();
  expect(screen.queryByRole('button', { name: /^Create/ })).toBeNull();
});

test('only marks typed: no match and nothing to create', async () => {
  setup();
  await type('!!!');
  expect(screen.getByText('No tag called "!!!".')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /^Create/ })).toBeNull();
});

test('a refused Create shows the server\'s words, keeps what was typed, and the screen still reads its tags again', async () => {
  createTag.mockRejectedValue(new Error('There is already a tag called "roosting".'));
  const { rerender, onToggle } = setup();
  const onCreated = vi.fn(async (tag) => {
    expect(tag).toBeNull();
    // another window made it a moment ago: the re-read has it
    await rerender({ tags: [...TAGS, user('roosting')] });
  });
  await rerender({ onCreated });

  await type('roosting');
  await key('Enter');

  expect((await screen.findByRole('alert')).textContent).toBe('There is already a tag called "roosting".');
  expect(onCreated).toHaveBeenCalledWith(null);
  expect(onToggle).not.toHaveBeenCalled();
  expect(box().value).toBe('roosting');
  await vi.waitFor(() => expect(matchNames()).toEqual(['roosting']));

  await key('Enter');
  expect(onToggle).toHaveBeenCalledWith('roosting');
  expect(screen.queryByRole('alert')).toBeNull();
});

test('while Create runs it is aria-disabled and a second press is ignored', async () => {
  let finish;
  createTag.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const { onToggle } = setup({ onCreated: vi.fn(async () => {}) });
  await type('roosting');
  const create = within(matches()).getByRole('button', { name: 'Create "roosting"' });
  await fireEvent.click(create);
  expect(create.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(create);
  await key('Enter');
  expect(createTag).toHaveBeenCalledOnce();
  finish(user('roosting'));
  await vi.waitFor(() => expect(onToggle).toHaveBeenCalledOnce());
});

test('a tag created after the scope changed (the viewer moved on) is not put on the next photo', async () => {
  let finish;
  createTag.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const onCreated = vi.fn(async () => {});
  const { onToggle, rerender } = setup({ onCreated, scope: 1 });
  await type('roosting');
  await key('Enter');
  await rerender({ scope: 2 });
  finish(user('roosting'));
  await vi.waitFor(() => expect(onCreated).toHaveBeenCalledOnce());
  await tick();
  expect(onToggle).not.toHaveBeenCalled();
});

test('a tag created after the picker closed is not chosen, but the screen still reads its tags again', async () => {
  let finish;
  createTag.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const onCreated = vi.fn(async () => {});
  const { onToggle, unmount } = setup({ onCreated });
  await type('roosting');
  await key('Enter');
  unmount();
  finish(user('roosting'));
  await vi.waitFor(() => expect(onCreated).toHaveBeenCalledOnce());
  await tick();
  expect(onToggle).not.toHaveBeenCalled();
});

test('while the screen is busy, chips, matches and Create are aria-disabled and do nothing', async () => {
  const { onToggle } = setup({ disabled: true, chosen: ['basking'] });
  for (const chip of screen.getByRole('group', { name: 'Tags' }).querySelectorAll('button')) {
    expect(chip.getAttribute('aria-disabled')).toBe('true');
  }
  await fireEvent.click(chips().getByRole('button', { name: 'egg' }));
  await type('lantana');
  for (const chip of matches().querySelectorAll('button')) expect(chip.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(within(matches()).getByRole('button', { name: 'on_lantana' }));
  await key('Enter');
  await type('roosting');
  await fireEvent.click(within(matches()).getByRole('button', { name: 'Create "roosting"' }));
  await key('Enter');
  expect(onToggle).not.toHaveBeenCalled();
  expect(createTag).not.toHaveBeenCalled();
  expect(box().value).toBe('roosting');
});

test('tristate: checkbox chips, all / some / none; a user tag some photos have shows as a chosen one, and is not a match', async () => {
  const states = { larva: 'on', egg: 'some', basking: 'some', on_lantana: 'off' };
  render(TagPicker, { tags: TAGS, stateOf: (k) => states[k] ?? 'off', onToggle: vi.fn(), tristate: true });

  const group = within(screen.getByRole('group', { name: 'Tags' }));
  expect(group.getByRole('checkbox', { name: 'larva' }).getAttribute('aria-checked')).toBe('true');
  expect(group.getByRole('checkbox', { name: 'egg' }).getAttribute('aria-checked')).toBe('mixed');
  expect(group.getByRole('checkbox', { name: 'egg' }).classList.contains('some')).toBe(true);
  expect(group.getByRole('checkbox', { name: 'DSF' }).getAttribute('aria-checked')).toBe('false');
  expect(group.getByRole('checkbox', { name: 'basking' }).getAttribute('aria-checked')).toBe('mixed');
  expect(group.queryByRole('button')).toBeNull();

  await type('bask');
  expect(matchNames()).toEqual(['basking_spot', 'Create "bask"']);
  expect(within(matches()).getByRole('checkbox', { name: 'basking_spot' }).getAttribute('aria-checked')).toBe('false');
});

test('filter mode: "Find a tag", and no Create', async () => {
  setup({ mode: 'filter' });
  expect(screen.getByRole('searchbox', { name: 'Find a tag' }).getAttribute('placeholder')).toBe('Find a tag');
  await type('roosting');
  expect(screen.getByText('No tag called "roosting".')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /^Create/ })).toBeNull();
  await key('Enter');
  expect(createTag).not.toHaveBeenCalled();
  await type('mud puddling on sand');
  expect(screen.queryByText(TAG_NAME_TOO_MANY_WORDS)).toBeNull();
});

test('a Recent row lists the user\'s tags used last, in that order, leaving out those already shown; in filters too', async () => {
  const tags = TAGS.map((tag) => ({ ...tag, recent: { wet_sand: 0, basking: 1, on_lantana: 2 }[tag.key] }));
  const { unmount } = setup({ tags, chosen: ['basking'] });
  const recent = () => within(screen.getByRole('group', { name: 'Recent tags' }));
  expect(recent().getAllByRole('button').map((b) => b.textContent)).toEqual(['wet_sand', 'on_lantana']);
  await fireEvent.click(recent().getByRole('button', { name: 'wet_sand' }));
  expect(chips().getByRole('button', { name: 'wet_sand' }).getAttribute('aria-pressed')).toBe('true');
  expect(recent().getAllByRole('button').map((b) => b.textContent)).toEqual(['on_lantana']);
  unmount();

  setup({ tags, mode: 'filter' });
  expect(recent().getAllByRole('button').map((b) => b.textContent)).toEqual(['wet_sand', 'basking', 'on_lantana']);
});

test('no Recent row when none of the user\'s tags has been used', () => {
  setup();
  expect(screen.queryByRole('group', { name: 'Recent tags' })).toBeNull();
});

test('while tagging, pressing mating says male and female change with it; a filter is left alone', async () => {
  const tags = [builtin('male'), builtin('female'), builtin('mating'), ...TAGS];
  const { onToggle, unmount } = setup({ tags, chosen: ['female'] });
  await fireEvent.click(chips().getByRole('button', { name: 'mating' }));
  expect(onToggle).toHaveBeenLastCalledWith('mating', ['male']);
  await fireEvent.click(chips().getByRole('button', { name: 'mating' }));
  expect(onToggle).toHaveBeenLastCalledWith('mating', ['female']);
  unmount();

  const filter = setup({ tags, mode: 'filter' });
  await fireEvent.click(chips().getByRole('button', { name: 'mating' }));
  expect(filter.onToggle).toHaveBeenLastCalledWith('mating');
});
