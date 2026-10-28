import { expect, test } from 'vitest';
import {
  GALLERY_BAR, HEIC_CHECKING, HEIC_DECODE_FAILED, aboutLine, addedLine, checkResult, heicLines, heicViewerProps, importBarLine, installNote,
  installSentence, isHeic,
} from './heic.js';

const missing = (system, reason = 'absent') => ({ state: 'missing', decoder: null, system, reason });
const WINDOWS_NOTE = "This computer can't show HEIC photos yet. Install Windows' HEVC decoder, then choose Check again in Settings → About. Your photo is safely in the library.";
const LINUX_NOTE = "This computer can't show HEIC photos yet. Install heif-convert from your distribution's packages, then choose Check again in Settings → About. Your photo is safely in the library.";
const MAC_NOTE = "This Mac couldn't show HEIC photos: its built-in sips tool didn't work. Choose Check again in Settings → About; if it keeps failing, save the photo as JPEG and add that.";

test('the install note follows the system, and the Windows reasons have their own (contract §1, audit 4)', () => {
  expect(installNote(missing('windows'))).toBe(WINDOWS_NOTE);
  expect(installNote(missing('linux'))).toBe(LINUX_NOTE);
  expect(installNote(missing('mac', 'failed'))).toBe(MAC_NOTE);
  expect(installNote(missing('other'))).toBe("This computer has no HEIC decoder Butterfly Atlas can use. Save the photo as JPEG and add that. Your photo is safely in the library.");
  expect(installNote(missing('windows', 'no_heif'))).toBe(
    "This computer can't show HEIC photos yet. Install Windows' HEIF and HEVC decoders, then choose Check again in Settings → About. Your photo is safely in the library.");
});

test('About says which decoder shows HEIC photos, that it is checking, or why not and what to do (audit 21)', () => {
  expect(aboutLine({ state: 'available', decoder: 'sips', system: 'mac', reason: null })).toBe("HEIC photos: shown with macOS's sips.");
  expect(aboutLine({ state: 'available', decoder: 'wic', system: 'windows', reason: null })).toBe("HEIC photos: shown with Windows' HEVC decoder.");
  expect(aboutLine({ state: 'available', decoder: 'heif-convert', system: 'linux', reason: null })).toBe('HEIC photos: shown with heif-convert.');
  expect(aboutLine({ state: 'checking', decoder: null, system: 'mac', reason: null })).toBe('HEIC photos: checking for a decoder…');
  expect(aboutLine(missing('windows'))).toBe(
    "HEIC photos can't be shown on this computer. Install Windows' HEVC decoder, then choose Check again in Settings → About.");
  expect(aboutLine(missing('linux'))).toBe(
    "HEIC photos can't be shown on this computer. Install heif-convert from your distribution's packages, then choose Check again in Settings → About.");
  expect(aboutLine(missing('mac', 'failed'))).toBe("HEIC photos can't be shown on this computer. macOS's built-in sips tool didn't work; choose Check again in Settings → About.");
  // Review fix U5: Check again's words name macOS too.
  expect(checkResult(missing('mac', 'failed'))).toBe("Still not available. macOS's built-in sips tool didn't work; choose Check again in Settings → About.");
  expect(aboutLine(null)).toBe('');
});

test('Check again says what it found', () => {
  expect(checkResult({ state: 'available', decoder: 'wic', system: 'windows', reason: null })).toBe('HEIC photos can be shown now. Making their thumbnails…');
  expect(checkResult(missing('windows'))).toBe(`Still not available. ${installSentence(missing('windows'))}`);
  expect(checkResult(missing('linux'))).toBe(
    "Still not available. Install heif-convert from your distribution's packages, then choose Check again in Settings → About.");
  expect(checkResult({ state: 'checking', decoder: null, system: 'windows', reason: null })).toBe('HEIC photos: checking for a decoder…');
});

test('the viewer lines: size, standard range and extra images when they apply', () => {
  const photo = { fileType: 'heic', width: 4032, height: 3024, heic: { tenBit: false, auxiliary: false } };
  expect(isHeic(photo)).toBe(true);
  expect(heicLines(photo)).toEqual(['HEIC, 4032 × 3024']);
  expect(heicLines({ ...photo, heic: { tenBit: true, auxiliary: true } })).toEqual([
    'HEIC, 4032 × 3024',
    'Shown in standard range (the file keeps its full range).',
    "Shows the main picture. The depth map and other extra images stay in the file but aren't shown.",
  ]);
  expect(heicLines({ ...photo, heic: null })).toEqual(['HEIC, 4032 × 3024']);
  expect(heicLines({ fileType: 'jpeg', width: 1, height: 1 })).toEqual([]);
});

test('the viewer note is chosen up front from the decoder state (audit 7)', () => {
  const photo = { fileType: 'heic', unreadable: false };
  expect(heicViewerProps({ ...photo, unreadable: true }, missing('linux'))).toEqual({ broken: true, failedNote: LINUX_NOTE });
  expect(heicViewerProps(photo, { state: 'checking', decoder: null, system: 'mac', reason: null })).toEqual({ broken: true, failedNote: HEIC_CHECKING });
  expect(HEIC_CHECKING).toBe('Checking for a HEIC decoder…');
  const available = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  expect(heicViewerProps({ ...photo, unreadable: true }, available)).toEqual({ broken: true, failedNote: HEIC_DECODE_FAILED });
  expect(heicViewerProps({ ...photo, previewFailed: true }, available)).toEqual({ broken: true, failedNote: HEIC_DECODE_FAILED });
  expect(heicViewerProps(photo, available)).toEqual({ explain: true, failedNote: HEIC_DECODE_FAILED });
  expect(heicViewerProps(photo, null)).toEqual({ explain: true, failedNote: HEIC_DECODE_FAILED });
});

