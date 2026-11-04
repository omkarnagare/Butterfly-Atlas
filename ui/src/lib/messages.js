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
export const LIFE_STAGES = 'Life stages';
export const NOT_WITH_LIFE_STAGES = 'Not with Life stages';
export const NOT_WITH_TAGS_OR_STATUS = 'Not with Tags or Photo status';
/** The line for filters an address had alongside Life stages and the screen took out ('tags', 'status'). */
export function removedForLifeStages(kinds) {
  const named = kinds.map((kind) => (kind === 'tags' ? 'Tags' : PHOTO_STATUS));
  return `Removed ${named.join(' and ')}: ${LIFE_STAGES} can't be combined with ${named.length > 1 ? 'them' : 'it'}.`;
}
export const NOTHING_MATCHES = 'Nothing matches these filters.';
export const PLACE_PROMPT = 'Type a district, state or country';

// Setup.
export const USE_THIS_FOLDER = 'Use this folder';
export const OPEN_EXISTING_LIBRARY = 'Open this existing library';

// Settings: Appearance (Meadow design §6, match-device design §2).
export const APPEARANCE = 'Appearance';
export const MATCH_DEVICE = 'Match device';
export const LIGHT = 'Light';
export const DARK = 'Dark';
export const APPEARANCE_NOTE = "Match device tries to follow your device's light or dark setting. If it stays light while your device is dark, choose Dark.";

// A page with no session (api.js's refusal and the NoSession page; app SessionGuard.MESSAGE says the same).
export const NO_SESSION_WORDS = 'To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.';

// Tag names (tag-names design §2, §5): the server's refusals, said before Add or Save, and the name it will be saved as.
export const TAG_NAME_EMPTY = 'Type a name for the tag.';
export const TAG_NAME_TOO_MANY_WORDS = 'Keep tag names to 3 words or fewer.';
export const TAG_NAME_TOO_LONG = 'Keep tag names to 40 characters or fewer.';
export const tagNameTaken = (name) => `There is already a tag called "${name}".`;
export const tagSavedAs = (name) => `Saved as: ${name}`;
export const OVER_3_WORDS = 'over 3 words';

