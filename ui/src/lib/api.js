import { LIBRARY_CODES, LIBRARY_PROBLEM, NO_SESSION as SESSION_LOST } from './events.js';
import { BAD_RESPONSE, NO_SESSION, OFFLINE } from './codes.js';
import { NO_SESSION_WORDS } from './messages.js';

/**
 * Calls the local Atlas service. Relative URLs keep this working on whatever port the app picked.
 * Failures throw an ApiError carrying the server's plain-language message, and the species the refusal names when it
 * names one (`taxon`: a custom name already in the list, Phase 5a), else null.
 */
export class ApiError extends Error {
  constructor(code, message, status, taxon = null) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.taxon = taxon;
  }
}

const OFFLINE_WORDS = 'Butterfly Atlas could not reach its service. Close the app and open it again.';

/**
 * Phase 6: the first `401 no_session` answer means this page has no session cookie. From then on nothing is sent (no
 * retry loop, no flood of failed requests): every call fails at once with the same refusal, and the page shows the
 * full-screen message (lib/session.svelte.js listens for NO_SESSION).
 */
let sessionLost = false;

/** Test-only: a session again, so tests do not depend on each other's leftovers. */
export function resetSessionLost() {
  sessionLost = false;
}

async function request(path, { method = 'GET', body, raw, type, signal } = {}) {
  const init = { method, headers: { Accept: 'application/json' } };
  if (raw !== undefined) {
    // A photo goes as its own bytes with its own type; the server streams it to disk.
    init.headers['Content-Type'] = type;
    init.body = raw;
  } else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  if (signal) init.signal = signal;
  if (sessionLost) throw new ApiError(NO_SESSION, NO_SESSION_WORDS, 401);
  let response;
  try {
    response = await fetch(`./api/${path}`, init);
  } catch (e) {
    if (e?.name === 'AbortError') throw e;
    throw new ApiError(OFFLINE, OFFLINE_WORDS, 0);
  }
  if (!response.ok) {
    let error = null;
    try {
      error = (await response.json())?.error ?? null;
    } catch {
      error = null;
    }
    if (response.status === 401 && error?.code === NO_SESSION && !sessionLost) {
      sessionLost = true;
      window.dispatchEvent(new CustomEvent(SESSION_LOST));
    }
    if (LIBRARY_CODES.includes(error?.code)) window.dispatchEvent(new CustomEvent(LIBRARY_PROBLEM, { detail: error }));
    throw new ApiError(
      error?.code ?? `http_${response.status}`,
      error?.message ?? `Butterfly Atlas answered with status ${response.status}.`,
      response.status,
      error?.taxon ?? null,
    );
  }
  if (response.status === 204) return null;
  try {
    return await response.json();
  } catch {
    throw new ApiError(BAD_RESPONSE, 'Butterfly Atlas sent an answer it could not read.', response.status);
  }
}

const id = (value) => encodeURIComponent(value);
const q = (value) => encodeURIComponent(value);

export const fetchHealth = () => request('health');
/** Phase 6: `{ mode: 'window' | 'tab', version, dataFolder, configFolder, logFolder, lockWarning }`. */
export const getSession = () => request('session');
/**
 * New window (several-windows contract §2): the app opens one more window, as a launch from the icon does. 204 (null),
 * or 500 `window_failed` with its message.
 */
export const openWindow = () => request('window', { method: 'POST', body: {} });
export const getSetup = () => request('setup');
/**
 * Setup's check of a folder, and setting the library up there (setup design §3). `exact`: the folder is the library
 * folder (the suggested one); false: the library is kept in it (the folder browser).
 */
export const inspectFolder = (path, exact = true) => request(`setup/inspect?path=${q(path)}${exact ? '' : '&exact=false'}`);
export const setUpLibrary = (dataFolder, exact = true) => request('setup', { method: 'POST', body: { dataFolder, exact } });
/**
 * Opens the remembered library again after it failed to open (Phase 5c: a place step that failed): the Setup status as
 * it is now (409 `not_failed` when nothing failed).
 */
