package com.butterflyatlas.app;

import com.butterflyatlas.app.imports.ImportStore;
import com.butterflyatlas.app.photos.HeicStatus;
import com.butterflyatlas.app.updates.PendingUpdate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state words the database keeps and the UI reads ({@code ui/src/lib/states.js} pins the same words), so a
 * constant's value never changes.
 */
class JobStatesTest {

    @Test
    void theJobStatesKeepTheirWireWords() {
        assertThat(JobStates.PENDING).isEqualTo("pending");
        assertThat(JobStates.WAITING).isEqualTo("waiting");
        assertThat(JobStates.RUNNING).isEqualTo("running");
        assertThat(JobStates.PAUSED).isEqualTo("paused");
        assertThat(JobStates.DONE).isEqualTo("done");
        assertThat(JobStates.CANCELLED).isEqualTo("cancelled");
        assertThat(PendingUpdate.UNAVAILABLE).isEqualTo("unavailable");
    }

    @Test
    void theHeicAndImportStatesKeepTheirWireWords() {
        assertThat(HeicStatus.CHECKING).isEqualTo("checking");
        assertThat(HeicStatus.AVAILABLE).isEqualTo("available");
        assertThat(HeicStatus.MISSING).isEqualTo("missing");
        assertThat(ImportStore.OPEN).isEqualTo("open");
        assertThat(ImportStore.PENDING).isEqualTo("pending");
        assertThat(ImportStore.READ).isEqualTo("read");
        assertThat(ImportStore.UNSUPPORTED).isEqualTo("unsupported");
        assertThat(ImportStore.FAILED).isEqualTo("failed");
        assertThat(ImportStore.MISSING).isEqualTo("missing");
    }
}
