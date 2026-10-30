import { CURRENT_LIBRARY, EXISTING_LIBRARY, INSIDE_LIBRARY, LIBRARY_IN_USE, NOT_ENOUGH_SPACE } from './codes.js';
import { formatBytes } from './filter.js';
import {
  CURRENT_LIBRARY_WORDS, EXISTING_LIBRARY_WORDS, LIBRARY_IN_USE_WORDS, MOVE_HERE, OUTSIDE_LIBRARY_WORDS, movingToLine, tooLittleSpaceLine,
} from './messages.js';
import { setupCanChoose, setupHere, setupLabel } from './setupView.js';

/**
 * What the folder browser says of the open folder when the library is moved or changed from Settings
 * (change-library-folder design §3), as pure functions of the server's check (GET /api/library/change/inspect, contract
 * §3: setup's FolderCheck with `refusal`, and for a move `free` and `needed`). `kind` is 'move' or 'switch'.
 *
 * Move library asks for a place for a new library: only `create` can be chosen ("Move here"), saying where it goes, how big
 * the library is (`size`, bytes) and what the drive has free. Use a different library says what setup says, with
 * setup's buttons. Either refuses, with its own words, a folder inside the library or the library itself, a library
 * another copy holds, and (move) a library already there or a drive without the room.
 */

function refusalWords(check, kind) {
  switch (check.refusal) {
    case EXISTING_LIBRARY: return EXISTING_LIBRARY_WORDS;
    case INSIDE_LIBRARY: return OUTSIDE_LIBRARY_WORDS;
    // A move's table says the same for the library itself as for a folder inside it.
    case CURRENT_LIBRARY: return kind === 'switch' ? CURRENT_LIBRARY_WORDS : OUTSIDE_LIBRARY_WORDS;
    case LIBRARY_IN_USE: return LIBRARY_IN_USE_WORDS;
    case NOT_ENOUGH_SPACE: return tooLittleSpaceLine(formatBytes(check.free ?? 0), formatBytes(check.needed ?? 0));
    default: return null;
  }
}

/** The line beside the button for a folder's check; '' before there is one. */
export function changeHere(check, kind, size) {
  if (!check) return '';
  const refused = check.refusal ? refusalWords(check, kind) : null;
  if (refused) return refused;
  if (kind === 'move') {
    if (check.kind === 'create') return movingToLine(check.libraryFolder, formatBytes(size ?? 0), formatBytes(check.free ?? 0));
    if (check.kind === 'existing') return EXISTING_LIBRARY_WORDS;
  }
  return setupHere(check);
}

/** The button's words: Move here, or setup's. */
export const changeLabel = (check, kind) => (kind === 'move' ? MOVE_HERE : setupLabel(check));

/** Only a folder the server didn't refuse: a new library for a move; a library to open or to create for a switch. */
export function changeCanChoose(check, kind) {
  if (!check || check.refusal) return false;
  return kind === 'move' ? check.kind === 'create' : setupCanChoose(check);
}