// Change the library folder (change-library-folder design). Sizes come in as words ("12.4 GB", lib/filter.js formatBytes).
const fileCount = (n) => (n === 1 ? '1 file' : `${n.toLocaleString()} files`);
// Settings → Library folder (§2, §3).
export const LIBRARY_FOLDER = 'Library folder';
export const LIBRARY_FOLDER_HINT = 'Where your photos and their database are kept';
export const FOLDER = 'Folder';
export const SIZE = 'Size';
export const FREE_ON_DRIVE = 'Free on its drive';
export const MOVE_LIBRARY = 'Move library…';
export const USE_DIFFERENT_LIBRARY = 'Use a different library…';
export const FIXED_FOLDER_NOTE = 'This copy of Butterfly Atlas was started on a fixed folder.';
// The folder browser for a move or a switch (§3).
export const CHOOSE_MOVE_TITLE = 'Choose where to move your library';
export const CHOOSE_SWITCH_TITLE = 'Choose a library to use';
export const MOVE_HERE = 'Move here';
export const movingToLine = (folder, size, free) => `Your library will be moved to ${folder}. It is ${size}; that drive has ${free} free.`;
export const tooLittleSpaceLine = (free, needed) => `That drive has ${free} free; moving your library needs about ${needed}.`;
export const EXISTING_LIBRARY_WORDS = 'That folder already holds a library. To open it, use Use a different library.';
export const OUTSIDE_LIBRARY_WORDS = 'Choose a folder outside your library.';
export const CURRENT_LIBRARY_WORDS = 'That is the library you are using.';
/** The server's words for a library another copy of the app holds (app SetupService, `library_in_use`). */
export const LIBRARY_IN_USE_WORDS = 'Another copy of Butterfly Atlas is using this library.';
// The change screen in every window (§5–§7).
export const MOVING_LIBRARY = 'Moving your library';
export const SWITCHING_LIBRARY = 'Changing to another library';
export const changeToLine = (folder) => `To ${folder}`;
export const STEP_COPYING = 'Copying your photos and files…';
export const STEP_COPYING_DATABASE = 'Copying the database…';
export const STEP_CHECKING_COPY = 'Checking the copy…';
export const STEP_CHECKING_LIBRARY = 'Checking the library…';
export const STEP_SWITCHING = 'Switching to the new folder…';
export const STEP_CANCELLING = 'Cancelling — removing the copy…';
export const STEP_REOPENING = 'Reopening Butterfly Atlas…';
export const CHANGE_BLOCKS = 'Butterfly Atlas can\'t be used in any window until this is done or cancelled.';
export const filesLine = (done, total) => `${done.toLocaleString()} of ${fileCount(total)}`;
export const FILES_COPIED = 'Files copied';
export const CANCEL = 'Cancel';
export const CANCELLING = 'Cancelling…';
export const MOVE_FAILED = "Your library couldn't be moved";
export const SWITCH_FAILED = "The library couldn't be changed";
export const MOVE_CANCELLED = 'The move was cancelled';
export const SWITCH_CANCELLED = 'The change was cancelled';
export const LIBRARY_UNCHANGED = 'Your library is where it was, and nothing in it was changed.';
export const copyLeftLine = (folder, files) => `Some of the copy at ${folder} couldn't be removed (${fileCount(files)}). You can delete it yourself.`;
export const BACK_TO_SETTINGS = 'Back to Settings';
export const GOING_BACK = 'Going back…';
// The last page (§6): the folder goes on its own line after LIBRARY_NOW_AT, so a long path wraps.
export const REOPENING_TITLE = 'Reopening Butterfly Atlas';
export const CLOSED_TITLE = 'Butterfly Atlas has closed';
export const LIBRARY_NOW_AT = 'Your library is now at';
export const OPENS_IN_NEW_WINDOW = 'It opens in a new window. You can close this one.';
export const OPEN_AGAIN = 'Open Butterfly Atlas again to continue.';
export const COULD_NOT_REOPEN = "Butterfly Atlas couldn't reopen itself.";
// Settings → the old copy after a move (§9).
export const oldCopyLine = (folder, size) => `The old copy of your library is still at ${folder} (${size}).`;
export const DELETE_OLD_COPY = 'Delete old copy';
export const DELETE_OLD_COPY_TITLE = 'Delete the old copy?';
export const DELETING = 'Deleting…';
export const deleteOldCopyAsk = (old, current) => `Delete the old copy at ${old}? Your library at ${current} is the one in use.`;
export const lastBackupLine = (what, date) => `Your last backup (${what}) was on ${date}.`;
export const NO_BACKUP_YET = 'You have no backup yet.';
export const GO_TO_BACKUP = 'Go to Backup';
export const OLD_COPY_DELETED = 'The old copy was deleted.';
export const oldCopyLeftLine = (folder, files) => `Some of the old copy at ${folder} couldn't be deleted (${fileCount(files)}). Choose Delete old copy`
  + ` to try again, or delete ${files === 1 ? 'it' : 'them'} yourself.`;
export const otherFilesLeftLine = (folder, files) => (files === 1
  ? `1 file that isn't part of a library was left in ${folder}.`
  : `${fileCount(files)} that aren't part of a library were left in ${folder}.`);
export const andMoreLine = (n) => `and ${n.toLocaleString()} more`;
// The setup screen when the new library won't open (§6).
export const usePreviousLabel = (folder) => `Use the old library at ${folder} instead`;
export const OPENING_OLD_LIBRARY = 'Opening the old library…';
export const movedCopyLine = (folder) => `The moved copy at ${folder} was not deleted.`;
// Home, once after a move (§6).
export const libraryMovedLine = (folder) => `Your library was moved to ${folder}.`;
export const LIBRARY_MOVED = 'Library moved';
export const DISMISS = 'Dismiss';

