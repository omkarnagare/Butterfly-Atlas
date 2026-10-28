import { heicLines, heicViewerProps, isHeic } from './heic.js';

/**
 * Camera RAW photos (Phase 7a): what the page says about them and how it turns their preview upright. A RAW photo
 * (`fileType: 'raw'`) shows the JPEG preview the camera stored inside it; `rawPreview` is `{ width, height,
 * orientation }`, the size as displayed and the EXIF orientation (1–8) the page applies to `/display`'s bytes
 * (thumbnails and import previews are already upright). Words are the contract's (§1).
 */

/** On a tile whose thumbnail can't be made (any format, decision D1). */
export const CANT_SHOW = "Can't show this photo";

/** The viewer's note for a RAW photo whose preview can't be shown, with what to do (review fix U2). */
export const RAW_UNREADABLE = "This RAW photo's preview can't be read any more. The file may have changed or be damaged. "
  + "Add it again from your camera's card, or save it as JPEG from your RAW software and add that.";

/** The viewer's note for any other photo that can't be shown (`unreadable`), with what to do (review fix U2). */
export const UNREADABLE = "This photo can't be shown: its file may be damaged, or in a form Butterfly Atlas can't read. "
  + 'Replace the file, or delete the photo here and add it again as a JPEG.';

export const isRaw = (photo) => photo?.fileType === 'raw';

/** The badge at a tile's top right for its file type: "RAW", "HEIC" (Phase 7b), else ''. */
export const typeBadge = (photo) => (isRaw(photo) ? 'RAW' : isHeic(photo) ? 'HEIC' : '');

/** Orientations 5–8 turn the picture a quarter: the stored image's width is the shown height. */
export const turned = (orientation) => orientation >= 5 && orientation <= 8;

// CSS transforms apply right to left: 5 (transpose) flips top to bottom, then turns a quarter clockwise.
const TRANSFORMS = {
  2: 'scaleX(-1)',
  3: 'rotate(180deg)',
  4: 'scaleY(-1)',
  5: 'rotate(90deg) scaleY(-1)',
  6: 'rotate(90deg)',
  7: 'rotate(90deg) scaleX(-1)',
  8: 'rotate(-90deg)',
};

/** The CSS transform that shows an image stored with EXIF `orientation` upright; '' for 1 or anything unknown. */
export const orientTransform = (orientation) => TRANSFORMS[orientation] ?? '';

/** The turn to apply to a photo's `/display` image: its RAW preview's orientation, else 1 (as it is). */
export const displayOrientation = (photo) => (isRaw(photo) ? photo.rawPreview?.orientation ?? 1 : 1);

/** A RAW photo the viewer can't show: no preview was found (rescan), or its thumbnail has failed for good. */
export const rawBroken = (photo) => isRaw(photo) && (!photo.rawPreview || Boolean(photo.unreadable));

/** The viewer's line: "RAW preview, 1616 × 1080" (the preview's size as displayed), or '' for other photos. */
export function rawLine(photo) {
  if (!isRaw(photo) || !photo.rawPreview) return '';
  return `RAW preview, ${photo.rawPreview.width} × ${photo.rawPreview.height}`;
}

/** The viewer side panel's lines under File: the RAW line, or a HEIC photo's (Phase 7b); [] for the rest. */
export const fileLines = (photo) => (isHeic(photo) ? heicLines(photo) : rawLine(photo) ? [rawLine(photo)] : []);

/** Past exports, the finished progress card and Home: "12 RAW photos exported with their location."; '' for none. */
export const rawExportedLine = (count) => exactExportedLine(count, 0);

// "12 RAW", "30 HEIC" or "12 RAW and 30 HEIC" (Phase 7b): the photos exported exactly, as said; the noun follows.
function exactWhat(raw, heic) {
  if (raw && heic) return { one: false, what: `${raw.toLocaleString()} RAW and ${heic.toLocaleString()} HEIC photos` };
  const n = raw || heic;
  const kind = heic ? 'HEIC' : 'RAW';
  return n === 1 ? { one: true, what: `1 ${kind} photo` } : { one: false, what: `${n.toLocaleString()} ${kind} photos` };
}

