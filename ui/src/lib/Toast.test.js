import { render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import { flushSync } from 'svelte';
import Toast from './Toast.svelte';
import Dialog from './Dialog.svelte';
import { showProblem, showToast, toast } from './toast.svelte.js';

afterEach(() => vi.useRealTimers());

test('shows a message in a status region for a few seconds', () => {
  vi.useFakeTimers();
  render(Toast);

  flushSync(() => showToast('3 photos added to Plain Tiger.'));
  expect(screen.getByRole('status').textContent).toBe('3 photos added to Plain Tiger.');

  vi.advanceTimersByTime(2600);
  flushSync();
  expect(screen.getByRole('status').textContent).toBe('');
});

test('a new message replaces the old one and starts its own time', () => {
  vi.useFakeTimers();
  render(Toast);

  flushSync(() => showToast('First'));
  vi.advanceTimersByTime(2000);
  flushSync(() => showToast('Second'));
  vi.advanceTimersByTime(2000);
  flushSync();

  expect(screen.getByRole('status').textContent).toBe('Second');
});

test('a toast can carry a link and stay longer (8 s with a link); the link closes it', async () => {
  vi.useFakeTimers();
  render(Toast);
  flushSync(() => showToast('Taxonomy update finished.', { link: { href: '#/update', label: 'See what changed' } }));
  const link = screen.getByRole('link', { name: 'See what changed' });
  expect(link.getAttribute('href')).toBe('#/update');
  expect(screen.getByRole('status').textContent.replace(/\s+/g, ' ').trim()).toBe('Taxonomy update finished. See what changed');
  vi.advanceTimersByTime(7900);
  flushSync();
  expect(screen.queryByRole('link')).not.toBeNull();
  vi.advanceTimersByTime(200);
  flushSync();
  expect(screen.queryByRole('link')).toBeNull();

  flushSync(() => showToast('Longer.', { ms: 5000 }));
  vi.advanceTimersByTime(4000);
  flushSync();
  expect(screen.getByRole('status').textContent).toBe('Longer.');
  flushSync(() => showToast('With a link', { link: { href: '#/update', label: 'Open' } }));
  link.ownerDocument.querySelector('.toast a').click();
  flushSync();
  expect(screen.getByRole('status').textContent).toBe('');
});

test('a refusal because the taxonomy is updating stays 8 s with a Taxonomy update link; others are plain', () => {
  showProblem(Object.assign(new Error('Wait for the taxonomy update to finish.'), { code: 'taxonomy_updating' }));
  expect(toast).toMatchObject({ message: 'Wait for the taxonomy update to finish.', link: { href: '#/update', label: 'Taxonomy update' }, ms: 8000 });
  showProblem(Object.assign(new Error('Not now.'), { code: 'other' }));
  expect(toast).toMatchObject({ message: 'Not now.', link: null, ms: 2600 });
});

test('the several-windows refusals stay 8 s, long enough to read; backup_copying links to the backup card on Home (A1)', () => {
  window.location.hash = '#/explore';
  const copying = 'A backup is copying your photos, so photos can\'t be changed right now. Wait for it to finish, or cancel it, change your photos, then start a new backup.';
  showProblem(Object.assign(new Error(copying), { code: 'backup_copying', status: 409 }));
  expect(toast).toMatchObject({ message: copying, link: { href: '#/', label: 'Backup' }, ms: 8000 });
  for (const [code, message] of [
    ['import_filing', 'An import is filing photos. Wait for filing to finish, or pause it, then start the backup.'],
    ['import_changed', 'This import was changed in another window. Check the photos, then press File again.'],
    ['busy', 'Another window is saving changes. Try again in a moment.'],
  ]) {
    showProblem(Object.assign(new Error(message), { code }));
    expect(toast).toMatchObject({ message, link: null, ms: 8000 });
  }
  window.location.hash = '';
});

test('while an overlay is open the toast\'s link is out of the Tab order; back in once it closes', async () => {
  render(Toast);
  const dialog = render(Dialog, { title: 'Merge Neptis sankara?', onClose: () => {} });
  await new Promise((resolve) => setTimeout(resolve, 0));
  flushSync(() => showToast('Wait for the taxonomy update to finish.', { link: { href: '#/update', label: 'Taxonomy update' } }));
  expect(screen.getByRole('link', { name: 'Taxonomy update' }).getAttribute('tabindex')).toBe('-1');
  dialog.unmount();
  await new Promise((resolve) => setTimeout(resolve, 0));
  flushSync();
  expect(screen.getByRole('link', { name: 'Taxonomy update' }).hasAttribute('tabindex')).toBe(false);
});
