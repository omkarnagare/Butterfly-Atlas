package com.butterflyatlas.app.photos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The install notes by system and reason (Phase 7b contract §1, audit 4, review fix J10): the words heic.js shares. */
class HeicStatusTest {

    private static String note(String system, String reason) {
        return HeicStatus.missing(system, reason).missingNote();
    }

    @Test
    void eachSystemAndReasonHasItsOwnNote() {
        String safe = " Your photo is safely in the library.";
        assertThat(note("windows", "absent")).isEqualTo("This device can't show HEIC photos yet. Install Windows' HEVC"
                + " decoder, then choose Check again in Settings → About." + safe);
        assertThat(note("windows", "failed")).isEqualTo("Windows' HEIC decoder didn't work. Reinstall Windows' HEVC decoder,"
                + " then choose Check again in Settings → About." + safe);
        assertThat(note("windows", "no_heif")).isEqualTo("This device can't show HEIC photos yet. Install Windows' HEIF"
                + " and HEVC decoders, then choose Check again in Settings → About." + safe);
        assertThat(note("windows", "blocked")).isEqualTo("Windows didn't let Butterfly Atlas run its HEIC decoder"
                + " (PowerShell). Security software may be blocking it: allow Windows PowerShell for Butterfly Atlas, then"
                + " choose Check again in Settings → About." + safe);
        assertThat(note("linux", "absent")).isEqualTo("This device can't show HEIC photos yet. Install heif-convert"
                + " from your distribution's packages, then choose Check again in Settings → About." + safe);
        assertThat(note("linux", "failed")).isEqualTo("heif-convert is installed but didn't work. Choose Check again in"
                + " Settings → About; if it keeps failing, reinstall it from your distribution's"
                + " packages." + safe);
        assertThat(note("mac", "failed")).isEqualTo("This Mac couldn't show HEIC photos: its built-in sips tool didn't"
                + " work. Choose Check again in Settings → About; if it keeps failing, save the photo as JPEG and add that.");
        assertThat(note("other", "absent")).isEqualTo("This device has no HEIC decoder Butterfly Atlas can use. Save the"
                + " photo as JPEG and add that." + safe);
        for (String system : new String[] {"mac", "windows", "linux", "other"}) {
            assertThat(note(system, "timed_out")).isEqualTo("Butterfly Atlas's check for a HEIC decoder took too long"
                    + " (the device may be busy). Choose Check again in Settings → About." + safe);
            assertThat(note(system, "workspace")).isEqualTo(HeicStatus.WORKSPACE_NOTE);
        }
    }
}