export const retrySetup = () => request('setup/retry', { method: 'POST', body: {} });
export const getSummary = () => request('summary');
export const getTaxon = (taxonId) => request(`taxa/${id(taxonId)}`);
export const getChildren = (taxonId) => request(`taxa/${id(taxonId)}/children`).then((r) => r.children);
export const getTree = () => request('tree');
export const search = (query, { limit = 20, signal } = {}) =>
  request(`search?q=${q(query)}&limit=${limit}`, { signal }).then((r) => r.results);
export const addSearchWord = (taxonId, word) =>
  request(`taxa/${id(taxonId)}/search-words`, { method: 'POST', body: { word } }).then((r) => r.words);
export const removeSearchWord = (taxonId, word) =>
  request(`taxa/${id(taxonId)}/search-words?word=${q(word)}`, { method: 'DELETE' }).then((r) => r.words);

/* ---------- custom species (Phase 5a) ---------- */

/** Adds a species the list does not have, in the official family `familyId`; `common` may be null. The new TaxonDetail. */
export const addCustomSpecies = (familyId, name, common) =>
  request('custom-species', { method: 'POST', body: { familyId, name, common } });
/** Renames a custom species (its photos move with a new scientific name). The TaxonDetail as it is now. */
export const renameCustomSpecies = (taxonId, name, common) =>
  request(`custom-species/${id(taxonId)}`, { method: 'PATCH', body: { name, common } });
/** Deletes a custom species; refused (409 `has_photos`) while any photo, live or in Recently deleted, is on it. */
export const deleteCustomSpecies = (taxonId) => request(`custom-species/${id(taxonId)}`, { method: 'DELETE' });
/**
 * Whether `name` (as typed) could be a custom species: `{ ok: true, name }` with the name as it would be saved, or
 * `{ ok: false, code, message, taxon }`. `id` is the species being renamed (its own name never blocks).
 */
export const checkCustomName = (name, { id: taxonId, signal } = {}) =>
  request(`custom-species/check?name=${q(name)}${taxonId ? `&id=${q(taxonId)}` : ''}`, { signal });

/* ---------- taxonomy updates, splits and custom-species matches (Phase 5b) ---------- */

/** The taxonomy update: pending (with its summary), waiting, running, paused, done (with its result), none or unavailable. */
export const getUpdate = () => request('update');
/** Applies the pending update; the Update as it starts (409 `no_update`, `update_running`). */
export const applyUpdate = () => request('update/apply', { method: 'POST', body: {} });
/** Runs a paused update again from where it stopped (409 `not_paused`). */
export const retryUpdate = () => request('update/retry', { method: 'POST', body: {} });
/** Later: the update is offered on Home instead of opening at start, until the app is closed. */
export const updateLater = () => request('update/later', { method: 'POST', body: {} });
/** Split species with live photos still to sort: SplitBriefs. */
export const getSplits = () => request('splits').then((r) => r.splits);
/** A split species, its targets and the photos still to sort. */
export const getSplit = (taxonId) => request(`splits/${id(taxonId)}`);
/** Sorts `photoIds` of the split species `taxonId` to `target` (one of its targets); the Split as it is now. */
export const sortSplit = (taxonId, photoIds, target) =>
  request(`splits/${id(taxonId)}/sort`, { method: 'POST', body: { photoIds, target } });
/** Open matches of a custom species with an official one. */
export const getCustomMatches = () => request('custom-matches').then((r) => r.matches);
/** Merges the custom species into the official one it matches, as an update job: the Update as it starts. */
export const mergeCustomMatch = (customId, officialId) =>
  request(`custom-matches/${id(customId)}/merge`, { method: 'POST', body: { officialId } });
/** Not now: the match is declined, and offered again after the next update if it still matches. */
export const declineCustomMatch = (customId, officialId) =>
  request(`custom-matches/${id(customId)}/decline`, { method: 'POST', body: { officialId } });

/**
 * Dismiss on Home's "The taxonomy was updated" card: kept in the library, so the status says `dismissed` after a page
 * reload; the next update or merge to finish shows the card again.
 */
