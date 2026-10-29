import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import FilterRow from './FilterRow.svelte';
import { EMPTY, mergeNames } from './filter.js';
import source from './FilterRow.svelte?raw';

const names = mergeNames(undefined, {
  taxa: [{ id: 'A12', name: 'Lycaenidae' }, { id: 'A230', name: 'Danaus' }],
  places: [{ id: 'P20', name: 'Maharashtra' }],
});
const filter = { ...EMPTY, taxa: ['A12', 'A230'], tags: ['larva', 'pupa'], places: ['P20', 'unknown'], status: 'not', stages: 'missing' };

test('one chip per active filter, each with a remove button, then + Add filter', () => {
  render(FilterRow, { filter, names, onChange: () => {}, onEdit: () => {} });
  const row = screen.getByRole('list', { name: 'Filters' });
  expect([...row.querySelectorAll('li')].map((li) => li.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Taxa: Lycaenidae, Danaus ×', 'Tags: larva or pupa ×', 'Place: Maharashtra, Unknown place ×', 'Not photographed ×', 'Life stages: missing ×', '+ Add filter',
  ]);
  expect(screen.getByRole('button', { name: 'Remove Taxa: Lycaenidae, Danaus' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Remove Life stages: missing' })).toBeTruthy();
  // Missing life stages is set only by its preset: nothing to edit, only to remove
  expect(screen.queryByRole('button', { name: 'Life stages: missing' })).toBeNull();
});

test('× clears that filter only, and focus moves to the next chip (or + Add filter)', async () => {
  const onChange = vi.fn();
  const { rerender } = render(FilterRow, { filter, names, onChange, onEdit: () => {} });
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Tags: larva or pupa' }));
  expect(onChange).toHaveBeenCalledWith({ ...filter, tags: [], tagMode: 'any' });
  await rerender({ filter: { ...filter, tags: [] } });
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Place: Maharashtra, Unknown place' })));

  await rerender({ filter: { ...EMPTY, status: 'photographed' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Photographed' }));
  await rerender({ filter: EMPTY });
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: '+ Add filter' })));
});

test('a × whose change made no new row forgets where focus was to go: a later new row (names arriving) does not take focus', async () => {
  const outside = document.createElement('button');
  document.body.append(outside);
  const { rerender } = render(FilterRow, { filter, names, onChange: vi.fn(), onEdit: () => {} });
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Tags: larva or pupa' }));
  // the page kept the filter (nothing changed); the user moved on
  outside.focus();
  await rerender({ names: mergeNames(names, { tags: [{ key: 'larva', label: 'Larva' }] }) });
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(document.activeElement).toBe(outside);
  await rerender({ filter: { ...filter, status: 'all' } });
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(document.activeElement).toBe(outside);
  outside.remove();
});

test('a chip opens its popover, named by the chip it came from', async () => {
  const onEdit = vi.fn();
  render(FilterRow, { filter, names, onChange: () => {}, onEdit });
  const chip = screen.getByRole('button', { name: 'Not photographed' });
  await fireEvent.click(chip);
  expect(onEdit).toHaveBeenCalledWith('status', chip);
  await fireEvent.click(screen.getByRole('button', { name: 'Place: Maharashtra, Unknown place' }));
  expect(onEdit).toHaveBeenLastCalledWith('places', screen.getByRole('button', { name: 'Place: Maharashtra, Unknown place' }));
});

test('+ Add filter opens a menu; Source only while there are manual species, File type after it; arrows move, Enter chooses', async () => {
  const onEdit = vi.fn();
  const { rerender } = render(FilterRow, { filter: EMPTY, names, onChange: () => {}, onEdit });
  const add = screen.getByRole('button', { name: '+ Add filter' });
  expect(add.getAttribute('aria-haspopup')).toBe('menu');
  await fireEvent.click(add);
  const menu = screen.getByRole('menu', { name: 'Add filter' });
  expect(add.getAttribute('aria-expanded')).toBe('true');
  expect(add.getAttribute('aria-controls')).toBe(menu.id);
  expect([...menu.querySelectorAll('[role="menuitem"]')].map((item) => item.textContent)).toEqual(['Taxa', 'Tags', 'Place', 'Photo status', 'File type']);
  await vi.waitFor(() => expect(document.activeElement.textContent).toBe('Taxa'));

  await fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
  expect(document.activeElement.textContent).toBe('Place');
  await fireEvent.keyDown(document.activeElement, { key: 'End' });
  expect(document.activeElement.textContent).toBe('File type');
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
  expect(document.activeElement.textContent).toBe('Taxa');
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
  await fireEvent.click(document.activeElement);
  expect(screen.queryByRole('menu')).toBeNull();
  await vi.waitFor(() => expect(onEdit).toHaveBeenCalledWith('status', add));

  await rerender({ manualSpecies: true });
  await fireEvent.click(add);
  expect([...screen.getByRole('menu').querySelectorAll('[role="menuitem"]')].map((item) => item.textContent))
    .toEqual(['Taxa', 'Tags', 'Place', 'Photo status', 'Source', 'File type']);
});

test('Escape closes the menu and focus goes back to + Add filter', async () => {
  render(FilterRow, { filter: EMPTY, names, onChange: () => {}, onEdit: () => {} });
  const add = screen.getByRole('button', { name: '+ Add filter' });
  add.focus();
  await fireEvent.click(add);
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('menu')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(add));
});

test('an active chip\'s label and × take the chip\'s own colour in every state (the shared ".chip button:hover" would make them invisible)', () => {
  render(FilterRow, { filter: { ...EMPTY, status: 'photographed' }, names, onChange: () => {}, onEdit: () => {} });
  const chip = screen.getByRole('button', { name: 'Photographed' }).closest('li');
  expect(chip.classList.contains('chip')).toBe(true);
  expect(chip.classList.contains('fchip')).toBe(true);
  expect([...chip.querySelectorAll('button')].map((b) => b.className.split(' ')[0])).toEqual(['fchip-l', 'fchip-x']);
  // jsdom applies no :hover, so the rule itself is checked: every state of a chip's buttons inherits its colour
  const css = source.slice(source.indexOf('<style>'));
  const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)].map(([, selectors, body]) => ({ selectors: selectors.split(',').map((x) => x.trim()), body }));
  // Any spelling of "color: inherit" counts. The selectors are what matters: Svelte scopes each with the component's
  // class, which outweighs the shared ".chip button:hover" (one class and a pseudo-class less), so naming every state
  // here is enough.
  const inherits = rules.filter((rule) => /color\s*:\s*inherit/.test(rule.body)).flatMap((rule) => rule.selectors);
  expect(inherits).toEqual(expect.arrayContaining(['.fchip button', '.fchip button:hover', '.fchip button:focus-visible', '.fchip button:active']));
});

test('a File type chip opens its popover and × clears it (Phase 7a)', async () => {
  const onEdit = vi.fn();
  const onChange = vi.fn();
  render(FilterRow, { filter: { ...EMPTY, types: ['raw'] }, names, onChange, onEdit });
  const chip = screen.getByRole('button', { name: 'File type: RAW' });
  await fireEvent.click(chip);
  expect(onEdit).toHaveBeenCalledWith('types', chip);
  await fireEvent.click(screen.getByRole('button', { name: 'Remove File type: RAW' }));
  expect(onChange).toHaveBeenCalledWith({ ...EMPTY, types: [] });
});
