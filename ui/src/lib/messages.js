/**
 * Words the UI shows in more than one place, kept here so every place says them the same way. Words used in one place
 * stay next to their screen.
 */

// The photo viewers (photos, import, export, split).
export const PREVIOUS_PHOTO = 'Previous photo';
export const NEXT_PHOTO = 'Next photo';
export const ABOUT_THIS_PHOTO = 'About this photo';
export const CLOSE = 'Close';
export const viewerLabel = (title) => `Photo viewer: ${title}`;
export const UNKNOWN_PLACE = 'Unknown place';

// Search results (the top bar's search and the Taxa filter): a hit found by an older or the user's own name.
export const ALSO_KNOWN_AS = 'also known as';

// Progress bars and their buttons.
export const PHOTOS_MOVED = 'Photos moved';
export const PHOTOS_BACKED_UP = 'Photos backed up';
export const PHOTOS_FILED = 'Photos filed';
export const PHOTOS_EXPORTED = 'Photos exported';
export const PAUSE = 'Pause';
export const PAUSING = 'Pausing…';
export const RESUME = 'Resume';
export const STARTING = 'Starting…';
export const STOPPING = 'Stopping…';
export const CHECKING_THE_FOLDER = 'Checking the folder…';

// Past exports and backups.
export const SHOW_DETAILS = 'Show details';
export const HIDE_DETAILS = 'Hide details';
export const SHOW_FEWER = 'Show fewer';
export const CLEAR_HISTORY = 'Clear history';
export const REMOVE = 'Remove';
export const NOT_COPIED = 'Not copied';
export const PHOTOS_NOT_COPIED = 'Photos that were not copied';
export const EXPORT_FINISHED = 'Export finished.';
export const BACKUP_FINISHED = 'Backup finished.';
export const DATABASE_ONLY = 'Database and settings only';
export const SPECIES_LIST_CSV = 'Species list (CSV)';

// Cards for work that stopped.
export const PAUSED_TAXONOMY_UPDATE = 'Paused taxonomy update';
export const TAXONOMY_UPDATED = 'Taxonomy updated';
export const CUSTOM_SPECIES_MERGED = 'Custom species merged';
export const PAUSED_EXPORT = 'Paused export';
export const IMPORTS_THAT_STOPPED = 'Imports that stopped';

// Imports.
export const WAIT_FOR_SAVE = 'Wait for the last change to be saved.';
export const WAIT_FOR_FILING = 'Wait for filing to finish or pause it.';

// Filters.
export const PHOTOGRAPHED = 'Photographed';
export const NOT_PHOTOGRAPHED = 'Not photographed';
export const PHOTO_STATUS = 'Photo status';
export const FILE_TYPE = 'File type';
export const NOTHING_MATCHES = 'Nothing matches these filters.';
export const PLACE_PROMPT = 'Type a district, state or country';

// Setup.
export const USE_THIS_FOLDER = 'Use this folder';
export const OPEN_EXISTING_LIBRARY = 'Open this existing library';

// Settings: Appearance (Meadow design §6).
export const APPEARANCE = 'Appearance';
export const LIGHT = 'Light';
export const DARK = 'Dark';
export const APPEARANCE_NOTE = 'Until you choose, Butterfly Atlas matches your computer.';

// A page with no session (api.js's refusal and the NoSession page; app SessionGuard.MESSAGE says the same).
export const NO_SESSION_WORDS = 'To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.';
