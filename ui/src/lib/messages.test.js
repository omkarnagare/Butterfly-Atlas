import { expect, test } from 'vitest';
import { NO_SESSION_WORDS } from './messages.js';

// The server says the same words (app SessionGuard.MESSAGE, pinned in SessionApiTest); the two must not drift apart.
test('the no-session words match the server', () => {
  expect(NO_SESSION_WORDS).toBe('To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.');
});
