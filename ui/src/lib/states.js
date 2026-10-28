/**
 * The state words the server sends (app JobStates.java, HeicStatus.java and ImportStore.java have the same words).
 * Export, backup and taxonomy update runs: `state`.
 */
export const PENDING = 'pending';
export const WAITING = 'waiting';
export const RUNNING = 'running';
export const PAUSED = 'paused';
export const DONE = 'done';
export const CANCELLED = 'cancelled';
/** A taxonomy update this copy of the app can't apply. */
export const UNAVAILABLE = 'unavailable';
/** A run's `pausedBy` when the app's closing stopped it. */
export const CLOSED = 'closed';

/** The HEIC decoder's `state`. */
export const DECODER_CHECKING = 'checking';
export const DECODER_AVAILABLE = 'available';
export const DECODER_MISSING = 'missing';

/** An import run's `state` while it can still be continued, and its items' read `state`s. */
export const IMPORT_OPEN = 'open';
export const ITEM_PENDING = 'pending';
export const ITEM_READ = 'read';
export const ITEM_UNSUPPORTED = 'unsupported';
export const ITEM_FAILED = 'failed';
export const ITEM_MISSING = 'missing';
