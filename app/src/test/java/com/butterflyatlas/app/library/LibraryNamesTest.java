package com.butterflyatlas.app.library;

import com.butterflyatlas.app.db.Database;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Names already on users' drives: a library made by an older version keeps them, and the startup cleanup finds
 * leftovers by them, so they never change.
 */
class LibraryNamesTest {

    @Test
    void theNamesOnDiskStayTheSame() {
        assertThat(Database.FILE_NAME).isEqualTo("atlas.db");
        assertThat(Library.INCOMING).isEqualTo(".incoming");
        assertThat(Library.PART).isEqualTo(".part");
    }
}