// One device at a time (one-device-library design §4–§8, §10). A sentence naming the other device comes as
// `{ lead, name, rest, bold }`: the name is bold where the spec has it, and "another device" (plain) when it couldn't be read.
export const ANOTHER_DEVICE = 'another device';
const THE_OTHER_DEVICE = 'the other device';
const named = (lead, name, rest) => ({ lead, name: name ?? ANOTHER_DEVICE, rest, bold: Boolean(name) });
/** "just now", "2 minutes ago", "3 hours ago", "2 days ago". */
export function agoWords(minutes) {
  if (minutes <= 0) return 'just now';
  if (minutes < 60) return minutes === 1 ? '1 minute ago' : `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? '1 day ago' : `${days} days ago`;
}
// The setup page when another device has the library open (§4).
export const ON_OTHER_DEVICE_TITLE = 'Your library is open on another device';
/** `seen` "4 Nov at 11:05", `ago` "2 minutes ago", `from` "11:20"; `late`: 15 minutes or more have passed. */
export const otherDeviceWords = ({ name, seen, ago, from, late }) => named('This library is open on ', name, ` (last seen ${seen}, ${ago}). `
  + (late
    ? 'It may be off or asleep, or it may have closed without quitting properly. Quit Butterfly Atlas there if you can, let your sync app'
      + ' finish, then choose Try again.'
    : `Quit Butterfly Atlas there, let your sync app finish, then choose Try again. If that device is off or asleep, you can open the library here from ${from}.`));
export const TRY_AGAIN = 'Try again';
export const TRYING_AGAIN = 'Trying again…';
export const triedAgainLine = (time) => `Tried again at ${time} — still couldn't open.`;
export const OPEN_HERE_ANYWAY_ASK = 'Open here anyway…';
export const OPEN_HERE_ANYWAY = 'Open here anyway';
export const OPENING_HERE = 'Opening…';
export const OPEN_HERE_TITLE = 'Open the library here?';
export const openAnywayWarning = (name) => `If ${name ?? THE_OTHER_DEVICE} is still running Butterfly Atlas, or didn't send its last changes,`
  + ' those changes will be lost.';
// The setup page when the database hasn't arrived yet (§5 case 1).
export const NOT_SYNCED_TITLE = "Your library hasn't finished syncing";
export const NOT_SYNCED_WORDS = "This library's database hasn't finished syncing to this device yet. Let your sync app finish, then choose Try again.";
// The setup page when unsent changes were discarded (§7). `at` "4 Nov at 14:20", `since` "4 Nov, 14:02".
export const DISCARDED_TITLE = 'Unsent changes were discarded';
export const discardedWords = ({ name, at, since }) => named('This library was used on ', name, ` (last on ${at}) after this device last`
  + ` sent its changes. Changes made here after ${since} couldn't be sent and have been discarded.`);
export const DISCARDED_PHOTOS_LEAD = "These photos are in your library's folder but no longer in your atlas; import them again to add them back:";
export const CONTINUE = 'Continue';
export const CONTINUING = 'Continuing…';
// Every window, when the library was opened on another device while this one ran (§6). `at` "4 Nov at 14:20".
export const OPENED_ELSEWHERE_TITLE = 'Your library was opened on another device';
export const openedElsewhereWords = ({ name, at }) => named('Your library was opened on ', name, ` on ${at} while Butterfly Atlas was still`
  + ' open here. To keep one version of your library, this device has stopped saving. To use the library here again, quit Butterfly'
  + ` Atlas on ${name ?? THE_OTHER_DEVICE} and let your sync app finish, then quit and reopen it here.`);
/** `since` "4 Nov, 14:02". */
export const unsentLine = (since, name) => `Changes made here since ${since} haven't reached the library yet. They will be sent when you`
  + ` reopen Butterfly Atlas here, unless changes are saved on ${name ?? THE_OTHER_DEVICE} first.`;
export const QUIT = 'Quit';
export const QUITTING = 'Quitting…';
export const CLOSED_HERE = 'Butterfly Atlas has closed here. You can close this window.';
// The publishing bar under the header (§10). The reason (SetupService.plain's sentences) goes inside the sentence.
export const PUBLISH_BAR_LABEL = 'Sending changes to your library';
const inSentence = (reason) => {
  const words = (reason ?? '').trim().replace(/\.$/, '');
  if (!words) return '';
  return words.startsWith('Butterfly Atlas') ? words : words.charAt(0).toLowerCase() + words.slice(1);
};
export function publishLine({ state, reason }) {
  const why = inSentence(reason);
  const until = "Don't open your library on another device until this message goes away.";
  if (state === 'stopped') {
    return `Your changes are saved on this device but couldn't be sent to your library folder after 5 tries${why ? `: ${why}` : ''}. Fix that`
      + ` (for example, start your sync app or free some space), then choose Try again. ${until}`;
  }
  return `Your changes are saved on this device but couldn't be sent to your library folder${why ? `: ${why}` : ''}. Trying again in a minute. ${until}`;
}
// Home's one-time notes (§5 case 2, §8).
export const SYNC_SENT_TITLE = 'Changes sent';
export const SYNC_SENT_LINE = 'Changes from your last session on this device were sent to the library.';
export const SYNC_BEFORE_OTHER_TITLE = 'Before using another device';
export const SYNC_BEFORE_OTHER_LINE = 'Before you open your library on another device, quit Butterfly Atlas here and let your sync app finish.';