export const dismissTaxonomyUpdate = () => request('update/dismiss', { method: 'POST', body: {} });

/* ---------- place-data updates (Phase 5c) ---------- */

/**
 * Home's place note (PlaceNote), or null (204) when there is nothing to show (contract §9). `signal` stops a read the
 * note gave up on.
 */
export const getPlaceUpdate = ({ signal } = {}) => request('place-update', { signal });
/** Dismiss: the note goes; its "former place" part comes back while any photo is in a former place. */
export const dismissPlaceUpdate = () => request('place-update/dismiss', { method: 'POST', body: {} });

/* ---------- photos (Phase 3a) ---------- */

/** Camera RAW extensions (Phase 7a contract §2): every family is sent as `image/x-raw` and the server reads the bytes. */
const RAW_EXTENSIONS = ['nef', 'nrw', 'dng', 'arw', 'srf', 'sr2', 'cr2', 'cr3', 'pef', 'srw', 'orf', 'rw2', 'raf', '3fr', 'iiq', 'rwl'];
/**
 * The formats the library accepts, by file extension (some systems leave File.type empty for TIFF, and name RAW files
 * by camera, e.g. image/x-nikon-nef).
 */
const PHOTO_TYPES = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', tif: 'image/tiff', tiff: 'image/tiff', webp: 'image/webp',
  ...Object.fromEntries(RAW_EXTENSIONS.map((ext) => [ext, 'image/x-raw'])),
  // HEIC and HEIF (Phase 7b contract §2): one format to the server; `.hif` is the cameras' 10-bit name for it.
  heic: 'image/heic', heif: 'image/heic', hif: 'image/heic',
};
const ACCEPTED = new Set(Object.values(PHOTO_TYPES));
// Other names systems give a type the library takes, and the type sent for it.
const SAME_AS = { 'image/heif': 'image/heic' };
/** For the file input's `accept`: the types and the extensions. */
export const PHOTO_ACCEPT = [...ACCEPTED, ...Object.keys(SAME_AS), ...Object.keys(PHOTO_TYPES).map((ext) => `.${ext}`)].join(',');
/** Largest photo the server takes (design §4). Checked here: a body cut off mid-stream can surface as a reset connection, not a 413. */
export const MAX_PHOTO_BYTES = 300 * 1024 * 1024;

/** The Content-Type to send for a picked file, or null when the library cannot take it. */
export function photoType(file) {
  const type = (file.type || '').toLowerCase();
  if (SEQUENCE_TYPES.has(type)) return null;
  if (ACCEPTED.has(type)) return type;
  if (SAME_AS[type]) return SAME_AS[type];
  const ext = (file.name.split('.').pop() || '').toLowerCase();
  return file.name.includes('.') ? PHOTO_TYPES[ext] ?? null : null;
}

// RAW families Butterfly Atlas does not read (no preview reader for them): refused with their own words (review fix U8).
const UNREAD_RAW = new Set(['crw', 'mrw', 'x3f', 'erf', 'kdc', 'dcr', 'mos']);
// HEIC image sequences (Phase 7b audit 22): refused here by name or by type, with the server's words.
const SEQUENCE_TYPES = new Set(['image/heic-sequence', 'image/heif-sequence']);
const SEQUENCE_EXTENSIONS = new Set(['heics', 'heifs']);
const SEQUENCE = "HEIC image sequences (several frames in one file) aren't supported. Save the frame you want as a photo and add that.";

/**
 * A HEIC image sequence (by name or type): not taken, but dropped files of this kind go to the add sheet so its refusal
 * says why (7b review fix U4).
 */
export function isPhotoSequence(file) {
  const ext = file.name.includes('.') ? (file.name.split('.').pop() || '').toLowerCase() : '';
  return SEQUENCE_EXTENSIONS.has(ext) || SEQUENCE_TYPES.has((file.type || '').toLowerCase());
}

