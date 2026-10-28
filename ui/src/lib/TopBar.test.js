import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import TopBar from './TopBar.svelte';
import { openWindow } from './api.js';
import { LINK_MS, hideToast, toast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ openWindow: vi.fn() }));

afterEach(() => {
  vi.mocked(openWindow).mockReset();
  hideToast();
});

test('shows the app name and the animated logo', () => {
  const { container } = render(TopBar);
  expect(screen.getByText('Butterfly Atlas')).toBeTruthy();
  expect(container.querySelector('.oak.perch')).not.toBeNull();
});

test('links to Home and Explore', () => {
  render(TopBar);
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('href')).toBe('#/');
  expect(screen.getByRole('link', { name: 'Explore' }).getAttribute('href')).toBe('#/explore');
});

test('links to Export, which is the current page on the Export screen', () => {
  const { rerender } = render(TopBar);
  const link = screen.getByRole('link', { name: 'Export' });
  expect(link.getAttribute('href')).toBe('#/export');
  expect(link.previousElementSibling.textContent).toBe('Explore');
  expect(link.getAttribute('aria-current')).toBeNull();

  rerender({ current: 'export' });
  expect(screen.getByRole('link', { name: 'Export' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
});

test('calls onSearch when the search button is pressed', async () => {
  const onSearch = vi.fn();
  render(TopBar, { onSearch });
  await fireEvent.click(screen.getByRole('button', { name: /search species/i }));
  expect(onSearch).toHaveBeenCalledOnce();
});

test('marks Home as the current page by default', () => {
  render(TopBar);
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Explore' }).getAttribute('aria-current')).toBeNull();
});

test('marks Explore as the current page when current is explore', () => {
  render(TopBar, { current: 'explore' });
  expect(screen.getByRole('link', { name: 'Explore' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
});

test('exposes a labelled main navigation landmark', () => {
  render(TopBar);
  expect(screen.getByRole('navigation', { name: 'Main' })).toBeTruthy();
});

test('links to Import folder, which is the current page while importing', () => {
  const { rerender } = render(TopBar);
  const link = screen.getByRole('link', { name: 'Import folder' });
  expect(link.getAttribute('href')).toBe('#/import');
  expect(link.getAttribute('aria-current')).toBeNull();

  rerender({ current: 'import' });
  expect(screen.getByRole('link', { name: 'Import folder' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
});

test('tells the page how tall it is, so keyboard scrolling clears it', () => {
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function () { return this.classList.contains('bar') ? 71 : 0; });
  render(TopBar);
  expect(document.documentElement.style.getPropertyValue('--bar-h')).toBe('71px');
  vi.restoreAllMocks();
});

test('links to Settings, which is the current page on the Settings screen', () => {
  const { rerender } = render(TopBar);
  const link = screen.getByRole('link', { name: 'Settings' });
  expect(link.getAttribute('href')).toBe('#/settings');
  expect(link.previousElementSibling.textContent).toBe('Export');
  expect(link.getAttribute('aria-current')).toBeNull();
  rerender({ current: 'settings' });
  expect(screen.getByRole('link', { name: 'Settings' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
});

test('New window sits after Settings and asks the app for a window: "Opening…" until the answer, then a toast', async () => {
  let answer;
  openWindow.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
  render(TopBar);
  const button = screen.getByRole('button', { name: 'New window' });
  expect(screen.getByRole('link', { name: 'Settings' }).nextElementSibling).toBe(button);
  expect(button.getAttribute('aria-disabled')).toBeNull();

  await fireEvent.click(button);
  expect(openWindow).toHaveBeenCalledTimes(1);
  expect(button.textContent).toBe('Opening…');
  expect(button.getAttribute('aria-disabled')).toBe('true');
  // pressed again while it waits: nothing more is asked
  await fireEvent.click(button);
  expect(openWindow).toHaveBeenCalledTimes(1);

  answer(null);
  await vi.waitFor(() => expect(button.textContent).toBe('New window'));
  expect(button.getAttribute('aria-disabled')).toBeNull();
  expect(toast.message).toBe('A new window is opening.');
});

test('New window that could not open says the server\'s words in a toast, long enough to read', async () => {
  const message = "Butterfly Atlas couldn't open another window. Open it from its icon instead.";
  openWindow.mockRejectedValue(Object.assign(new Error(message), { code: 'window_failed' }));
  render(TopBar);
  await fireEvent.click(screen.getByRole('button', { name: 'New window' }));
  await vi.waitFor(() => expect(toast.message).toBe(message));
  expect(toast.ms).toBe(LINK_MS);
  expect(screen.getByRole('button', { name: 'New window' }).textContent).toBe('New window');
});