/**
 * Past exports and the "Export finished." notice (Phase 7b contract §1), from `rawCount` and `heicCount`: "30 HEIC photos
 * exported with their location.", "1 HEIC photo exported with its location.", "12 RAW and 30 HEIC photos exported with
 * their location."; '' for none.
 */
export function exactExportedLine(rawCount, heicCount) {
  if (!rawCount && !heicCount) return '';
  const { one, what } = exactWhat(rawCount ?? 0, heicCount ?? 0);
  return `${what} exported with ${one ? 'its' : 'their'} location.`;
}

/** The Export sheet's box (Phase 7b contract §1): "Export the RAW photos …", "… the HEIC photos …", "… the RAW and HEIC photos …". */
export function exactBox(raw, heic) {
  const kinds = [raw?.photos ? 'RAW' : '', heic?.photos ? 'HEIC' : ''].filter(Boolean).join(' and ');
  return `Export the ${kinds || 'RAW'} photos with their location`;
}

/**
 * What a library viewer passes ZoomPan for a photo (spread into its props): for RAW, the turn, whether its preview can't
 * be shown at all, and the RAW note; for any other photo that can't be shown (`unreadable`), that it can't, said at once
 * with its own note (review fix U2), so ZoomPan's "showing a preview" never stands for it; nothing for the rest. A HEIC
 * photo's are chosen from the decoder's state `heic` (/api/status, Phase 7b; lib/heic.js).
 */
export function viewerProps(photo, heic = null) {
  if (isHeic(photo)) return heicViewerProps(photo, heic);
  if (isRaw(photo)) return { orientation: displayOrientation(photo), broken: rawBroken(photo), failedNote: RAW_UNREADABLE };
  if (photo?.unreadable) return { broken: true, failedNote: UNREADABLE };
  return {};
}

const REMOVE_APPLIES = 'Remove location applies only to JPEG, PNG, TIFF and WebP.';

/**
 * The Export sheet's warning for the RAW photos it would copy (`check.raw`, `{ photos, withGps, withUnknown }`): "12 RAW
 * photos will be exported exactly as they are, with any location they hold. 9 of them have GPS positions. Remove
 * location applies only to JPEG, PNG, TIFF and WebP." (one photo: "… as it is, … it holds. It has a GPS position." /
 * "It has no GPS position."; none with GPS: "None of them have GPS positions."). RAW photos whose stored details could
 * not be read (`withUnknown`, GPS unknown) are said apart: "3 of them couldn't be checked for a GPS position." (one
 * photo: "It couldn't be checked for a GPS position."), and "none" / "no GPS position" only when every one was checked
 * (review fix U3).
 */
export const rawWarning = (raw) => exactWarning(raw, null);

/**
 * The same warning for the RAW and the HEIC photos (`check.raw`, `check.heic`, Phase 7b contract §1): "30 HEIC photos will
 * be exported …", "12 RAW and 30 HEIC photos will be exported …", the GPS counts added up.
 */
export function exactWarning(raw, heic) {
  const withGps = (raw?.withGps ?? 0) + (heic?.withGps ?? 0);
  const withUnknown = (raw?.withUnknown ?? 0) + (heic?.withUnknown ?? 0);
  const { one, what } = exactWhat(raw?.photos ?? 0, heic?.photos ?? 0);
  const parts = [];
  if (one) {
    parts.push(`${what} will be exported exactly as it is, with any location it holds.`);
    parts.push(withGps ? 'It has a GPS position.' : withUnknown ? "It couldn't be checked for a GPS position." : 'It has no GPS position.');
  } else {
    parts.push(`${what} will be exported exactly as they are, with any location they hold.`);
    if (withGps) parts.push(withGps === 1 ? '1 of them has a GPS position.' : `${withGps.toLocaleString()} of them have GPS positions.`);
    else if (!withUnknown) parts.push('None of them have GPS positions.');
    if (withUnknown) parts.push(`${withUnknown.toLocaleString()} of them couldn't be checked for a GPS position.`);
  }
  parts.push(REMOVE_APPLIES);
  return parts.join(' ');
}
