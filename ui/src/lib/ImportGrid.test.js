import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import ImportGrid from './ImportGrid.svelte';
import { folderSections, visibleRange } from './importView.js';

vi.mock('./importView.js', async (importOriginal) => {
  const real = await importOriginal();
  return { ...real, visibleRange: vi.fn(real.visibleRange) };
});

afterEach(() => vi.restoreAllMocks());

const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }]]);
const item = (id, extra = {}) => ({
  id, path: `IMG_${id}.JPG`, folder: '', name: `IMG_${id}.JPG`, size: 1, modifiedAt: '2024-08-12T10:00:00Z',
  capturedAt: new Date(Date.UTC(2024, 7, 12) + id * 600000).toISOString(), state: 'read', problem: null, suggestedTaxonId: null,
  libraryTaxonId: null, copyOf: null, taxonId: null, tags: [], skipped: false, outcome: null, imageId: null, error: null, v: 'v',
  place: null, ...extra,
});
const handlers = () => ({
  onTileClick: vi.fn(), onOpen: vi.fn(), onMove: vi.fn(), onSpace: vi.fn(), onFocusTile: vi.fn(),
  onSelectSection: vi.fn(), onSelectBurst: vi.fn(), onAcceptSection: vi.fn(),
});
const tileIds = (container) => [...container.querySelectorAll('[data-item-id]')].map((t) => Number(t.dataset.itemId));

/** jsdom has no layout: give the grid a width and a place on the page. */
function layout({ width = 1140, top = 0 } = {}) {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function () { return this.classList.contains('ig') ? width : 0; });
  const rect = vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(() => ({ top, left: 0, width, height: 0, right: width, bottom: top }));
  return (next) => rect.mockImplementation(() => ({ top: next, left: 0, width, height: 0, right: width, bottom: next }));
}

test('of ten thousand photos only the rows in view are in the page, and scrolling brings in the next ones', async () => {
  const moveTo = layout({ width: 1140, top: 100 });
  const items = Array.from({ length: 10000 }, (_, k) => item(k + 1, { folder: `Day ${Math.floor(k / 2500) + 1}` }));
  const { container } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, ...handlers() });

  const grid = container.querySelector('.ig');
  // 7 columns of 154 px tiles: rows of 164 px; 4 section headers of 64 px
  expect(grid.style.height).toBe(`${4 * 64 + Math.ceil(2500 / 7) * 4 * 164}px`);
  const first = tileIds(container);
  expect(first[0]).toBe(1);
  expect(first.length).toBeLessThan(120);

  moveTo(-200000);
  await fireEvent.scroll(window);
  const later = tileIds(container);
  expect(later.length).toBeLessThan(120);
  // the row holding the tile Tab lands on (the first) stays; the rest are far down
  expect(Math.min(...later.filter((id) => id > 7))).toBeGreaterThan(8000);
  expect(later.filter((id) => id <= 7)).toEqual([1, 2, 3, 4, 5, 6, 7]);
});

test('keeps the focused tile in the page, wherever the view is', () => {
  layout();
  const items = Array.from({ length: 3000 }, (_, k) => item(k + 1));
  const { container } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, focusId: 2900, ...handlers() });

  expect(tileIds(container)).toContain(2900);
  expect(container.querySelector('[data-item-id="2900"]').getAttribute('tabindex')).toBe('0');
  expect(container.querySelector('[data-item-id="1"]').getAttribute('tabindex')).toBe('-1');
});

test('arrows move by tile and by row, Space toggles and Enter opens', async () => {
  layout({ width: 490 }); // 3 columns
  const h = handlers();
  const items = Array.from({ length: 8 }, (_, k) => item(k + 1));
  const { container } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, ...h });
  const tile = (id) => container.querySelector(`[data-item-id="${id}"]`);

  expect(tile(1).getAttribute('tabindex')).toBe('0');
  await fireEvent.keyDown(tile(2), { key: 'ArrowRight' });
  expect(h.onMove).toHaveBeenLastCalledWith(3, false);
  await fireEvent.keyDown(tile(2), { key: 'ArrowDown', shiftKey: true });
  expect(h.onMove).toHaveBeenLastCalledWith(5, true);
  await fireEvent.keyDown(tile(8), { key: 'Home' });
  expect(h.onMove).toHaveBeenLastCalledWith(1, false);
  await fireEvent.keyDown(tile(4), { key: ' ' });
  expect(h.onSpace).toHaveBeenCalledWith(expect.objectContaining({ id: 4 }), false);
  await fireEvent.keyDown(tile(4), { key: 'Enter' });
  expect(h.onOpen).toHaveBeenCalledWith(expect.objectContaining({ id: 4 }));
});

