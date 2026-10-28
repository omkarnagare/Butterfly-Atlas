import { DECODER_AVAILABLE, DECODER_CHECKING, DECODER_MISSING } from './states.js';
/**
 * HEIC photos (Phase 7b): what the page says about them. A HEIC photo (`fileType: 'heic'`) is shown through the
 * computer's own decoder; whether there is one is `/api/status` `heic` (`{ state: 'checking' | 'available' | 'missing',
 * decoder: 'sips' | 'wic' | 'heif-convert' | null, system: 'mac' | 'windows' | 'linux' | 'other', reason: 'absent' |
 * 'failed' | 'blocked' | 'timed_out' | 'no_heif' | null }`, null from an older server). A photo's `heic` is
 * `{ tenBit, auxiliary }` (null when its details could not be read). Words are the contract's (§1) with the audit's
 * (4, 7, 20, 21).
 */

export const isHeic = (photo) => photo?.fileType === 'heic';

/** While the decoder check runs, the viewer says this and loads the photo once it is over (audit 7). */
export const HEIC_CHECKING = 'Checking for a HEIC decoder…';

/** The viewer's note for a HEIC photo the decoder couldn't read. */
export const HEIC_DECODE_FAILED = "This HEIC photo can't be shown: the computer's decoder couldn't read it. The file may be damaged, "
  + "or use a HEIC feature the decoder doesn't support. Save it as JPEG on the device it came from and add that.";

const SAFE = 'Your photo is safely in the library.';
const NOT_YET = "This computer can't show HEIC photos yet.";
const NO_HEIF = "Install Windows' HEIF and HEVC decoders, then choose Check again in Settings → About.";
// Review fix U2: the reasons' own words (J10, shared with the server's notes).
const TIMED_OUT = "Butterfly Atlas's check for a HEIC decoder took too long (the computer may be busy). Choose Check again in Settings → About.";
const BLOCKED = "Windows didn't let Butterfly Atlas run its HEIC decoder (PowerShell). Security software may be blocking it: "
  + 'allow Windows PowerShell for Butterfly Atlas, then choose Check again in Settings → About.';
const LINUX_FAILED = "heif-convert is installed but didn't work. Choose Check again in Settings → About; if it keeps failing, reinstall it "
  + "from your distribution's packages.";
const WINDOWS_FAILED = "Windows' HEIC decoder didn't work. Reinstall Windows' HEVC decoder, then choose "
  + 'Check again in Settings → About.';
/** A working copy that can't be made in the library's `.thumbs` (J3): never "the decoder is missing". */
export const WORKSPACE = "Butterfly Atlas couldn't use its working folder in your library (.thumbs). Check that the library's drive is "
  + 'connected and has free space, then try again.';

// The sentence for a reason that has its own (whatever the system), else null.
function reasonSentence(heic) {
  switch (heic?.reason) {
    case 'workspace': return WORKSPACE;
    case 'timed_out': return TIMED_OUT;
    case 'blocked': return BLOCKED;
    case 'no_heif': return NO_HEIF;
    case 'failed': return heic.system === 'linux' ? LINUX_FAILED : heic.system === 'windows' ? WINDOWS_FAILED : null;
    default: return null;
  }
}

/**
 * The viewer's note for a HEIC photo on a computer without a working decoder, by its system and why (contract §1, audit
 * 4, review fix U2): the reason's own words when it has them (timed out, failed on Linux or Windows, blocked, the working
 * folder), else the system's install note.
 */
export function installNote(heic) {
  if (heic?.reason === 'workspace') return WORKSPACE;
  if (heic?.reason === 'no_heif') return `${NOT_YET} ${NO_HEIF} ${SAFE}`;
  const own = reasonSentence(heic);
  if (own) return `${own} ${SAFE}`;
  switch (heic?.system) {
    case 'windows':
      return `${NOT_YET} Install Windows' HEVC decoder, then choose Check again in Settings → About. ${SAFE}`;
    case 'linux':
      return `${NOT_YET} Install heif-convert from your distribution's packages, then choose Check again in Settings → About. ${SAFE}`;
    case 'mac':
      return "This Mac couldn't show HEIC photos: its built-in sips tool didn't work. Choose Check again in Settings → About; if it keeps failing, save the photo as JPEG and add that.";
    default:
      return `This computer has no HEIC decoder Butterfly Atlas can use. Save the photo as JPEG and add that. ${SAFE}`;
  }
}

/**
 * What to do, in one sentence (audit 20, 21, review fix U2): after "HEIC photos can't be shown on this computer.", "Still
 * not available." and the Add photos report.
 */
export function installSentence(heic) {
  const own = reasonSentence(heic);
  if (own) return own;
  switch (heic?.system) {
    case 'windows':
      return "Install Windows' HEVC decoder, then choose Check again in Settings → About.";
    case 'linux':
      return "Install heif-convert from your distribution's packages, then choose Check again in Settings → About.";
    case 'mac':
      return "macOS's built-in sips tool didn't work; choose Check again in Settings → About.";
    default:
      return 'Save HEIC photos as JPEG and add those.';
  }
}

/** Is the decoder known to be missing (a HEIC can't be shown now). */
export const heicMissing = (heic) => heic?.state === DECODER_MISSING;

