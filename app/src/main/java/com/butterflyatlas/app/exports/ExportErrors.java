package com.butterflyatlas.app.exports;

import com.butterflyatlas.app.AtlasException.Kind;
import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;

import java.util.Locale;

/** The export refusals (4b contract §2), each with its own error code and plain words. */
public final class ExportErrors {

    public static final String NO_PATH = "Choose where to export.";
    public static final String NOT_FOUND = "That folder can't be found.";
    public static final String NOT_FOLDER = "That isn't a folder.";
    public static final String LIBRARY_FOLDER = "Choose a folder outside the library.";
    public static final String NOT_WRITABLE = "Butterfly Atlas can't write to that folder.";
    static final String NO_PHOTOS = "No photos match these filters.";
    static final String NO_SPECIES = "No species match these filters.";
    static final String PATH_TOO_LONG = "The chosen folder's path is too long for these folders. Choose a folder nearer the"
            + " top of the drive, or All photos in one folder.";
    /** The path is too long with All photos in one folder already chosen. */
    public static final String PATH_TOO_LONG_FLAT = "The chosen folder's path is too long. Choose a folder nearer the top of the"
            + " drive.";
    /** The path of a species list exported on its own is too long. */
    static final String PATH_TOO_LONG_LIST = "The chosen folder's path is too long for the species list. Choose a folder"
            + " nearer the top of the drive.";
    static final String RUNNING = "Finish or cancel the export in progress first.";
    /** RAW photos go out exactly, with any location they hold, only once the user ticks that (7a contract §1, §5). */
    public static final String RAW_LOCATION = "Tick the RAW photos box, or leave RAW photos out with File type.";
    /** HEIC photos likewise (Phase 7b contract §1). */
    public static final String HEIC_LOCATION = "Tick the HEIC photos box, or leave HEIC photos out with File type.";
    public static final String RAW_AND_HEIC_LOCATION = "Tick the RAW and HEIC photos box, or leave them out with File type.";

    /** The {@code raw_location} words for the kinds of exact photos the export has. */
    static String exactLocation(boolean raw, boolean heic) {
        return raw && heic ? RAW_AND_HEIC_LOCATION : heic ? HEIC_LOCATION : RAW_LOCATION;
    }
    /** A pause or cancel asked while the last photo is still being copied. */
    public static final String STILL_FINISHING = "Butterfly Atlas is still finishing the last photo. Try again in a moment.";
    static final String FINISHED = "This export has already finished.";
    static final String NOT_FINISHED = "Finish or cancel the export first.";

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private ExportErrors() {
    }

    /** A refusal of the Check table: 400 with its own code. */
    public static AtlasException refused(String code, String message) {
        return new AtlasException(Kind.INVALID, code, message);
    }

    /** {@code path_too_long} for photos: in species folders (the contract's words), or in one folder ({@code flat}). */
    static AtlasException pathTooLong(boolean flat) {
        return refused(ErrorCodes.PATH_TOO_LONG, flat ? PATH_TOO_LONG_FLAT : PATH_TOO_LONG);
    }

    /** {@code path_too_long} for a species list on its own. */
    static AtlasException listPathTooLong() {
        return refused(ErrorCodes.PATH_TOO_LONG, PATH_TOO_LONG_LIST);
    }

    static AtlasException invalid(String message) {
        return new AtlasException(Kind.INVALID, ErrorCodes.INVALID, message);
    }

    /** An export is running or paused: one at a time (409 {@code export_running}). */
    static AtlasException running(String message) {
        return new AtlasException(Kind.CONFLICT, "export_running", message);
    }

    static AtlasException finished() {
        return new AtlasException(Kind.CONFLICT, "export_finished", FINISHED);
    }

    /**
     * "There isn't enough space: 435.2 MB needed plus 200.0 MB to spare, 583.2 MB free.": the photos' size, the margin
     * kept free on top of it, and the free space.
     */
    static String noSpace(long photos, long margin, long free) {
        return "There isn't enough space: " + size(photos) + " needed plus " + size(margin) + " to spare, " + size(free)
                + " free.";
    }

    /** "The drive is full: about 2.1 GB more is needed." */
    public static String driveFull(long more) {
        return "The drive is full: about " + size(more) + " more is needed.";
    }

    /** A size as the UI writes it ({@code formatBytes}): B and KB whole, one decimal from MB up, 1 KB = 1024 B. */
    public static String size(long bytes) {
        double value = Math.max(0, bytes);
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        double shown = unit < 2 ? Math.round(value) : Math.round(value * 10) / 10.0;
        if (shown >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit < 2 ? Math.round(value) + " " + UNITS[unit] : String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