test('section headers select all and accept the suggestions; burst headers select the burst', async () => {
  layout();
  const h = handlers();
  const items = [
    item(1, { folder: 'Sinhagad', suggestedTaxonId: 'A5' }), item(2, { folder: 'Sinhagad', suggestedTaxonId: 'A5' }),
    item(3, { folder: 'Sinhagad', capturedAt: '2024-08-13T10:00:00Z' }), item(4, { folder: 'Sinhagad', capturedAt: '2024-08-13T10:01:00Z' }),
  ];
  const sections = folderSections(items, 'Root');
  const { container } = render(ImportGrid, { importId: 3, sections, taxa, ...h });

  expect(screen.getByRole('heading', { name: 'Sinhagad' })).toBeTruthy();
  const header = container.querySelector('.r-section');
  expect(header.textContent).toContain('4 photos');
  expect(header.textContent).toContain('Suggested: Plain Tiger');
  await fireEvent.click(within(header).getByRole('button', { name: 'Select all in Sinhagad' }));
  expect(h.onSelectSection).toHaveBeenCalledWith(sections[0]);
  await fireEvent.click(within(header).getByRole('button', { name: 'Accept all suggestions in Sinhagad' }));
  expect(h.onAcceptSection).toHaveBeenCalledWith(sections[0], [1, 2]);

  await fireEvent.click(screen.getByRole('button', { name: /^Select burst of 2 photos from/ }));
  expect(h.onSelectBurst).toHaveBeenCalledWith(expect.objectContaining({ burst: true, items: [items[2], items[3]] }));
});

test('focusItem moves focus to a tile', async () => {
  layout();
  const h = handlers();
  const items = Array.from({ length: 20 }, (_, k) => item(k + 1));
  const { component, container } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, ...h });

  await component.focusItem(12);
  expect(h.onFocusTile).toHaveBeenCalledWith(12);
  expect(document.activeElement).toBe(container.querySelector('[data-item-id="12"]'));
});

test('is a grid of photos with a name, rows and columns that say where each photo is, and which are selected', () => {
  layout({ width: 490 }); // 3 columns
  const items = [...Array.from({ length: 4 }, (_, k) => item(k + 1)), item(5, { outcome: 'filed', taxonId: 'A5' })];
  render(ImportGrid, { importId: 3, label: 'Photos in Root', sections: folderSections(items, 'Root'), taxa, selected: new Set([2]), ...handlers() });

  const grid = screen.getByRole('grid', { name: 'Photos in Root' });
  expect(grid.getAttribute('aria-multiselectable')).toBe('true');
  expect(grid.getAttribute('aria-rowcount')).toBe('3'); // a header and two rows of tiles
  expect(grid.getAttribute('aria-colcount')).toBe('3');
  const rows = within(grid).getAllByRole('row');
  expect(rows.map((r) => r.getAttribute('aria-rowindex'))).toEqual(['1', '2', '3']);
  expect(within(rows[0]).getByRole('gridcell').getAttribute('aria-colspan')).toBe('3');
  const cells = within(rows[2]).getAllByRole('gridcell');
  expect(cells.map((c) => c.getAttribute('aria-colindex'))).toEqual(['1', '2']);
  expect(screen.getByRole('gridcell', { name: 'IMG_2.JPG' }).getAttribute('aria-selected')).toBe('true');
  expect(screen.getByRole('gridcell', { name: 'IMG_1.JPG' }).getAttribute('aria-selected')).toBe('false');
  expect(cells[1].hasAttribute('aria-selected')).toBe(false); // filed: can't be selected
});

test('keeps focus in the grid when the rows around the focused tile are laid out anew', async () => {
  layout({ width: 490 });
  const h = handlers();
  const items = Array.from({ length: 6 }, (_, k) => item(k + 1));
  const { container, rerender } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, focusId: 2, ...h });
  const tile = (id) => container.querySelector(`[data-item-id="${id}"]`);
  tile(2).focus();

  // photo 1 goes: the row that held 2 is keyed anew and rebuilt
  await rerender({ sections: folderSections(items.slice(1), 'Root'), focusId: 2 });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(2)));

  // the focused photo itself goes: focus moves to the one now in its place
  await rerender({ sections: folderSections(items.slice(2), 'Root'), focusId: 2 });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(3)));
  expect(h.onFocusTile).toHaveBeenLastCalledWith(3);
});

test('focus outside the grid is left alone when the rows change', async () => {
  layout({ width: 490 });
  const items = Array.from({ length: 6 }, (_, k) => item(k + 1));
  const outside = document.createElement('button');
  document.body.append(outside);
  const { container, rerender } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, focusId: 2, ...handlers() });
  outside.focus();
  await rerender({ sections: folderSections(items.slice(1), 'Root'), focusId: 2 });
  await new Promise((r) => setTimeout(r, 0));
  expect(document.activeElement).toBe(outside);
  expect(container.contains(document.activeElement)).toBe(false);
  outside.remove();
});

