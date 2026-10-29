import { OPEN_EXISTING_LIBRARY, USE_THIS_FOLDER } from './messages.js';

/**
 * What the setup screen and the folder browser's setup mode say of a folder, as pure functions of the server's check
 * (GET /api/setup/inspect, setup design §2): the line under the button, the button's words, and whether it can be chosen.
 */

const plural = (n, one, many) => (n === 1 ? `1 ${one}` : `${n.toLocaleString()} ${many}`);

/** The unfinished work an existing library will lose, e.g. "1 import and 1 paused export"; '' when there is none. */
export function unfinishedNote(unfinished) {
  if (!unfinished) return '';
  const parts = [];
  if (unfinished.imports) parts.push(plural(unfinished.imports, 'import', 'imports'));
  if (unfinished.exports) parts.push(plural(unfinished.exports, 'paused export', 'paused exports'));
  if (unfinished.backups) parts.push(plural(unfinished.backups, 'paused backup', 'paused backups'));
  return parts.length > 1 ? `${parts.slice(0, -1).join(', ')} and ${parts.at(-1)}` : parts.join('');
}

/** The line under the button for a folder's check; '' before there is one. */
export function setupHere(check) {
  if (!check) return '';
  switch (check.kind) {
    case 'existing': {
      const opens = check.libraryFolder === check.path
        ? 'This folder already has a Butterfly Atlas library. It will open as it is'
        : `Your library in ${check.libraryFolder} will open as it is`;
      const note = unfinishedNote(check.unfinished);
      if (!note) return `${opens}.`;
      const one = Object.values(check.unfinished).reduce((sum, n) => sum + n, 0) === 1;
      return `${opens}, except for work left unfinished where it was used before: ${note}. ${one ? 'It' : 'They'} will be`
        + ' cleared; photos already added stay in the library.';
    }
    case 'create': return `Your library will be created in ${check.libraryFolder}.`;
    case 'taken': return `${check.libraryFolder} already exists and isn't a Butterfly Atlas library. Choose another folder.`;
    case 'not_writable': return "Butterfly Atlas can't save files in this folder.";
    case 'not_found': return "That folder can't be found.";
    case 'not_a_folder': return 'That is a file, not a folder.';
    default: return 'Enter the full path of a folder.';
  }
}

/** The button's words: open the library that is there, or use the folder. */
export const setupLabel = (check) => (check?.kind === 'existing' ? OPEN_EXISTING_LIBRARY : USE_THIS_FOLDER);

/** Only a library to open or to create can be chosen. */
export const setupCanChoose = (check) => check?.kind === 'existing' || check?.kind === 'create';
