package com.butterflyatlas.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error codes are on the wire: the UI reads them ({@code ui/src/lib/codes.js} pins the same words) and runs keep
 * them in the database, so a constant's value never changes.
 */
class ErrorCodesTest {

    @Test
    void theCodesKeepTheirWireWords() {
        assertThat(ErrorCodes.INVALID).isEqualTo("invalid");
        assertThat(ErrorCodes.NOT_FOUND).isEqualTo("not_found");
        assertThat(ErrorCodes.NOT_FOLDER).isEqualTo("not_folder");
        assertThat(ErrorCodes.LIBRARY_FOLDER).isEqualTo("library_folder");
        assertThat(ErrorCodes.NOT_WRITABLE).isEqualTo("not_writable");
        assertThat(ErrorCodes.NO_SPACE).isEqualTo("no_space");
        assertThat(ErrorCodes.PATH_TOO_LONG).isEqualTo("path_too_long");
        assertThat(ErrorCodes.WRITE_ERRORS).isEqualTo("write_errors");
        assertThat(ErrorCodes.DRIVE_FULL).isEqualTo("drive_full");
        assertThat(ErrorCodes.DRIVE_GONE).isEqualTo("drive_gone");
        assertThat(ErrorCodes.LIBRARY_IN_USE).isEqualTo("library_in_use");
        assertThat(ErrorCodes.TAXONOMY_UPDATING).isEqualTo("taxonomy_updating");
        assertThat(ErrorCodes.BUSY).isEqualTo("busy");
        assertThat(ErrorCodes.BACKUP_COPYING).isEqualTo("backup_copying");
        assertThat(ErrorCodes.IMPORT_FILING).isEqualTo("import_filing");
        assertThat(ErrorCodes.IMPORT_CHANGED).isEqualTo("import_changed");
        assertThat(ErrorCodes.NO_SESSION).isEqualTo("no_session");
        assertThat(ErrorCodes.UNSUPPORTED_PHOTO).isEqualTo("unsupported_photo");
        assertThat(ErrorCodes.FOLDER_MISSING).isEqualTo("folder_missing");
        assertThat(ErrorCodes.IMPORT_FINISHED).isEqualTo("import_finished");
        assertThat(ErrorCodes.NOTHING_TO_FILE).isEqualTo("nothing_to_file");
        assertThat(ErrorCodes.PAIR_CHOICE_NEEDED).isEqualTo("pair_choice_needed");
        assertThat(ErrorCodes.PAIR_CHOICE_LOCKED).isEqualTo("pair_choice_locked");
    }
}
