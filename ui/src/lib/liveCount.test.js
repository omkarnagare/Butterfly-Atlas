import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { liveCount } from './liveCount.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

/** A fetcher whose answers the test releases one by one. */
function deferred() {
  const calls = [];
  const fetcher = vi.fn((query, { signal }) => new Promise((resolve, reject) => calls.push({ query, signal, resolve, reject })));
  return { fetcher, calls };
}

test('asks once typing has paused for the delay, with the newest filter only', async () => {
  const { fetcher, calls } = deferred();
  const onAnswer = vi.fn();
  const live = liveCount(fetcher, { delay: 200, onAnswer });

  live.ask('taxa=A1');
  await vi.advanceTimersByTimeAsync(150);
  live.ask('taxa=A1,A2');
  await vi.advanceTimersByTimeAsync(199);
  expect(fetcher).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect(fetcher).toHaveBeenCalledOnce();
  expect(calls[0].query).toBe('taxa=A1,A2');

  calls[0].resolve({ photos: 3 });
  await vi.advanceTimersByTimeAsync(0);
  expect(onAnswer).toHaveBeenCalledWith({ photos: 3 }, 'taxa=A1,A2');
});

test('an answer for an older filter is ignored, even when it arrives last; the older request is aborted', async () => {
  const { fetcher, calls } = deferred();
  const onAnswer = vi.fn();
  const live = liveCount(fetcher, { delay: 200, onAnswer });

  live.ask('status=not');
  await vi.advanceTimersByTimeAsync(200);
  live.ask('status=photographed');
  await vi.advanceTimersByTimeAsync(200);
  expect(calls[0].signal.aborted).toBe(true);

  calls[1].resolve({ species: 2 });
  calls[0].resolve({ species: 9 });
  await vi.advanceTimersByTimeAsync(0);
  expect(onAnswer).toHaveBeenCalledOnce();
  expect(onAnswer).toHaveBeenCalledWith({ species: 2 }, 'status=photographed');
});

test('a failure of the newest request is reported; aborts and older failures are not; stop drops what is pending', async () => {
  const { fetcher, calls } = deferred();
  const onAnswer = vi.fn();
  const onError = vi.fn();
  const live = liveCount(fetcher, { delay: 0, onAnswer, onError });

  live.ask('a');
  await vi.advanceTimersByTimeAsync(0);
  live.ask('b');
  await vi.advanceTimersByTimeAsync(0);
  calls[0].reject(new Error('older'));
  calls[1].reject(Object.assign(new Error('gone'), { name: 'AbortError' }));
  await vi.advanceTimersByTimeAsync(0);
  expect(onError).not.toHaveBeenCalled();

  live.ask('c');
  await vi.advanceTimersByTimeAsync(0);
  calls[2].reject(new Error('Butterfly Atlas could not reach its service.'));
  await vi.advanceTimersByTimeAsync(0);
  expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'Butterfly Atlas could not reach its service.' }), 'c');

  live.ask('d');
  live.stop();
  await vi.advanceTimersByTimeAsync(500);
  expect(fetcher).toHaveBeenCalledTimes(3);
  expect(onAnswer).not.toHaveBeenCalled();
});

test('stop aborts a request already on its way, and its answer is ignored', async () => {
  const { fetcher, calls } = deferred();
  const onAnswer = vi.fn();
  const live = liveCount(fetcher, { delay: 0, onAnswer });
  live.ask('a');
  await vi.advanceTimersByTimeAsync(0);
  live.stop();
  expect(calls[0].signal.aborted).toBe(true);
  calls[0].resolve({ photos: 1 });
  await vi.advanceTimersByTimeAsync(0);
  expect(onAnswer).not.toHaveBeenCalled();
});