test('the focused tile\'s row stays in the page in its place among the rows, not after them', () => {
  layout();
  const items = Array.from({ length: 3000 }, (_, k) => item(k + 1));
  const { container } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, focusId: 2, ...handlers() });
  // the view is at the top, so the focused row (near the top) is among them; move focus far down instead
  const indexes = [...container.querySelectorAll('[role="row"]')].map((r) => Number(r.getAttribute('aria-rowindex')));
  expect(indexes).toEqual([...indexes].sort((a, b) => a - b));
});

test('a focused row out of view comes before the rows in view when it is above them', async () => {
  const moveTo = layout({ width: 1140, top: 0 });
  const items = Array.from({ length: 3000 }, (_, k) => item(k + 1));
  const { container } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, focusId: 2, ...handlers() });
  moveTo(-50000);
  await fireEvent.scroll(window);
  const indexes = [...container.querySelectorAll('[role="row"]')].map((r) => Number(r.getAttribute('aria-rowindex')));
  expect(indexes[0]).toBe(2);
  expect(indexes).toEqual([...indexes].sort((a, b) => a - b));
});

test('while reading reshuffles the rows above, the page scrolls so the focused tile stays where it was on screen', async () => {
  layout({ width: 490, top: -300 }); // 3 columns, the grid scrolled 300 px up
  const scrollBy = vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  const h = handlers();
  const items = Array.from({ length: 30 }, (_, k) => item(k + 10));
  const { container, rerender } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, focusId: 25, ...h });
  container.querySelector('[data-item-id="25"]').focus();

  // three photos taken earlier arrive: one more row above the focused tile
  const more = [item(1), item(2), item(3), ...items];
  await rerender({ sections: folderSections(more, 'Root'), focusId: 25 });
  const rowHeight = Math.floor((490 - 2 * 10) / 3) + 10;
  await vi.waitFor(() => expect(scrollBy).toHaveBeenCalledWith(0, rowHeight));
  expect(scrollBy).toHaveBeenCalledTimes(1);
});

test('follows the grid\'s width, and the page\'s height (content above it), once a frame; a height-only change of the grid does nothing', async () => {
  layout({ width: 490 });
  const observed = [];
  let callback;
  vi.stubGlobal('ResizeObserver', class {
    constructor(cb) { callback = cb; }
    observe(el) { observed.push(el); }
    disconnect() {}
  });
  const frames = [];
  vi.stubGlobal('requestAnimationFrame', (fn) => frames.push(fn));
  const items = Array.from({ length: 12 }, (_, k) => item(k + 1));
  const { container } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, ...handlers() });
  const grid = container.querySelector('.ig');
  expect(observed).toContain(grid);
  expect(observed).toContain(document.body);
  const rect = vi.spyOn(grid, 'getBoundingClientRect');

  callback([{ target: grid, contentRect: { width: 490, height: 900 } }]);
  expect(frames.length).toBe(0);
  callback([{ target: grid, contentRect: { width: 700, height: 900 } }]);
  callback([{ target: grid, contentRect: { width: 720, height: 900 } }]);
  expect(frames.length).toBe(1);
  frames.shift()();
  expect(rect).toHaveBeenCalled();
  callback([{ target: document.body, contentRect: { width: 1200, height: 3000 } }]);
  expect(frames.length).toBe(1);
  vi.unstubAllGlobals();
});

test('no scroll to follow the focused tile when focus is no longer in the grid', async () => {
  layout({ width: 490, top: -300 });
  const scrollBy = vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  const items = Array.from({ length: 30 }, (_, k) => item(k + 10));
  const outside = document.createElement('button');
  document.body.append(outside);
  const { container, rerender } = render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, focusId: 25, ...handlers() });
  container.querySelector('[data-item-id="25"]').focus();
  outside.focus();
  await rerender({ sections: folderSections([item(1), item(2), item(3), ...items], 'Root'), focusId: 25 });
  await new Promise((r) => setTimeout(r, 0));
  expect(scrollBy).not.toHaveBeenCalled();
  outside.remove();
});

test('a scroll that does not move the view works nothing out again', async () => {
  layout({ width: 1140, top: 100 });
  const items = Array.from({ length: 300 }, (_, k) => item(k + 1));
  render(ImportGrid, { importId: 3, sections: folderSections(items, 'Root'), taxa, ...handlers() });
  const before = visibleRange.mock.calls.length;
  await fireEvent.scroll(window);
  await fireEvent.scroll(window);
  expect(visibleRange.mock.calls.length).toBe(before);
});
