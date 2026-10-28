import { afterEach, expect, test, vi } from 'vitest';
import { pollImport } from './importPoll.js';
import { getImport, getImportItems } from './api.js';

vi.mock('./api.js', () => ({ getImport: vi.fn(), getImportItems: vi.fn() }));
afterEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
});

test('an error in applying an answer is not taken for the app being out of reach, and polling goes on', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValue({ phase: 'reading' });
  getImportItems.mockResolvedValue({ items: [] });
  const onReach = vi.fn();
  const answer = vi.fn(() => { throw new Error('bug'); });
  const stop = pollImport(3, { since: () => 1, answer, onReach });
  await vi.advanceTimersByTimeAsync(1000 * 5);
  stop();
  expect(answer.mock.calls.length).toBe(5); // still once a second, not backing off
  expect(onReach).not.toHaveBeenCalled();
});
