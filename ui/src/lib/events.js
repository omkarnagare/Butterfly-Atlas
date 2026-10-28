import { DRIVE_FULL, DRIVE_GONE } from './codes.js';
/**
 * Sent on window by api.js when any request is refused because the library's drive is full or gone (507 `drive_full`,
 * 503 `drive_gone`): the status is read at once, so every screen says so (lib/status.svelte.js).
 */
export const LIBRARY_PROBLEM = 'atlas:library-problem';
export const LIBRARY_CODES = [DRIVE_FULL, DRIVE_GONE];
/**
 * Sent on window by api.js when an API answer is `401 no_session` (the page has no session cookie: it was not opened
 * from the app's icon); lib/session.svelte.js then replaces the page with the plain message.
 */
export const NO_SESSION = 'atlas:no-session';
