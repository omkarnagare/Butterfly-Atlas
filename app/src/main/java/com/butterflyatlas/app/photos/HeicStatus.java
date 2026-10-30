package com.butterflyatlas.app.photos;

import com.butterflyatlas.app.AtlasException;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Locale;

/**
 * Whether this computer can show HEIC photos now (Phase 7b contract §0 and audit 4): {@code state} {@code checking},
 * {@code available} or {@code missing}; {@code decoder} ({@code sips}, {@code wic}, {@code heif-convert}) when
 * available; {@code system} ({@code mac}, {@code windows}, {@code linux}, {@code other}) always; {@code reason}
 * ({@code absent}, {@code failed}, {@code blocked}, {@code timed_out}, {@code no_heif}) only when missing. Kept in memory
 * only, never written anywhere.
 *
 * <p>The decoder belongs to the computer, not to a library, so the current status is one value for the whole app
 * ({@link #current()}), set by the imaging layer's decoder check; the photo rows, covers and thumbnails read it. Also
 * here: the words the server says about it (contract §1).
 */
public record HeicStatus(String state, String decoder, String system, String reason) {

    public static final String CHECKING = "checking";
    public static final String AVAILABLE = "available";
    public static final String MISSING = "missing";

    /** The error code of a HEIC {@code /display} while the decoder is missing (415). */
    public static final String DECODER_MISSING = "heic_decoder_missing";
    /**
     * The library's working folder ({@code .thumbs/.decode}) could not be used (review fix J3): {@code /display} answers
     * 503 {@value #WORKSPACE_CODE} with these words, unless the library's drive is full or gone (then its own banner).
     */
    public static final String WORKSPACE_NOTE = "Butterfly Atlas couldn't use its working folder in your library (.thumbs)."
            + " Check that the library's drive is connected and has free space, then try again.";
    public static final String WORKSPACE_CODE = "heic_workspace";

    /** {@code /display} while the check runs (contract §1). */
    public static final String CHECKING_NOTE = "Butterfly Atlas is still checking for a HEIC decoder. Try again in a moment.";
    /** The decoder ran but could not read this file (contract §1). */
    public static final String DECODE_FAILED = "This HEIC photo can't be shown: the device's decoder couldn't read it."
            + " The file may be damaged, or use a HEIC feature the decoder doesn't support. Save it as JPEG on the device it"
            + " came from and add that.";

    private static volatile HeicStatus current = checking(systemOf(System.getProperty("os.name", "")));

    /** The status the app has now. */
    public static HeicStatus current() {
        return current;
    }

    /** Sets the app's status (the decoder check does). */
    public static void publish(HeicStatus status) {
        current = status;
    }

    public static HeicStatus checking(String system) {
        return new HeicStatus(CHECKING, null, system, null);
    }

    public static HeicStatus available(String decoder, String system) {
        return new HeicStatus(AVAILABLE, decoder, system, null);
    }

    public static HeicStatus missing(String system, String reason) {
        return new HeicStatus(MISSING, null, system, reason);
    }

    /** {@code mac}, {@code windows}, {@code linux} or {@code other}, from {@code os.name}. */
    public static String systemOf(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return "mac";
        }
        if (os.startsWith("windows")) {
            return "windows";
        }
        return os.startsWith("linux") ? "linux" : "other";
    }

    /** The 503 for a working folder that could not be used (the web layer checks the library's drive first). */
    public static AtlasException workspaceProblem() {
        return new AtlasException(AtlasException.Kind.BUSY, WORKSPACE_CODE, WORKSPACE_NOTE);
    }

    /**
     * For showing a HEIC picture now: fails while the decoder is being checked (503 {@code busy}, "still checking") or
     * missing (415 {@code heic_decoder_missing} with the system's install note).
     */
    public static void requireShown() {
        HeicStatus status = current();
        if (status.isChecking()) {
            throw AtlasException.busy(CHECKING_NOTE);
        }
        if (status.isMissing()) {
            throw new AtlasException(AtlasException.Kind.UNSUPPORTED, DECODER_MISSING, status.missingNote());
        }
    }

    @JsonIgnore
    public boolean isAvailable() {
        return AVAILABLE.equals(state);
    }

    @JsonIgnore
    public boolean isMissing() {
        return MISSING.equals(state);
    }

    @JsonIgnore
    public boolean isChecking() {
        return CHECKING.equals(state);
    }

    /**
     * The install note for this system and reason (contract §1, audit 4, review fix J10, shared word for word with the
     * UI's heic.js): the {@code /display} 415's message and the viewer's note for a HEIC photo while the decoder is
     * missing.
     */
    public String missingNote() {
        String safe = " Your photo is safely in the library.";
        String checkAgain = "choose Check again in Settings → About.";
        if ("workspace".equals(reason)) {
            return WORKSPACE_NOTE;
        }
        if ("timed_out".equals(reason)) {
            return "Butterfly Atlas's check for a HEIC decoder took too long (the device may be busy). Choose Check"
                    + " again in Settings → About." + safe;
        }
        boolean failed = "failed".equals(reason);
        return switch (system) {
            case "windows" -> switch (reason == null ? "" : reason) {
                case "blocked" -> "Windows didn't let Butterfly Atlas run its HEIC decoder (PowerShell). Security software"
                        + " may be blocking it: allow Windows PowerShell for Butterfly Atlas, then " + checkAgain + safe;
                case "no_heif" -> "This device can't show HEIC photos yet. Install Windows' HEIF and HEVC decoders, then "
                        + checkAgain + safe;
                case "failed" -> "Windows' HEIC decoder didn't work. Reinstall Windows' HEVC decoder, then "
                        + checkAgain + safe;
                default -> "This device can't show HEIC photos yet. Install Windows' HEVC decoder, then "
                        + checkAgain + safe;
            };
            case "linux" -> failed
                    ? "heif-convert is installed but didn't work. Choose Check again in Settings → About; if it keeps"
                            + " failing, reinstall it from your distribution's packages." + safe
                    : "This device can't show HEIC photos yet. Install heif-convert from your"
                            + " distribution's packages, then " + checkAgain + safe;
            case "mac" -> "This Mac couldn't show HEIC photos: its built-in sips tool didn't work. Choose Check again in"
                    + " Settings → About; if it keeps failing, save the photo as JPEG and add that.";
            default -> "This device has no HEIC decoder Butterfly Atlas can use. Save the photo as JPEG and add that."
                    + safe;
        };
    }
}