function refusal(file) {
  const ext = file.name.includes('.') ? (file.name.split('.').pop() || '').toLowerCase() : '';
  if (isPhotoSequence(file)) return SEQUENCE;
  if (ext === 'avif' || /avif/.test(file.type || '')) return "AVIF photos aren't supported yet.";
  if (UNREAD_RAW.has(ext)) return "This RAW format isn't supported yet.";
  return "This file isn't a photo Butterfly Atlas can read.";
}

/**
 * Sends one photo. fetch cannot report how much of a body has gone, so `onProgress` hears 0 when the
 * file starts and 1 when the server has filed it; the add sheet shows progress as files done / total.
 * Formats and sizes the server would refuse are refused here, without sending anything.
 */
export async function uploadPhoto(taxonId, file, { tags = [], onProgress, signal } = {}) {
  const type = photoType(file);
  if (!type) throw new ApiError('unsupported_photo', refusal(file), 415);
  if (file.size > MAX_PHOTO_BYTES) {
    throw new ApiError('too_large', 'This photo is larger than 300 MB.', 413);
  }
  onProgress?.(0);
  const query = `name=${q(file.name)}${tags.length ? `&tags=${q(tags.join(','))}` : ''}`;
  const result = await request(`taxa/${id(taxonId)}/photos?${query}`, { method: 'POST', raw: file, type, signal });
  onProgress?.(1);
  return result;
}

/** Thumbnail URL. `v` (the file's modified time) changes whenever the photo does, so each version may be cached for good. */
export const thumbUrl = (photoId, v) => `./api/photos/${id(photoId)}/thumb?v=${q(v ?? '')}`;
export const photoThumb = (photo) => thumbUrl(photo.id, photo.v);
/**
 * Full-size image for the viewer: the original, or for TIFF and HEIC (Phase 7b) a large JPEG copy every web view can
 * show.
 */
export const displayUrl = (photoId, v) => `./api/photos/${id(photoId)}/display${v == null ? '' : `?v=${q(v)}`}`;

export const getPhotos = (taxonId) => request(`taxa/${id(taxonId)}/photos`).then((r) => r.photos);
export const setPhotoTags = (photoId, tags) => request(`photos/${id(photoId)}`, { method: 'PATCH', body: { tags } });
export const editTags = (ids, add = [], remove = []) =>
  request('photos/tags', { method: 'POST', body: { ids, add, remove } }).then((r) => r.photos);
export const movePhotos = (ids, taxonId) => request('photos/move', { method: 'POST', body: { ids, taxonId } }).then((r) => r.photos);
export const setCover = (taxonId, photoId) =>
  request(`taxa/${id(taxonId)}/cover`, { method: 'PUT', body: { photoId } }).then((r) => r.coverId);
export const deletePhotos = (ids) => request('photos/delete', { method: 'POST', body: { ids } }).then((r) => r.deleted);
export const getDeleted = () => request('deleted').then((r) => r.photos);
export const restorePhotos = (ids) => request('deleted/restore', { method: 'POST', body: { ids } }).then((r) => r.photos);
/** Removes the given deleted photos for good, or every one when `ids` is left out. */
export const emptyDeleted = (ids) =>
  request('deleted/empty', { method: 'POST', body: ids ? { ids } : {} }).then((r) => r.removed);
/** Sets one place on every listed photo, as the user's (GPS or not): the changed photos. */
export const placePhotos = (ids, placeId) => request('photos/place', { method: 'POST', body: { ids, placeId } }).then((r) => r.photos);
/** Takes the place off every listed photo (it becomes Unknown place, as the user's choice). */
export const unplacePhotos = (ids) => request('photos/place', { method: 'POST', body: { ids, remove: true } }).then((r) => r.photos);
export const getTags = ({ signal } = {}) => request('tags', { signal }).then((r) => r.tags);
export const createTag = (label) => request('tags', { method: 'POST', body: { label } });
export const renameTag = (tagId, label) => request(`tags/${id(tagId)}`, { method: 'PATCH', body: { label } });
export const deleteTag = (tagId) => request(`tags/${id(tagId)}`, { method: 'DELETE' }).then((r) => r.removedFromPhotos);
/** The Home note of tag names (tag-names design §4): `{ merged, numbered, long, dismissed }`. */
export const getTagNames = () => request('tag-names');
export const dismissTagNames = () => request('tag-names/dismiss', { method: 'POST', body: {} });
export const getStatus = () => request('status');

