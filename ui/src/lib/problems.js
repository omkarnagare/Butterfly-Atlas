import { LIBRARY_CODES } from './events.js';

/**
 * What a page or list says when its data couldn't be loaded: the server's words, except when the library's drive is
 * full or gone (the banner at the top already says why): "Couldn't load this page.".
 */
export const LOAD_FAILED = "Couldn't load this page.";
export const loadProblem = (e) => (LIBRARY_CODES.includes(e?.code) ? LOAD_FAILED : e?.message ?? String(e));
