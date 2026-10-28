import { expect, test } from 'vitest';
import { displayOrientation, exactBox, exactExportedLine, exactWarning, orientTransform, rawBroken, rawExportedLine, rawLine, rawWarning, viewerProps, turned } from './raw.js';

const raw = (rawPreview, more = {}) => ({ id: 7, fileType: 'raw', rawPreview, unreadable: false, ...more });

test('every EXIF orientation has the transform that shows it upright; 5–8 turn it a quarter', () => {
  expect([1, 2, 3, 4, 5, 6, 7, 8].map(orientTransform)).toEqual([
    '', 'scaleX(-1)', 'rotate(180deg)', 'scaleY(-1)', 'rotate(90deg) scaleY(-1)', 'rotate(90deg)', 'rotate(90deg) scaleX(-1)', 'rotate(-90deg)',
  ]);
  expect(orientTransform(9)).toBe('');
  expect(orientTransform(undefined)).toBe('');
  expect([1, 2, 3, 4, 5, 6, 7, 8].map(turned)).toEqual([false, false, false, false, true, true, true, true]);
});

test('only a RAW photo\'s display image is turned', () => {
  expect(displayOrientation(raw({ width: 1080, height: 1616, orientation: 8 }))).toBe(8);
  expect(displayOrientation({ fileType: 'jpeg', rawPreview: null })).toBe(1);
  expect(displayOrientation(raw(null))).toBe(1);
});

test('the viewer line says the preview\'s size as displayed', () => {
  expect(rawLine(raw({ width: 1616, height: 1080, orientation: 1 }))).toBe('RAW preview, 1616 × 1080');
  expect(rawLine(raw(null))).toBe('');
  expect(rawLine({ fileType: 'jpeg', rawPreview: null })).toBe('');
});

test('a RAW photo without a preview, or whose thumbnail failed for good, cannot be shown', () => {
  expect(rawBroken(raw(null))).toBe(true);
  expect(rawBroken(raw({ width: 1, height: 1, orientation: 1 }, { unreadable: true }))).toBe(true);
  expect(rawBroken(raw({ width: 1, height: 1, orientation: 1 }))).toBe(false);
  expect(rawBroken({ fileType: 'jpeg', rawPreview: null, unreadable: true })).toBe(false);
});

test('the exported line counts RAW photos, one in the singular, none says nothing', () => {
  expect(rawExportedLine(12)).toBe('12 RAW photos exported with their location.');
  expect(rawExportedLine(1)).toBe('1 RAW photo exported with its location.');
  expect(rawExportedLine(1200)).toBe('1,200 RAW photos exported with their location.');
  expect(rawExportedLine(0)).toBe('');
  expect(rawExportedLine(undefined)).toBe('');
});

test('a viewer passes ZoomPan the turn, whether the preview is gone and the RAW note with its next step (review fix U2)', () => {
  expect(viewerProps(raw({ width: 1080, height: 1616, orientation: 6 }))).toEqual({
    orientation: 6, broken: false, failedNote: "This RAW photo's preview can't be read any more. The file may have changed or be damaged. Add it again from your camera's card, or save it as JPEG from your RAW software and add that.",
  });
  expect(viewerProps(raw(null))).toMatchObject({ orientation: 1, broken: true, failedNote: "This RAW photo's preview can't be read any more. The file may have changed or be damaged. Add it again from your camera's card, or save it as JPEG from your RAW software and add that." });
  expect(viewerProps({ fileType: 'jpeg', rawPreview: null, unreadable: false })).toEqual({});
});

test('any other photo that can\'t be shown says why and what to do, at once (review fix U2)', () => {
  for (const fileType of ['jpeg', 'png', 'tiff', 'webp']) {
    expect(viewerProps({ fileType, rawPreview: null, unreadable: true })).toEqual({
      broken: true, failedNote: "This photo can't be shown: its file may be damaged, or in a form Butterfly Atlas can't read. Replace the file, or delete the photo here and add it again as a JPEG.",
    });
  }
});

test('the Export sheet\'s warning, in the plural, the singular and with no GPS positions', () => {
  const rest = 'Remove location applies only to JPEG, PNG, TIFF and WebP.';
  expect(rawWarning({ photos: 12, withGps: 9 })).toBe(`12 RAW photos will be exported exactly as they are, with any location they hold. 9 of them have GPS positions. ${rest}`);
  expect(rawWarning({ photos: 12, withGps: 0 })).toBe(`12 RAW photos will be exported exactly as they are, with any location they hold. None of them have GPS positions. ${rest}`);
  expect(rawWarning({ photos: 12, withGps: 1 })).toBe(`12 RAW photos will be exported exactly as they are, with any location they hold. 1 of them has a GPS position. ${rest}`);
  expect(rawWarning({ photos: 1, withGps: 1 })).toBe(`1 RAW photo will be exported exactly as it is, with any location it holds. It has a GPS position. ${rest}`);
  expect(rawWarning({ photos: 1, withGps: 0 })).toBe(`1 RAW photo will be exported exactly as it is, with any location it holds. It has no GPS position. ${rest}`);
  expect(rawWarning({ photos: 1200, withGps: 1100 })).toMatch(/^1,200 RAW photos .* 1,100 of them have GPS positions\./);
});

