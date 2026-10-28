package com.butterflyatlas.app.imports;

import com.butterflyatlas.app.AtlasException.Kind;
import com.butterflyatlas.app.AtlasException;
import com.butterflyatlas.app.ErrorCodes;

/** The import and folder-browser refusals (contract §1, §2), each with its own error code and plain words. */
final class ImportErrors {

    private ImportErrors() {
    }

    static AtlasException importNotFound() {
        return new AtlasException(Kind.NOT_FOUND, "import_not_found", "That import can't be found.");
    }

    static AtlasException filing() {
        return new AtlasException(Kind.CONFLICT, ErrorCodes.IMPORT_FILING, "Wait for filing to finish or pause it.");
    }

    /** File from a window whose revision is not the import's (multi-window contract §5): nothing is filed. */
    static AtlasException changed() {
        return new AtlasException(Kind.CONFLICT, ErrorCodes.IMPORT_CHANGED, "This import was changed in another window. Check the"
                + " photos, then press File again.");
    }

    /** A finished import is read-only (it can still be deleted). */
    static AtlasException importFinished() {
        return new AtlasException(Kind.CONFLICT, ErrorCodes.IMPORT_FINISHED, "This import is finished.");
    }

    static AtlasException notASpecies() {
        return new AtlasException(Kind.INVALID, "not_a_species", "Photos can only be filed under a species.");
    }

    static AtlasException unknownTag(String key) {
        return new AtlasException(Kind.INVALID, "unknown_tag", "There is no tag called \"" + key + "\".");
    }

    static AtlasException folderMissing() {
        return new AtlasException(Kind.CONFLICT, ErrorCodes.FOLDER_MISSING,
                "The folder can't be found. Connect the drive, or choose where the folder is now.");
    }

    /** The library's drive has no room for the next batch: filing stops, no photo is blamed. */
    static AtlasException libraryFull() {
        return new AtlasException(Kind.CONFLICT, "library_full",
                "Your library's drive is full. Free up some space, then continue filing.");
    }

    /** A copy could not be written into the library (not a full drive): filing stops, no photo is blamed. */
    static AtlasException libraryUnwritable() {
        return new AtlasException(Kind.CONFLICT, "library_unwritable",
                "Butterfly Atlas can't save photos in your library folder. Check that its drive is connected, then continue filing.");
    }

    static AtlasException nothingToFile() {
        return new AtlasException(Kind.CONFLICT, ErrorCodes.NOTHING_TO_FILE, "Assign a species to some photos first.");
    }

    static AtlasException folderMismatch() {
        return new AtlasException(Kind.INVALID, "folder_mismatch", "None of this import's photos are in that folder.");
    }

    static AtlasException folderInUse() {
        return new AtlasException(Kind.CONFLICT, "folder_in_use", "Another import already uses that folder.");
    }

    static AtlasException badPath() {
        return new AtlasException(Kind.INVALID, "bad_path", "Type or paste the full path of a folder.");
    }

    static AtlasException folderNotFound() {
        return new AtlasException(Kind.NOT_FOUND, "folder_not_found", "That folder can't be found.");
    }

    static AtlasException folderUnreadable() {
        return new AtlasException(Kind.FORBIDDEN, "folder_unreadable", "Butterfly Atlas can't open that folder.");
    }

    static AtlasException packageFolder() {
        return new AtlasException(Kind.INVALID, "package_folder",
                "That's a photo library or app package, not a folder of photos. Choose a normal folder.");
    }

    static AtlasException libraryFolder() {
        return new AtlasException(Kind.INVALID, ErrorCodes.LIBRARY_FOLDER,
                "That's your library folder. Choose a folder of photos outside it.");
    }
}
