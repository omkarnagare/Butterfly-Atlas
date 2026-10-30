import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { getSession } from './api.js';
import { NO_SESSION } from './events.js';
import { refreshStatus } from './status.svelte.js';
import * as sessionModule from './session.svelte.js';
import {
  LOCK_NOTE, TAB_NOTE, loadSession, loadSessionWithRetry, resetSession, session, startSession,
} from './session.svelte.js';

vi.mock('./api.js', () => ({ getSession: vi.fn() }));
vi.mock('./status.svelte.js', () => ({ refreshStatus: vi.fn() }));

/** A stand-in for the browser's EventSource that remembers every one made. */
class FakeSource {
  static made = [];
  constructor(url) {
    this.url = url;
    this.readyState = 1;
    this.closed = false;
    this.listeners = {};
    FakeSource.made.push(this);
  }
  addEventListener(type, listener) {
    (this.listeners[type] ??= []).push(listener);
  }
  emit(type) {
    for (const listener of this.listeners[type] ?? []) listener({ type, data: '{}' });
  }
  close() {
    this.closed = true;
    this.readyState = 2;
  }
}

beforeEach(() => {
  vi.resetAllMocks();
  FakeSource.made = [];
  vi.stubGlobal('EventSource', FakeSource);
  resetSession();
  window.history.replaceState(null, '', '/');
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// Change the library folder (contract §2): once the app quits to reopen on the new folder, the window keeps its last page;
// the stream is closed and never made again, and its events ask nothing.
test('ending the session after a reopen closes the stream for good', () => {
  vi.useFakeTimers();
  startSession();
  const [first] = FakeSource.made;
  sessionModule.endSession();
  expect(first.closed).toBe(true);
  first.emit('status');
  first.onerror?.();
  vi.advanceTimersByTime(30000);
  expect(FakeSource.made).toHaveLength(1);
  expect(refreshStatus).not.toHaveBeenCalled();
  startSession();
  expect(FakeSource.made).toHaveLength(1);
});

test('opens one session stream at start-up, and starting again never makes a second', () => {
  startSession();
  startSession();
  expect(FakeSource.made.map((s) => s.url)).toEqual(['/api/session/stream']);
  expect(FakeSource.made[0].closed).toBe(false);
});

test('a stream the browser has given up on is replaced after a pause, once, and never closed by the page', () => {
  vi.useFakeTimers();
  startSession();
  const [first] = FakeSource.made;
  // a dropped connection the browser reconnects by itself is left alone
  first.onerror?.();
  vi.advanceTimersByTime(30000);
  expect(FakeSource.made).toHaveLength(1);

  first.readyState = 2;
  first.onerror?.();
  first.onerror?.();
  vi.advanceTimersByTime(5000);
  expect(FakeSource.made).toHaveLength(2);
  expect(FakeSource.made[1].url).toBe('/api/session/stream');
  expect(first.closed).toBe(false);
});

test('once the session is lost the stream is not replaced, so nothing retries', () => {
  vi.useFakeTimers();
  startSession();
  const [first] = FakeSource.made;
  window.dispatchEvent(new CustomEvent(NO_SESSION));
  expect(session.lost).toBe(true);
  first.readyState = 2;
  first.onerror?.();
  vi.advanceTimersByTime(60000);
  expect(FakeSource.made).toHaveLength(1);
});

test('event: status asks for the status as the footer does on focus; events within 250 ms make one read', () => {
  vi.useFakeTimers();
  startSession();
  const [stream] = FakeSource.made;
  stream.emit('status');
  vi.advanceTimersByTime(100);
  stream.emit('status');
  expect(refreshStatus).not.toHaveBeenCalled();
  vi.advanceTimersByTime(150);
  expect(refreshStatus).toHaveBeenCalledTimes(1);

  // a later event is a read of its own
  stream.emit('status');
  vi.advanceTimersByTime(250);
  expect(refreshStatus).toHaveBeenCalledTimes(2);
  // comments (: open, : ping) are no events: nothing else asks
  vi.advanceTimersByTime(5000);
  expect(refreshStatus).toHaveBeenCalledTimes(2);
});

test('a replaced stream listens for status too; once the session is lost an event asks nothing', () => {
  vi.useFakeTimers();
  startSession();
  const [first] = FakeSource.made;
  first.readyState = 2;
  first.onerror?.();
  vi.advanceTimersByTime(5000);
  FakeSource.made[1].emit('status');
  vi.advanceTimersByTime(250);
  expect(refreshStatus).toHaveBeenCalledTimes(1);

  window.dispatchEvent(new CustomEvent(NO_SESSION));
  FakeSource.made[1].emit('status');
  vi.advanceTimersByTime(250);
  expect(refreshStatus).toHaveBeenCalledTimes(1);
});

test('a page without EventSource still starts', () => {
  vi.stubGlobal('EventSource', undefined);
  expect(() => startSession()).not.toThrow();
});

test('a stray ?notice= is taken out of the address and ignored, keeping the rest (several-windows contract §1)', () => {
  window.history.replaceState(null, '', '/?notice=already-running#/explore');
  startSession();
  expect('notice' in session).toBe(false);
  expect(sessionModule.ALREADY_RUNNING).toBeUndefined();
  expect(sessionModule.dismissNotice).toBeUndefined();
  expect(window.location.search).toBe('');
  expect(window.location.hash).toBe('#/explore');
});

test('an address without the notice is left as it is; other parameters stay', () => {
  window.history.replaceState(null, '', '/?v=3');
  startSession();
  expect(window.location.search).toBe('?v=3');
  window.history.replaceState(null, '', '/?notice=already-running&v=3');
  resetSession();
  startSession();
  expect(window.location.search).toBe('?v=3');
});

test('a tab session and a drive that cannot lock each have their standing note', async () => {
  getSession.mockResolvedValue({ mode: 'tab', version: '0.1.0', dataFolder: null, configFolder: '/c', logFolder: '/c/logs', lockWarning: true });
  await loadSession();
  expect(session.info.mode).toBe('tab');
  expect(session.tabNote).toBe(TAB_NOTE);
  expect(TAB_NOTE).toBe('Running in a browser tab. Closing it quits the app.');
  expect(session.lockNote).toBe(LOCK_NOTE);
  expect(LOCK_NOTE).toBe('This drive cannot prevent a second copy from opening this library. Open only one.');
});

test('a window session without a lock problem has no notes', async () => {
  getSession.mockResolvedValue({ mode: 'window', version: '0.1.0', dataFolder: '/d', configFolder: '/c', logFolder: '/c/logs', lockWarning: false });
  await loadSession();
  expect(session.tabNote).toBe('');
  expect(session.lockNote).toBe('');
});

test('a session that cannot be read leaves the notes out', async () => {
  getSession.mockRejectedValue(new Error('down'));
  await loadSession();
  expect(session.info).toBeNull();
  expect(session.tabNote).toBe('');
});

const WINDOW = { mode: 'tab', version: '0.1.0', dataFolder: '/d', configFolder: '/c', logFolder: '/c/logs', lockWarning: true };

test('the start-up read asks again after a failure, so one hiccup does not drop the standing notes', async () => {
  vi.useFakeTimers();
  getSession.mockRejectedValueOnce(new Error('down')).mockRejectedValueOnce(new Error('down')).mockResolvedValue(WINDOW);
  const done = loadSessionWithRetry();
  await vi.advanceTimersByTimeAsync(0);
  expect(session.info).toBeNull();
  await vi.advanceTimersByTimeAsync(3000);
  await done;
  expect(getSession).toHaveBeenCalledTimes(3);
  expect(session.tabNote).toBe(TAB_NOTE);
  expect(session.lockNote).toBe(LOCK_NOTE);
});

test('the start-up read gives up after three tries, and stops at once when the session is lost', async () => {
  vi.useFakeTimers();
  getSession.mockRejectedValue(new Error('down'));
  const done = loadSessionWithRetry();
  await vi.advanceTimersByTimeAsync(10000);
  await done;
  expect(getSession).toHaveBeenCalledTimes(3);
  expect(session.info).toBeNull();

  getSession.mockClear();
  const lost = loadSessionWithRetry();
  await vi.advanceTimersByTimeAsync(0);
  session.lost = true;
  await vi.advanceTimersByTimeAsync(10000);
  await lost;
  expect(getSession).toHaveBeenCalledTimes(1);
});