test('RAW photos whose details couldn\'t be read are said apart, and "none" only when every one was checked (review fixes U3, U9)', () => {
  const rest = 'Remove location applies only to JPEG, PNG, TIFF and WebP.';
  const many = '12 RAW photos will be exported exactly as they are, with any location they hold.';
  expect(rawWarning({ photos: 12, withGps: 9, withUnknown: 0 })).toBe(`${many} 9 of them have GPS positions. ${rest}`);
  expect(rawWarning({ photos: 12, withGps: 9, withUnknown: 3 })).toBe(`${many} 9 of them have GPS positions. 3 of them couldn't be checked for a GPS position. ${rest}`);
  expect(rawWarning({ photos: 12, withGps: 1, withUnknown: 1 })).toBe(`${many} 1 of them has a GPS position. 1 of them couldn't be checked for a GPS position. ${rest}`);
  expect(rawWarning({ photos: 12, withGps: 0, withUnknown: 2 })).toBe(`${many} 2 of them couldn't be checked for a GPS position. ${rest}`);
  expect(rawWarning({ photos: 1200, withGps: 0, withUnknown: 1100 })).toMatch(/ 1,100 of them couldn't be checked for a GPS position\. /);
  const one = '1 RAW photo will be exported exactly as it is, with any location it holds.';
  expect(rawWarning({ photos: 1, withGps: 0, withUnknown: 1 })).toBe(`${one} It couldn't be checked for a GPS position. ${rest}`);
  expect(rawWarning({ photos: 1, withGps: 0, withUnknown: 0 })).toBe(`${one} It has no GPS position. ${rest}`);
  expect(rawWarning({ photos: 1, withGps: 1, withUnknown: 0 })).toBe(`${one} It has a GPS position. ${rest}`);
});

/* ---------- RAW and HEIC exported exactly (Phase 7b contract §1, §6) ---------- */

const rest = 'Remove location applies only to JPEG, PNG, TIFF and WebP.';

test('the warning names what is there: HEIC only, RAW and HEIC, one photo, unknown counts', () => {
  expect(exactWarning(null, { photos: 30, withGps: 25, withUnknown: 0 })).toBe(
    `30 HEIC photos will be exported exactly as they are, with any location they hold. 25 of them have GPS positions. ${rest}`);
  expect(exactWarning({ photos: 12, withGps: 9 }, { photos: 30, withGps: 25 })).toBe(
    `12 RAW and 30 HEIC photos will be exported exactly as they are, with any location they hold. 34 of them have GPS positions. ${rest}`);
  expect(exactWarning({ photos: 0, withGps: 0 }, { photos: 1, withGps: 0, withUnknown: 1 })).toBe(
    `1 HEIC photo will be exported exactly as it is, with any location it holds. It couldn't be checked for a GPS position. ${rest}`);
  expect(exactWarning({ photos: 1, withGps: 0 }, { photos: 1, withGps: 0 })).toBe(
    `1 RAW and 1 HEIC photos will be exported exactly as they are, with any location they hold. None of them have GPS positions. ${rest}`);
  expect(exactWarning({ photos: 2, withGps: 0, withUnknown: 1 }, { photos: 3, withGps: 1, withUnknown: 2 })).toBe(
    `2 RAW and 3 HEIC photos will be exported exactly as they are, with any location they hold. 1 of them has a GPS position. 3 of them couldn't be checked for a GPS position. ${rest}`);
  expect(exactWarning({ photos: 12, withGps: 9 }, null)).toBe(rawWarning({ photos: 12, withGps: 9 }));
});

test('the box names the kinds there', () => {
  expect(exactBox({ photos: 12 }, { photos: 0 })).toBe('Export the RAW photos with their location');
  expect(exactBox(null, { photos: 30 })).toBe('Export the HEIC photos with their location');
  expect(exactBox({ photos: 12 }, { photos: 30 })).toBe('Export the RAW and HEIC photos with their location');
});

test('the exported line counts RAW and HEIC photos (contract §1)', () => {
  expect(exactExportedLine(0, 30)).toBe('30 HEIC photos exported with their location.');
  expect(exactExportedLine(undefined, 1)).toBe('1 HEIC photo exported with its location.');
  expect(exactExportedLine(12, 30)).toBe('12 RAW and 30 HEIC photos exported with their location.');
  expect(exactExportedLine(12, undefined)).toBe('12 RAW photos exported with their location.');
  expect(exactExportedLine(0, 0)).toBe('');
});
