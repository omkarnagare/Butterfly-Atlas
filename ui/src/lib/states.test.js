import { expect, test } from 'vitest';
import * as states from './states.js';

// The server's state words (app JobStatesTest pins the same words on the Java side).
test('the states keep their wire words', () => {
  expect({ ...states }).toEqual({
    PENDING: 'pending',
    WAITING: 'waiting',
    RUNNING: 'running',
    PAUSED: 'paused',
    DONE: 'done',
    CANCELLED: 'cancelled',
    UNAVAILABLE: 'unavailable',
    CLOSED: 'closed',
    DECODER_CHECKING: 'checking',
    DECODER_AVAILABLE: 'available',
    DECODER_MISSING: 'missing',
    IMPORT_OPEN: 'open',
    ITEM_PENDING: 'pending',
    ITEM_READ: 'read',
    ITEM_UNSUPPORTED: 'unsupported',
    ITEM_FAILED: 'failed',
    ITEM_MISSING: 'missing',
  });
});