test('the import bar counts the HEIC photos and says what they wait for, by system (contract §1)', () => {
  expect(importBarLine(214, missing('windows'))).toBe(
    "This computer can't show the 214 HEIC photos in this folder. They can still be assigned and filed; they'll appear once Windows' HEVC decoder is installed.");
  expect(importBarLine(1, missing('linux'))).toBe(
    "This computer can't show the 1 HEIC photo in this folder. It can still be assigned and filed; it'll appear once heif-convert is installed.");
  expect(importBarLine(3, missing('mac', 'failed'))).toBe(
    "This computer can't show the 3 HEIC photos in this folder. They can still be assigned and filed; they'll appear once a HEIC decoder works on this computer.");
  expect(importBarLine(3, missing('windows', 'no_heif'))).toContain("they'll appear once Windows' HEIF and HEVC decoders are installed.");
  expect(GALLERY_BAR).toBe("This computer can't show HEIC photos yet.");
});

test('Add photos says how many HEIC photos were added that this computer can\'t show yet (audit 20)', () => {
  expect(addedLine(3, missing('windows'))).toBe(
    "3 HEIC photos were added, but this computer can't show them yet. Install Windows' HEVC decoder, then choose Check again in Settings → About.");
  expect(addedLine(1, missing('linux'))).toBe(
    "1 HEIC photo was added, but this computer can't show it yet. Install heif-convert from your distribution's packages, then choose Check again in Settings → About.");
  expect(addedLine(0, missing('linux'))).toBe('');
});

/* ---------- review fix U2: notes by reason (J10's words, shared with the server) ---------- */

const SAFE = 'Your photo is safely in the library.';
const TIMED_OUT = "Butterfly Atlas's check for a HEIC decoder took too long (the computer may be busy). Choose Check again in Settings → About.";
const LINUX_FAILED = "heif-convert is installed but didn't work. Choose Check again in Settings → About; if it keeps failing, reinstall it from your distribution's packages.";
const WINDOWS_FAILED = "Windows' HEIC decoder didn't work. Reinstall Windows' HEVC decoder, then choose Check again in Settings → About.";
const BLOCKED = "Windows didn't let Butterfly Atlas run its HEIC decoder (PowerShell). Security software may be blocking it: allow Windows PowerShell for Butterfly Atlas, then choose Check again in Settings → About.";
const WORKSPACE = "Butterfly Atlas couldn't use its working folder in your library (.thumbs). Check that the library's drive is connected and has free space, then try again.";

test('the notes follow the reason: timed out, failed on Linux and Windows, blocked, the working folder (U2)', () => {
  expect(installNote(missing('mac', 'timed_out'))).toBe(`${TIMED_OUT} ${SAFE}`);
  expect(installNote(missing('windows', 'timed_out'))).toBe(`${TIMED_OUT} ${SAFE}`);
  expect(installNote(missing('linux', 'failed'))).toBe(`${LINUX_FAILED} ${SAFE}`);
  expect(installNote(missing('windows', 'failed'))).toBe(`${WINDOWS_FAILED} ${SAFE}`);
  expect(installNote(missing('windows', 'blocked'))).toBe(`${BLOCKED} ${SAFE}`);
  expect(installNote(missing('linux', 'workspace'))).toBe(WORKSPACE);
  // absent: the install notes as before
  expect(installNote(missing('linux', 'absent'))).toBe(LINUX_NOTE);
  expect(installNote(missing('windows', 'absent'))).toBe(WINDOWS_NOTE);

  expect(installSentence(missing('mac', 'timed_out'))).toBe(TIMED_OUT);
  expect(installSentence(missing('linux', 'failed'))).toBe(LINUX_FAILED);
  expect(installSentence(missing('windows', 'failed'))).toBe(WINDOWS_FAILED);
  expect(installSentence(missing('windows', 'blocked'))).toBe(BLOCKED);
  expect(installSentence(missing('windows', 'workspace'))).toBe(WORKSPACE);
  expect(checkResult(missing('linux', 'failed'))).toBe(`Still not available. ${LINUX_FAILED}`);
  expect(aboutLine(missing('windows', 'workspace'))).toBe(`HEIC photos can't be shown on this computer. ${WORKSPACE}`);
});

test('About says the working folder problem beside a decoder that is there (U2)', () => {
  expect(aboutLine({ state: 'available', decoder: 'sips', system: 'mac', reason: 'workspace' })).toBe(`HEIC photos: shown with macOS's sips. ${WORKSPACE}`);
});

test('the import bar\'s ending follows the reason too (U2)', () => {
  const end = (heic) => importBarLine(2, heic).replace(/^.*they'll appear /, '');
  expect(end(missing('windows', 'failed'))).toBe("once Windows' HEVC decoder works again.");
  expect(end(missing('linux', 'failed'))).toBe('once heif-convert works.');
  expect(end(missing('windows', 'blocked'))).toBe('once Windows lets Butterfly Atlas run its HEIC decoder.');
  expect(end(missing('mac', 'timed_out'))).toBe('once Check again finds the decoder.');
  expect(end(missing('linux', 'workspace'))).toBe('once Butterfly Atlas can use its working folder again.');
});