/* ---------- HEIC decoder (Phase 7b) ---------- */

/**
 * Check again: the computer's HEIC decoder is looked for afresh (the server waits for it, at most 30 s, and joins a check
 * already running). The HeicStatus after it, `{ state, decoder, system, reason }`.
 */
export const checkHeic = () => request('heic/check', { method: 'POST', body: {} });
/** Hides Home's note of the files the last rescan did not add (`/api/status` `rescanLeft`) until the next rescan. */
export const dismissRescanLeft = () => request('rescan-left/dismiss', { method: 'POST', body: {} });

/* ---------- places (Phase 3c-2) ---------- */

/**
 * Places whose name or other spelling matches (at most 20, best first); fewer than 2 characters finds none.
 * `level` ('state') limits it to places of that level. The list also carries `formerMatches` (Phase 5c contract §10:
 * `[{ place, now }]`, former places whose name matches, with what they became; never choosable themselves).
 */
export const searchPlaces = (query, { level, signal } = {}) =>
  request(`places/search?q=${q(query)}${level ? `&level=${q(level)}` : ''}`, { signal })
    .then((r) => Object.assign(r.places, { formerMatches: r.formerMatches ?? [] }));
/** The last five places applied, newest first. */
export const getRecentPlaces = () => request('places/recent').then((r) => r.places);
/** The places the user added, by name, each with `photos` (how many live photos use it). */
export const getMyPlaces = () => request('places/mine').then((r) => r.places);
/** Adds a district of the user's inside the state `parentId`. */
export const addPlace = (name, parentId) => request('places', { method: 'POST', body: { name, parentId } });
export const renamePlace = (placeId, name) => request(`places/${id(placeId)}`, { method: 'PATCH', body: { name } });
/** Deletes one of the user's places; its photos become Unknown place. How many photos that was. */
export const deletePlace = (placeId) => request(`places/${id(placeId)}`, { method: 'DELETE' }).then((r) => r.photosCleared);

/* ---------- backup (Phase 6) ---------- */

/** `{ asked, preference, lastAt, lastWhat, lastUnread, daysSince, due, current, past }` (contract §3). */
export const getBackups = () => request('backup');
/** Sets the reminder preference ('remind' | 'less' | 'none'); the Backups as they are now (400 `bad_preference`). */
export const setBackupPreference = (preference) => request('backup/preference', { method: 'PUT', body: { preference } });
/** What a backup of `what` ('database' | 'everything') into the folder `path` would need, and the problem it would meet. */
export const checkBackup = (what, path, { signal } = {}) => request(`backup/check?what=${q(what)}&path=${q(path)}`, { signal });
/** Starts a backup: `{ what, path }`. The Backup, running (done for a database alone, which is quick). */
export const startBackup = (body) => request('backup', { method: 'POST', body });
/** One backup with its `problems` (at most 1,000) and `moreProblems`. */
export const getBackup = (backupId) => request(`backup/${id(backupId)}`);
export const pauseBackup = (backupId) => request(`backup/${id(backupId)}/pause`, { method: 'POST', body: {} });
export const resumeBackup = (backupId) => request(`backup/${id(backupId)}/resume`, { method: 'POST', body: {} });
/** Stops a backup; `remove` deletes the files it wrote and that are unchanged (nothing else). The Backup, cancelled. */
export const cancelBackup = (backupId, remove) => request(`backup/${id(backupId)}/cancel`, { method: 'POST', body: { remove } });
/** Removes a finished or cancelled backup from the list; its files are never touched. */
export const deleteBackup = (backupId) => request(`backup/${id(backupId)}`, { method: 'DELETE' });
/** Clears the list of past backups (their files stay): how many were removed. */
export const clearBackups = () => request('backup', { method: 'DELETE' }).then((r) => r.removed);