const DECODERS = {
  sips: "HEIC photos: shown with macOS's sips.",
  wic: "HEIC photos: shown with Windows' HEVC decoder.",
  'heif-convert': 'HEIC photos: shown with heif-convert.',
};
const CHECKING_LINE = 'HEIC photos: checking for a decoder…';

/** Settings → About's line (contract §1, audit 21); '' when the server says nothing about HEIC. */
export function aboutLine(heic) {
  if (!heic) return '';
  if (heic.state === DECODER_CHECKING) return CHECKING_LINE;
  if (heic.state === DECODER_MISSING) return `HEIC photos can't be shown on this computer. ${installSentence(heic)}`;
  const shown = DECODERS[heic.decoder] ?? "HEIC photos: shown with this computer's decoder.";
  // The working folder can fail with the decoder there (J3): said beside it.
  return heic.reason === 'workspace' ? `${shown} ${WORKSPACE}` : shown;
}

/** What Check again found: shown now, still checking (the page keeps following it), or still not available, and what to do. */
export function checkResult(heic) {
  if (heic?.state === DECODER_AVAILABLE) return 'HEIC photos can be shown now. Making their thumbnails…';
  if (heic?.state === DECODER_CHECKING) return CHECKING_LINE;
  return `Still not available. ${installSentence(heic)}`;
}

/**
 * The viewer side panel's lines under File (contract §1): "HEIC, 4032 × 3024" (the size as displayed), then, when they
 * apply, the standard-range line (10-bit) and the extra-images line. [] for other photos.
 */
export function heicLines(photo) {
  if (!isHeic(photo)) return [];
  const lines = photo.width && photo.height ? [`HEIC, ${photo.width} × ${photo.height}`] : ['HEIC'];
  if (photo.heic?.tenBit) lines.push('Shown in standard range (the file keeps its full range).');
  if (photo.heic?.auxiliary) lines.push("Shows the main picture. The depth map and other extra images stay in the file but aren't shown.");
  return lines;
}

/**
 * What a viewer passes ZoomPan for a HEIC photo or import item, chosen up front from the decoder's state (audit 7):
 * missing → the install note at once, nothing loaded; checking → "Checking for a HEIC decoder…", loaded once the state
 * changes; a photo the decoder failed on (`unreadable`, or an import item's `previewFailed`) → the decode note at once;
 * otherwise loaded, and on an error the page asks the server why (`explain`) and says its words.
 */
export function heicViewerProps(photo, heic) {
  if (heic?.state === DECODER_MISSING) return { broken: true, failedNote: installNote(heic) };
  if (heic?.state === DECODER_CHECKING) return { broken: true, failedNote: HEIC_CHECKING };
  if (photo.unreadable || photo.previewFailed) return { broken: true, failedNote: HEIC_DECODE_FAILED };
  return { explain: true, failedNote: HEIC_DECODE_FAILED };
}

const things = (n, one, many) => (n === 1 ? one : many);

// What the import bar's photos wait for, by reason, then by system (review fix U2).
function barEnding(heic) {
  switch (heic?.reason) {
    case 'workspace': return 'once Butterfly Atlas can use its working folder again';
    case 'timed_out': return 'once Check again finds the decoder';
    case 'blocked': return 'once Windows lets Butterfly Atlas run its HEIC decoder';
    case 'no_heif': return "once Windows' HEIF and HEVC decoders are installed";
    case 'failed':
      if (heic.system === 'windows') return "once Windows' HEVC decoder works again";
      if (heic.system === 'linux') return 'once heif-convert works';
      break;
    default:
  }
  return heic?.system === 'windows' ? "once Windows' HEVC decoder is installed"
    : heic?.system === 'linux' ? 'once heif-convert is installed' : 'once a HEIC decoder works on this computer';
}

/**
 * The import grid's bar while the decoder is missing (contract §1): "This computer can't show the 214 HEIC photos in this
 * folder. They can still be assigned and filed; they'll appear once Windows' HEVC decoder is installed." (one photo:
 * "the 1 HEIC photo … It can still be … it'll appear …").
 */
export function importBarLine(count, heic) {
  const once = barEnding(heic);
  const photos = things(count, 'the 1 HEIC photo', `the ${count.toLocaleString()} HEIC photos`);
  const still = things(count, "It can still be assigned and filed; it'll appear", "They can still be assigned and filed; they'll appear");
  return `This computer can't show ${photos} in this folder. ${still} ${once}.`;
}

/** The gallery's, species page's and Explore's bar while the decoder is missing and HEIC photos are shown (audit D1). */
export const GALLERY_BAR = "This computer can't show HEIC photos yet.";
/** On a system with no decoder Butterfly Atlas knows (`system: 'other'`): nothing to wait for, so no "yet" (review fix U11). */
export const NO_DECODER = 'This computer has no HEIC decoder Butterfly Atlas can use.';
/** The bars' first sentence, by system (review fix U11). */
export const galleryBar = (heic) => (heic?.system === 'other' ? NO_DECODER : GALLERY_BAR);

/**
 * The Add photos report's line for HEIC photos added while the decoder is missing (audit 20): "3 HEIC photos were added,
 * but this computer can't show them yet." and what to do; '' for none.
 */
export function addedLine(count, heic) {
  if (!count) return '';
  const added = count === 1 ? "1 HEIC photo was added, but this computer can't show it yet."
    : `${count.toLocaleString()} HEIC photos were added, but this computer can't show them yet.`;
  return `${added} ${installSentence(heic)}`;
}