/* ---------- appearance (Meadow) ---------- */

/** `{ theme }`: 'light' | 'dark', or null while none is saved (the page follows the computer; Meadow contract §2). */
export const getAppearance = () => request('appearance');
/** Saves Light or Dark: `{ theme }` as saved (400 `invalid`, 409 `not_set_up`, 500 `save_failed` with the reason). */
export const setAppearance = (theme) => request('appearance', { method: 'PUT', body: { theme } });

/* ---------- the filter (Phase 4a) ---------- */

// `query` is the filter as lib/filter.js prints it (contract §1), '' for no filter.
const withFilter = (path, query, more = '') => {
  const all = [query, more].filter(Boolean).join('&');
  return all ? `${path}?${all}` : path;
};

/** The count line's numbers and the filter as the server used it (`filter.query`, names of the chosen taxa, tags and places). */
export const filterCount = (query, { signal } = {}) => request(withFilter('filter/count', query), { signal });
/** Every species in the result, in taxonomic order. */
export const filterSpecies = (query, { signal } = {}) => request(withFilter('filter/species', query), { signal }).then((r) => r.species);
/** One page of matching photos: `{ photos, next }`, `next` the cursor of the following page (null on the last). */
export const filterPhotos = (query, { after = null, limit = 200, signal } = {}) =>
  request(withFilter('filter/photos', query, `${after == null ? '' : `after=${q(after)}&`}limit=${limit}`), { signal })
    .then((r) => ({ photos: r.photos, next: r.next ?? null }));
/** Species and photo counts per place: the countries, the chosen place's children, or the chosen places. */
export const filterPlaces = (query, { signal } = {}) => request(withFilter('filter/places', query), { signal }).then((r) => r.places);

/* ---------- exports (Phase 4b) ---------- */

/**
 * What an export of the filter (`query`, as lib/filter.js prints it) to the folder `path` would need: `{ photos, species,
 * bytes, neededBytes, freeBytes, name, problem, raw, heic }`, `problem` null or the refusal starting it would get. `what`
 * is photos, csv or both. `raw` (Phase 7a) and `heic` (Phase 7b) are `{ photos, withGps, withUnknown }`;
 * `rawWithLocation` says the RAW and HEIC box is ticked.
 * Read-only.
 */
export const checkExport = (query, what, path, { signal, rawWithLocation = false } = {}) =>
  request(withFilter('exports/check', query, `what=${q(what)}&path=${q(path)}${rawWithLocation ? '&rawWithLocation=true' : ''}`), { signal });
/**
 * Starts an export: `{ filter, what, path, layout, stripLocation, placeDepth, rawWithLocation, acceptedKinds }` (Phase 7a:
 * the user's acceptance that RAW photos go out with their location; Phase 7b: it covers HEIC photos too, and
 * `acceptedKinds`, `['raw']`, `['heic']` or both, says which kinds the box was shown for). The Export, running (done for
 * a species list only).
 */
export const startExport = (body) => request('exports', { method: 'POST', body });
/** `{ current, past }`: the running or paused export (or null), and the finished and cancelled ones, newest first. */
export const getExports = () => request('exports');
/** One export with its `problems` (at most 1,000) and `moreProblems`. */
export const getExport = (exportId) => request(`exports/${id(exportId)}`);
export const pauseExport = (exportId) => request(`exports/${id(exportId)}/pause`, { method: 'POST', body: {} });
export const resumeExport = (exportId) => request(`exports/${id(exportId)}/resume`, { method: 'POST', body: {} });
/** Stops an export; `remove` deletes the files it wrote (nothing else). The Export, cancelled. */
export const cancelExport = (exportId, remove) => request(`exports/${id(exportId)}/cancel`, { method: 'POST', body: { remove } });
/** Removes a finished or cancelled export from the list; its files are never touched. */
export const deleteExport = (exportId) => request(`exports/${id(exportId)}`, { method: 'DELETE' });
/** Clears the list of past exports (their files stay): how many were removed. */
export const clearExports = () => request('exports', { method: 'DELETE' }).then((r) => r.removed);

/* ---------- import folder (Phase 3b-2) ---------- */

/** Shortcuts (home, pictures, desktop, documents) and drives, for the folder browser. */
export const getFsRoots = () => request('fs/roots').then((r) => r.roots);
/**
 * One folder: its crumbs, subfolders with counts, photo count, whether it is the library and any open import of it.
 * `signal` aborts a listing a newer one has replaced.
 */
export const listFolder = (path, { signal } = {}) => request(`fs/list?path=${q(path)}`, { signal });

export const getImports = () => request('imports').then((r) => r.imports);
/** Starts an import of the folder, or continues the open one of it: `{ import, continued }`. */
export const startImport = (path) => request('imports', { method: 'POST', body: { path } });
export const getImport = (importId) => request(`imports/${id(importId)}`);
/** Every item, or only those changed after `since` (the version of an earlier answer). */
export const getImportItems = (importId, since = null) =>
  request(`imports/${id(importId)}/items${since == null ? '' : `?since=${q(since)}`}`);
export const assignItems = (importId, itemIds, taxonId) =>
  request(`imports/${id(importId)}/assign`, { method: 'POST', body: { itemIds, taxonId } });
export const tagItems = (importId, itemIds, add = [], remove = []) =>
  request(`imports/${id(importId)}/tags`, { method: 'POST', body: { itemIds, add, remove } });
/** Sets a place on import items (filed with it as the user's); answers with the changed items, as assign does. */
export const placeItems = (importId, itemIds, placeId) =>
  request(`imports/${id(importId)}/place`, { method: 'POST', body: { itemIds, placeId } });
export const unplaceItems = (importId, itemIds) =>
  request(`imports/${id(importId)}/place`, { method: 'POST', body: { itemIds, remove: true } });
export const skipItems = (importId, itemIds, skipped) =>
  request(`imports/${id(importId)}/skip`, { method: 'POST', body: { itemIds, skipped } });
export const acceptSuggestions = (importId, itemIds) =>
  request(`imports/${id(importId)}/accept-suggestions`, { method: 'POST', body: { itemIds } });
/**
 * How RAW + JPEG (or RAW + HEIC, Phase 7b) pairs are filed: 'both', 'jpeg' (the JPEG or HEIC half) or 'raw' (Phase 7a
 * contract §3). The ImportSummary as it is now.
 */
export const putImportPairs = (importId, choice) => request(`imports/${id(importId)}/pairs`, { method: 'PUT', body: { choice } });
/**
 * Files the assigned photos. `revision` is the latest the page received (several-windows contract §5): an import changed
 * since, in another window, is refused with 409 `import_changed` and nothing is filed.
 */
export const fileImport = (importId, revision) => request(`imports/${id(importId)}/file`, { method: 'POST', body: { revision } });
export const pauseImport = (importId) => request(`imports/${id(importId)}/pause`, { method: 'POST', body: {} });
export const relocateImport = (importId, path) => request(`imports/${id(importId)}/relocate`, { method: 'POST', body: { path } });
/** Finishes the import; `skipRest` marks every photo still waiting as skipped. `{ import, result }`. */
export const finishImport = (importId, skipRest) => request(`imports/${id(importId)}/finish`, { method: 'POST', body: { skipRest } });
export const getImportResult = (importId) => request(`imports/${id(importId)}/result`);
/** Removes the import and its previews; the folder and photos already filed are never touched. */
export const discardImport = (importId) => request(`imports/${id(importId)}`, { method: 'DELETE' });
/** An item's 480 px preview, or null until it has been read (`v` is set once it is, and changes when it is read again). */
export const importPreviewUrl = (importId, item) =>
  item.v == null ? null : `./api/imports/${id(importId)}/items/${id(item.id)}/preview?v=${q(item.v)}`;
/** The source photo for the large view. */
export const importDisplayUrl = (importId, item) =>
  `./api/imports/${id(importId)}/items/${id(item.id)}/display${item.v == null ? '' : `?v=${q(item.v)}`}`;
